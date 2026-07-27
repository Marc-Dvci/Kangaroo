package com.kangaroo.clinical;

import com.kangaroo.audit.ClinicalEvents;

/**
 * Neonatal medication dosing with hard, non-negotiable ceilings.
 *
 * <p><strong>The rule the whole system is built around: the model may request a calculation, it may
 * never produce a number.</strong> A language model that writes "give 4 ml" is a language model
 * making up a dose. Every number a health worker sees comes from this class, from a WHO reference
 * table, through arithmetic that is a dozen lines long and can be checked by hand.
 *
 * <p>The ceiling is applied unconditionally and is not a suggestion. It is also not applied
 * silently: a capped dose emits {@link ClinicalEvents.DoseCapped} to the flight recorder and is
 * surfaced in the UI, because a dose that hit a ceiling means the weight, the age or the medication
 * choice deserves a second look by a person.
 *
 * <p>The safety property — <em>no input, ever, produces a dose above the ceiling</em> — is machine
 * checked over millions of generated inputs in {@code DosingPropertyTest}, not asserted by three
 * examples.
 */
public final class Dosing {

    private Dosing() {}

    /** The safe neonatal weight range this calculator will accept at all. */
    public static final double MIN_WEIGHT_KG = 0.5;
    public static final double MAX_WEIGHT_KG = 10.0;

    /**
     * @param medication        display name
     * @param dose              the amount to give per dose, after the ceiling
     * @param unit              "mg" or "IU"
     * @param volumeMl          the volume to draw up, or -1 for non-liquid routes
     * @param capApplied        true when the weight-based dose was reduced to the ceiling
     * @param uncappedDose      what the weight-based calculation produced before capping
     * @param ceiling           the ceiling that was applied
     */
    public record Dose(
            String medicationKey,
            String medication,
            double dose,
            String unit,
            double volumeMl,
            int dosesPerDay,
            int durationDays,
            String route,
            String frequency,
            String instruction,
            String source,
            boolean capApplied,
            double uncappedDose,
            double ceiling,
            double weightKg) {

        /** The single line a health worker reads off the screen. */
        public String prescription() {
            String amount = Reference.Medication.formatAmount(dose) + " " + unit;
            String vol = volumeMl > 0 ? " (" + Reference.Medication.formatAmount(volumeMl) + " ml)" : "";
            return medication + " - give " + amount + vol + ", " + frequency;
        }

        public String safetyNote() {
            return capApplied
                    ? "Dose limited to the " + Reference.Medication.formatAmount(ceiling) + " " + unit
                      + " neonatal ceiling (weight-based calculation gave "
                      + Reference.Medication.formatAmount(uncappedDose) + " " + unit
                      + "). Confirm weight and age with a clinician."
                    : "Verify with a qualified health professional before administration.";
        }
    }

    /** A topical medication, which has no weight-based amount at all. */
    public record Topical(
            String medicationKey,
            String medication,
            String application,
            String frequency,
            String route,
            String instruction,
            String source) {}

    /**
     * The result is one or the other, and the sealed pair forces every caller to handle the topical
     * case rather than reading a {@code null} dose off a chlorhexidine result.
     */
    public sealed interface Result permits Weighted, NotWeighed {}

    public record Weighted(Dose dose) implements Result {}

    public record NotWeighed(Topical topical) implements Result {}

    /**
     * Calculate a dose.
     *
     * @throws IllegalArgumentException when the weight is outside the safe neonatal range or the
     *         medication is unknown. Both are refusals rather than best-effort answers.
     */
    public static Result calculate(String medicationKey, double weightKg) {
        Reference.Medication med = Reference.medication(medicationKey);

        if (!med.weightBased()) {
            return new NotWeighed(new Topical(
                    med.key(), med.displayName(),
                    "Apply a thin layer to the umbilical stump and 2 cm of surrounding skin",
                    "Once daily until the cord separates",
                    med.route(), med.instruction(), med.source()));
        }

        // NaN must be rejected explicitly. Every ordinary comparison against NaN is false, so a
        // range check written as `weight < MIN || weight > MAX` lets it straight through, and it
        // then propagates silently through the arithmetic to produce a dose of NaN. A dosing
        // routine that returns NaN has failed in the worst available way: quietly.
        if (!Double.isFinite(weightKg) || weightKg < MIN_WEIGHT_KG || weightKg > MAX_WEIGHT_KG) {
            throw new IllegalArgumentException(
                    "weight " + weightKg + " kg is outside the safe neonatal dosing range ("
                            + MIN_WEIGHT_KG + "-" + MAX_WEIGHT_KG + " kg)");
        }

        double uncapped = weightKg * med.perKgPerDose();
        double dose = Math.min(uncapped, med.maxSingleDose());
        boolean capped = dose < uncapped;

        // The ceiling is the last thing that touches the number. Nothing below this line raises it.
        assert dose <= med.maxSingleDose() : "dosing ceiling violated";

        double volumeMl = med.concentrationPerMl() > 0
                ? Math.round(dose / med.concentrationPerMl() * 10.0) / 10.0
                : -1;

        if (capped) {
            ClinicalEvents.doseCapped(med.key(), weightKg, uncapped, med.maxSingleDose(), med.unit());
        }

        return new Weighted(new Dose(
                med.key(),
                med.displayName(),
                Math.round(dose * 10.0) / 10.0,
                med.unit(),
                volumeMl,
                med.dosesPerDay(),
                med.durationDays(),
                med.route(),
                frequency(med),
                med.instruction(),
                med.source(),
                capped,
                Math.round(uncapped * 10.0) / 10.0,
                med.maxSingleDose(),
                weightKg));
    }

    private static String frequency(Reference.Medication med) {
        String every = switch (med.dosesPerDay()) {
            case 1 -> "once daily";
            case 2 -> "every 12 hours";
            case 3 -> "every 8 hours";
            case 4 -> "every 6 hours";
            default -> med.dosesPerDay() + " times a day";
        };
        return med.durationDays() > 0 ? every + " for " + med.durationDays() + " days" : every;
    }
}
