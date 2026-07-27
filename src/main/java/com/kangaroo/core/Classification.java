package com.kangaroo.core;

import java.util.List;

/**
 * The WHO IMNCI outcome of an assessment.
 *
 * <p>Sealed, so {@link #light()} below and every consumer downstream is an exhaustive switch with
 * no default clause. There is deliberately no "unknown" or "error" case: a classification that
 * could not be computed is not represented here at all — the caller gets
 * {@link UrgentReferral} instead, because "I don't know" resolves to "go".
 */
public sealed interface Classification
        permits Classification.UrgentReferral, Classification.TreatmentNeeded, Classification.HomeCare {

    /** The IMNCI classification name, e.g. "POSSIBLE SERIOUS BACTERIAL INFECTION". */
    String name();

    /** Why. Every reason is traceable to an observed {@link DangerSign} or a measured value. */
    List<String> reasons();

    /**
     * No default clause, by design. Add a permitted subtype and this method stops compiling —
     * which is the entire point of modelling the domain this way.
     */
    default TrafficLight light() {
        return switch (this) {
            case UrgentReferral _ -> TrafficLight.RED;
            case TreatmentNeeded _ -> TrafficLight.YELLOW;
            case HomeCare _ -> TrafficLight.GREEN;
        };
    }

    /** Refer now, and treat before transport if transport will take time. */
    record UrgentReferral(String name, List<String> reasons, List<String> preReferralTreatment)
            implements Classification {
        public UrgentReferral {
            reasons = List.copyOf(reasons);
            preReferralTreatment = List.copyOf(preReferralTreatment);
        }
    }

    /** Treatable at this level of care, with a mandatory follow-up date. */
    record TreatmentNeeded(String name, List<String> reasons, List<String> treatments)
            implements Classification {
        public TreatmentNeeded {
            reasons = List.copyOf(reasons);
            treatments = List.copyOf(treatments);
        }
    }

    /**
     * Home care and counselling.
     *
     * <p>Note what this record does not have: a "reassurance" field. Kangaroo never tells a
     * caregiver that their baby is fine. It tells them that nothing it can see needs a clinician
     * today, and it always ships the return-immediately advice alongside.
     */
    record HomeCare(String name, List<String> reasons, List<String> advice, List<String> returnImmediatelyIf)
            implements Classification {
        public HomeCare {
            reasons = List.copyOf(reasons);
            advice = List.copyOf(advice);
            returnImmediatelyIf = List.copyOf(returnImmediatelyIf);
        }
    }
}
