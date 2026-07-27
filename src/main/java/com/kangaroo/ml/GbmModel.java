package com.kangaroo.ml;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A gradient-boosted ensemble, parsed from the LightGBM v4 text format and scored in pure Java.
 *
 * <p>The models were trained elsewhere on a GPU; nothing in Kangaroo's runtime needs Python, a
 * native library, or a model server to use them. That is the point. A Raspberry Pi in a village
 * with no network runs the same classifier, from the same file, with bit-identical arithmetic.
 *
 * <p>"Bit-identical" is a claim, so it is tested rather than asserted: {@code GbmParityTest} scores
 * a large set of generated feature vectors through both this engine and the reference LightGBM
 * implementation and fails the build if any probability differs by more than 1e-9. Porting a model
 * and proving you ported a model are different things.
 *
 * <h2>What is honoured</h2>
 * <ul>
 *   <li>{@code num_tree_per_iteration} — multiclass models interleave one tree per class.</li>
 *   <li>{@code best_iteration} — the file contains every tree that was ever built, including the
 *       ones after early stopping decided they were making things worse. Scoring all of them is a
 *       silent accuracy regression, and it is the single commonest way to get this wrong.</li>
 *   <li>The exact {@code <=} split boundary and missing-value semantics of {@code Tree}.</li>
 * </ul>
 */
public final class GbmModel {

    private final List<Tree> trees;
    private final int numClass;
    private final int treesPerIteration;
    private final int usedIterations;
    private final int maxFeatureIdx;
    private final String objective;
    private final String[] featureNames;
    private final boolean allThresholdsFloatSafe;

    private GbmModel(List<Tree> trees, int numClass, int treesPerIteration, int usedIterations,
                     int maxFeatureIdx, String objective, String[] featureNames) {
        this.trees = List.copyOf(trees);
        this.numClass = numClass;
        this.treesPerIteration = treesPerIteration;
        this.usedIterations = usedIterations;
        this.maxFeatureIdx = maxFeatureIdx;
        this.objective = objective;
        this.featureNames = featureNames.clone();
        this.allThresholdsFloatSafe = trees.stream().allMatch(Tree::floatSafe);
    }

    // ---------------------------------------------------------------- loading

    /**
     * @param bestIteration the iteration count to score, or a non-positive value to use every tree
     */
    public static GbmModel load(InputStream in, int bestIteration) throws IOException {
        int numClass = 1;
        int treesPerIteration = 1;
        int maxFeatureIdx = -1;
        String objective = "";
        String[] featureNames = new String[0];
        List<Tree> trees = new ArrayList<>();

        // Per-tree accumulators, reset at each "Tree=" marker.
        int numLeaves = 0;
        int numCat = 0;
        int[] splitFeature = null;
        double[] threshold = null;
        byte[] decisionType = null;
        int[] left = null;
        int[] right = null;
        double[] leafValue = null;
        boolean inTree = false;

        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) continue;

