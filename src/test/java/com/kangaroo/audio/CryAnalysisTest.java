package com.kangaroo.audio;

import com.kangaroo.core.DangerSign;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cry analysis, driven with synthesised audio.
 *
 * <p>Synthesised rather than recorded, deliberately. A handful of real cries would be a handful of
 * anecdotes; a signal generator lets the boundary be tested from both sides — a cry at 600 Hz and
 * the same cry at 900 Hz, identical in every other respect — which is the only way to know that the
 * pitch threshold is what moved the answer and not the recording level or the background noise.
 *
 * <p>Everything here is deterministic: the noise is seeded.
 */
class CryAnalysisTest {

    private static final int RATE = 16_000;

    @Nested
    @DisplayName("reading the recording")
    class Decoding {

        @Test
        @DisplayName("a 16-bit WAV round-trips through the decoder")
        void wavRoundTrip() {
            byte[] wav = wav(tone(440, 1.0, 0.5), RATE);
            Pcm pcm = Pcm.decodeWav(wav);

            assertEquals(RATE, pcm.sampleRate());
            assertEquals(1.0, pcm.durationSeconds(), 0.01);
            // 16-bit quantisation is the only loss; the waveform must survive it.
            assertEquals(0.5, peak(pcm.samples()), 0.01);
        }

        @Test
        @DisplayName("chunks other than fmt and data are skipped, not read as audio")
        void extraChunksSkipped() {
            byte[] plain = wav(tone(440, 1.0, 0.5), RATE);
            byte[] withList = wavWithListChunk(tone(440, 1.0, 0.5), RATE);

            Pcm a = Pcm.decodeWav(plain);
            Pcm b = Pcm.decodeWav(withList);

            assertEquals(a.samples().length, b.samples().length,
                    "a LIST chunk must not shift where the audio is read from");
            assertEquals(peak(a.samples()), peak(b.samples()), 1e-6);
        }

        @Test
        @DisplayName("something that is not a WAV is a refusal, not an exception")
        void notAWavIsRefused() {
            CryAnalysis.Result r = CryAnalysis.analyse("this is not audio".getBytes());
            assertFalse(r.graded());
            assertEquals(CryAnalysis.Refusal.UNREADABLE, r.refusal().orElseThrow());
        }
    }

    @Nested
    @DisplayName("refusing what it cannot grade")
    class Refusals {

        @Test
        @DisplayName("a clip shorter than three seconds is refused")
        void tooShort() {
            CryAnalysis.Result r = CryAnalysis.analyse(wav(cry(500, 1.0), RATE));
            assertFalse(r.graded());
            assertEquals(CryAnalysis.Refusal.TOO_SHORT, r.refusal().orElseThrow());
        }

        @Test
        @DisplayName("a silent clip is a microphone problem, not an absent cry")
        void silenceIsRefusedNotGraded() {
            CryAnalysis.Result r = CryAnalysis.analyse(wav(new float[RATE * 8], RATE));
            assertFalse(r.graded(), """
                    Silence must never be graded. An absent cry and a dead microphone produce the \
                    same bytes and have opposite clinical meanings, so the only safe reading is to \
                    ask for the recording again.""");
            assertEquals(CryAnalysis.Refusal.SILENT, r.refusal().orElseThrow());
        }

        @Test
        @DisplayName("a distorted clip is refused rather than measured")
        void clippingIsRefused() {
            float[] x = cry(500, 8.0);
            for (int i = 0; i < x.length; i++) x[i] = x[i] > 0 ? 1.0f : -1.0f;   // hard square
            CryAnalysis.Result r = CryAnalysis.analyse(wav(x, RATE));
            assertFalse(r.graded());
            assertEquals(CryAnalysis.Refusal.CLIPPED, r.refusal().orElseThrow());
        }

        @Test
        @DisplayName("a sample rate too low to hold a cry is refused")
        void lowSampleRateRefused() {
            CryAnalysis.Result r = CryAnalysis.analyse(wav(cry(500, 8.0), 4_000));
            assertFalse(r.graded());
            assertEquals(CryAnalysis.Refusal.LOW_SAMPLE_RATE, r.refusal().orElseThrow());
        }
    }

