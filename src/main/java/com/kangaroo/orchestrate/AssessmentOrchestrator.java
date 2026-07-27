package com.kangaroo.orchestrate;

import com.kangaroo.audit.ClinicalEvents;
import com.kangaroo.clinical.ImnciRule;
import com.kangaroo.color.Frame;
import com.kangaroo.color.JaundiceAnalyzer;
import com.kangaroo.core.Assessment;
import com.kangaroo.core.Capture;
import com.kangaroo.core.DangerSign;
import com.kangaroo.core.Encounter;
import com.kangaroo.core.Feature;
import com.kangaroo.core.JaundiceGrade;
import com.kangaroo.core.ModelVerdict;
import com.kangaroo.core.Rung;
import com.kangaroo.core.SignProfile;
import com.kangaroo.core.TrafficLight;
import com.kangaroo.infer.FailoverEngine;
import com.kangaroo.infer.InferenceEngine;
import com.kangaroo.infer.Narrative;
import com.kangaroo.ml.Abstention;
import com.kangaroo.ml.Models;
import com.kangaroo.ml.features.ClinicalFeatures;
import com.kangaroo.store.PatientMemory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.StructuredTaskScope;

/**
 * Runs one encounter through every analysis pass and reconciles what they say.
 *
 * <h2>Why structured concurrency (JEP 525)</h2>
 * The four evidence passes are independent, they take wildly different times, and they share one
 * deadline: a health worker holding a baby will not wait thirty seconds. That shape — fan out, one
 * budget, one cancellation domain, join — is exactly what {@link StructuredTaskScope} is for, and
 * the version written with it is shorter and safer than the executor-and-futures version it
 * replaces.
 *
 * <p>The concrete wins are worth naming, because "we used the new API" is not an argument.
 * The passes are forked into a scope whose {@code withTimeout} covers the group rather than each
 * task, so a slow visual pass cannot consume the audio pass's budget. If the caller's thread is
 * interrupted, every pass is cancelled with it — no orphan threads still decoding a JPEG for an
 * encounter nobody is waiting for. And the scope cannot be left open: the passes are guaranteed to
 * have finished, one way or another, before {@code close} returns, so there is no path where the
 * assessment is written to the store while a pass is still mutating its inputs.
 *
 * <h2>Partial results</h2>
 * A pass that fails or times out is recorded as a <em>gap in the evidence</em>, not as a failed
 * encounter. This is a deliberate departure from failing the whole assessment: losing the cry
 * classifier should not deny a health worker the twenty other danger signs that were assessed fine.
 * What must never degrade is the decision itself, and it does not — the deterministic rule runs on
 * whatever evidence was gathered, and in parent mode a gap escalates rather than being ignored.
 */
public final class AssessmentOrchestrator implements AutoCloseable {

    /** The whole-encounter budget for the evidence passes. */
    public static final Duration DEFAULT_BUDGET = Duration.ofSeconds(20);

    private final FailoverEngine engines;
    private final JaundiceAnalyzer jaundice;
    private final Abstention abstention;
    private final PatientMemory memory;
    private final Duration budget;

    public AssessmentOrchestrator(FailoverEngine engines, PatientMemory memory) {
        this(engines, memory, new JaundiceAnalyzer(), Abstention.byMargin(), DEFAULT_BUDGET);
    }

    public AssessmentOrchestrator(FailoverEngine engines, PatientMemory memory,
                                  JaundiceAnalyzer jaundice, Abstention abstention, Duration budget) {
        this.engines = engines;
        this.memory = memory;
        this.jaundice = jaundice;
        this.abstention = abstention;
        this.budget = budget;
    }

    /** The outcome of one evidence pass. Sealed so the reconciliation below is exhaustive. */
    sealed interface Pass {
        record Visual(Optional<JaundiceGrade> grade, List<String> notes) implements Pass {}
        record Audio(Optional<DangerSign> cry, List<String> notes) implements Pass {}
        record Vitals(SignProfile profile) implements Pass {}
        record History(List<String> trend, boolean worsening) implements Pass {}
    }

