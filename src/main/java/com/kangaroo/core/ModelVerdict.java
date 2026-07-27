package com.kangaroo.core;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * What the calibrated gradient-boosted head concluded, with its full probability vector and its
 * conformal prediction set.
 *
 * @param light       the argmax class
 * @param probs       calibrated probabilities over the three classes, summing to 1
 * @param predictionSet the conformal set at the configured error rate; a non-singleton means "unsure"
 */
public record ModelVerdict(TrafficLight light, Map<TrafficLight, Double> probs, Set<TrafficLight> predictionSet) {

    public ModelVerdict {
        probs = Map.copyOf(probs);
        predictionSet = Set.copyOf(predictionSet);
    }

    public static ModelVerdict of(double[] p) {
        if (p.length != 3) throw new IllegalArgumentException("expected 3 class probabilities, got " + p.length);
        Map<TrafficLight, Double> m = new EnumMap<>(TrafficLight.class);
        m.put(TrafficLight.GREEN, p[0]);
        m.put(TrafficLight.YELLOW, p[1]);
        m.put(TrafficLight.RED, p[2]);
        int arg = 0;
        for (int i = 1; i < 3; i++) if (p[i] > p[arg]) arg = i;
        TrafficLight light = TrafficLight.values()[arg];
        return new ModelVerdict(light, m, Set.of(light));
    }

    public double probability(TrafficLight t) {
        return probs.getOrDefault(t, 0.0);
    }

    public double confidence() {
        return probability(light);
    }

    /**
     * True when the conformal set contains more than one class — the model is not confident enough
     * to distinguish them at the configured coverage. Kangaroo treats this as a reason to refer,
     * not as a reason to guess.
     */
    public boolean uncertain() {
        return predictionSet.size() > 1;
    }

    /** The most severe class the conformal set admits. Abstention escalates to this. */
    public TrafficLight worstCaseInSet() {
        return predictionSet.stream().reduce(TrafficLight.GREEN, TrafficLight::escalatedWith);
    }

    public ModelVerdict withPredictionSet(Set<TrafficLight> set) {
        return new ModelVerdict(light, probs, set);
    }
}
