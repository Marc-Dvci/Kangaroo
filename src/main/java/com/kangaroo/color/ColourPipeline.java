package com.kangaroo.color;

import java.util.Arrays;

/**
 * The colorimetry pipeline: a 512x512 frame in, {@value ColourFeature#COUNT} colour features out.
 *
 * <p>Three passes over a quarter of a million pixels, all of it elementwise float arithmetic — the
 * textbook case for SIMD. So there are two implementations of the four hot kernels below,
 * {@link ScalarPipeline} and {@link VectorPipeline}, selected at runtime and benchmarked against
 * each other live at {@code /api/bench}. Everything else — the algorithm, the ordering, the
 * fallbacks — lives here once, in this interface, so the two implementations cannot drift apart.
 *
 * <h2>What the pipeline does</h2>
 * <ol>
 *   <li><b>Estimate the illuminant.</b> Bright, near-neutral pixels are the printed card's white
 *       patches. Their average is what white should have been, so the per-channel gains that map it
 *       to neutral are the correction the rest of the frame needs. With too little reference white
 *       in shot, it degrades to grey-world rather than refusing.</li>
 *   <li><b>Find skin.</b> A central window, then a warm-ordered, non-blown-out, non-shadow mask, so
 *       the card's own coloured patches and the shadowed edges do not contaminate the statistics.</li>
 *   <li><b>Summarise the colour.</b> Channel means, medians, percentiles, spreads, the opponent
 *       differences and ratios that isolate the blue-yellow axis, and CIE-Lab and HSV of the mean.</li>
 * </ol>
 *
 * <p><b>On agreement between the two implementations:</b> they are equal to within about 1e-4
 * relative, not bit-identical, because a lane-wise reduction reassociates the additions. That is
 * stated plainly rather than papered over — {@code ColourPipelineTest} asserts the tolerance and,
 * more importantly, asserts that both produce the same severity grade on the same frame. The
 * feature scale here is tens of units and the model's thresholds are far coarser than 1e-4, so the
 * reassociation is immaterial; if it ever stopped being immaterial, the test would say so.
 */
public sealed interface ColourPipeline permits ScalarPipeline, VectorPipeline {

    String name();

    // ---------------------------------------------------------------- kernels

    /** Illuminant estimate: masked and total channel sums plus the count of reference-white pixels. */
    WhiteBalanceStats whiteBalanceStats(Frame f);

    /**
     * Apply per-channel gains in place, clipped to 0-255.
     * Returns the same arrays; the frame is a working copy by this point.
     */
    void applyGains(Frame f, float gr, float gg, float gb);

    /** Select skin-like pixels from an already white-balanced crop, compacted into dense arrays. */
    Skin selectSkin(Frame corrected);

    /** Sum and sum-of-squares of a dense array, for mean and standard deviation. */
    Moments moments(float[] values, int n);

    /** Per-pixel chroma, the RMS spread between the three channels. */
    Moments chromaMoments(float[] r, float[] g, float[] b, int n);

    // ---------------------------------------------------------------- records

    record WhiteBalanceStats(double maskedR, double maskedG, double maskedB, int maskedCount,
                             double totalR, double totalG, double totalB, int totalCount) {}

    record Skin(float[] r, float[] g, float[] b, int count, double fraction) {}

    record Moments(double sum, double sumSq, int count) {
        public double mean() { return count == 0 ? 0 : sum / count; }
        public double std() {
            if (count == 0) return 0;
            double m = mean();
            return Math.sqrt(Math.max(0, sumSq / count - m * m));
        }
    }

    // ---------------------------------------------------------------- the algorithm

    /** Below this fraction of reference-white pixels, fall back to grey-world. */
    double MIN_WHITE_FRACTION = 0.005;
    /** Below this fraction of skin pixels, the window is judged misaligned. */
    double MIN_SKIN_FRACTION = 0.05;
    /** The central window the cut-out card frames. */
    double WINDOW_LO = 0.27;
    double WINDOW_HI = 0.73;