    @Nested
    @DisplayName("grading")
    class Grading {

        @Test
        @DisplayName("a healthy cry at 500 Hz produces no danger sign")
        void healthyCryIsClean() {
            CryAnalysis.Result r = CryAnalysis.analyse(wav(withNoise(cry(500, 8.0)), RATE));

            assertTrue(r.graded(), "a good recording must be gradeable");
            assertTrue(r.sign().isEmpty(), "a normal cry is not a danger sign: " + r.summary());
            assertEquals(500, r.medianF0Hz(), 30, "the fundamental must be measured, not guessed");
        }

        @Test
        @DisplayName("a sustained high-pitched cry is reported")
        void highPitchedCryDetected() {
            CryAnalysis.Result r = CryAnalysis.analyse(wav(withNoise(cry(1000, 8.0)), RATE));

            assertTrue(r.graded());
            DangerSign sign = r.sign().orElseThrow(() -> new AssertionError(
                    "a 1000 Hz sustained cry must be flagged: " + r.summary()));
            assertEquals(DangerSign.Sign.WEAK_OR_ABSENT_CRY, sign.sign());
            assertTrue(sign instanceof DangerSign.Auditory, "provenance must say a machine heard it");
        }

        @Test
        @DisplayName("the pitch boundary is what moved the answer, not the recording")
        void pitchBoundaryFromBothSides() {
            // Identical duration, level and noise. Only the fundamental differs.
            CryAnalysis.Result normal = CryAnalysis.analyse(wav(withNoise(cry(600, 8.0)), RATE));
            CryAnalysis.Result high = CryAnalysis.analyse(wav(withNoise(cry(1000, 8.0)), RATE));

            assertTrue(normal.sign().isEmpty(), "600 Hz is a normal newborn cry");
            assertTrue(high.sign().isPresent(), "1000 Hz is not");
            assertNotEquals(normal.sign(), high.sign());
        }

        @Test
        @DisplayName("a quiet room with no crying in it is never called an absent cry")
        void quietRoomIsNotAnAbsentCry() {
            // A sleeping healthy newborn and a newborn too sick to cry produce the same ten
            // seconds of audio. Reading this as a danger sign would refer most healthy infants,
            // and a tool that cries wolf on the majority of visits stops being used.
            float[] room = new float[RATE * 8];
            Random random = new Random(20260727);
            for (int i = 0; i < room.length; i++) {
                room[i] = (float) (random.nextGaussian() * 0.02);
            }
            CryAnalysis.Result r = CryAnalysis.analyse(wav(room, RATE));

            assertFalse(r.graded());
            assertEquals(CryAnalysis.Refusal.NO_CRY_HEARD, r.refusal().orElseThrow());
            assertTrue(r.sign().isEmpty(),
                    "the absence of crying in a recording is not evidence of an absent cry");
            assertTrue(r.summary().contains("roused"),
                    "the message must tell the user how to record the sign that does matter");
        }

        @Test
        @DisplayName("the analysis never returns a sign the chart does not contain")
        void onlyChartSignsAreEmitted() {
            for (double f0 : new double[] {200, 300, 450, 600, 800, 1000, 1300}) {
                CryAnalysis.Result r = CryAnalysis.analyse(wav(withNoise(cry(f0, 8.0)), RATE));
                r.sign().ifPresent(s -> assertTrue(
                        s.sign() == DangerSign.Sign.WEAK_OR_ABSENT_CRY
                                || s.sign() == DangerSign.Sign.GRUNTING_OR_STRIDOR,
                        "the cry pass may only emit signs the IMNCI chart lists for audio, got "
                                + s.sign()));
            }
        }

        @Test
        @DisplayName("a graded result never carries full confidence")
        void machineHearingIsNeverCertain() {
            CryAnalysis.Result r = CryAnalysis.analyse(wav(withNoise(cry(1000, 8.0)), RATE));
            double confidence = r.sign().orElseThrow().confidence();
            assertTrue(confidence > 0 && confidence < 1.0,
                    "a machine listening to a phone microphone is not a certainty: " + confidence);
        }
    }

