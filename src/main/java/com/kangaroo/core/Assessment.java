package com.kangaroo.core;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The complete, auditable result of assessing one encounter.
 *
 * <p>Everything a supervisor or a receiving clinician would need to second-guess the machine is in
 * here: which rung served it, what each head thought independently, whether they agreed, what
 * evidence was used, and whether the system abstained. Nothing is summarised away.
 *
 * @param encounterId       the encounter this assesses
 * @param light             the final traffic light — the number that matters
 * @param classification    the WHO IMNCI classification
 * @param signs             the danger signs found, with provenance
 * @param profile           the structured evidence both heads saw
 * @param ruleLight         what the deterministic WHO rule alone concluded
 * @param modelVerdict      what the gradient-boosted head concluded, with calibrated probabilities
 * @param narrativeLight    what the language model concluded, or empty when no model ran
 * @param narrative         the localised explanation and action plan
 * @param jaundice          colorimetric jaundice grading, when a usable image was captured
 * @param rung              which rung of the ladder served this
 * @param abstained         true when the conformal prediction set was not a singleton and we referred upward
 * @param supervisorReview  true when the heads disagreed and a human must sign off
 * @param toolResults       deterministic tool outputs (dose, z-score, ORS, referral, follow-up)
 * @param cry               what the cry recording sounded like, when one was made
 * @param assessedAt        when
 * @param elapsed           wall-clock time for the whole orchestrated assessment
 */
public record Assessment(
        EncounterId encounterId,
        TrafficLight light,
        Classification classification,
        List<DangerSign> signs,
        SignProfile profile,
        TrafficLight ruleLight,
        ModelVerdict modelVerdict,
        Optional<TrafficLight> narrativeLight,
        String narrative,
        Optional<JaundiceGrade> jaundice,
        Optional<CryFinding> cry,
        Rung rung,
        boolean abstained,
        boolean supervisorReview,
        Map<String, Object> toolResults,
        Instant assessedAt,
        Duration elapsed) {

    public Assessment {
        signs = signs == null ? List.of() : List.copyOf(signs);
        toolResults = toolResults == null ? Map.of() : Map.copyOf(toolResults);
        if (assessedAt == null) assessedAt = Instant.now();
        if (elapsed == null) elapsed = Duration.ZERO;
        if (narrative == null) narrative = "";
        if (narrativeLight == null) narrativeLight = Optional.empty();
        if (jaundice == null) jaundice = Optional.empty();
        if (cry == null) cry = Optional.empty();
    }

    /** The one-line answer, in the vocabulary of the front door that asked. */
    public String headline(Mode mode) {
        return switch (mode) {
            case PARENT -> switch (light) {
                case RED -> "Go to a clinic now.";
                case YELLOW -> "Someone should see your baby today.";
                case GREEN -> "Nothing here needs a clinician today.";
            };
            case CHW -> switch (light) {
                case RED -> "URGENT REFERRAL - " + classification.name();
                case YELLOW -> "TREAT AND FOLLOW UP - " + classification.name();
                case GREEN -> "HOME CARE - " + classification.name();
            };
        };
    }

    /** Did the deterministic rule and the trained head reach the same colour? */
    public boolean headsAgree() {
        return ruleLight == modelVerdict.light();
    }

    public List<DangerSign> redSigns() {
        return signs.stream().filter(s -> s.sign().red()).toList();
    }

    /** Everything the audit trail needs about how this was decided, as a flat map. */
    public Map<String, Object> decisionTrace() {
        return Map.of(
                "final", light.name(),
                "rule", ruleLight.name(),
                "model", modelVerdict.light().name(),
                "narrative", narrativeLight.map(Enum::name).orElse("none"),
                "rung", rung.name(),
                "abstained", abstained,
                "supervisorReview", supervisorReview,
                "signCount", signs.size(),
                "elapsedMs", elapsed.toMillis());
    }
}