    /**
     * Assess an encounter.
     *
     * @throws InterruptedException if the caller is interrupted; every pass is cancelled with it
     */
    public Assessment assess(Encounter encounter) throws InterruptedException {
        Instant started = Instant.now();
        long t0 = System.nanoTime();

        // The evidence passes. One scope, one deadline, one cancellation domain.
        List<String> gaps = new ArrayList<>();
        Optional<JaundiceGrade> grade = Optional.empty();
        Optional<DangerSign> cry = Optional.empty();
        SignProfile profile;
        List<String> trend = List.of();
        boolean worsening = false;

        // allUntil(never) collects every subtask's outcome instead of cancelling the group on the
        // first failure: a pass that fails is a gap in the evidence, not a reason to abandon the
        // other three. The shared timeout still bounds the whole group.
        try (var scope = StructuredTaskScope.open(
                StructuredTaskScope.Joiner.<Pass>allUntil(_ -> false),
                cfg -> cfg.withName("assessment-" + encounter.id()).withTimeout(budget))) {

            var visual = scope.fork(() -> visualPass(encounter));
            var audio = scope.fork(() -> audioPass(encounter));
            var vitals = scope.fork(() -> vitalsPass(encounter));
            var history = scope.fork(() -> historyPass(encounter));

            scope.join();

            // Vitals is the only pass whose absence would leave nothing to reason over, so it has
            // a fallback rather than a gap.
            profile = result(vitals, Pass.Vitals.class)
                    .map(Pass.Vitals::profile)
                    .orElseGet(() -> {
                        gaps.add("structured extraction");
                        return ClinicalFeatures.extract(encounter.intakeText(),
                                encounter.subject(), encounter.vitals());
                    });

            var v = result(visual, Pass.Visual.class);
            if (v.isPresent()) {
                grade = v.get().grade();
            } else if (encounter.hasImages()) {
                gaps.add("image analysis");
            }

            var a = result(audio, Pass.Audio.class);
            if (a.isPresent()) {
                cry = a.get().cry();
            } else if (encounter.cryAudio().isPresent()) {
                gaps.add("cry analysis");
            }

            var h = result(history, Pass.History.class);
            if (h.isPresent()) {
                trend = h.get().trend();
                worsening = h.get().worsening();
            }
        }

        // Fold the visual and audio findings back into the profile, so both heads and the model
        // all see exactly the same evidence. That shared view is what makes comparing them honest.
        profile = merge(profile, grade, cry);

        // ---- the two independent heads
        TrafficLight ruleLight = ImnciRule.label(profile);
        ImnciRule.Outcome outcome = ImnciRule.evaluate(profile, encounter.subject(),
                encounter.vitals(), encounter.mode());

        ModelVerdict verdict = abstention.apply(
                ModelVerdict.of(Models.clinical().predict(profile.toDoubleVector())));

        // ---- the narrative, down the ladder; this cannot fail
        var request = new InferenceEngine.Request(encounter, profile, ruleLight,
                outcome.classification(), outcome.signs());
        Narrative narrative = engines.explain(request);

        // ---- reconcile
        Reconciled r = reconcile(encounter, outcome, ruleLight, verdict, narrative, gaps, worsening);

        Duration elapsed = Duration.ofNanos(System.nanoTime() - t0);
        Assessment assessment = new Assessment(
                encounter.id(), r.light(), r.classification(), outcome.signs(), profile,
                ruleLight, verdict, narrative.suggested(), narrativeText(narrative, r, trend),
                grade, narrative.rung(), r.abstained(), r.supervisorReview(),
                toolResults(encounter, outcome, r), started, elapsed);

        record(assessment, encounter);
        return assessment;
    }

    // ------------------------------------------------------------------ passes

