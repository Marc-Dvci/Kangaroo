package com.kangaroo.clinical;

import com.kangaroo.core.Sex;

import java.util.List;

/**
 * Weight-for-age z-score by the WHO 2006 LMS method.
 *
 * <p>{@code Z = ((weight/M)^L - 1) / (L * S)}, degenerating to {@code ln(weight/M) / S} as L
 * approaches zero. The LMS triple is read from the WHO table for the infant's sex and interpolated
 * linearly between the bracketing days when the exact age is not tabulated.
 *
 * <p>This is a calculation, not a judgement, and it lives here rather than in a model for exactly
 * that reason: "is she gaining enough?" deserves a real answer, computed the same way every time
 * and checkable against a published table.
 */
public final class ZScore {

    private ZScore() {}

    /** Neonatal weight-for-age bands, in the WHO cut-offs. */
    public enum Band {
        SEVERELY_UNDERWEIGHT("Severely underweight (below -3 SD)"),
        UNDERWEIGHT("Underweight (below -2 SD)"),
        NORMAL("Normal weight for age"),
        OVERWEIGHT("Above +2 SD");

        private final String label;
        Band(String label) { this.label = label; }
        public String label() { return label; }
    }

    /**
     * @param z          the z-score, rounded to 2 decimals as WHO reports it
     * @param band       the classification band
     * @param percentile the equivalent percentile of the standard normal
     * @param medianKg   the WHO median weight for this age and sex, for context in the UI
     */
    public record Result(double z, Band band, double percentile, double medianKg,
                         double weightKg, int ageDays, Sex sex) {

        public boolean concerning() {
            return band == Band.UNDERWEIGHT || band == Band.SEVERELY_UNDERWEIGHT;
        }

        /** What a parent is actually asking when they ask about weight. */
        public String plainLanguage() {
            return switch (band) {
                case SEVERELY_UNDERWEIGHT -> "Your baby weighs much less than expected for this age. See a health worker today.";
                case UNDERWEIGHT -> "Your baby weighs less than expected for this age. Worth having someone check feeding.";
                case NORMAL -> "Your baby's weight is in the expected range for this age.";
                case OVERWEIGHT -> "Your baby weighs more than expected for this age. Not usually a concern on its own.";
            };
        }
    }

    /**
     * @throws IllegalArgumentException on a weight or age outside the neonatal assessment range.
     *         Refusing is correct here: silently extrapolating a growth standard past its published
     *         domain would produce a number that looks authoritative and is not.
     */
    public static Result calculate(double weightKg, int ageDays, Sex sex) {
        if (weightKg <= 0) {
            throw new IllegalArgumentException("weight must be positive, got " + weightKg);
        }
        if (ageDays < 0 || ageDays > 28) {
            throw new IllegalArgumentException("age must be 0-28 days for the neonatal assessment, got " + ageDays);
        }

        Reference.Lms row = interpolate(Reference.lms(sex), ageDays);
        double z = Math.abs(row.l()) > 0.01
                ? (Math.pow(weightKg / row.m(), row.l()) - 1) / (row.l() * row.s())
                : Math.log(weightKg / row.m()) / row.s();

        z = Math.round(z * 100.0) / 100.0;

        Band band;
        if (z < -3) band = Band.SEVERELY_UNDERWEIGHT;
        else if (z < -2) band = Band.UNDERWEIGHT;
        else if (z > 2) band = Band.OVERWEIGHT;
        else band = Band.NORMAL;

        double percentile = Math.round(0.5 * (1 + erf(z / Math.sqrt(2))) * 1000.0) / 10.0;
        double median = Math.round(row.m() * 100.0) / 100.0;

        return new Result(z, band, percentile, median, weightKg, ageDays, sex);
    }

    /** Linear interpolation between the bracketing tabulated days; clamped at both ends. */
    static Reference.Lms interpolate(List<Reference.Lms> table, int ageDays) {
        Reference.Lms lower = null;
        Reference.Lms upper = null;
        for (Reference.Lms row : table) {
            if (row.ageDays() == ageDays) return row;
            if (row.ageDays() < ageDays && (lower == null || row.ageDays() > lower.ageDays())) lower = row;
            if (row.ageDays() > ageDays && (upper == null || row.ageDays() < upper.ageDays())) upper = row;
        }
        if (lower == null) return upper != null ? upper : table.getFirst();
        if (upper == null) return lower;

        double f = (double) (ageDays - lower.ageDays()) / (upper.ageDays() - lower.ageDays());
        return new Reference.Lms(ageDays,
                lower.l() + f * (upper.l() - lower.l()),
                lower.m() + f * (upper.m() - lower.m()),
                lower.s() + f * (upper.s() - lower.s()));
    }

    /**
     * Abramowitz &amp; Stegun 7.1.26 error function, accurate to about 1.5e-7 — well inside the
     * precision at which a percentile is reported to a health worker.
     */
    static double erf(double x) {
        double sign = Math.signum(x);
        double ax = Math.abs(x);
        double t = 1.0 / (1.0 + 0.3275911 * ax);
        double y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t
                + 0.254829592) * t * Math.exp(-ax * ax);
        return sign * y;
    }
}
