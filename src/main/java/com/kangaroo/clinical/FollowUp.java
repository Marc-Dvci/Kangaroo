package com.kangaroo.clinical;

import com.kangaroo.core.TrafficLight;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * When to come back, on the WHO postnatal schedule, adjusted for what was found today.
 *
 * <p>The red branch is the interesting one. A referral that nobody verifies is a referral that did
 * not happen: families do not always reach the facility, and the commonest reason is transport, not
 * refusal. So a red classification schedules a mandatory check the next day whose purpose is to find
 * out whether the family actually got there — and, if they did not, to reassess and consider the
 * simplified outpatient regimen in {@link Psbi}.
 */
public final class FollowUp {

    private FollowUp() {}

    /**
     * @param date        the suggested date
     * @param inDays      how far away
     * @param visitType   what the visit is for
     * @param mandatory   true when skipping it is itself a clinical risk
     */
    public record Visit(
            LocalDate date,
            int inDays,
            String visitType,
            String urgency,
            boolean mandatory,
            List<String> instructions) {
        public Visit { instructions = List.copyOf(instructions); }
    }

    public static Visit suggest(TrafficLight light) {
        return suggest(light, Clock.systemDefaultZone());
    }

    /** Clock-injected overload so the scheduling logic is testable without freezing wall time. */
    public static Visit suggest(TrafficLight light, Clock clock) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.systemDefault()));

        return switch (light) {
            case RED -> new Visit(today.plusDays(1), 1, "Post-referral verification",
                    "Mandatory", true,
                    List.of("Confirm the family reached the facility and the infant was seen.",
                            "If they did not go, find out why - transport and cost are the usual reasons.",
                            "If referral is genuinely not possible, reassess and consider the "
                                    + "WHO simplified outpatient antibiotic regimen.",
                            "Record the outcome either way."));

            case YELLOW -> new Visit(today.plusDays(2), 2, "Treatment follow-up",
                    "Important", true,
                    List.of("Reassess the treated condition against today's findings.",
                            "Check that the medication was given, at the right dose, for the right days.",
                            "Weigh the infant and compare with today's weight.",
                            "Escalate to referral if anything has worsened."));

            case GREEN -> new Visit(today.plusDays(7), 7, "Routine postnatal visit",
                    "Scheduled", false,
                    List.of("Standard postnatal assessment.",
                            "Weigh, check feeding, check the cord.",
                            "Remind the caregiver of the danger signs that mean returning immediately."));
        };
    }
}