    private Pass visualPass(Encounter encounter) throws Exception {
        List<String> notes = new ArrayList<>();
        Optional<Capture> primary = encounter.capture(Capture.Kind.CHEST)
                .or(() -> encounter.capture(Capture.Kind.FACE));
        if (primary.isEmpty()) {
            return new Pass.Visual(Optional.empty(), List.of("no image captured"));
        }

        Frame frame = Frame.decode(primary.get().bytes());
        Frame wholeBody = encounter.capture(Capture.Kind.PALMS_SOLES)
                .map(c -> decodeQuietly(c.bytes()))
                .orElse(null);

        JaundiceGrade g = jaundice.grade(frame, wholeBody);
        if (g.refused()) {
            notes.add(g.refusalReason());
        } else {
            notes.add("jaundice graded " + g.severity() + ", Kramer zone " + g.kramerZone());
        }
        return new Pass.Visual(Optional.of(g), notes);
    }

    private Pass audioPass(Encounter encounter) {
        // The cry classifier is future work; the pass exists so that the shape of the pipeline is
        // honest about where it goes, and it reports its own absence rather than pretending.
        if (encounter.cryAudio().isEmpty()) {
            return new Pass.Audio(Optional.empty(), List.of("no cry recording"));
        }
        return new Pass.Audio(Optional.empty(),
                List.of("cry recording captured and stored; automated grading not enabled"));
    }

    private Pass vitalsPass(Encounter encounter) {
        return new Pass.Vitals(ClinicalFeatures.extract(
                encounter.intakeText(), encounter.subject(), encounter.vitals()));
    }

    private Pass historyPass(Encounter encounter) {
        if (memory == null || encounter.subjectRef().isBlank()) {
            return new Pass.History(List.of(), false);
        }
        var history = memory.trendFor(encounter.subjectRef());
        return new Pass.History(history.summary(), history.worsening());
    }

    private static Frame decodeQuietly(byte[] bytes) {
        try {
            return Frame.decode(bytes);
        } catch (Exception e) {
            return null;
        }
    }

    private static <T extends Pass> Optional<T> result(StructuredTaskScope.Subtask<Pass> subtask,
                                                       Class<T> type) {
        if (subtask.state() != StructuredTaskScope.Subtask.State.SUCCESS) return Optional.empty();
        Pass p = subtask.get();
        return type.isInstance(p) ? Optional.of(type.cast(p)) : Optional.empty();
    }

    // ------------------------------------------------------------------ merging and reconciling

    /**
     * Fold the image and audio findings into the structured profile.
     *
     * <p>Only ever raises a flag, never lowers one. A colorimetric grade that disagrees with what
     * the caregiver reported is additional evidence, not a correction: if the parent says the baby
     * is yellow and the photo says otherwise, the photo may simply be badly lit.
     */
    private SignProfile merge(SignProfile profile, Optional<JaundiceGrade> grade,
                              Optional<DangerSign> cry) {
        SignProfile out = profile;

        if (grade.isPresent() && !grade.get().refused()) {
            JaundiceGrade g = grade.get();
            int measuredExtent = JaundiceAnalyzer.toExtent(g.kramerZone());
            boolean anyJaundice = g.severity() != JaundiceGrade.Severity.NORMAL || measuredExtent > 0;

            if (anyJaundice) {
                out = out.with(Feature.JAUNDICE_PRESENT, 1.0);
                double reported = out.get(Feature.JAUNDICE_EXTENT);
                out = out.with(Feature.JAUNDICE_EXTENT, Math.max(reported, measuredExtent));
            }
        }

        if (cry.isPresent()) {
            out = out.with(Feature.WEAK_ABSENT_CRY, 1.0);
        }
        return out;
    }

    private record Reconciled(TrafficLight light, com.kangaroo.core.Classification classification,
                              boolean abstained, boolean supervisorReview, String note) {}

