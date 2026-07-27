package com.kangaroo.core;

/**
 * The infant. Deliberately minimal: Kangaroo stores what the protocol needs and nothing else,
 * and everything here stays on the device unless the caregiver explicitly consents to a sync.
 *
 * @param ageDays  age in days, 0..28 for the neonatal protocol; -1 when genuinely unknown
 * @param weightKg weight in kilograms, or -1 when not weighed
 * @param sex      required for the WHO weight-for-age tables
 * @param preterm  born before 37 weeks
 */
public record Subject(int ageDays, double weightKg, Sex sex, boolean preterm) {

    public static final int UNKNOWN_AGE = -1;
    public static final double UNKNOWN_WEIGHT = -1.0;

    public Subject {
        if (ageDays < -1 || ageDays > 365) {
            throw new IllegalArgumentException("ageDays out of range: " + ageDays);
        }
        if (weightKg != UNKNOWN_WEIGHT && (weightKg <= 0 || weightKg > 15)) {
            throw new IllegalArgumentException("weightKg out of plausible range: " + weightKg);
        }
        if (sex == null) throw new IllegalArgumentException("sex is required");
    }

    public static Subject unknown() {
        return new Subject(UNKNOWN_AGE, UNKNOWN_WEIGHT, Sex.MALE, false);
    }

    public boolean ageKnown() { return ageDays >= 0; }

    public boolean weightKnown() { return weightKg > 0; }

    /** IMNCI treats day 0 and day 1 specially — jaundice on day one is always urgent. */
    public boolean firstDayOfLife() { return ageKnown() && ageDays <= 1; }

    /** Low birth weight / low weight for age threshold used by the danger-sign profile. */
    public boolean lowWeight() { return weightKnown() && weightKg < 2.5; }

    /** Age used by the rule engine when the caregiver could not say. Mid-neonatal, deliberately neutral. */
    public int ageDaysOrDefault() { return ageKnown() ? ageDays : 7; }
}