                // The trailing "[key: value]" parameter dump and the "end of parameters" marker are
                // not model structure; stop consuming them as such.
                if (line.startsWith("[") || line.equals("end of parameters")) continue;

                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq);
                String value = line.substring(eq + 1);

                switch (key) {
                    case "num_class" -> numClass = Integer.parseInt(value);
                    case "num_tree_per_iteration" -> treesPerIteration = Integer.parseInt(value);
                    case "max_feature_idx" -> maxFeatureIdx = Integer.parseInt(value);
                    case "objective" -> objective = value;
                    case "feature_names" -> featureNames = value.split(" ");
                    case "Tree" -> {
                        if (inTree) {
                            trees.add(buildTree(numLeaves, numCat, splitFeature, threshold,
                                    decisionType, left, right, leafValue));
                        }
                        inTree = true;
                        numLeaves = 0; numCat = 0;
                        splitFeature = null; threshold = null; decisionType = null;
                        left = null; right = null; leafValue = null;
                    }
                    case "num_leaves" -> numLeaves = Integer.parseInt(value);
                    case "num_cat" -> numCat = Integer.parseInt(value);
                    case "split_feature" -> splitFeature = parseInts(value);
                    case "threshold" -> threshold = parseDoubles(value);
                    case "decision_type" -> decisionType = parseBytes(value);
                    case "left_child" -> left = parseInts(value);
                    case "right_child" -> right = parseInts(value);
                    case "leaf_value" -> leafValue = parseDoubles(value);
                    default -> { /* tree_sizes, split_gain, leaf_weight, internal_* : not needed to score */ }
                }
            }
        }
        if (inTree) {
            trees.add(buildTree(numLeaves, numCat, splitFeature, threshold,
                    decisionType, left, right, leafValue));
        }

        if (trees.isEmpty()) throw new IOException("model file contained no trees");
        if (treesPerIteration <= 0) treesPerIteration = 1;

        int availableIterations = trees.size() / treesPerIteration;
        int used = bestIteration > 0 ? Math.min(bestIteration, availableIterations) : availableIterations;

        return new GbmModel(trees, numClass, treesPerIteration, used, maxFeatureIdx,
                objective, featureNames);
    }

    private static Tree buildTree(int numLeaves, int numCat, int[] splitFeature, double[] threshold,
                                  byte[] decisionType, int[] left, int[] right, double[] leafValue) {
        if (numCat > 0) {
            // Neither shipped model uses categorical splits. Refusing loudly is better than
            // scoring them as numerical and being quietly wrong.
            throw new IllegalStateException(
                    "categorical splits are not supported by this scorer (num_cat=" + numCat + ")");
        }
        if (leafValue == null) {
            throw new IllegalStateException("tree has no leaf_value line");
        }
        if (numLeaves <= 1) {
            return new Tree(1, new int[0], new double[0], new byte[0], new int[0], new int[0],
                    leafValue, true);
        }
        int internal = numLeaves - 1;
        requireLength(splitFeature, internal, "split_feature");
        requireLength(threshold, internal, "threshold");
        requireLength(decisionType, internal, "decision_type");
        requireLength(left, internal, "left_child");
        requireLength(right, internal, "right_child");
        requireLength(leafValue, numLeaves, "leaf_value");

        return new Tree(numLeaves, splitFeature, threshold, decisionType, left, right, leafValue,
                floatSafe(threshold));
    }

    /**
     * True when every threshold in the tree survives a round trip through {@code float}.
     *
     * <p>This is a primitive type pattern (JEP 530) doing real work. {@code d instanceof float f}
     * succeeds only when the {@code double} is exactly representable in a {@code float} — the
     * narrowing is checked, not assumed. LightGBM computes its thresholds from float32 feature
     * values, so in principle they should all pass; in practice bin boundaries are emitted as
     * doubles like {@code 59.000000000000007} that do not. Knowing which trees are genuinely
     * float-safe is what tells us whether a float-width vectorised scorer would be bit-identical or
     * merely close, and for a clinical model "merely close" is not a tradeoff worth making.
     */
    private static boolean floatSafe(double[] thresholds) {
        for (double t : thresholds) {
            if (!(t instanceof float _)) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- scoring

    /**
     * Raw additive scores, one per class, over the used iterations only.
     */
    public double[] rawScore(double[] x) {
        if (x.length <= maxFeatureIdx) {
            throw new IllegalArgumentException(
                    "feature vector has " + x.length + " values but the model indexes up to " + maxFeatureIdx);
        }
        double[] raw = new double[treesPerIteration];
        int limit = usedIterations * treesPerIteration;
        for (int t = 0; t < limit; t++) {
            raw[t % treesPerIteration] += trees.get(t).score(x);
        }
        return raw;
    }

    /**
     * Class probabilities. Multiclass objectives go through softmax; binary through the logistic
     * function; anything else is returned raw.
     */
    public double[] predict(double[] x) {
        double[] raw = rawScore(x);
        if (objective.startsWith("multiclass")) return softmax(raw);
        if (objective.startsWith("binary")) {
            double p = 1.0 / (1.0 + Math.exp(-raw[0]));
            return new double[] {1 - p, p};
        }
        return raw;
    }

    public double[] predict(float[] x) {
        double[] d = new double[x.length];
        for (int i = 0; i < x.length; i++) d[i] = x[i];   // widening float->double is exact
        return predict(d);
    }

    /** Numerically stable softmax; the shift by the max is what keeps large raw scores finite. */
    static double[] softmax(double[] raw) {
        double max = Double.NEGATIVE_INFINITY;
        for (double v : raw) max = Math.max(max, v);
        double sum = 0;
        double[] out = new double[raw.length];
        for (int i = 0; i < raw.length; i++) {
            out[i] = Math.exp(raw[i] - max);
            sum += out[i];
        }
        for (int i = 0; i < out.length; i++) out[i] /= sum;
        return out;
    }

    // ---------------------------------------------------------------- description

    public int numClass() { return numClass; }
    public int treesPerIteration() { return treesPerIteration; }
    public int usedIterations() { return usedIterations; }
    public int totalTrees() { return trees.size(); }
    public int usedTrees() { return usedIterations * treesPerIteration; }
    public int numFeatures() { return maxFeatureIdx + 1; }
    public String objective() { return objective; }
    public String[] featureNames() { return featureNames.clone(); }
    public boolean allThresholdsFloatSafe() { return allThresholdsFloatSafe; }

    /**
     * Verify the model file's own feature-name list against the caller's expected order.
     *
     * @throws IllegalStateException on any mismatch, naming the first offending index. A model
     *         scored with permuted features produces confident nonsense, so this is a load-time
     *         failure rather than a warning.
     */
    public void requireFeatureOrder(String[] expected) {
        if (featureNames.length != expected.length) {
            throw new IllegalStateException("model has " + featureNames.length
                    + " features but " + expected.length + " were expected");
        }
        for (int i = 0; i < expected.length; i++) {
            if (!featureNames[i].equals(expected[i])) {
                throw new IllegalStateException("feature order mismatch at index " + i
                        + ": model has '" + featureNames[i] + "', expected '" + expected[i] + "'");
            }
        }
    }

    @Override
    public String toString() {
        return "GbmModel[" + objective + ", " + numClass + " classes, "
                + usedTrees() + "/" + totalTrees() + " trees ("
                + usedIterations + " iterations), " + numFeatures() + " features]";
    }

    // ---------------------------------------------------------------- parsing helpers

    private static int[] parseInts(String s) {
        String[] parts = s.split(" ");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i]);
        return out;
    }

    private static double[] parseDoubles(String s) {
        String[] parts = s.split(" ");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Double.parseDouble(parts[i]);
        return out;
    }

    private static byte[] parseBytes(String s) {
        String[] parts = s.split(" ");
        byte[] out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = (byte) Integer.parseInt(parts[i]);
        return out;
    }

    private static void requireLength(Object array, int expected, String name) {
        int actual = array == null ? -1 : java.lang.reflect.Array.getLength(array);
        if (actual != expected) {
            throw new IllegalStateException(
                    name + " has " + actual + " entries, expected " + expected);
        }
    }
}
