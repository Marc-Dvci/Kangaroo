package com.kangaroo.core;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Colorimetric jaundice grading from a calibration-card photo.
 *
 * <p>Two things here are honest in a way this kind of feature usually is not. First,
 * {@link #kramerZones()} reports how far the yellow has progressed down the body rather than a
 * single central reading, because cephalocaudal extent is the actual clinical variable. Second,
 * {@link #refused} exists: when the frame is too dark, the card is missing, or the skin-tone
 * confidence is insufficient, Kangaroo declines to grade rather than producing a number it cannot
 * stand behind.
 *
 * @param severity   the graded severity
 * @param probs      probabilities across all five severities
 * @param kramerZone the highest Kramer zone showing jaundice, 0..5
 * @param kramerZones per-zone yellowness index, head to soles
 * @param refused    true when the image was not gradeable; severity is then meaningless
 * @param refusalReason why, in words the user can act on ("too dark, take it again")
 */
public record JaundiceGrade(
        Severity severity,
        Map<Severity, Double> probs,
        int kramerZone,
        List<Double> kramerZones,
        boolean refused,
        String refusalReason) {

    public JaundiceGrade {
        probs = Map.copyOf(probs);
        kramerZones = List.copyOf(kramerZones);
    }

    /** Grades in increasing order of concern. */
    public enum Severity {
        NORMAL(TrafficLight.GREEN),
        MILD(TrafficLight.GREEN),
        MODERATE(TrafficLight.YELLOW),
        HIGH(TrafficLight.RED),
        SEVERE(TrafficLight.RED);

        private final TrafficLight light;

        Severity(TrafficLight light) { this.light = light; }

        public TrafficLight light() { return light; }
    }

    public static JaundiceGrade refused(String reason) {
        Map<Severity, Double> p = new EnumMap<>(Severity.class);
        for (Severity s : Severity.values()) p.put(s, 0.0);
        return new JaundiceGrade(Severity.NORMAL, p, 0, List.of(), true, reason);
    }

    public static JaundiceGrade of(double[] p, int kramerZone, List<Double> zones) {
        if (p.length != 5) throw new IllegalArgumentException("expected 5 severity probabilities");
        Map<Severity, Double> m = new EnumMap<>(Severity.class);
        Severity[] all = Severity.values();
        int arg = 0;
        for (int i = 0; i < 5; i++) {
            m.put(all[i], p[i]);
            if (p[i] > p[arg]) arg = i;
        }
        return new JaundiceGrade(all[arg], m, kramerZone, zones, false, "");
    }

    /**
     * The traffic light this grading contributes.
     *
     * <p>A refusal contributes GREEN, not RED: an ungradeable photo is not evidence of disease, and
     * escalating on it would train users to distrust the whole tool. The refusal is surfaced in the
     * UI as "retake this photo" instead.
     */
    public TrafficLight light() {
        return refused ? TrafficLight.GREEN : severity.light();
    }

    /** Kramer zone 4 or 5 — jaundice on the forearms, legs, palms or soles — is a referral trigger. */
    public boolean extensive() {
        return !refused && kramerZone >= 4;
    }

    public double probability(Severity s) {
        return probs.getOrDefault(s, 0.0);
    }
}
