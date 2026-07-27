package com.kangaroo.motion;

import java.util.Optional;

/**
 * Respiratory rate measured from chest movement, using the camera as a motion sensor.
 *
 * <h2>Why this exists</h2>
 * Counting breaths for a full minute is the single most error-prone step in the IMNCI young-infant
 * assessment, and the threshold it feeds is unforgiving: fifty-nine breaths a minute is normal and
 * sixty is fast breathing. A health worker doing this at the end of a long day, on a baby who will
 * not stay still, taps a number that nobody can check afterwards.
 *
 * <p>Pointing the camera at the chest for fifteen seconds gives a second, independent measurement of
 * the same thing — and, unlike the tap count, one that can be recomputed from the recorded signal.
 * The two do not replace each other: when they disagree, that disagreement is the finding.
 *
 * <h2>What arrives here</h2>
 * Not video. The client reduces each frame to a single number — mean absolute difference from the
 * previous frame over the chest region — and sends that one-dimensional signal. Fifteen seconds at
 * ten frames a second is 150 floats rather than 150 JPEGs, which matters when the upload is a phone
 * on a village Wi-Fi, and it means no image of the infant has to leave the device for this
 * measurement at all.
 *
 * <h2>Sampling</h2>
 * A newborn breathes at roughly 30 to 70 breaths a minute, which is 0.5 to 1.17 Hz. Nyquist puts the
 * floor for that band at about 2.4 frames a second; the client targets ten, which leaves room for
 * dropped frames without the estimate folding an aliased harmonic back into the band.
 */
public final class RespiratoryMotion {

    /** The clinically plausible band for a young infant, in breaths per minute. */
    public static final double MIN_RATE = 20;
    public static final double MAX_RATE = 120;

    /** Below this the signal is not periodic enough to call a rate from. */
    private static final double MIN_PERIODICITY = 0.35;
    private static final double MIN_SECONDS = 8.0;
    private static final double MIN_FPS = 2.5;

    private RespiratoryMotion() {}

    /** Why a motion trace could not produce a rate. */
    public enum Refusal {
        TOO_SHORT("Hold the camera on the chest for about fifteen seconds."),
        TOO_SLOW("The camera could not capture frames fast enough to count breathing."),
        NOT_PERIODIC("The movement was not regular enough to count. Hold the phone still, with the "
                + "baby's chest filling the frame, while they are calm."),
        FLAT("No chest movement was detected. Check the framing and the light.");

        private final String message;

        Refusal(String message) { this.message = message; }

        public String message() { return message; }
    }

    /**
     * @param measured      breaths per minute, when one could be measured
     * @param refusal       why not, otherwise
     * @param periodicity   how periodic the trace was, 0..1; the confidence in {@code measured}
     * @param seconds       length of the trace
     */
    public record Result(Optional<Double> measured, Optional<Refusal> refusal,
                         double periodicity, double seconds) {

        public boolean measuredOk() { return measured.isPresent(); }

        static Result refused(Refusal why, double seconds) {
            return new Result(Optional.empty(), Optional.of(why), 0, seconds);
        }

        public String summary() {
            return measured
                    .map(r -> "%.0f breaths per minute from chest movement".formatted(r))
                    .orElseGet(() -> refusal.map(Refusal::message).orElse("not measured"));
        }
    }

    /**
     * Estimate the rate from a motion trace.
     *
     * @param signal per-frame chest movement, arbitrary units; only its periodicity is used
     * @param fps    frames per second the trace was sampled at
     */
    public static Result analyse(double[] signal, double fps) {
        if (signal == null || fps < MIN_FPS) {
            return Result.refused(Refusal.TOO_SLOW, 0);
        }
        double seconds = signal.length / fps;
        if (seconds < MIN_SECONDS) return Result.refused(Refusal.TOO_SHORT, seconds);

        double[] x = detrend(signal);

        // A trace with no variation is a camera pointed at a wall, not a still baby.
        double energy = 0;
        for (double v : x) energy += v * v;
        if (Math.sqrt(energy / x.length) < 1e-6) return Result.refused(Refusal.FLAT, seconds);

        int minLag = (int) Math.floor(fps * 60.0 / MAX_RATE);
        int maxLag = (int) Math.ceil(fps * 60.0 / MIN_RATE);
        maxLag = Math.min(maxLag, x.length / 2);
        if (minLag < 1 || maxLag <= minLag) return Result.refused(Refusal.TOO_SHORT, seconds);

        double[] score = new double[maxLag + 1];
        double best = 0;
        for (int lag = minLag; lag <= maxLag; lag++) {
            score[lag] = normalisedCorrelation(x, lag);
            best = Math.max(best, score[lag]);
        }
        if (best < MIN_PERIODICITY) return Result.refused(Refusal.NOT_PERIODIC, seconds);

        // Shortest lag within 90% of the best, for the same octave reason the cry analysis has it:
        // a periodic trace correlates at every multiple of its period, and half the true rate is a
        // clinically opposite answer at this threshold.
        int bestLag = 0;
        for (int lag = minLag; lag <= maxLag; lag++) {
            if (score[lag] < 0.90 * best) continue;
            boolean localMax = (lag == minLag || score[lag] >= score[lag - 1])
                    && (lag == maxLag || score[lag] >= score[lag + 1]);
            if (localMax) { bestLag = lag; break; }
        }
        if (bestLag == 0) return Result.refused(Refusal.NOT_PERIODIC, seconds);

        double refined = bestLag;
        if (bestLag > minLag && bestLag < maxLag) {
            double a = score[bestLag - 1];
            double b = score[bestLag];
            double c = score[bestLag + 1];
            double denominator = 2 * (2 * b - a - c);
            if (denominator != 0) refined = bestLag + (c - a) / denominator;
        }

        double rate = 60.0 * fps / refined;
        if (rate < MIN_RATE || rate > MAX_RATE) return Result.refused(Refusal.NOT_PERIODIC, seconds);

        return new Result(Optional.of(rate), Optional.empty(), score[bestLag], seconds);
    }

    /**
     * Remove the slow drift a hand-held phone always has, so the autocorrelation sees breathing
     * rather than the operator's arm gradually dropping.
     */
    private static double[] detrend(double[] signal) {
        int window = Math.max(3, signal.length / 10);
        double[] out = new double[signal.length];
        for (int i = 0; i < signal.length; i++) {
            int from = Math.max(0, i - window);
            int to = Math.min(signal.length, i + window + 1);
            double sum = 0;
            for (int j = from; j < to; j++) sum += signal[j];
            out[i] = signal[i] - sum / (to - from);
        }
        return out;
    }

    private static double normalisedCorrelation(double[] x, int lag) {
        double correlation = 0;
        double energyA = 0;
        double energyB = 0;
        int n = x.length - lag;
        for (int i = 0; i < n; i++) {
            correlation += x[i] * x[i + lag];
            energyA += x[i] * x[i];
            energyB += x[i + lag] * x[i + lag];
        }
        double denominator = Math.sqrt(energyA * energyB);
        return denominator <= 0 ? 0 : correlation / denominator;
    }
}
