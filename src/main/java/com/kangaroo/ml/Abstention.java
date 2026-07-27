package com.kangaroo.ml;

import com.kangaroo.core.ModelVerdict;
import com.kangaroo.core.TrafficLight;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * When the model does not know, say so — and refer.
 *
 * <p>Almost no deployed classifier implements this. Asked for a class, it returns its argmax, and
 * the argmax of {@code [0.34, 0.33, 0.33]} looks exactly like the argmax of {@code [0.98, 0.01,
 * 0.01]} by the time it reaches a user interface. In a clinical setting that difference is the
 * whole question, and "I do not know, so go" is the correct answer to it.
 *
 * <p>So the model returns a <em>set</em> rather than a label. When the set is a singleton the model
 * is confident and the pipeline uses it. When it is not, {@link #escalate} takes the most severe
 * class the set admits, which is what "abstain upward" means in practice.
 *
 * <h2>What this is, and what it is not</h2>
 * <p>Split-conformal prediction gives a distribution-free coverage guarantee — the true label is in
 * the set at least {@code 1 - alpha} of the time — provided you calibrate the threshold on a
 * labelled holdout drawn from the deployment population. Kangaroo supports exactly that through
 * {@link #calibrated}, and a deployment that has collected such a holdout should use it.
 *
 * <p>What ships by default is <b>not</b> that. It is the top-two margin rule in {@link #byMargin},
 * which produces the same abstain-upward behaviour without any coverage guarantee at all. Saying
 * "conformal" and shipping a hard-coded threshold would be a claim the evidence does not support,
 * so this class says which one it is doing, in {@link Rule#guaranteed()}, and the interface reports
 * it.
 */
public final class Abstention {

    /** How the prediction set was produced. */
    public enum Rule {
        /** Top-two margin. No coverage guarantee; ships by default. */
        MARGIN(false),
        /** Split conformal against a labelled calibration holdout. Guaranteed coverage. */
        CONFORMAL(true);

        private final boolean guaranteed;

        Rule(boolean guaranteed) { this.guaranteed = guaranteed; }

        /** True when the set carries a real, distribution-free coverage guarantee. */
        public boolean guaranteed() { return guaranteed; }
    }

    /** Below this top-two gap the model is treated as unable to distinguish the two classes. */
    public static final double DEFAULT_MARGIN = 0.20;

    private final Rule rule;
    private final double parameter;

    private Abstention(Rule rule, double parameter) {
        this.rule = rule;
        this.parameter = parameter;
    }

    /** The shipped default: top-two margin, no coverage claim. */
    public static Abstention byMargin() {
        return new Abstention(Rule.MARGIN, DEFAULT_MARGIN);
    }

    public static Abstention byMargin(double margin) {
        return new Abstention(Rule.MARGIN, margin);
    }

    /**
     * Split-conformal calibration from a labelled holdout.
     *
     * <p>The threshold is the {@code alpha} empirical quantile of the true-class probabilities on
     * the holdout: include every class whose probability is at least that. This is the Least
     * Ambiguous Set-valued Classifier, and it gives marginal coverage of at least {@code 1 - alpha}
     * when the holdout is exchangeable with what the device actually sees.
     *
     * @param trueClassProbabilities the model's probability for the correct class, per holdout case
     * @param alpha                  the permitted miscoverage rate, e.g. 0.05
     */
    public static Abstention calibrated(double[] trueClassProbabilities, double alpha) {
        if (trueClassProbabilities.length < 50) {
            throw new IllegalArgumentException(
                    "conformal calibration needs a meaningful holdout; got only "
                            + trueClassProbabilities.length + " cases. Use byMargin() instead and "
                            + "do not claim coverage.");
        }
        double[] sorted = trueClassProbabilities.clone();
        Arrays.sort(sorted);
        // The finite-sample correction: ceil((n+1) * alpha) rather than n * alpha.
        int n = sorted.length;
        int index = (int) Math.ceil((n + 1) * alpha) - 1;
        index = Math.max(0, Math.min(n - 1, index));
        return new Abstention(Rule.CONFORMAL, sorted[index]);
    }

    public Rule rule() { return rule; }

    public double parameter() { return parameter; }

    /**
     * Attach a prediction set to a verdict.
     *
     * <p>The verdict's argmax is always in the set — a set that excluded the model's own best guess
     * would be incoherent — so the set is never empty and the pipeline always has something to act
     * on.
     */
    public ModelVerdict apply(ModelVerdict verdict) {
        Set<TrafficLight> set = EnumSet.of(verdict.light());

        switch (rule) {
            case MARGIN -> {
                double best = verdict.confidence();
                for (TrafficLight t : TrafficLight.values()) {
                    if (t != verdict.light() && best - verdict.probability(t) < parameter) {
                        set.add(t);
                    }
                }
            }
            case CONFORMAL -> {
                for (TrafficLight t : TrafficLight.values()) {
                    if (verdict.probability(t) >= parameter) set.add(t);
                }
            }
        }
        return verdict.withPredictionSet(set);
    }

    /**
     * Resolve a possibly-ambiguous verdict into a single colour, escalating upward on doubt.
     *
     * <p>This is the asymmetry the whole class exists for. Uncertainty between GREEN and YELLOW
     * resolves to YELLOW; uncertainty between YELLOW and RED resolves to RED. Uncertainty never
     * resolves downward, because the cost of the two errors is not remotely symmetric.
     */
    public static TrafficLight escalate(ModelVerdict verdict) {
        return verdict.uncertain() ? verdict.worstCaseInSet() : verdict.light();
    }

    /** A sentence for the interface, explaining why the answer moved. */
    public static String explain(ModelVerdict verdict) {
        if (!verdict.uncertain()) return "";
        return "The model could not clearly separate "
                + verdict.predictionSet().stream().map(Enum::name).sorted().reduce((a, b) -> a + " and " + b).orElse("")
                + ", so this was treated as the more serious of the two.";
    }
}