    // ------------------------------------------------------------------ signal generation

    /** A steady tone. */
    private static float[] tone(double hz, double seconds, double amplitude) {
        float[] x = new float[(int) (seconds * RATE)];
        for (int i = 0; i < x.length; i++) {
            x[i] = (float) (amplitude * Math.sin(2 * Math.PI * hz * i / RATE));
        }
        return x;
    }

    /**
     * Something shaped like a cry: a harmonic-rich tone in bursts with pauses, since a real infant
     * breathes. A pure unbroken sine would make the segmentation logic look better than it is.
     */
    private static float[] cry(double f0, double seconds) {
        int n = (int) (seconds * RATE);
        float[] x = new float[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            // 1.2 s of cry, 0.5 s of breath.
            boolean voiced = (t % 1.7) < 1.2;
            if (!voiced) continue;
            double v = Math.sin(2 * Math.PI * f0 * t)
                    + 0.5 * Math.sin(4 * Math.PI * f0 * t)
                    + 0.25 * Math.sin(6 * Math.PI * f0 * t);
            x[i] = (float) (0.35 * v / 1.75);
        }
        return x;
    }

    /** A quiet room behind the cry, so the noise floor is not exactly zero. */
    private static float[] withNoise(float[] x) {
        Random random = new Random(20260727);
        float[] out = x.clone();
        for (int i = 0; i < out.length; i++) {
            out[i] = (float) Math.clamp(out[i] + random.nextGaussian() * 0.003, -1.0, 1.0);
        }
        return out;
    }

    private static double peak(float[] x) {
        double p = 0;
        for (float v : x) p = Math.max(p, Math.abs(v));
        return p;
    }

    // ------------------------------------------------------------------ WAV writing

    private static byte[] wav(float[] samples, int rate) {
        return wav(samples, rate, false);
    }

    private static byte[] wavWithListChunk(float[] samples, int rate) {
        return wav(samples, rate, true);
    }

    private static byte[] wav(float[] samples, int rate, boolean withList) {
        byte[] list = withList ? listChunk() : new byte[0];
        int dataBytes = samples.length * 2;
        var out = new ByteArrayOutputStream();

        ByteBuffer header = ByteBuffer.allocate(12 + 24).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes());
        header.putInt(4 + 24 + list.length + 8 + dataBytes);
        header.put("WAVE".getBytes());
        header.put("fmt ".getBytes());
        header.putInt(16);
        header.putShort((short) 1);           // PCM
        header.putShort((short) 1);           // mono
        header.putInt(rate);
        header.putInt(rate * 2);              // byte rate
        header.putShort((short) 2);           // block align
        header.putShort((short) 16);          // bits
        out.writeBytes(header.array());

        out.writeBytes(list);

        ByteBuffer data = ByteBuffer.allocate(8 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        data.put("data".getBytes());
        data.putInt(dataBytes);
        for (float s : samples) {
            data.putShort((short) Math.clamp(Math.round(s * 32767), -32768, 32767));
        }
        out.writeBytes(data.array());
        return out.toByteArray();
    }

    /**
     * A metadata chunk of the kind real recorders insert between fmt and data.
     *
     * <p>The body is an odd number of bytes on purpose, and is followed by the pad byte that RIFF
     * requires. Getting that padding wrong shifts every following chunk by one, which is exactly
     * the failure the decoder has to be immune to.
     */
    private static byte[] listChunk() {
        byte[] body = "INFOISFT kangaroo".getBytes();      // 17 bytes: odd
        ByteBuffer b = ByteBuffer.allocate(8 + body.length + 1).order(ByteOrder.LITTLE_ENDIAN);
        b.put("LIST".getBytes());
        b.putInt(body.length);
        b.put(body);
        b.put((byte) 0);                                    // RIFF pad byte
        return b.array();
    }
}
