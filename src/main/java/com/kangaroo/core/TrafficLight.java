package com.kangaroo.core;

/**
 * The one output every path in Kangaroo must be able to produce.
 *
 * <p>The whole safety architecture rests on this being decided identically at every rung of the
 * inference ladder — native model, cloud model, or deterministic rules with no model at all.
 * A degraded Kangaroo gives you a less eloquent explanation. It does not give you a different colour.
 */
public enum TrafficLight {

    /** Nothing here needs a clinician today. Never phrased to the user as "this is fine". */
    GREEN,

    /** Treatable at this level of care, but it needs treating and a scheduled follow-up. */
    YELLOW,

    /** Refer now. Every ambiguous case resolves upward into this one. */
    RED;

    /** Ordering for "escalate upward on doubt". RED wins over YELLOW wins over GREEN. */
    public TrafficLight escalatedWith(TrafficLight other) {
        return this.ordinal() >= other.ordinal() ? this : other;
    }

    public boolean atLeast(TrafficLight other) {
        return this.ordinal() >= other.ordinal();
    }

    /** Parse defensively: anything we cannot read is treated as RED, never as GREEN. */
    public static TrafficLight parseOrRed(String s) {
        if (s == null) return RED;
        return switch (s.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "GREEN", "HOME_CARE", "HOME CARE" -> GREEN;
            case "YELLOW", "TREATMENT_NEEDED", "TREATMENT NEEDED" -> YELLOW;
            default -> RED;
        };
    }
}
