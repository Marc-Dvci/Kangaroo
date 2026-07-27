package com.kangaroo.color;

import java.util.Random;

/**
 * A live benchmark of the scalar and vectorised colorimetry pipelines.
 *
 * <p>Exposed at {@code /api/bench} so the speedup can be measured on the machine actually running
 * the product, on demand, rather than quoted from a README written on somebody else's laptop. On a
 * Raspberry Pi it reports the Pi's numbers.
 *
 * <p>It is a plain harness, not JMH, and says so. It warms up, runs timed batches, and reports the
 * best batch rather than the mean — the best batch is the one least contaminated by GC and by
 * whatever else the device was doing, which is the number a reader wants when comparing two
 * implementations of the same function. It does not attempt dead-code-elimination defences beyond
 * consuming the result, and it will not resolve a 5% difference. It resolves the difference this
 * comparison actually has.
 */
public final class Bench {

    private Bench() {}

    /**
     * @param pipeline     which implementation
     * @param medianNanos  best-batch time per frame
     * @param framesPerSec throughput
     */
    public record Result(String pipeline, long medianNanos, double framesPerSec, int frames) {
        public double millisPerFrame() {
            return medianNanos / 1_000_000.0;
        }
    }

    /**
     * Both pipelines, measured two ways, because one number would be misleading.
     *
     * <p>{@code kernelSpeedup} is what the Vector API actually does to the arithmetic: the four
     * kernels are pure elementwise float work over a quarter of a million pixels and that is where
     * SIMD applies. {@code endToEndSpeedup} is the whole {@code extract} call, which is the number
     * that matters to a user and is always the smaller of the two — because the pipeline also has
     * to sort the selected skin pixels to compute exact percentiles, and a sort does not vectorise.
     *
     * <p>Reporting only the kernel figure would be the flattering half of the truth. Reporting only
     * the end-to-end figure would hide where the time actually goes. Both are shown, and the
     * interface says which is which.
     */
    public record Comparison(Result scalar, Result vector, double speedup,
                             Result scalarKernels, Result vectorKernels, double kernelSpeedup,
                             int width, int height,
                             boolean vectorAvailable, String vectorDescription) {
        public double endToEndSpeedup() { return speedup; }
    }

    public static final int DEFAULT_SIZE = 512;
    private static final int BATCHES = 7;
    private static final int BATCH_FRAMES = 12;

    /**
     * Warm-up is budgeted in <em>pixels</em>, not frames.
     *
     * <p>A fixed frame count warms a 512x512 run sixteen times as hard as a 128x128 one, and the
     * vectorised path is the one that suffers: until C2 has compiled it the intrinsics are not
     * applied and the "vectorised" pipeline is measured while it is not yet vectorised. That
     * produces a speedup below 1.0 on the first small-frame run, on any machine, which is a
     * property of the harness rather than of the hardware.
     *
     * <p>Budgeting by pixels means every frame size crosses the same compilation thresholds, so
     * {@code /api/bench} is measuring the same thing on a Raspberry Pi at 128 as on a laptop at 512.
     */
    private static final long WARMUP_PIXELS = 30L * DEFAULT_SIZE * DEFAULT_SIZE;
    private static final int MIN_WARMUP_FRAMES = 30;

    /** Run the comparison on a synthetic frame of the given size. */
    public static Comparison run(int size) {
        Frame frame = syntheticFrame(size, size);

        ScalarPipeline scalar = new ScalarPipeline();
        Result scalarResult = measure(scalar, frame, Bench::fullPipeline);
        Result scalarKernels = measure(scalar, frame, Bench::kernelsOnly);

        VectorPipeline vector;
        try {
            vector = new VectorPipeline();
        } catch (Throwable t) {
            return new Comparison(scalarResult, scalarResult, 1.0,
                    scalarKernels, scalarKernels, 1.0, size, size, false,
                    "Vector API unavailable on this runtime");
        }

        Result vectorResult = measure(vector, frame, Bench::fullPipeline);
        Result vectorKernels = measure(vector, frame, Bench::kernelsOnly);

        return new Comparison(
                scalarResult, vectorResult,
                (double) scalarResult.medianNanos() / vectorResult.medianNanos(),
                scalarKernels, vectorKernels,
                (double) scalarKernels.medianNanos() / vectorKernels.medianNanos(),
                size, size, true, vector.name());
    }

    /** What a caller actually invokes. */
    private static float fullPipeline(ColourPipeline p, Frame f) {
        return p.extract(f)[0];
    }