    /**
     * Decide the final colour from three opinions.
     *
     * <p>The rule is short and it is the safety argument for the whole product:
     * <ol>
     *   <li>The deterministic WHO rule is the floor. Nothing lowers it.</li>
     *   <li>The calibrated head may raise it, and does when it abstains upward.</li>
     *   <li>The language model may raise it, but may never lower it — a model that talks a health
     *       worker out of a referral the protocol called for is the single worst thing this system
     *       could do.</li>
     *   <li>Any disagreement is recorded and routed to a human.</li>
     * </ol>
     */
    private Reconciled reconcile(Encounter encounter, ImnciRule.Outcome outcome, TrafficLight ruleLight,
                                 ModelVerdict verdict, Narrative narrative, List<String> gaps,
                                 boolean worsening) {

        TrafficLight modelLight = Abstention.escalate(verdict);
        boolean abstained = verdict.uncertain();

        TrafficLight light = outcome.light().escalatedWith(modelLight);

        // The model may escalate. It may never de-escalate.
        Optional<TrafficLight> narrativeLight = narrative.suggested();
        if (narrativeLight.isPresent()) {
            light = light.escalatedWith(narrativeLight.get());
        }

        // A worsening trend across visits is itself evidence, and it is the signal single
        // snapshots cannot see.
        if (worsening && light == TrafficLight.GREEN) {
            light = TrafficLight.YELLOW;
        }

        // A gap in the evidence escalates in parent mode, where there is no trained observer to
        // have noticed what the missing pass would have caught.
        if (!gaps.isEmpty() && encounter.mode().escalateBorderline() && light == TrafficLight.GREEN) {
            light = TrafficLight.YELLOW;
        }

        boolean disagreement = ruleLight != verdict.light()
                || narrativeLight.map(n -> n != ruleLight).orElse(false);

        if (abstained) {
            ClinicalEvents.abstention(encounter.id().value(),
                    verdict.predictionSet().toString(), modelLight.name(),
                    "prediction set was not a singleton");
        }
        if (disagreement) {
            ClinicalEvents.disagreement(encounter.id().value(), ruleLight.name(),
                    verdict.light().name(), narrativeLight.map(Enum::name).orElse("none"),
                    light.name());
        }

        // The classification must agree with the final colour. When something above the rule raised
        // the light, the classification is rebuilt to say so -- and, critically, to say *why it was
        // raised* rather than inventing a WHO classification the findings do not support.
        var classification = light == outcome.light()
                ? outcome.classification()
                : escalatedClassification(light, outcome, verdict, narrativeLight, worsening, gaps);

        return new Reconciled(light, classification, abstained, disagreement,
                gaps.isEmpty() ? "" : "Evidence gaps: " + String.join(", ", gaps));
    }