    /**
     * Extract the full feature vector. The array is indexed by {@link ColourFeature#ordinal()}.
     */
    default float[] extract(Frame original) {
        float[] out = new float[ColourFeature.COUNT];

        // 1. Illuminant.
        WhiteBalanceStats wb = whiteBalanceStats(original);
        double whiteFrac = (double) wb.maskedCount() / wb.totalCount();

        double mr;
        double mg;
        double mb;
        if (whiteFrac < MIN_WHITE_FRACTION || wb.maskedCount() == 0) {
            mr = wb.totalR() / wb.totalCount();
            mg = wb.totalG() / wb.totalCount();
            mb = wb.totalB() / wb.totalCount();
        } else {
            mr = wb.maskedR() / wb.maskedCount();
            mg = wb.maskedG() / wb.maskedCount();
            mb = wb.maskedB() / wb.maskedCount();
        }
        mr = Math.max(mr, 1.0);
        mg = Math.max(mg, 1.0);
        mb = Math.max(mb, 1.0);

        // Equalise the channels while preserving overall brightness.
        double avg = (mr + mg + mb) / 3.0;
        float gr = (float) (avg / mr);
        float gg = (float) (avg / mg);
        float gb = (float) (avg / mb);

        // Correct only the central window; the card border outside it is not skin and never
        // contributes to the statistics, so there is no reason to pay for it.
        Frame window = original.crop(WINDOW_LO, WINDOW_LO, WINDOW_HI, WINDOW_HI);
        applyGains(window, gr, gg, gb);

        // 2. Skin.
        Skin skin = selectSkin(window);
        float[] sr = skin.r();
        float[] sg = skin.g();
        float[] sb = skin.b();
        int n = skin.count();
        if (n == 0) {
            sr = window.r();
            sg = window.g();
            sb = window.b();
            n = window.pixels();
        }

        // 3. Summaries.
        Moments mR = moments(sr, n);
        Moments mG = moments(sg, n);
        Moments mB = moments(sb, n);
        double rMean = mR.mean();
        double gMean = mG.mean();
        double bMean = mB.mean();

        double[] lab = srgbToLab(rMean, gMean, bMean);
        double[] hsv = rgbToHsv(rMean, gMean, bMean);
        Moments chroma = chromaMoments(sr, sg, sb, n);

        float[] rSorted = Arrays.copyOf(sr, n);
        float[] gSorted = Arrays.copyOf(sg, n);
        float[] bSorted = Arrays.copyOf(sb, n);
        Arrays.sort(rSorted);
        Arrays.sort(gSorted);
        Arrays.sort(bSorted);

        set(out, ColourFeature.WB_GAIN_R, gr);
        set(out, ColourFeature.WB_GAIN_G, gg);
        set(out, ColourFeature.WB_GAIN_B, gb);
        set(out, ColourFeature.WB_N_WHITE_FRAC, whiteFrac);
        set(out, ColourFeature.SKIN_PIXEL_FRAC, skin.fraction());

        set(out, ColourFeature.SKIN_R_MEAN, rMean);
        set(out, ColourFeature.SKIN_G_MEAN, gMean);
        set(out, ColourFeature.SKIN_B_MEAN, bMean);
        set(out, ColourFeature.SKIN_R_MED, percentile(rSorted, 50));
        set(out, ColourFeature.SKIN_G_MED, percentile(gSorted, 50));
        set(out, ColourFeature.SKIN_B_MED, percentile(bSorted, 50));
        set(out, ColourFeature.SKIN_R_P10, percentile(rSorted, 10));
        set(out, ColourFeature.SKIN_R_P90, percentile(rSorted, 90));
        set(out, ColourFeature.SKIN_B_P10, percentile(bSorted, 10));
        set(out, ColourFeature.SKIN_B_P90, percentile(bSorted, 90));
        set(out, ColourFeature.SKIN_R_STD, mR.std());
        set(out, ColourFeature.SKIN_G_STD, mG.std());
        set(out, ColourFeature.SKIN_B_STD, mB.std());

        set(out, ColourFeature.RB_DIFF, rMean - bMean);
        set(out, ColourFeature.GB_DIFF, gMean - bMean);
        set(out, ColourFeature.RG_DIFF, rMean - gMean);
        set(out, ColourFeature.YELLOW_IDX, (rMean + gMean) / 2.0 - bMean);
        set(out, ColourFeature.R_OVER_B, rMean / Math.max(bMean, 1.0));
        set(out, ColourFeature.G_OVER_B, gMean / Math.max(bMean, 1.0));
        set(out, ColourFeature.RG_OVER_B, (rMean + gMean) / 2.0 / Math.max(bMean, 1.0));

        set(out, ColourFeature.LAB_L, lab[0]);
        set(out, ColourFeature.LAB_A, lab[1]);
        set(out, ColourFeature.LAB_B, lab[2]);
        set(out, ColourFeature.HSV_H, hsv[0]);
        set(out, ColourFeature.HSV_S, hsv[1]);
        set(out, ColourFeature.HSV_V, hsv[2]);

        set(out, ColourFeature.CHROMA_MEAN, chroma.mean());
        set(out, ColourFeature.CHROMA_STD, chroma.std());

        return out;
    }

