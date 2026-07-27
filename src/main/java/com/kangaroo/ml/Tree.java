package com.kangaroo.ml;

/**
 * One decision tree from a gradient-boosted ensemble, in the flat array form it is scored in.
 *
 * <p>Deliberately not a node object graph. A 99-tree ensemble scored as objects is a few thousand
 * pointer chases through cold cache lines; scored as parallel primitive arrays it is a handful of
 * predictable loads, and on a Raspberry Pi that difference is the whole latency budget. The tree is
 * a record so it is trivially immutable and shareable across every virtual thread serving an
 * encounter, with no defensive copying.
 *
 * <p>Node indexing follows the LightGBM convention exactly: internal nodes are non-negative indices
 * into the split arrays; a child index {@code c < 0} denotes leaf {@code -c - 1}.
 *
 * @param numLeaves    leaf count; a tree with one leaf is a constant and has no split arrays
 * @param splitFeature feature index tested at each internal node
 * @param threshold    the threshold tested at each internal node
 * @param decisionType packed LightGBM decision flags per internal node
 * @param left         left child index per internal node, negative for a leaf
 * @param right        right child index per internal node, negative for a leaf
 * @param leafValue    the additive output of each leaf
 * @param floatSafe    true when every threshold is exactly representable as a float
 */
public record Tree(
        int numLeaves,
        int[] splitFeature,
        double[] threshold,
        byte[] decisionType,
        int[] left,
        int[] right,
        double[] leafValue,
        boolean floatSafe) {

    /** Bit 0 of {@code decision_type}: the split is categorical rather than numerical. */
    public static final int CATEGORICAL = 0b0001;
    /** Bit 1: missing values travel down the left branch. */
    public static final int DEFAULT_LEFT = 0b0010;
    /** Bits 2-3: how missing values are encoded. */
    public static final int MISSING_TYPE_MASK = 0b1100;

    /** How a model encodes "this feature was not observed". */
    public enum MissingType { NONE, ZERO, NAN }

    /**
     * Unpack the missing-value encoding from a packed decision-type byte.
     *
     * <p>Written with primitive type patterns (JEP 530). The alternative is a chain of shifts and
     * masks with a trailing {@code else throw}; here the {@code switch} is over the primitive
     * itself, each arm states the bit condition it matches as a guard, and the compiler checks that
     * the arms cover the domain. Bit-twiddling that a reviewer has to simulate in their head is
     * exactly the kind of code a clinical model loader should not contain.
     */
    public static MissingType missingType(byte decisionType) {
        return switch ((decisionType & MISSING_TYPE_MASK) >> 2) {
            case int t when t == 0 -> MissingType.NONE;
            case int t when t == 1 -> MissingType.ZERO;
            case int t when t == 2 -> MissingType.NAN;
            case int t -> throw new IllegalArgumentException("unknown missing type bits: " + t);
        };
    }

    public static boolean defaultLeft(byte decisionType) {
        return (decisionType & DEFAULT_LEFT) != 0;
    }

    public static boolean categorical(byte decisionType) {
        return (decisionType & CATEGORICAL) != 0;
    }

    /**
     * Score one feature vector, reproducing LightGBM's {@code Tree::NumericalDecision} exactly,
     * including its missing-value handling and its {@code <=} boundary.
     */
    public double score(double[] x) {
        if (numLeaves <= 1) return leafValue[0];

        int node = 0;
        while (node >= 0) {
            double v = x[splitFeature[node]];
            byte dt = decisionType[node];
            MissingType mt = missingType(dt);

            // LightGBM coerces NaN to zero unless the model was trained to treat NaN as missing.
            if (Double.isNaN(v) && mt != MissingType.NAN) {
                v = 0.0;
            }

            boolean isMissing = (mt == MissingType.ZERO && v == 0.0)
                    || (mt == MissingType.NAN && Double.isNaN(v));

            node = isMissing
                    ? (defaultLeft(dt) ? left[node] : right[node])
                    : (v <= threshold[node] ? left[node] : right[node]);
        }
        return leafValue[-node - 1];
    }

    /** Number of internal nodes. */
    public int internalNodes() {
        return Math.max(0, numLeaves - 1);
    }
}
