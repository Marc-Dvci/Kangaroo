package com.kangaroo.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The structured danger-sign profile for one encounter: the complete evidence the clinical engine
 * reasons over, in the exact shape the models consume.
 *
 * <p>Immutable, built once per encounter, and the only thing that flows from the extraction layer
 * into the rule engine, the gradient-boosted head and the language model alike. That single shared
 * representation is what lets the deterministic path and the model path be compared honestly:
 * they are, provably, looking at the same evidence.
 *
 * <p>Absent continuous values are held as {@code -1}, which is what the models were trained to read
 * as "missing". Use {@link #isPresent(Feature)} rather than testing for {@code -1} at call sites.
 */
public final class SignProfile {

    private final double[] values;
    private final Map<Feature, String> evidence;

    private SignProfile(double[] values, Map<Feature, String> evidence) {
        this.values = values;
        this.evidence = evidence;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** An empty profile: nothing observed, nothing measured. */
    public static SignProfile empty() {
        return builder().build();
    }

    public double get(Feature f) {
        return values[f.ordinal()];
    }

    public boolean flag(Feature f) {
        return values[f.ordinal()] >= 0.5;
    }

    public int ordinal(Feature f) {
        return (int) Math.round(values[f.ordinal()]);
    }

    public boolean isPresent(Feature f) {
        return values[f.ordinal()] >= 0;
    }

    /**
     * The verbatim text fragment that caused a feature to be set, when extraction found one.
     * This is what the audit trail and the referral letter quote — a classification whose reasons
     * cannot be traced back to something the caregiver actually said is not auditable.
     */
    public String evidenceFor(Feature f) {
        return evidence.get(f);
    }

    public Map<Feature, String> allEvidence() {
        return evidence;
    }

    /**
     * The feature vector, in model order. A fresh copy every call: the models are handed this array
     * and must not be able to mutate the profile.
     */
    public float[] toFeatureVector() {
        float[] v = new float[Feature.COUNT];
        for (int i = 0; i < Feature.COUNT; i++) {
            v[i] = (float) values[i];
        }
        return v;
    }

    public double[] toDoubleVector() {
        return values.clone();
    }

    /** Every flag that is set, in declaration order — the "what did we actually find" list. */
    public List<Feature> setFlags() {
        List<Feature> out = new ArrayList<>();
        for (Feature f : Feature.values()) {
            if (f.isFlag() && flag(f)) out.add(f);
        }
        return List.copyOf(out);
    }

    public SignProfile with(Feature f, double value) {
        double[] copy = values.clone();
        copy[f.ordinal()] = value;
        return new SignProfile(copy, evidence);
    }

    @Override
    public String toString() {
        return "SignProfile" + setFlags();
    }

    /** Mutable builder; the profile it produces is not. */
    public static final class Builder {
        private final double[] values = new double[Feature.COUNT];
        private final Map<Feature, String> evidence = new EnumMap<>(Feature.class);

        private Builder() {
            // Continuous features default to "missing"; flags default to 0 (absent).
            for (Feature f : Feature.values()) {
                values[f.ordinal()] = f.type() == Feature.Type.CONTINUOUS ? -1.0 : 0.0;
            }
        }

        public Builder set(Feature f, double value) {
            values[f.ordinal()] = value;
            return this;
        }

        public Builder flag(Feature f, boolean on) {
            values[f.ordinal()] = on ? 1.0 : 0.0;
            return this;
        }

        /** Set a flag and record the text that justified it. */
        public Builder flag(Feature f, boolean on, String because) {
            values[f.ordinal()] = on ? 1.0 : 0.0;
            if (on && because != null && !because.isBlank()) {
                evidence.put(f, because.strip());
            }
            return this;
        }

        /** Turn a flag on without ever turning it off — used when several sources can each set it. */
        public Builder raise(Feature f, boolean on, String because) {
            if (on) flag(f, true, because);
            return this;
        }

        public Builder subject(Subject s) {
            if (s.ageKnown()) set(Feature.AGE_DAYS, s.ageDays());
            if (s.weightKnown()) set(Feature.WEIGHT_KG, s.weightKg());
            flag(Feature.SEX_FEMALE, s.sex() == Sex.FEMALE);
            raise(Feature.PRETERM, s.preterm(), "recorded as preterm");
            raise(Feature.LOW_WEIGHT, s.lowWeight(), "weight below 2.5 kg");
            return this;
        }

        public Builder vitals(Vitals v) {
            v.respiratoryRateOpt().ifPresent(rr -> {
                set(Feature.RESP_RATE, rr);
                raise(Feature.RR_GE_60, rr >= 60, "counted respiratory rate " + rr + "/min");
            });
            v.temperatureOpt().ifPresent(t -> {
                raise(Feature.FEVER, t >= 38.0, "measured temperature " + t + " C");
                raise(Feature.MEASURED_COLD, t < 35.5, "measured temperature " + t + " C");
            });
            return this;
        }

        public double get(Feature f) {
            return values[f.ordinal()];
        }

        public boolean isSet(Feature f) {
            return values[f.ordinal()] >= 0.5;
        }

        public SignProfile build() {
            return new SignProfile(values.clone(),
                    Collections.unmodifiableMap(new EnumMap<>(evidence)));
        }
    }
}
