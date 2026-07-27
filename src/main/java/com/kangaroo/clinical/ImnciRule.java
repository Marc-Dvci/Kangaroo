package com.kangaroo.clinical;

import com.kangaroo.core.Classification;
import com.kangaroo.core.DangerSign;
import com.kangaroo.core.Feature;
import com.kangaroo.core.Mode;
import com.kangaroo.core.SignProfile;
import com.kangaroo.core.Subject;
import com.kangaroo.core.TrafficLight;
import com.kangaroo.core.Vitals;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The WHO IMNCI young-infant danger-sign algorithm, written out.
 *
 * <p>This class is the floor of the whole system. It needs no model, no network, no native library
 * and no reference data beyond what is compiled into it, and it always produces a valid WHO
 * classification. Every other component — the gradient-boosted head, the language model, the cloud
 * — is an accelerant layered on top of this, and none of them is allowed to make the answer less
 * safe than what this class alone would have said.
 *
 * <p>{@link #label(SignProfile)} is the exact decision function the gradient-boosted head was
 * trained to distil, kept deliberately separate from {@link #evaluate} so it can be held to
 * bit-parity against the reference implementation in the conformance suite. {@code evaluate} adds
 * everything a human needs around that decision: which signs fired, why, in what words, and what to
 * do next.
 */
public final class ImnciRule {

    private ImnciRule() {}

    /**
     * @param light          the traffic light
     * @param classification the WHO classification with its reasons and actions
     * @param signs          every danger sign that fired, with provenance
     * @param modeEscalated  true when parent mode's lower threshold moved the light up
     */
    public record Outcome(
            TrafficLight light,
            Classification classification,
            List<DangerSign> signs,
            boolean modeEscalated) {
        public Outcome {
            signs = List.copyOf(signs);
        }
    }

    // ------------------------------------------------------------------ the decision function

    /**
     * The pure WHO IMNCI decision: profile in, colour out.
     *
     * <p>No side effects, no clock, no locale, no I/O. It is deliberately a single readable
     * expression per band so that it can be checked line by line against the WHO chart booklet by
     * someone who does not read Java well.
     */
    public static TrafficLight label(SignProfile p) {
        int age = p.isPresent(Feature.AGE_DAYS) ? p.ordinal(Feature.AGE_DAYS) : 7;
        boolean jaundice = p.flag(Feature.JAUNDICE_PRESENT);
        int extent = p.ordinal(Feature.JAUNDICE_EXTENT);

        // "Not feeding well" in the IMNCI sense covers reduced feeding, no feeding, and lethargy —
        // a baby too sleepy to feed and a baby refusing to feed present the same clinical risk.
        boolean poorFeeding = p.flag(Feature.FEEDING_POOR)
                || p.flag(Feature.FEEDING_UNABLE)
                || p.flag(Feature.LETHARGY);

        boolean red =
                p.flag(Feature.CONVULSION)
                || p.flag(Feature.FEEDING_UNABLE)
                || p.flag(Feature.LETHARGY)
                || p.flag(Feature.CHEST_INDRAWING)
                || p.flag(Feature.GRUNTING_STRIDOR)
                || p.flag(Feature.RR_GE_60)
                || p.flag(Feature.WEAK_ABSENT_CRY)
                || p.flag(Feature.CENTRAL_CYANOSIS)
                || p.flag(Feature.PALLOR)
                || p.flag(Feature.FEVER)
                || p.flag(Feature.MEASURED_COLD)
                || p.flag(Feature.OMPHALITIS_SEVERE)
                || p.flag(Feature.UMBILICAL_BLEEDING)
                || p.flag(Feature.PUSTULES_MANY)
                || p.flag(Feature.BULGING_FONTANELLE)
                || p.flag(Feature.DEHYDRATION_SEVERE)
                // Jaundice is urgent by extent, by age, or by company it keeps.
                || (jaundice && extent >= 3)
                || (jaundice && age <= 1)
                || (jaundice && poorFeeding);

        if (red) return TrafficLight.RED;

        boolean yellow =
                p.flag(Feature.OMPHALITIS)
                || p.flag(Feature.PUSTULES)
                || p.flag(Feature.PURULENT_EYE)
                || (jaundice && extent == 2)
                || (p.flag(Feature.DIARRHEA) && p.flag(Feature.DEHYDRATION_SIGNS));

        return yellow ? TrafficLight.YELLOW : TrafficLight.GREEN;
    }

    // ------------------------------------------------------------------ the full assessment

    public static Outcome evaluate(SignProfile p, Subject subject, Vitals vitals, Mode mode) {
        TrafficLight base = label(p);
        List<DangerSign> signs = signsFrom(p, vitals);

        // Parent mode escalates a bare GREEN that still has *something* in it. A parent reporting
        // "she just seems off" with a preterm baby and reduced feeding has told us something a
        // trained observer would have followed up on, and we would rather send them to a clinic
        // for nothing than miss it.
        boolean escalated = false;
        TrafficLight light = base;
        if (mode.escalateBorderline() && base == TrafficLight.GREEN && borderline(p)) {
            light = TrafficLight.YELLOW;
            escalated = true;
        }

        Classification classification = classify(light, p, subject, signs, escalated);
        return new Outcome(light, classification, signs, escalated);
    }

    /**
     * Soft signals that do not fire the WHO rule but should not be waved away when the observer is
     * a first-time parent rather than a trained health worker.
     */
    private static boolean borderline(SignProfile p) {
        return p.flag(Feature.JAUNDICE_PRESENT)
                || p.flag(Feature.NASAL_FLARING)
                || p.flag(Feature.PRETERM)
                || p.flag(Feature.LOW_WEIGHT)
                || p.flag(Feature.DIARRHEA)
                || (p.isPresent(Feature.RESP_RATE) && p.get(Feature.RESP_RATE) >= 55);
    }

    // ------------------------------------------------------------------ signs with provenance

    private static List<DangerSign> signsFrom(SignProfile p, Vitals vitals) {
        List<DangerSign> out = new ArrayList<>();

        // Measured signs carry their number, because a referral letter that says "fast breathing"
        // is worth much less to a receiving clinician than one that says "respiratory rate 68".
        vitals.respiratoryRateOpt().ifPresent(rr -> {
            if (rr >= 60) out.add(new DangerSign.Measured(DangerSign.Sign.FAST_BREATHING, rr, "breaths/min"));
        });
        vitals.temperatureOpt().ifPresent(t -> {
            if (t >= 38.0) out.add(new DangerSign.Measured(DangerSign.Sign.FEVER, t, "C"));
            if (t < 35.5) out.add(new DangerSign.Measured(DangerSign.Sign.HYPOTHERMIA, t, "C"));
        });
        vitals.spo2Opt().ifPresent(s -> {
            if (s < 90) out.add(new DangerSign.Measured(DangerSign.Sign.CENTRAL_CYANOSIS, s, "% SpO2"));
        });

        Set<DangerSign.Sign> already = new LinkedHashSet<>();
        for (DangerSign s : out) already.add(s.sign());

        // Everything else came from what the caregiver said or what the health worker observed.
        addReported(out, already, p, Feature.CONVULSION, DangerSign.Sign.CONVULSION);
        addReported(out, already, p, Feature.FEEDING_UNABLE, DangerSign.Sign.UNABLE_TO_FEED);
        addReported(out, already, p, Feature.LETHARGY, DangerSign.Sign.LETHARGY);
        addReported(out, already, p, Feature.CHEST_INDRAWING, DangerSign.Sign.CHEST_INDRAWING);
        addReported(out, already, p, Feature.GRUNTING_STRIDOR, DangerSign.Sign.GRUNTING_OR_STRIDOR);
        addReported(out, already, p, Feature.RR_GE_60, DangerSign.Sign.FAST_BREATHING);
        addReported(out, already, p, Feature.WEAK_ABSENT_CRY, DangerSign.Sign.WEAK_OR_ABSENT_CRY);
        addReported(out, already, p, Feature.CENTRAL_CYANOSIS, DangerSign.Sign.CENTRAL_CYANOSIS);
        addReported(out, already, p, Feature.PALLOR, DangerSign.Sign.PALLOR);
        addReported(out, already, p, Feature.FEVER, DangerSign.Sign.FEVER);
        addReported(out, already, p, Feature.MEASURED_COLD, DangerSign.Sign.HYPOTHERMIA);
        addReported(out, already, p, Feature.OMPHALITIS_SEVERE, DangerSign.Sign.SEVERE_OMPHALITIS);
        addReported(out, already, p, Feature.UMBILICAL_BLEEDING, DangerSign.Sign.UMBILICAL_BLEEDING);
        addReported(out, already, p, Feature.PUSTULES_MANY, DangerSign.Sign.MANY_PUSTULES);
        addReported(out, already, p, Feature.BULGING_FONTANELLE, DangerSign.Sign.BULGING_FONTANELLE);
        addReported(out, already, p, Feature.DEHYDRATION_SEVERE, DangerSign.Sign.SEVERE_DEHYDRATION);

        if (p.flag(Feature.JAUNDICE_PRESENT)) {
            int extent = p.ordinal(Feature.JAUNDICE_EXTENT);
            int age = p.isPresent(Feature.AGE_DAYS) ? p.ordinal(Feature.AGE_DAYS) : 7;
            if (extent >= 3) {
                out.add(new DangerSign.Visual(DangerSign.Sign.JAUNDICE_EXTENSIVE, 1.0));
            } else if (extent == 2) {
                out.add(new DangerSign.Visual(DangerSign.Sign.JAUNDICE_TRUNK, 1.0));
            }
            if (age <= 1) {
                out.add(new DangerSign.Visual(DangerSign.Sign.JAUNDICE_DAY_ONE, 1.0));
            }
        }

        // Signs that only ever contribute yellow.
        if (p.flag(Feature.OMPHALITIS) && !p.flag(Feature.OMPHALITIS_SEVERE)) {
            addSign(out, already, p, Feature.OMPHALITIS, DangerSign.Sign.LOCAL_OMPHALITIS);
        }
        if (p.flag(Feature.PUSTULES) && !p.flag(Feature.PUSTULES_MANY)) {
            addSign(out, already, p, Feature.PUSTULES, DangerSign.Sign.SKIN_PUSTULES);
        }
        addReported(out, already, p, Feature.PURULENT_EYE, DangerSign.Sign.PURULENT_EYE);
        if (p.flag(Feature.DIARRHEA) && p.flag(Feature.DEHYDRATION_SIGNS)) {
            addSign(out, already, p, Feature.DEHYDRATION_SIGNS, DangerSign.Sign.DIARRHOEA_WITH_DEHYDRATION);
        }
        if (p.flag(Feature.FEEDING_POOR) && !p.flag(Feature.FEEDING_UNABLE)) {
            addSign(out, already, p, Feature.FEEDING_POOR, DangerSign.Sign.POOR_FEEDING);
        }
        addReported(out, already, p, Feature.LOW_WEIGHT, DangerSign.Sign.LOW_WEIGHT_FOR_AGE);
        addReported(out, already, p, Feature.PRETERM, DangerSign.Sign.PRETERM);

        return out;
    }

    private static void addReported(List<DangerSign> out, Set<DangerSign.Sign> already,
                                    SignProfile p, Feature f, DangerSign.Sign sign) {
        if (p.flag(f)) addSign(out, already, p, f, sign);
    }

    private static void addSign(List<DangerSign> out, Set<DangerSign.Sign> already,
                                SignProfile p, Feature f, DangerSign.Sign sign) {
        if (!already.add(sign)) return;
        out.add(new DangerSign.Reported(sign));
    }

    // ------------------------------------------------------------------ classification

    private static Classification classify(TrafficLight light, SignProfile p, Subject subject,
                                           List<DangerSign> signs, boolean modeEscalated) {
        List<String> reasons = new ArrayList<>();
        for (DangerSign s : signs) {
            if (light == TrafficLight.RED && !s.sign().red()) continue;
            reasons.add(s.sign().label() + " (" + s.provenance() + ")");
        }
        if (reasons.isEmpty() && modeEscalated) {
            reasons.add("Something in this check is worth a second opinion, even though no danger sign fired.");
        }

        return switch (light) {
            case RED -> new Classification.UrgentReferral(
                    redName(p),
                    reasons,
                    preReferral(p, subject));

            case YELLOW -> new Classification.TreatmentNeeded(
                    yellowName(p, modeEscalated),
                    reasons,
                    treatments(p, subject));

            case GREEN -> new Classification.HomeCare(
                    "NO DANGER SIGNS FOUND",
                    reasons,
                    List.of("Keep the baby warm, ideally skin-to-skin.",
                            "Breastfeed on demand, at least 8 times in 24 hours.",
                            "Keep the cord stump clean and dry.",
                            "Attend the scheduled postnatal visit."),
                    returnImmediatelyIf());
        };
    }

    private static String redName(SignProfile p) {
        boolean jaundiceDriven = p.flag(Feature.JAUNDICE_PRESENT)
                && (p.ordinal(Feature.JAUNDICE_EXTENT) >= 3 || p.ordinal(Feature.AGE_DAYS) <= 1);
        boolean infectionDriven = p.flag(Feature.FEVER) || p.flag(Feature.MEASURED_COLD)
                || p.flag(Feature.OMPHALITIS_SEVERE) || p.flag(Feature.PUSTULES_MANY)
                || p.flag(Feature.CHEST_INDRAWING) || p.flag(Feature.RR_GE_60)
                || p.flag(Feature.GRUNTING_STRIDOR) || p.flag(Feature.FEEDING_UNABLE)
                || p.flag(Feature.LETHARGY) || p.flag(Feature.CONVULSION);

        if (infectionDriven) return "POSSIBLE SERIOUS BACTERIAL INFECTION";
        if (jaundiceDriven) return "SEVERE JAUNDICE";
        if (p.flag(Feature.DEHYDRATION_SEVERE)) return "SEVERE DEHYDRATION";
        return "VERY SEVERE DISEASE";
    }

    private static String yellowName(SignProfile p, boolean modeEscalated) {
        if (p.flag(Feature.DIARRHEA) && p.flag(Feature.DEHYDRATION_SIGNS)) {
            return "DIARRHOEA WITH SOME DEHYDRATION";
        }
        if (p.flag(Feature.OMPHALITIS) || p.flag(Feature.PUSTULES) || p.flag(Feature.PURULENT_EYE)) {
            return "LOCAL BACTERIAL INFECTION";
        }
        if (p.flag(Feature.JAUNDICE_PRESENT)) return "JAUNDICE";
        return modeEscalated ? "WORTH CHECKING TODAY" : "TREATMENT NEEDED";
    }

    private static List<String> preReferral(SignProfile p, Subject subject) {
        List<String> out = new ArrayList<>();
        out.add("Refer urgently. Arrange transport before anything else.");
        out.add("Keep the baby warm on the way - skin-to-skin with the mother.");
        if (!p.flag(Feature.FEEDING_UNABLE)) {
            out.add("Encourage breastfeeding during transfer to prevent low blood sugar.");
        } else {
            out.add("The baby is not feeding: give expressed breast milk by cup or tube if trained to.");
        }
        if (subject.weightKnown()) {
            out.add("Give the first dose of antibiotic before transfer if transfer will take over 2 hours "
                    + "and you are trained to - use the dose calculator, never estimate.");
        }
        out.add("Send the referral letter with the family.");
        return out;
    }

    private static List<String> treatments(SignProfile p, Subject subject) {
        List<String> out = new ArrayList<>();
        if (p.flag(Feature.OMPHALITIS)) {
            out.add("Clean the cord stump with clean water, dry it, and apply chlorhexidine 7.1% gel once daily.");
        }
        if (p.flag(Feature.PUSTULES)) {
            out.add("Wash the pustules with soap and clean water twice daily, then dry gently.");
        }
        if (p.flag(Feature.PURULENT_EYE)) {
            out.add("Clean the eye with a clean damp cloth and apply tetracycline eye ointment.");
        }
        if (p.flag(Feature.DIARRHEA) && p.flag(Feature.DEHYDRATION_SIGNS)) {
            out.add("Start ORS plan B - use the ORS calculator for the volume.");
        }
        if (p.flag(Feature.JAUNDICE_PRESENT)) {
            out.add("Recheck the jaundice tomorrow and the day after. The rate of change matters more "
                    + "than any single reading.");
        }
        out.add("Teach the caregiver the danger signs that mean returning immediately.");
        out.add("Schedule the follow-up visit and record it.");
        return out;
    }

    /**
     * The list that goes home with every green result. Kangaroo never tells a caregiver that their
     * baby is fine and leaves it there.
     */
    public static List<String> returnImmediatelyIf() {
        return List.of("The baby stops feeding or feeds much less than usual.",
                "The baby becomes sleepy, floppy, or hard to wake.",
                "Breathing becomes fast, noisy, or the chest pulls in.",
                "The baby feels hot or unusually cold.",
                "Yellow colour reaches the arms, legs, palms or soles.",
                "The cord stump becomes red, smelly or starts bleeding.",
                "There are fits or twitching movements.");
    }
}
