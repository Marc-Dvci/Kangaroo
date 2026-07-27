package com.kangaroo.color;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * The same four colorimetry kernels, over the Vector API (JEP 529).
 *
 * <p>Every operation in these kernels is elementwise float arithmetic across a quarter of a million
 * pixels with no data-dependent control flow, which is precisely the shape the Vector API is for.
 * The mask-and-reduce idiom does the work that would otherwise be a branch per pixel: the
 * white-reference test, the skin test and the fallback selection are all computed as
 * {@link VectorMask}s and then folded with masked reductions, so there is no branch in the inner
 * loop at all.
 *
 * <p>{@link #selectSkin} is the interesting one. Compacting the selected pixels into dense arrays
 * is the part that looks like it should defeat vectorisation, and {@code Vector.compress} is the
 * operation that does not — it packs the active lanes to the low end of the vector in one
 * instruction on hardware that supports it, so the whole select-and-compact pass stays branch-free.
 *
 * <p>The species is {@link FloatVector#SPECIES_PREFERRED}, so the same source runs at 128, 256 or
 * 512 bits according to what the machine actually has — 8 lanes on the laptop this was developed on,
 * 4 on the Raspberry Pi's NEON, without a recompile or a second code path.
 */
public final class VectorPipeline implements ColourPipeline {

    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

    private static final FloatVector ZERO = FloatVector.zero(SPECIES);

    @Override
    public String name() {
        return "vector(" + SPECIES.vectorBitSize() + "-bit, " + SPECIES.length() + " lanes)";
    }

    public int laneCount() {
        return SPECIES.length();
    }

    public int vectorBits() {
        return SPECIES.vectorBitSize();
    }

    /**
     * How many vector blocks to accumulate in float lanes before flushing into a double.
     *
     * <p>This is the crux of writing a correct <em>and</em> fast reduction, and it is a genuine
     * tension rather than a tuning knob. Reducing each block horizontally
     * ({@code reduceLanes} inside the loop) keeps full double precision but puts a
     * log-depth shuffle chain on the critical path of every iteration — which is why the first
     * version of this kernel was no faster than the scalar one. Accumulating into lane-wise
     * vector accumulators removes that entirely, but a {@code float} has a 24-bit mantissa, and
     * summing a quarter of a million pixel values of up to 255 each overflows its exact range
     * long before the end of the frame.
     *
     * <p>So: accumulate in lanes, flush to {@code double} every {@value} blocks. The largest
     * partial sum any lane can hold is {@code 64 * 255² ≈ 4.2e6} for the sum of squares, comfortably
     * inside the {@code 1.6e7} that a float represents exactly, so no addition in the inner loop
     * ever loses a bit — and the horizontal reduction happens once per sixty-four blocks instead of
     * once per block.
     */
    private static final int FLUSH_BLOCKS = 64;

    @Override
    public WhiteBalanceStats whiteBalanceStats(Frame f) {
        float[] r = f.r();
        float[] g = f.g();
        float[] b = f.b();
        int n = f.pixels();
        int lanes = SPECIES.length();
        int upper = SPECIES.loopBound(n);

        double mr = 0;
        double mg = 0;
        double mb = 0;
        double tr = 0;
        double tg = 0;
        double tb = 0;
        int masked = 0;

        FloatVector accTr = ZERO;
        FloatVector accTg = ZERO;
        FloatVector accTb = ZERO;
        FloatVector accMr = ZERO;
        FloatVector accMg = ZERO;
        FloatVector accMb = ZERO;
        int blocks = 0;

        for (int i = 0; i < upper; i += lanes) {
            FloatVector vr = FloatVector.fromArray(SPECIES, r, i);
            FloatVector vg = FloatVector.fromArray(SPECIES, g, i);
            FloatVector vb = FloatVector.fromArray(SPECIES, b, i);

            accTr = accTr.add(vr);
            accTg = accTg.add(vg);
            accTb = accTb.add(vb);

            FloatVector mx = vr.max(vg).max(vb);
            FloatVector mn = vr.min(vg).min(vb);

            // Bright and near-neutral, computed as a mask instead of a branch per pixel.
            VectorMask<Float> white = mn.compare(VectorOperators.GT, 170f)
                    .and(mx.sub(mn).compare(VectorOperators.LT, 28f));

            masked += white.trueCount();
            // add(v, mask) leaves the inactive lanes untouched: a masked accumulate, no blend.
            accMr = accMr.add(vr, white);
            accMg = accMg.add(vg, white);
            accMb = accMb.add(vb, white);

            if (++blocks == FLUSH_BLOCKS) {
                tr += accTr.reduceLanes(VectorOperators.ADD);
                tg += accTg.reduceLanes(VectorOperators.ADD);
                tb += accTb.reduceLanes(VectorOperators.ADD);
                mr += accMr.reduceLanes(VectorOperators.ADD);
                mg += accMg.reduceLanes(VectorOperators.ADD);
                mb += accMb.reduceLanes(VectorOperators.ADD);
                accTr = ZERO; accTg = ZERO; accTb = ZERO;
                accMr = ZERO; accMg = ZERO; accMb = ZERO;
                blocks = 0;
            }
        }
        tr += accTr.reduceLanes(VectorOperators.ADD);
        tg += accTg.reduceLanes(VectorOperators.ADD);
        tb += accTb.reduceLanes(VectorOperators.ADD);
        mr += accMr.reduceLanes(VectorOperators.ADD);
        mg += accMg.reduceLanes(VectorOperators.ADD);
        mb += accMb.reduceLanes(VectorOperators.ADD);

        // Tail. Kept scalar and identical to ScalarPipeline so the two agree on the last few pixels.
        for (int i = upper; i < n; i++) {
            float rv = r[i];
            float gv = g[i];
            float bv = b[i];
            tr += rv;
            tg += gv;
            tb += bv;
            float mx = Math.max(rv, Math.max(gv, bv));
            float mn = Math.min(rv, Math.min(gv, bv));
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
        scaleClamp(f.r(), gr);
        scaleClamp(f.g(), gg);
        scaleClamp(f.b(), gb);
    }

    private static void scaleClamp(float[] a, float gain) {
        int n = a.length;
        int upper = SPECIES.loopBound(n);
        FloatVector max = FloatVector.broadcast(SPECIES, 255f);
        for (int i = 0; i < upper; i += SPECIES.length()) {
            FloatVector.fromArray(SPECIES, a, i)
                    .mul(gain)
                    .max(ZERO)
                    .min(max)
                    .intoArray(a, i);
        }
        for (int i = upper; i < n; i++) {
            float v = a[i] * gain;
            a[i] = v < 0f ? 0f : (v > 255f ? 255f : v);
        }
    }

    @Override
    public Skin selectSkin(Frame c) {
        float[] r = c.r();
        float[] g = c.g();
        float[] b = c.b();
        int n = c.pixels();
        int lanes = SPECIES.length();
        int upper = SPECIES.loopBound(n);

        // Padded by one vector so a compress store at the last block cannot run off the end.
        float[] or = new float[n + lanes];
        float[] og = new float[n + lanes];
        float[] ob = new float[n + lanes];
        int k = 0;

        for (int i = 0; i < upper; i += lanes) {
            FloatVector vr = FloatVector.fromArray(SPECIES, r, i);
            FloatVector vg = FloatVector.fromArray(SPECIES, g, i);
            FloatVector vb = FloatVector.fromArray(SPECIES, b, i);

            FloatVector mx = vr.max(vg).max(vb);
            FloatVector mn = vr.min(vg).min(vb);

            VectorMask<Float> skin = vr.compare(VectorOperators.GE, vg)
                    .and(vg.compare(VectorOperators.GE, vb.sub(8f)))
                    .and(vr.compare(VectorOperators.GT, 60f))
                    .and(mx.compare(VectorOperators.LT, 250f))
                    .and(mn.compare(VectorOperators.LT, 245f))
                    .and(mx.compare(VectorOperators.GT, 40f))
                    .and(mx.sub(mn).compare(VectorOperators.LT, 130f));

            // Pack the selected lanes down to the low end, then store them contiguously.
            int count = skin.trueCount();
            if (count > 0) {
                vr.compress(skin).intoArray(or, k);
                vg.compress(skin).intoArray(og, k);
                vb.compress(skin).intoArray(ob, k);
                k += count;
            }
        }

        for (int i = upper; i < n; i++) {
            float rv = r[i];
            float gv = g[i];
            float bv = b[i];
            float mx = Math.max(rv, Math.max(gv, bv));
            float mn = Math.min(rv, Math.min(gv, bv));
            if (ScalarPipeline.isSkin(rv, gv, bv, mx, mn)) {
                or[k] = rv;
                og[k] = gv;
                ob[k] = bv;
                k++;
            }
        }

        double fraction = (double) k / n;
        if (fraction < MIN_SKIN_FRACTION) {
            return ScalarPipeline.brighterHalf(c, fraction);
        }
        return new Skin(or, og, ob, k, fraction);
    }

    @Override
    public Moments moments(float[] values, int n) {
        int lanes = SPECIES.length();
        int upper = SPECIES.loopBound(n);
        double sum = 0;
        double sumSq = 0;

        FloatVector accSum = ZERO;
        FloatVector accSq = ZERO;
        int blocks = 0;

        for (int i = 0; i < upper; i += lanes) {
            FloatVector v = FloatVector.fromArray(SPECIES, values, i);
            accSum = accSum.add(v);
            // fma keeps the multiply-accumulate in one instruction and one rounding.
            accSq = v.fma(v, accSq);

            if (++blocks == FLUSH_BLOCKS) {
                sum += accSum.reduceLanes(VectorOperators.ADD);
                sumSq += accSq.reduceLanes(VectorOperators.ADD);
                accSum = ZERO;
                accSq = ZERO;
                blocks = 0;
            }
        }
        sum += accSum.reduceLanes(VectorOperators.ADD);
        sumSq += accSq.reduceLanes(VectorOperators.ADD);

        for (int i = upper; i < n; i++) {
            double v = values[i];
            sum += v;
            sumSq += v * v;
        }
        return new Moments(sum, sumSq, n);
    }

    @Override
    public Moments chromaMoments(float[] r, float[] g, float[] b, int n) {
        int lanes = SPECIES.length();
        int upper = SPECIES.loopBound(n);
        double sum = 0;
        double sumSq = 0;

        FloatVector accSum = ZERO;
        FloatVector accSq = ZERO;
        int blocks = 0;

        for (int i = 0; i < upper; i += lanes) {
            FloatVector vr = FloatVector.fromArray(SPECIES, r, i);
            FloatVector vg = FloatVector.fromArray(SPECIES, g, i);
            FloatVector vb = FloatVector.fromArray(SPECIES, b, i);

            FloatVector drg = vr.sub(vg);
            FloatVector dgb = vg.sub(vb);
            FloatVector dbr = vb.sub(vr);

            FloatVector sq = drg.mul(drg).add(dgb.mul(dgb)).add(dbr.mul(dbr));

            accSum = accSum.add(sq.lanewise(VectorOperators.SQRT));
            accSq = accSq.add(sq);   // chroma squared is sq, exactly

            if (++blocks == FLUSH_BLOCKS) {
                sum += accSum.reduceLanes(VectorOperators.ADD);
                sumSq += accSq.reduceLanes(VectorOperators.ADD);
                accSum = ZERO;
                accSq = ZERO;
                blocks = 0;
            }
        }
        sum += accSum.reduceLanes(VectorOperators.ADD);
        sumSq += accSq.reduceLanes(VectorOperators.ADD);

        for (int i = upper; i < n; i++) {
            float rg = r[i] - g[i];
            float gb = g[i] - b[i];
            float br = b[i] - r[i];
            double sq = (double) rg * rg + (double) gb * gb + (double) br * br;
            sum += Math.sqrt(sq);
            sumSq += sq;
        }
        return new Moments(sum, sumSq, n);
    }
}
