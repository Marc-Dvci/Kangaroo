package com.kangaroo.core;

import java.util.List;

/**
 * A single observed danger sign, tagged by how it was observed.
 *
 * <p>This is the type that makes the safety argument for Java in this domain. It is {@code sealed},
 * so every consumer that switches over a danger sign must handle every kind, and the compiler —
 * not a field incident — is what tells you when someone adds a new one. Adding a permitted subtype
 * or a new {@link Sign} constant breaks the build of every incomplete switch in the repository.
 *
 * <p>The provenance distinction is clinical, not decorative. A {@link Measured} respiratory rate of
 * 62 and a {@link Reported} "she's breathing fast" are not the same evidence, they do not carry the
 * same weight, and a referral letter must say which one it had.
 */
public sealed interface DangerSign
        permits DangerSign.Visual, DangerSign.Auditory, DangerSign.Reported, DangerSign.Measured {

    /** The underlying WHO IMNCI sign. */
    Sign sign();

    /** How much we trust this observation, 0..1. Reported signs are taken at face value (1.0). */
    double confidence();

    /** Human-readable provenance, printed on the referral letter. */
    default String provenance() {
        return switch (this) {
            case Visual v -> "observed on camera";
            case Auditory a -> "heard in the cry recording";
            case Reported r -> "reported by caregiver";
            case Measured m -> "measured (" + m.value() + " " + m.unit() + ")";
        };
    }

    /** Seen by the camera or by the health worker's eye. */
    record Visual(Sign sign, double confidence) implements DangerSign {
        public Visual {
            requireVisual(sign);
        }
        private static void requireVisual(Sign s) {
            if (!s.observableVisually()) {
                throw new IllegalArgumentException(s + " is not a visually observable sign");
            }
        }
    }

    /** Extracted from the cry recording. */
    record Auditory(Sign sign, double confidence) implements DangerSign {}

    /** Stated by the caregiver during intake. */
    record Reported(Sign sign) implements DangerSign {
        @Override public double confidence() { return 1.0; }
    }

    /** Backed by a number — a counted respiratory rate, a thermometer, a scale, a pulse oximeter. */
    record Measured(Sign sign, double value, String unit) implements DangerSign {
        @Override public double confidence() { return 1.0; }
    }

    /**
     * The WHO IMNCI young-infant danger signs Kangaroo can represent.
     *
     * <p>{@link #red()} marks the signs that are, on their own, sufficient for urgent referral in
     * the 0–59 day age band. Everything else contributes but does not decide alone.
     */
    enum Sign {
        CONVULSION                 (true,  false, "Convulsions or fits"),
        UNABLE_TO_FEED             (true,  true,  "Not feeding at all"),
        LETHARGY                   (true,  true,  "Lethargic, floppy or unrousable"),
        CHEST_INDRAWING            (true,  true,  "Severe chest indrawing"),
        GRUNTING_OR_STRIDOR        (true,  true,  "Grunting or stridor"),
        FAST_BREATHING             (true,  true,  "Respiratory rate 60 or more"),
        WEAK_OR_ABSENT_CRY         (true,  true,  "Weak, absent or high-pitched cry"),
        CENTRAL_CYANOSIS           (true,  true,  "Blue lips or tongue"),
        PALLOR                     (true,  true,  "Marked pallor"),
        FEVER                      (true,  false, "Temperature 38.0 C or above"),
        HYPOTHERMIA                (true,  true,  "Temperature below 35.5 C, cold to touch"),
        SEVERE_OMPHALITIS          (true,  true,  "Umbilical redness spreading to the skin"),
        UMBILICAL_BLEEDING         (true,  true,  "Bleeding from the cord stump"),
        MANY_PUSTULES              (true,  true,  "Ten or more skin pustules"),
        BULGING_FONTANELLE         (true,  true,  "Bulging or tense fontanelle"),
        SEVERE_DEHYDRATION         (true,  true,  "Severe dehydration"),
        JAUNDICE_EXTENSIVE         (true,  true,  "Jaundice reaching limbs, palms or soles"),
        JAUNDICE_DAY_ONE           (true,  true,  "Jaundice on the first day of life"),

        LOCAL_OMPHALITIS           (false, true,  "Localised umbilical redness or discharge"),
        SKIN_PUSTULES              (false, true,  "Fewer than ten skin pustules"),
        PURULENT_EYE               (false, true,  "Pus draining from the eye"),
        JAUNDICE_TRUNK             (false, true,  "Jaundice reaching the trunk"),
        DIARRHOEA_WITH_DEHYDRATION (false, true,  "Diarrhoea with some dehydration"),
        POOR_FEEDING               (false, true,  "Feeding less than usual"),
        LOW_WEIGHT_FOR_AGE         (false, true,  "Low weight for age"),
        PRETERM                    (false, false, "Born before 37 weeks");

        private final boolean red;
        private final boolean visual;
        private final String label;

        Sign(boolean red, boolean visual, String label) {
            this.red = red;
            this.visual = visual;
            this.label = label;
        }

        /** True if this sign alone justifies urgent referral under IMNCI. */
        public boolean red() { return red; }

        /** True if a camera or an eye can see it (used to validate {@link Visual}). */
        public boolean observableVisually() { return visual; }

        public String label() { return label; }

        public static List<Sign> redSigns() {
            return List.of(values()).stream().filter(Sign::red).toList();
        }
    }
}
