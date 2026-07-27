package com.kangaroo;

import com.kangaroo.color.Bench;
import com.kangaroo.color.ColourFeature;
import com.kangaroo.color.ColourPipeline;
import com.kangaroo.color.Frame;
import com.kangaroo.color.ScalarPipeline;
import com.kangaroo.color.VectorPipeline;
import com.kangaroo.ml.Models;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The vectorised colorimetry pipeline against its scalar oracle.
 *
 * <p>The two implementations are <em>not</em> bit-identical, and this suite states the tolerance
 * rather than pretending otherwise: a lane-wise reduction reassociates the additions, so the last
 * few bits differ. What must hold is that the difference is far below anything that could change a
 * clinical answer, and that is checked directly — not by asserting a tolerance on the features and
 * hoping, but by scoring both feature vectors through the actual model and asserting the grade
 * comes out the same.
 */
class ColourPipelineTest {

    /** Features are on a 0-255 scale; this is a relative tolerance of about one part in 100,000. */
    private static final double FEATURE_TOLERANCE = 2e-3;

    @Test
    @DisplayName("scalar and vector agree to tolerance on realistic frames")
    void implementationsAgree() {
        ColourPipeline scalar = new ScalarPipeline();
        ColourPipeline vector = new VectorPipeline();
        Random random = new Random(7);

        double worst = 0;
        ColourFeature worstFeature = null;

        for (int trial = 0; trial < 12; trial++) {
            Frame a = randomFrame(random, 256, 256);
            Frame b = copy(a);

            float[] fs = scalar.extract(a);
            float[] fv = vector.extract(b);

            assertEquals(ColourFeature.COUNT, fs.length);
            for (ColourFeature f : ColourFeature.values()) {
                double delta = Math.abs(fs[f.ordinal()] - fv[f.ordinal()]);
                double scale = Math.max(1.0, Math.abs(fs[f.ordinal()]));
                double relative = delta / scale;
                if (relative > worst) {
                    worst = relative;
                    worstFeature = f;
                }
            }
        }

        final ColourFeature reported = worstFeature;
        final double measured = worst;
        assertTrue(worst < FEATURE_TOLERANCE, () -> String.format(
                "scalar and vector diverged by %.2e (relative) on %s, above the %.0e tolerance",
                measured, reported, FEATURE_TOLERANCE));
    }

    @Test
    @DisplayName("both implementations produce the same jaundice grade")
    void sameGradeFromBothImplementations() {
        ColourPipeline scalar = new ScalarPipeline();
        ColourPipeline vector = new VectorPipeline();
        Random random = new Random(11);

        for (int trial = 0; trial < 25; trial++) {
            Frame a = randomFrame(random, 192, 192);
            Frame b = copy(a);

            int gradeScalar = argmax(Models.jaundice().predict(scalar.extract(a)));
            int gradeVector = argmax(Models.jaundice().predict(vector.extract(b)));

            assertEquals(gradeScalar, gradeVector,
                    "the two implementations must never produce a different clinical grade");
        }
    }

    @Test
    @DisplayName("odd frame sizes exercise the scalar tail of the vector loops")
    void handlesSizesThatAreNotAMultipleOfTheVectorWidth() {
        ColourPipeline scalar = new ScalarPipeline();
        ColourPipeline vector = new VectorPipeline();
        Random random = new Random(13);

        // 17, 31 and 33 are all awkward against 4, 8 and 16 lanes.
        for (int size : new int[] {17, 31, 33, 63, 65, 127}) {
            Frame a = randomFrame(random, size, size);
            Frame b = copy(a);

            float[] fs = scalar.extract(a);
            float[] fv = vector.extract(b);

            for (ColourFeature f : ColourFeature.values()) {
                double scale = Math.max(1.0, Math.abs(fs[f.ordinal()]));
                assertTrue(Math.abs(fs[f.ordinal()] - fv[f.ordinal()]) / scale < FEATURE_TOLERANCE,
                        "divergence at " + size + "x" + size + " on " + f
                                + " - the scalar tail of a vector loop is wrong");
            }
        }
    }