    /**
     * Build the classification for an encounter that was escalated above the deterministic rule.
     *
     * <p>The reasons carry the escalation itself, in plain words, because a health worker handed
     * "URGENT REFERRAL" with no finding to justify it will not trust it — and should not. The WHO
     * findings that the rule did identify are kept alongside, so the letter shows both what was
     * observed and what caused the upgrade.
     */
    private com.kangaroo.core.Classification escalatedClassification(
            TrafficLight light, ImnciRule.Outcome outcome, ModelVerdict verdict,
            Optional<TrafficLight> narrativeLight, boolean worsening, List<String> gaps) {

        List<String> reasons = new ArrayList<>(outcome.classification().reasons());

        if (verdict.uncertain() && Abstention.escalate(verdict).atLeast(light)) {
            reasons.add(Abstention.explain(verdict));
        }
        if (narrativeLight.map(n -> n.atLeast(light)).orElse(false)
                && narrativeLight.get() != outcome.light()) {
            reasons.add("The language model read this as more serious than the rule alone did, "
                    + "so it has been raised and flagged for supervisor review.");
        }
        if (worsening) {
            reasons.add("This baby has been getting worse across recent visits, which a single "
                    + "check cannot see.");
        }
        if (!gaps.isEmpty()) {
            reasons.add("Part of the check could not be completed (" + String.join(", ", gaps)
                    + "), so this was treated cautiously.");
        }
        if (reasons.isEmpty()) {
            reasons.add("Raised above the protocol result because the assessment was not confident.");
        }

        return switch (light) {
            case RED -> new com.kangaroo.core.Classification.UrgentReferral(
                    "URGENT REFERRAL - PRECAUTIONARY", reasons,
                    List.of("Refer. Arrange transport before anything else.",
                            "Keep the baby warm, skin-to-skin, during transfer.",
                            "Send the referral letter, which records that this was a precautionary "
                                    + "escalation rather than a confirmed danger sign.",
                            "Encourage breastfeeding on the way."));

            case YELLOW -> new com.kangaroo.core.Classification.TreatmentNeeded(
                    "WORTH CHECKING TODAY", reasons,
                    List.of("Have a health worker see this baby today.",
                            "Bring the record from this check with you.",
                            "Teach the caregiver the danger signs that mean going immediately."));

            // Nothing in this method can lower the light, so a GREEN here would mean a logic error
            // upstream rather than a clinical finding.
            case GREEN -> outcome.classification();
        };
    }

    private String narrativeText(Narrative narrative, Reconciled r, List<String> trend) {
        StringBuilder sb = new StringBuilder();
        if (!narrative.reasoning().isBlank()) sb.append(narrative.reasoning()).append("\n\n");
        if (!narrative.actionPlan().isBlank()) sb.append(narrative.actionPlan()).append('\n');
        if (!trend.isEmpty()) {
            sb.append("\nAcross previous visits:\n");
            for (String line : trend) sb.append("  - ").append(line).append('\n');
        }
        if (!r.note().isBlank()) sb.append('\n').append(r.note()).append('\n');
        return sb.toString();
    }

    private Map<String, Object> toolResults(Encounter encounter, ImnciRule.Outcome outcome,
                                            Reconciled r) {
        var results = new java.util.LinkedHashMap<String, Object>();
        var visit = com.kangaroo.clinical.FollowUp.suggest(r.light());
        results.put("followup_date", visit.date().toString());
        results.put("followup_type", visit.visitType());

        if (encounter.subject().weightKnown() && encounter.subject().ageKnown()
                && encounter.subject().ageDays() <= 28) {
            try {
                var z = com.kangaroo.clinical.ZScore.calculate(encounter.subject().weightKg(),
                        encounter.subject().ageDays(), encounter.subject().sex());
                results.put("zscore", z.z());
                results.put("zscore_band", z.band().name());
            } catch (IllegalArgumentException e) {
                // Out of the published range: reporting nothing is correct, guessing is not.
            }
        }
        return results;
    }

    private void record(Assessment assessment, Encounter encounter) {
        var e = new ClinicalEvents.ClinicalDecision();
        if (e.shouldCommit()) {
            e.encounterId = assessment.encounterId().value();
            e.mode = encounter.mode().name();
            e.finalLight = assessment.light().name();
            e.ruleLight = assessment.ruleLight().name();
            e.modelLight = assessment.modelVerdict().light().name();
            e.narrativeLight = assessment.narrativeLight().map(Enum::name).orElse("none");
            e.classification = assessment.classification().name();
            e.rung = assessment.rung().name();
            e.signCount = assessment.signs().size();
            e.abstained = assessment.abstained();
            e.supervisorReview = assessment.supervisorReview();
            e.confidence = assessment.modelVerdict().confidence();
            e.commit();
        }
        if (memory != null) memory.record(encounter, assessment);
    }

    /** Which rung would serve right now, for the interface badge. */
    public Optional<Rung> preferredRung() {
        return engines.preferredRung();
    }

    @Override
    public void close() {
        engines.close();
    }
}
