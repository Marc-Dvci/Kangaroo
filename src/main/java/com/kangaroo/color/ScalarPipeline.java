package com.kangaroo.color;

/**
 * The reference implementation of the four colorimetry kernels: plain loops, no intrinsics, no
 * incubator modules.
 *
 * <p>It exists for three reasons, in order of importance. It is the correctness oracle the
 * vectorised path is tested against. It is the fallback on any machine where the Vector API is
 * unavailable or has too few lanes to be worth the setup. And it is what a reviewer reads to
 * understand the algorithm, because {@link VectorPipeline} is optimised code and optimised code is
 * a poor specification.
 */
public final class ScalarPipeline implements ColourPipeline {

    @Override
    public String name() {
        return "scalar";
    }

    @Override
    public WhiteBalanceStats whiteBalanceStats(Frame f) {
        float[] r = f.r();
        float[] g = f.g();
        float[] b = f.b();
        int n = f.pixels();

        double mr = 0;
        double mg = 0;
        double mb = 0;
        int masked = 0;
        double tr = 0;
        double tg = 0;
        double tb = 0;

        for (int i = 0; i < n; i++) {
            float rv = r[i];
            float gv = g[i];
            float bv = b[i];

            tr += rv;
            tg += gv;
            tb += bv;

            float mx = Math.max(rv, Math.max(gv, bv));
            float mn = Math.min(rv, Math.min(gv, bv));

            // Bright and near-neutral: the printed card's white border and grey ramp.
            if (mn > 170f && (mx - mn) < 28f) {
                mr += rv;
                mg += gv;
                mb += bv;
                masked++;
            }
        }
        return new WhiteBalanceStats(mr, mg, mb, masked, tr, tg, tb, n);
    }

    @Override
    public void applyGains(Frame f, float gr, float gg, float gb) {
        float[] r = f.r();
        float[] g = f.g();
        float[] b = f.b();
        for (int i = 0; i < r.length; i++) {
            r[i] = clamp(r[i] * gr);
            g[i] = clamp(g[i] * gg);
            b[i] = clamp(b[i] * gb);
        }
    }

    @Override
    public Skin selectSkin(Frame c) {
        float[] r = c.r();
        float[] g = c.g();
        float[] b = c.b();
        int n = c.pixels();

        float[] or = new float[n];
        float[] og = new float[n];
        float[] ob = new float[n];
        int k = 0;

        for (int i = 0; i < n; i++) {
            float rv = r[i];
            float gv = g[i];
            float bv = b[i];
            float mx = Math.max(rv, Math.max(gv, bv));
            float mn = Math.min(rv, Math.min(gv, bv));

            if (isSkin(rv, gv, bv, mx, mn)) {
                or[k] = rv;
                og[k] = gv;
                ob[k] = bv;
                k++;
            }
        }

        double fraction = (double) k / n;
        if (fraction < MIN_SKIN_FRACTION) {
            // The window is misaligned. Rather than grading whatever happens to be there, fall back
            // to the brighter half of the crop, which is at worst uninformative rather than wrong.
            return brighterHalf(c, fraction);
        }
        return new Skin(or, og, ob, k, fraction);
    }

    /**
     * Warm-ordered, not blown out, not in shadow, and not one of the card's saturated colour
     * patches. Jaundiced skin keeps red and green high while blue falls, so the ordering test has
     * to tolerate green rising above blue by a margin rather than requiring a strict R&gt;G&gt;B.
     */
    static boolean isSkin(float r, float g, float b, float mx, float mn) {
        return r >= g
                && g >= b - 8f
                && r > 60f
                && mx < 250f
                && mn < 245f
                && mx > 40f
                && (mx - mn) < 130f;
    }

    /**
     * @param trueSkinFraction the real skin fraction, which is reported unchanged so the model still
     *        sees that the crop was poor. Substituting the fallback's own fraction would hide it.
     */
    static Skin brighterHalf(Frame c, double trueSkinFraction) {
        float[] r = c.r();
        float[] g = c.g();
        float[] b = c.b();
        int n = c.pixels();

        float[] mxs = new float[n];
        for (int i = 0; i < n; i++) {
            mxs[i] = Math.max(r[i], Math.max(g[i], b[i]));
        }
        float[] sorted = mxs.clone();
        java.util.Arrays.sort(sorted);
        float median = sorted[n / 2];

        float[] or = new float[n];
        float[] og = new float[n];
        float[] ob = new float[n];
        int k = 0;
        for (int i = 0; i < n; i++) {
            if (mxs[i] > median) {
                or[k] = r[i];
                og[k] = g[i];
                ob[k] = b[i];
                k++;
            }
        }
        return new Skin(or, og, ob, k, trueSkinFraction);
    }

    @Override
    public Moments moments(float[] values, int n) {
        double sum = 0;
        double sumSq = 0;
        for (int i = 0; i < n; i++) {
            double v = values[i];
            sum += v;
            sumSq += v * v;
        }
        return new Moments(sum, sumSq, n);
    }

    @Override
    public Moments chromaMoments(float[] r, float[] g, float[] b, int n) {
        double sum = 0;
        double sumSq = 0;
        for (int i = 0; i < n; i++) {
            float rg = r[i] - g[i];
            float gb = g[i] - b[i];
            float br = b[i] - r[i];
            double chroma = Math.sqrt(rg * rg + gb * gb + br * br);
            sum += chroma;
            sumSq += chroma * chroma;
        }
        return new Moments(sum, sumSq, n);
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : (v > 255f ? 255f : v);
    }
}