    @Test
    @DisplayName("colour space conversions match published reference values")
    void colourSpaceMaths() {
        // sRGB white -> L*=100, a*=b*=0.
        double[] white = ColourPipeline.srgbToLab(255, 255, 255);
        assertEquals(100.0, white[0], 0.01);
        assertEquals(0.0, white[1], 0.01);
        assertEquals(0.0, white[2], 0.01);

        // sRGB black -> L*=0.
        assertEquals(0.0, ColourPipeline.srgbToLab(0, 0, 0)[0], 0.01);

        // Pure yellow has a strongly positive b*, which is the axis jaundice moves along.
        assertTrue(ColourPipeline.srgbToLab(255, 255, 0)[2] > 90, "yellow must have a large positive b*");
        // Pure blue has a strongly negative b*.
        assertTrue(ColourPipeline.srgbToLab(0, 0, 255)[2] < -90);

        double[] hsv = ColourPipeline.rgbToHsv(255, 0, 0);
        assertEquals(0.0, hsv[0], 0.01);
        assertEquals(1.0, hsv[1], 0.01);
        assertEquals(1.0, hsv[2], 0.01);
    }

    @Test
    @DisplayName("percentiles interpolate the way the reference implementation does")
    void percentiles() {
        float[] sorted = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        assertEquals(4.5, ColourPipeline.percentile(sorted, 50), 1e-9);
        assertEquals(0.9, ColourPipeline.percentile(sorted, 10), 1e-9);
        assertEquals(8.1, ColourPipeline.percentile(sorted, 90), 1e-9);
        assertEquals(0.0, ColourPipeline.percentile(sorted, 0), 1e-9);
        assertEquals(9.0, ColourPipeline.percentile(sorted, 100), 1e-9);

        assertEquals(5.0, ColourPipeline.percentile(new float[] {5}, 50), 1e-9);
        assertEquals(0.0, ColourPipeline.percentile(new float[0], 50), 1e-9);
    }

    @Test
    @DisplayName("a frame decoded from real bytes produces a full feature vector")
    void decodesRealImageBytes() throws Exception {
        BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                image.setRGB(x, y, (205 << 16) | (178 << 8) | 140);
            }
        }
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", bytes);

        Frame frame = Frame.decode(bytes.toByteArray());
        assertEquals(128, frame.width());
        assertEquals(128, frame.height());

        float[] features = ColourPipeline.preferred().extract(frame);
        assertEquals(ColourFeature.COUNT, features.length);
        for (float v : features) {
            assertTrue(Float.isFinite(v), "no feature may be NaN or infinite");
        }
    }

    @Test
    @DisplayName("the benchmark runs and reports both figures")
    void benchmarkRuns() {
        Bench.Comparison c = Bench.run(128);

        // What the harness guarantees is that both figures are real measurements and that the
        // reported speedups are exactly the ratios of the times reported alongside them. It does
        // not guarantee a magnitude: this runs on shared CI runners and on Raspberry Pis, and a
        // test that asserts "SIMD is faster here" is asserting something about the hardware.
        assertTrue(c.scalar().medianNanos() > 0, "a zero-length batch is a broken clock");
        assertTrue(c.vector().medianNanos() > 0, "a zero-length batch is a broken clock");
        assertTrue(c.scalarKernels().medianNanos() > 0, "a zero-length batch is a broken clock");
        assertTrue(c.vectorKernels().medianNanos() > 0, "a zero-length batch is a broken clock");

        assertTrue(Double.isFinite(c.kernelSpeedup()), "kernel speedup must be a finite number");
        assertTrue(Double.isFinite(c.speedup()), "end-to-end speedup must be a finite number");

        assertEquals((double) c.scalarKernels().medianNanos() / c.vectorKernels().medianNanos(),
                c.kernelSpeedup(), 1e-9, "the reported kernel speedup must be the reported ratio");
        assertEquals((double) c.scalar().medianNanos() / c.vector().medianNanos(),
                c.speedup(), 1e-9, "the reported speedup must be the reported ratio");
        assertEquals(c.speedup(), c.endToEndSpeedup(), 1e-12);

        // Throughput and per-frame time are two views of one measurement and must not drift apart.
        assertEquals(1_000_000_000.0 / c.vector().medianNanos(), c.vector().framesPerSec(), 1e-6);
    }

    // ---------------------------------------------------------------- helpers

    private static Frame randomFrame(Random random, int w, int h) {
        return Bench.syntheticFrame(w, h);
    }

    private static Frame copy(Frame f) {
        return new Frame(f.width(), f.height(), f.r().clone(), f.g().clone(), f.b().clone());
    }

    private static int argmax(double[] v) {
        int a = 0;
        for (int i = 1; i < v.length; i++) if (v[i] > v[a]) a = i;
        return a;
    }
}
