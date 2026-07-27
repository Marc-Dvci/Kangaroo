package com.kangaroo.motion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Respiratory rate from chest motion.
 *
 * <p>The boundary that matters is 60 breaths a minute: 59 is normal and 60 is fast breathing, and
 * everything downstream of this measurement turns on which side it lands. So it is tested from both
 * sides, at the accuracy the measurement actually has, and with the drift and noise a hand-held
 * phone produces.
 */
class RespiratoryMotionTest {

    private static final double FPS = 10;

    @Test
    @DisplayName("a clean 40 per minute trace measures 40")
    void measuresCleanRate() {
        RespiratoryMotion.Result r = RespiratoryMotion.analyse(breathing(40, 15, 0, 0), FPS);

        assertTrue(r.measuredOk(), r.summary());
        assertEquals(40, r.measured().orElseThrow(), 2.0);
    }

    @Test
    @DisplayName("the 59/60 boundary is resolved from both sides")
    void boundaryFromBothSides() {
        double slow = RespiratoryMotion.analyse(breathing(52, 15, 0.15, 0.4), FPS)
                .measured().orElseThrow();
        double fast = RespiratoryMotion.analyse(breathing(68, 15, 0.15, 0.4), FPS)
                .measured().orElseThrow();

        assertTrue(slow < 60, "52 per minute must not read as fast breathing, got " + slow);
        assertTrue(fast > 60, "68 per minute must read as fast breathing, got " + fast);
    }

    @Test
    @DisplayName("a hand-held phone's drift does not become the measurement")
    void driftIsRemoved() {
        // A strong linear drift, as if the operator's arm were slowly dropping, over a real rate.
        RespiratoryMotion.Result r = RespiratoryMotion.analyse(breathing(45, 15, 0.1, 3.0), FPS);

        assertTrue(r.measuredOk(), r.summary());
        assertEquals(45, r.measured().orElseThrow(), 3.0,
                "the drift is far larger than the breathing and must not dominate");
    }

    @Test
    @DisplayName("the rate is not reported at half or double the truth")
    void noOctaveError() {
        for (double rate : new double[] {30, 40, 50, 60, 70, 90}) {
            double measured = RespiratoryMotion.analyse(breathing(rate, 15, 0.1, 0.3), FPS)
                    .measured().orElseThrow(() -> new AssertionError("no rate at " + rate));
            assertEquals(rate, measured, Math.max(3.0, rate * 0.08),
                    "a periodic trace correlates at every multiple of its period; "
                            + rate + " must not read as " + rate / 2 + " or " + rate * 2);
        }
    }

    @Test
    @DisplayName("random movement is refused rather than turned into a number")
    void noiseIsRefused() {
        Random random = new Random(20260727);
        double[] noise = new double[(int) (15 * FPS)];
        for (int i = 0; i < noise.length; i++) noise[i] = random.nextGaussian();

        RespiratoryMotion.Result r = RespiratoryMotion.analyse(noise, FPS);
        assertFalse(r.measuredOk(),
                "a squirming baby must produce a refusal, not a confident rate: " + r.summary());
        assertEquals(RespiratoryMotion.Refusal.NOT_PERIODIC, r.refusal().orElseThrow());
    }

    @Test
    @DisplayName("a camera pointed at nothing is refused")
    void flatTraceIsRefused() {
        RespiratoryMotion.Result r = RespiratoryMotion.analyse(new double[(int) (15 * FPS)], FPS);
        assertFalse(r.measuredOk());
        assertEquals(RespiratoryMotion.Refusal.FLAT, r.refusal().orElseThrow());
    }

    @Test
    @DisplayName("too short, and too slow a frame rate, are both refused")
    void samplingGates() {
        assertEquals(RespiratoryMotion.Refusal.TOO_SHORT,
                RespiratoryMotion.analyse(breathing(40, 4, 0, 0), FPS).refusal().orElseThrow());

        // Two frames a second cannot resolve one breath a second without aliasing, so it is
        // refused rather than answered wrongly.
        assertEquals(RespiratoryMotion.Refusal.TOO_SLOW,
                RespiratoryMotion.analyse(breathing(40, 15, 0, 0), 2.0).refusal().orElseThrow());
    }

    /**
     * A chest-motion trace: a breathing sinusoid, optional noise, optional linear drift.
     *
     * @param rate    breaths per minute
     * @param seconds trace length
     * @param noise   standard deviation of added noise, relative to the breathing amplitude
     * @param drift   total linear drift over the trace, relative to the breathing amplitude
     */
    private static double[] breathing(double rate, double seconds, double noise, double drift) {
        int n = (int) (seconds * FPS);
        double[] x = new double[n];
        Random random = new Random(20260727);
        double hz = rate / 60.0;
        for (int i = 0; i < n; i++) {
            double t = i / FPS;
            x[i] = Math.sin(2 * Math.PI * hz * t)
                    + random.nextGaussian() * noise
                    + drift * (t / seconds);
        }
        return x;
    }
}
