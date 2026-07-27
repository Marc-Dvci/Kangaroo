package com.kangaroo.clinical;

import java.util.List;

/**
 * Oral rehydration solution volumes, per the WHO plan A/B/C structure adapted to the young infant.
 *
 * <p>Diarrhoea in a neonate is never routine, so even the "no dehydration" branch here carries a
 * return-immediately instruction rather than plain reassurance.
 */
public final class Ors {

    private Ors() {}

    /** WHO dehydration assessment for the young infant. */
    public enum Dehydration {
        NONE("No dehydration"),
        SOME("Some dehydration"),
        SEVERE("Severe dehydration");

        private final String label;
        Dehydration(String label) { this.label = label; }
        public String label() { return label; }

        public static Dehydration parse(String s) {
            if (s == null) return NONE;
            return switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "some", "moderate" -> SOME;
                case "severe" -> SEVERE;
                default -> NONE;
            };
        }
    }

    /**
     * @param plan          the WHO plan letter
     * @param volumeMl      total volume over the treatment window, or -1 when dosed per stool
     * @param volumePerHour hourly volume, or -1
     * @param window        how long the volume is given over
     * @param referUrgently true when ORS alone is not sufficient treatment
     */
    public record Plan(
            Dehydration dehydration,
            String plan,
            String recommendation,
            double volumeMl,
            double volumePerHour,
            String window,
            String mixing,
            List<String> instructions,
            boolean referUrgently,
            double weightKg) {
        public Plan {
            instructions = List.copyOf(instructions);
        }
    }

    public static Plan calculate(double weightKg, Dehydration dehydration) {
        if (weightKg < Dosing.MIN_WEIGHT_KG || weightKg > Dosing.MAX_WEIGHT_KG) {
            throw new IllegalArgumentException(
                    "weight " + weightKg + " kg is outside the neonatal range");
        }

        return switch (dehydration) {
            case NONE -> new Plan(dehydration, "A", "Prevent dehydration at home",
                    -1, -1, "after each loose stool",
                    "One sachet in one litre of clean water. Discard after 24 hours.",
                    List.of("Give 50-100 ml of ORS after each loose stool, by cup or oral syringe.",
                            "Keep breastfeeding on demand - do not stop or dilute feeds.",
                            "Return immediately if the baby feeds less, becomes sleepy, or passes no urine for 6 hours."),
                    false, weightKg);

            case SOME -> {
                double total = Math.round(75 * weightKg);
                yield new Plan(dehydration, "B", "Rehydrate under observation",
                        total, Math.round(total / 4.0), "4 hours",
                        "One sachet in one litre of clean water. Discard after 24 hours.",
                        List.of("Give " + (long) total + " ml over 4 hours - about "
                                        + (long) Math.round(total / 4.0) + " ml each hour.",
                                "Give small frequent sips by cup or oral syringe. Never by bottle.",
                                "If the baby vomits, wait 10 minutes then continue more slowly.",
                                "Keep breastfeeding between ORS doses.",
                                "Reassess after 4 hours."),
                        false, weightKg);
            }

            case SEVERE -> new Plan(dehydration, "C", "URGENT REFERRAL - severe dehydration",
                    Math.round(20 * weightKg), Math.round(20 * weightKg), "each hour, en route",
                    "One sachet in one litre of clean water.",
                    List.of("Severe dehydration in a young infant needs intravenous fluids. Refer now.",
                            "Start ORS by mouth or nasogastric tube on the way if IV is not available.",
                            "Give about " + (long) Math.round(20 * weightKg) + " ml each hour during transport.",
                            "Keep the baby warm and skin-to-skin during transfer.",
                            "Do not delay transport to complete rehydration."),
                    true, weightKg);
        };
    }
}