    private static void set(float[] out, ColourFeature f, double v) {
        out[f.ordinal()] = (float) v;
    }

    // ---------------------------------------------------------------- colour space maths

    /** sRGB (0-255, D65) to CIE-Lab. */
    static double[] srgbToLab(double r, double g, double b) {
        double cr = linearise(clamp255(r) / 255.0);
        double cg = linearise(clamp255(g) / 255.0);
        double cb = linearise(clamp255(b) / 255.0);

        double x = (0.4124564 * cr + 0.3575761 * cg + 0.1804375 * cb) / 0.95047;
        double y = (0.2126729 * cr + 0.7151522 * cg + 0.0721750 * cb) / 1.0;
        double z = (0.0193339 * cr + 0.1191920 * cg + 0.9503041 * cb) / 1.08883;

        double fx = labF(x);
        double fy = labF(y);
        double fz = labF(z);

        return new double[] {116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz)};
    }

    private static double linearise(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double labF(double t) {
        return t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16.0 / 116.0;
    }

    /** sRGB (0-255) to HSV, with hue in degrees. */
    static double[] rgbToHsv(double r, double g, double b) {
        double rn = clamp255(r) / 255.0;
        double gn = clamp255(g) / 255.0;
        double bn = clamp255(b) / 255.0;
        double mx = Math.max(rn, Math.max(gn, bn));
        double mn = Math.min(rn, Math.min(gn, bn));
        double d = mx - mn;

        double h;
        if (d == 0) h = 0;
        else if (mx == rn) h = ((60 * ((gn - bn) / d)) + 360) % 360;
        else if (mx == gn) h = ((60 * ((bn - rn) / d)) + 120) % 360;
        else h = ((60 * ((rn - gn) / d)) + 240) % 360;

        double s = mx == 0 ? 0 : d / mx;
        return new double[] {h, s, mx};
    }

    private static double clamp255(double v) {
        return Math.max(0, Math.min(255, v));
    }

    /**
     * Linear-interpolated percentile over a sorted array, matching the standard definition used by
     * the reference implementation these features were trained against.
     */
    static double percentile(float[] sorted, double p) {
        int n = sorted.length;
        if (n == 0) return 0;
        if (n == 1) return sorted[0];
        double idx = (p / 100.0) * (n - 1);
        int lo = (int) Math.floor(idx);
        int hi = (int) Math.ceil(idx);
        if (lo == hi) return sorted[lo];
        double frac = idx - lo;
        return sorted[lo] * (1 - frac) + sorted[hi] * frac;
    }

    // ---------------------------------------------------------------- selection

    /**
     * The vectorised pipeline when the Vector API is present and the hardware has usable lanes,
     * the scalar one otherwise. Both are always available; this only picks the default.
     */
    static ColourPipeline preferred() {
        try {
            VectorPipeline v = new VectorPipeline();
            return v.laneCount() >= 4 ? v : new ScalarPipeline();
        } catch (Throwable t) {
            // The Vector API is an incubator module; if it is absent the product still works.
            return new ScalarPipeline();
        }
    }

    static ColourPipeline scalar() { return new ScalarPipeline(); }
}
