package com.kangaroo.audio;

import com.kangaroo.core.CryFinding;
import com.kangaroo.core.DangerSign;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Acoustic analysis of the ten-second cry recording.
 *
 * <p>The WHO IMNCI young-infant chart already contains the signs this looks for — a weak or absent
 * cry, a high-pitched cry, and grunting are all listed danger signs. What this class adds is a
 * measurement where there was previously only the health worker's ear, and it is deliberately
 * scoped to that: it reports what the recording sounds like, and the deterministic rule decides
 * what to do about it.
 *
 * <h2>The three measurements</h2>
 * <ul>
 *   <li><b>Voicing and level.</b> Frame energy against the clip's own noise floor, which separates
 *       a cry from the room it was recorded in. A cry that is present but brief and quiet is
 *       reported as a weak cry. A recording with no crying in it is <em>not</em>: see
 *       {@link Refusal#NO_CRY_HEARD} for why a quiet recording must never be read as an absent
 *       cry.</li>
 *   <li><b>Fundamental frequency.</b> Normalised autocorrelation over the voiced frames. A healthy
 *       newborn cry sits around 400-600 Hz. Sustained phonation far above that band is the
 *       "high-pitched cry" the chart lists as a neurological danger sign.</li>
 *   <li><b>Low-frequency expiratory bursts.</b> Short, repeated, low-pitched voiced segments are
 *       the acoustic shape of grunting.</li>
 * </ul>
 *
 * <h2>What it will not do</h2>
 * It never lowers a classification, it never produces a number a clinician has to accept, and it
 * refuses when the recording cannot support a reading. Everything it emits is
 * {@link DangerSign.Auditory}, which carries a confidence below 1.0 and is printed on the referral
 * letter as "heard in the cry recording" so that a receiving clinician knows a machine heard it and
 * a person did not.
 */
public final class CryAnalysis {

    // ---- framing ---------------------------------------------------------------------------
    private static final double FRAME_SECONDS = 0.040;
    private static final double HOP_SECONDS = 0.010;

    // ---- the published bands ---------------------------------------------------------------
    /** The lowest fundamental worth searching for; below this is not phonation. */
    private static final double F0_MIN_HZ = 150;
    /** Above this an autocorrelation peak is noise, not a cry. */
    private static final double F0_MAX_HZ = 1400;
    /**
     * Sustained phonation above this is reported as a high-pitched cry. Newborn cry fundamentals
     * cluster around 400-600 Hz; the cry-acoustics literature associates markedly raised pitch with
     * neurological compromise. The threshold is set well clear of the normal band rather than at its
     * edge, because the cost of over-calling here is a referral and the cost of under-calling is not.
     */
    private static final double HIGH_PITCH_HZ = 850;
    /** Grunting is low and short. */
    private static final double GRUNT_MAX_HZ = 350;

    // ---- quality gates ---------------------------------------------------------------------
    private static final double MIN_DURATION_SECONDS = 3.0;
    private static final double MAX_CLIPPED_FRACTION = 0.02;
    private static final double MIN_VOICED_SECONDS = 0.30;
    /** How far above the noise floor a frame must sit to count as voiced. */
    private static final double VOICING_MARGIN_DB = 10.0;
    /** Below this the clip has no usable dynamic range: a dead or muted microphone. */
    private static final double MIN_PEAK_AMPLITUDE = 0.01;

    private CryAnalysis() {}

    /** Why a recording could not be graded, in words the person holding the phone can act on. */
    public enum Refusal {
        TOO_SHORT("The recording is too short. Record about ten seconds."),
        SILENT("The recording is nearly silent. Check the microphone permission and try again."),
        CLIPPED("The recording is too loud and distorted. Hold the phone further away."),
        LOW_SAMPLE_RATE("The recording quality is too low to grade."),
        UNREADABLE("The recording could not be read."),
        /**
         * Audible recording, no crying in it.
         *
         * <p>This is deliberately a refusal and not an absent-cry finding. A newborn who will not
         * cry when roused is a danger sign; a newborn who simply was not crying during ten seconds
         * of recording is a sleeping baby, and those two produce the same audio. Calling the second
         * one an urgent referral would flag most healthy infants and would teach the health worker
         * to ignore the tool, which costs more than the feature is worth.
         *
         * <p>So the machine reports what it heard and the person decides: the danger sign is
         * recorded through the explicit check, where it carries examiner provenance.
         */
        NO_CRY_HEARD("No crying was heard. If the baby will not cry when roused, tick "
                + "\"weak or absent cry\" - that is a danger sign.");

        private final String message;

        Refusal(String message) { this.message = message; }

        public String message() { return message; }
    }

    /**
     * What the recording sounded like.
     *
     * @param graded          false when the clip was refused; {@code sign} is then always empty
     * @param refusal         why, when it was refused
     * @param sign            the danger sign heard, if any
     * @param medianF0Hz      the median fundamental over voiced frames, 0 when unvoiced
     * @param voicedSeconds   how much phonation was found
     * @param peakLevelDb     loudest frame relative to full scale
     * @param notes           what to show under "how this was decided"
     */
    public record Result(boolean graded,
                         Optional<Refusal> refusal,
                         Optional<DangerSign> sign,
                         double medianF0Hz,
                         double voicedSeconds,
                         double peakLevelDb,
                         List<String> notes) {

        static Result refused(Refusal why) {
            return new Result(false, Optional.of(why), Optional.empty(), 0, 0, 0,
                    List.of(why.message()));
        }

        /** A short line for the audit grid. */
        public String summary() {
            if (!graded) return refusal().map(Refusal::message).orElse("not graded");
            return sign.map(s -> s.sign().label()).orElse("The cry sounds normal")
                    + (medianF0Hz > 0 ? " (fundamental %.0f Hz)".formatted(medianF0Hz) : "");
        }

        /** The domain-model view, for the assessment record and the API. */
        public CryFinding finding() {
            return new CryFinding(graded, summary(), medianF0Hz, voicedSeconds);
        }
    }

    /** Analyse a WAV clip. Never throws for bad audio: a bad clip is a refusal, not an error. */
    public static Result analyse(byte[] wav) {
        Pcm pcm;
        try {
            pcm = Pcm.decodeWav(wav);
        } catch (RuntimeException e) {
            return Result.refused(Refusal.UNREADABLE);
        }
        return analyse(pcm);
    }

    /** Analyse decoded samples. */
    public static Result analyse(Pcm pcm) {
        if (pcm.sampleRate() < Pcm.MIN_SAMPLE_RATE) return Result.refused(Refusal.LOW_SAMPLE_RATE);
        if (pcm.durationSeconds() < MIN_DURATION_SECONDS) return Result.refused(Refusal.TOO_SHORT);
        if (pcm.clippedFraction() > MAX_CLIPPED_FRACTION) return Result.refused(Refusal.CLIPPED);

        float[] x = pcm.samples();
        int rate = pcm.sampleRate();
        int frameLength = (int) Math.round(FRAME_SECONDS * rate);
        int hop = (int) Math.round(HOP_SECONDS * rate);
        if (x.length < frameLength * 2) return Result.refused(Refusal.TOO_SHORT);

        double peak = 0;
        for (float v : x) peak = Math.max(peak, Math.abs(v));
        if (peak < MIN_PEAK_AMPLITUDE) return Result.refused(Refusal.SILENT);

        // --- frame energies --------------------------------------------------------------
        int frames = 1 + (x.length - frameLength) / hop;
        double[] rms = new double[frames];
        for (int i = 0; i < frames; i++) {
            rms[i] = rms(x, i * hop, frameLength);
        }

        // The noise floor is the clip's own quiet decile, not a constant: a hut at night and a
        // clinic corridor have very different floors and the same cry is loud in both.
        double floor = percentile(rms.clone(), 0.10);
        double top = percentile(rms.clone(), 0.95);
        double floorDb = db(floor);
        double topDb = db(top);

        if (topDb - floorDb < VOICING_MARGIN_DB) {
            // Nothing stands out from the background. Distinguish "quiet room, no cry" from
            // "microphone produced nothing at all" -- only the second is a refusal.
            if (topDb < -45) return Result.refused(Refusal.SILENT);
            return Result.refused(Refusal.NO_CRY_HEARD);
        }

        double voicingThreshold = floorDb + VOICING_MARGIN_DB;

        // --- fundamental frequency over the loud frames -----------------------------------
        List<Double> f0s = new ArrayList<>();
        List<Segment> segments = new ArrayList<>();
        Segment current = null;

        for (int i = 0; i < frames; i++) {
            boolean loud = db(rms[i]) >= voicingThreshold;
            double f0 = loud ? fundamental(x, i * hop, frameLength, rate) : 0;
            boolean voiced = loud && f0 > 0;

            if (voiced) {
                f0s.add(f0);
                if (current == null) current = new Segment(i, i, new ArrayList<>());
                current.end = i;
                current.f0s.add(f0);
            } else if (current != null) {
                segments.add(current);
                current = null;
            }
        }
        if (current != null) segments.add(current);

        double voicedSeconds = f0s.size() * HOP_SECONDS;
        if (voicedSeconds < MIN_VOICED_SECONDS) {
            return Result.refused(Refusal.NO_CRY_HEARD);
        }

        double medianF0 = median(f0s);
        List<String> notes = new ArrayList<>();
        notes.add("median fundamental %.0f Hz over %.1f s of phonation".formatted(medianF0, voicedSeconds));

        // --- grading, most urgent first ---------------------------------------------------
        //
        // Only one sign is emitted. These are not independent observations of one recording; they
        // are competing readings of it, and handing the rule engine three at once would triple-count
        // a single ten-second clip.

        if (medianF0 >= HIGH_PITCH_HZ) {
            notes.add("sustained above %.0f Hz, which the chart lists as a high-pitched cry".formatted(HIGH_PITCH_HZ));
            return new Result(true, Optional.empty(),
                    Optional.of(new DangerSign.Auditory(DangerSign.Sign.WEAK_OR_ABSENT_CRY, 0.6)),
                    medianF0, voicedSeconds, db(peak), List.copyOf(notes));
        }

        if (grunting(segments, medianF0)) {
            notes.add("short repeated low-pitched bursts, the acoustic shape of grunting");
            return new Result(true, Optional.empty(),
                    Optional.of(new DangerSign.Auditory(DangerSign.Sign.GRUNTING_OR_STRIDOR, 0.5)),
                    medianF0, voicedSeconds, db(peak), List.copyOf(notes));
        }

        // A cry that is present but quiet and short. Both conditions are required: a brief loud
        // cry is a baby who stopped crying, which is not a danger sign.
        double voicedLevelDb = db(percentile(voicedRms(rms, voicingThreshold), 0.5));
        if (voicedSeconds < 1.5 && voicedLevelDb - floorDb < VOICING_MARGIN_DB + 6) {
            notes.add("brief and quiet relative to the background");
            return new Result(true, Optional.empty(),
                    Optional.of(new DangerSign.Auditory(DangerSign.Sign.WEAK_OR_ABSENT_CRY, 0.45)),
                    medianF0, voicedSeconds, db(peak), List.copyOf(notes));
        }

        notes.add("level and pitch are within the range expected of a healthy newborn cry");
        return new Result(true, Optional.empty(), Optional.empty(),
                medianF0, voicedSeconds, db(peak), List.copyOf(notes));
    }

    /** A run of consecutive voiced frames. */
    private static final class Segment {
        int start;
        int end;
        final List<Double> f0s;

        Segment(int start, int end, List<Double> f0s) {
            this.start = start;
            this.end = end;
            this.f0s = f0s;
        }

        double seconds() { return (end - start + 1) * HOP_SECONDS; }
    }

    /**
     * Grunting: several short, low-pitched voiced bursts rather than one sustained cry.
     *
     * <p>The discriminator against a normal cry is duration and pitch together. A cry is long and
     * mid-pitched; grunting is a sequence of expiratory pushes, each a few hundred milliseconds,
     * low in the band.
     */
    private static boolean grunting(List<Segment> segments, double medianF0) {
        if (medianF0 > GRUNT_MAX_HZ) return false;
        long shortLowBursts = segments.stream()
                .filter(s -> s.seconds() >= 0.08 && s.seconds() <= 0.50)
                .filter(s -> median(s.f0s) <= GRUNT_MAX_HZ)
                .count();
        boolean noSustainedCry = segments.stream().noneMatch(s -> s.seconds() > 1.0);
        return shortLowBursts >= 3 && noSustainedCry;
    }

    /**
     * Fundamental frequency by normalised autocorrelation.
     *
     * <p>Normalising by the energy of the two windows being compared is what stops the function
     * from simply preferring lag zero, and it makes the peak height a usable confidence: a periodic
     * signal peaks near 1.0, noise does not peak at all.
     *
     * @return the estimate in hertz, or 0 when the frame is not periodic enough to call
     */
    static double fundamental(float[] x, int offset, int length, int sampleRate) {
        int minLag = (int) Math.floor(sampleRate / F0_MAX_HZ);
        int maxLag = (int) Math.ceil(sampleRate / F0_MIN_HZ);
        maxLag = Math.min(maxLag, length / 2);
        if (minLag < 1 || maxLag <= minLag) return 0;

        double[] score = new double[maxLag + 1];
        double bestScore = 0;

        for (int lag = minLag; lag <= maxLag; lag++) {
            double correlation = 0;
            double energyA = 0;
            double energyB = 0;
            int n = length - lag;
            for (int i = 0; i < n; i++) {
                double a = x[offset + i];
                double b = x[offset + i + lag];
                correlation += a * b;
                energyA += a * a;
                energyB += b * b;
            }
            double denominator = Math.sqrt(energyA * energyB);
            score[lag] = denominator <= 0 ? 0 : correlation / denominator;
            bestScore = Math.max(bestScore, score[lag]);
        }

        // 0.55 keeps genuinely periodic frames and drops the broadband noise between cries.
        if (bestScore < 0.55) return 0;

        // Take the SHORTEST lag that is nearly as good as the best one, not the best one.
        //
        // A periodic signal correlates with itself at every multiple of its period, so the global
        // maximum lands an octave or two below the true fundamental as often as not -- a cry at
        // 500 Hz reads as 250, and one at 1000 Hz reads as 333. Since the whole clinical point of
        // this measurement is distinguishing a normal cry from a high-pitched one, an octave error
        // is not a rounding difference; it is the entire answer, backwards.
        int bestLag = 0;
        double acceptable = 0.90 * bestScore;
        for (int lag = minLag; lag <= maxLag; lag++) {
            if (score[lag] < acceptable) continue;
            boolean localMax = (lag == minLag || score[lag] >= score[lag - 1])
                    && (lag == maxLag || score[lag] >= score[lag + 1]);
            if (localMax) {
                bestLag = lag;
                break;
            }
        }
        if (bestLag == 0) return 0;

        // Parabolic interpolation around the peak: the true period rarely lands exactly on a
        // sample, and at 16 kHz one lag step near 1000 Hz is already tens of hertz.
        double refined = bestLag;
        if (bestLag > minLag && bestLag < maxLag) {
            double a = score[bestLag - 1];
            double b = score[bestLag];
            double c = score[bestLag + 1];
            double denominator = 2 * (2 * b - a - c);
            if (denominator != 0) {
                refined = bestLag + (c - a) / denominator;
            }
        }
        return refined > 0 ? sampleRate / refined : 0;
    }

    private static double[] voicedRms(double[] rms, double thresholdDb) {
        return java.util.Arrays.stream(rms).filter(v -> db(v) >= thresholdDb).toArray();
    }

    private static double rms(float[] x, int offset, int length) {
        double sum = 0;
        for (int i = offset; i < offset + length && i < x.length; i++) {
            sum += (double) x[i] * x[i];
        }
        return Math.sqrt(sum / length);
    }

    private static double db(double amplitude) {
        return 20 * Math.log10(Math.max(amplitude, 1e-9));
    }

    private static double percentile(double[] values, double q) {
        if (values.length == 0) return 0;
        java.util.Arrays.sort(values);
        int index = (int) Math.clamp(Math.round(q * (values.length - 1)), 0, values.length - 1);
        return values[index];
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) return 0;
        double[] copy = values.stream().mapToDouble(Double::doubleValue).toArray();
        return percentile(copy, 0.5);
    }
}