    /**
     * Just the four kernels, in the order and on the data the real pipeline uses them: the
     * elementwise work SIMD applies to, with the percentile sort left out.
     */
    private static float kernelsOnly(ColourPipeline p, Frame f) {
        var wb = p.whiteBalanceStats(f);
        p.applyGains(f, 1.02f, 1.0f, 0.97f);
        var skin = p.selectSkin(f);
        int n = Math.max(1, skin.count());
        var mR = p.moments(skin.r(), n);
        var mG = p.moments(skin.g(), n);
        var mB = p.moments(skin.b(), n);
        var chroma = p.chromaMoments(skin.r(), skin.g(), skin.b(), n);
        return (float) (wb.maskedR() + mR.mean() + mG.mean() + mB.mean() + chroma.mean());
    }

    @FunctionalInterface
    private interface Work {
        float run(ColourPipeline pipeline, Frame frame);
    }

    public static Comparison run() {
        return run(DEFAULT_SIZE);
    }

    private static Result measure(ColourPipeline pipeline, Frame frame, Work work) {
        // Warm up until C2 has compiled the kernels; the vectorised path in particular is a very
        // different shape before and after the intrinsics are applied.
        float sink = 0;
        for (int i = 0; i < warmupFrames(frame); i++) {
            sink += work.run(pipeline, copyOf(frame));
        }

        long best = Long.MAX_VALUE;
        for (int b = 0; b < BATCHES; b++) {
            Frame[] frames = new Frame[BATCH_FRAMES];
            for (int i = 0; i < BATCH_FRAMES; i++) frames[i] = copyOf(frame);

            long t0 = System.nanoTime();
            for (int i = 0; i < BATCH_FRAMES; i++) {
                sink += work.run(pipeline, frames[i]);
            }
            long perFrame = (System.nanoTime() - t0) / BATCH_FRAMES;
            best = Math.min(best, perFrame);
        }

        // Consume the accumulator so the whole loop cannot be optimised away.
        if (Float.isNaN(sink)) throw new AssertionError("unreachable");

        // A batch that measures as zero would make the reported speedup a division by zero and the
        // throughput infinite. One nanosecond is the floor the clock can express.
        best = Math.max(1L, best);

        return new Result(pipeline.name(), best, 1_000_000_000.0 / best, BATCHES * BATCH_FRAMES);
    }

    /** Enough frames of this size to spend {@link #WARMUP_PIXELS} on warm-up. */
    private static int warmupFrames(Frame frame) {
        long pixels = (long) frame.width() * frame.height();
        long frames = (WARMUP_PIXELS + pixels - 1) / Math.max(1, pixels);
        return (int) Math.max(MIN_WARMUP_FRAMES, Math.min(frames, 20_000));
    }

    /**
     * The pipeline mutates its working copy when it applies the white-balance gains, so each
     * timed iteration gets a fresh frame. The copy is outside the timed region.
     */
    private static Frame copyOf(Frame f) {
        return new Frame(f.width(), f.height(), f.r().clone(), f.g().clone(), f.b().clone());
    }

    /**
     * A synthetic frame shaped like a real capture: a bright near-neutral card border with a
     * warm-toned skin window in the middle, plus noise. The point is to exercise both the
     * white-reference mask and the skin mask realistically, since a uniform frame would make every
     * branch predictable and the measurement meaningless.
     */
    public static Frame syntheticFrame(int width, int height) {
        Random random = new Random(20260726);
        int n = width * height;
        float[] r = new float[n];
        float[] g = new float[n];
        float[] b = new float[n];

        int lo = (int) (height * ColourPipeline.WINDOW_LO);
        int hi = (int) (height * ColourPipeline.WINDOW_HI);
        int xlo = (int) (width * ColourPipeline.WINDOW_LO);
        int xhi = (int) (width * ColourPipeline.WINDOW_HI);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                boolean inWindow = y >= lo && y < hi && x >= xlo && x < xhi;
                if (inWindow) {
                    // Warm skin with a yellow cast, the shape a jaundiced infant produces.
                    r[i] = clamp(205 + random.nextGaussian() * 9);
                    g[i] = clamp(178 + random.nextGaussian() * 9);
                    b[i] = clamp(140 + random.nextGaussian() * 11);
                } else {
                    // The card: bright and near-neutral, so the white-reference mask fires.
                    float v = clamp(214 + random.nextGaussian() * 6);
                    r[i] = v;
                    g[i] = v;
                    b[i] = clamp(v - 2);
                }
            }
        }
        return new Frame(width, height, r, g, b);
    }

    private static float clamp(double v) {
        return (float) Math.max(0, Math.min(255, v));
    }
}
