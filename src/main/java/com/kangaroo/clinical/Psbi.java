package com.kangaroo.clinical;

import java.util.ArrayList;
import java.util.List;

/**
 * The WHO simplified outpatient antibiotic regimen, for when referral is genuinely not possible.
 *
 * <p>This is the branch that most decision-support tools leave out, and it is the branch that
 * decides outcomes. The guidance says "refer urgently", and sometimes there is no vehicle, no road,
 * no money, or no one to mind the other children. WHO published a simplified regimen for exactly
 * this situation in 2015 precisely because the alternative in practice is that the infant receives
 * nothing at all.
 *
 * <p>Kangaroo therefore treats "referral not possible" as a real clinical state with its own
 * protocol, rather than as a failure to follow instructions. Two hard conditions apply, and this
 * class enforces both: the regimen is only offered after urgent referral has been advised and
 * declined or found impossible, and it never replaces the daily follow-up that makes it safe.
 */
public final class Psbi {

    private Psbi() {}

    /** Which of the WHO simplified regimens applies, by the signs present. */
    public enum Regimen {
        /**
         * Fast breathing alone, in an infant 7-59 days old, with no other danger sign:
         * oral amoxicillin only.
         */
        FAST_BREATHING_ONLY,
        /**
         * Clinical severe infection where referral is not possible: gentamicin injection plus
         * oral amoxicillin.
         */
        CLINICAL_SEVERE_INFECTION
    }

    /**
     * @param regimen        the applicable simplified regimen
     * @param medications    the medication keys to dose, in the order they are given
     * @param steps          what the health worker does, in order
     * @param followUpDays   the days on which the infant must be seen again
     * @param eligible       false when the regimen must not be offered at all
     * @param ineligibleWhy  why, when not eligible
     */
    public record Protocol(
            Regimen regimen,
            List<String> medications,
            List<String> steps,
            List<Integer> followUpDays,
            boolean eligible,
            String ineligibleWhy) {
        public Protocol {
            medications = List.copyOf(medications);
            steps = List.copyOf(steps);
            followUpDays = List.copyOf(followUpDays);
        }
    }

    /**
     * @param ageDays               infant age
     * @param fastBreathingOnly     true when fast breathing is the only danger sign present
     * @param referralAdvisedAndRefused true when urgent referral was advised and is genuinely not possible
     * @param criticalSignsPresent  convulsions, unable to feed at all, unconscious, or unable to move
     */
    public static Protocol evaluate(int ageDays,
                                    boolean fastBreathingOnly,
                                    boolean referralAdvisedAndRefused,
                                    boolean criticalSignsPresent) {

        if (!referralAdvisedAndRefused) {
            return ineligible("Urgent referral has not yet been advised and declined. "
                    + "Refer first. This regimen exists only for when transfer is impossible.");
        }

        if (criticalSignsPresent) {
            return ineligible("Convulsions, inability to feed at all, unconsciousness or inability to move "
                    + "are not covered by the outpatient regimen. This infant needs inpatient care. "
                    + "Keep trying to arrange transport.");
        }

        if (fastBreathingOnly && ageDays >= 7 && ageDays <= 59) {
            return new Protocol(Regimen.FAST_BREATHING_ONLY,
                    List.of("amoxicillin_oral"),
                    List.of("Confirm the respiratory rate by counting again over a full 60 seconds, "
                                    + "with the infant calm.",
                            "Confirm no other danger sign is present. If any is, this regimen does not apply.",
                            "Give oral amoxicillin twice daily for 7 days. Use the dose calculator.",
                            "Teach the caregiver to give the dose and to watch for the danger signs.",
                            "Watch the first dose being given.",
                            "See the infant again on day 1, day 2 (or 3) and day 4 (or 7)."),
                    List.of(1, 3, 7),
                    true, "");
        }

        if (fastBreathingOnly) {
            return ineligible("Fast breathing alone in an infant under 7 days old is treated as clinical "
                    + "severe infection, not as the amoxicillin-only regimen.");
        }

        return new Protocol(Regimen.CLINICAL_SEVERE_INFECTION,
                List.of("gentamicin_im", "amoxicillin_oral"),
                List.of("Give gentamicin by intramuscular injection once daily for 2 days. "
                                + "Use the dose calculator - never estimate.",
                        "Give oral amoxicillin twice daily for 7 days. Use the dose calculator.",
                        "Watch the first dose of each being given.",
                        "Keep the infant warm, skin-to-skin, and support continued breastfeeding.",
                        "See the infant every day for the first 3 days, then on day 4 and day 7.",
                        "Refer immediately at any visit if the infant worsens or a new danger sign appears.",
                        "Keep trying to arrange transfer throughout."),
                List.of(1, 2, 3, 4, 7),
                true, "");
    }

    private static Protocol ineligible(String why) {
        return new Protocol(Regimen.CLINICAL_SEVERE_INFECTION, List.of(), List.of(), List.of(), false, why);
    }

    /** The counselling that must accompany any outpatient regimen. */
    public static List<String> caregiverCounselling() {
        List<String> out = new ArrayList<>();
        out.add("This treatment is being given because reaching a facility is not possible today. "
                + "It is second best. Keep trying to arrange transport.");
        out.add("Give every dose, at the right time, for the full number of days, even if the baby looks better.");
        out.add("Come back immediately - do not wait for the next visit - if the baby feeds less, "
                + "becomes sleepy or floppy, breathes fast or noisily, feels hot or cold, or has fits.");
        out.addAll(ImnciRule.returnImmediatelyIf());
        return List.copyOf(out);
    }
}
