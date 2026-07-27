package com.kangaroo.infer;

import com.kangaroo.core.Classification;
import com.kangaroo.core.DangerSign;
import com.kangaroo.core.Encounter;
import com.kangaroo.core.Rung;
import com.kangaroo.core.SignProfile;
import com.kangaroo.core.TrafficLight;

import java.util.List;

/**
 * One rung of the inference ladder.
 *
 * <p>Sealed, so the ladder in {@link FailoverEngine} is provably exhaustive and a new engine cannot
 * be added without deciding where in the descent it belongs.
 *
 * <p>Every engine answers the same question and returns the same type. What differs between them is
 * where the computation happened and how good the prose is — never what the traffic light means.
 * That invariant is the reason degradation is safe: pulling the network cable changes the quality
 * of the explanation, not the safety of the answer.
 */
public sealed interface InferenceEngine extends AutoCloseable
        permits NativeEngine, OpenAiCompatibleEngine, DeterministicEngine {

    /** Where this engine sits on the ladder. */
    Rung rung();

    /**
     * Whether this engine could serve a request right now. Cheap: no network round trip, no model
     * load. The ladder calls it before every attempt.
     */
    boolean available();

    /** A short human-readable description for the honest badge in the UI. */
    String describe();

    /**
     * Produce the narrative for an encounter.
     *
     * @throws Exception on any failure. The ladder catches it and descends; an engine is never
     *         responsible for its own fallback.
     */
    Narrative explain(Request request) throws Exception;

    @Override
    void close();

    /**
     * Everything an engine needs. The deterministic outcome is included deliberately: the model is
     * told what the rule engine concluded is <em>not</em> its job to reproduce, and the bottom rung
     * needs it because it has nothing else.
     *
     * @param encounter      the encounter
     * @param profile        the structured evidence
     * @param ruleLight      what the deterministic WHO rule concluded
     * @param classification the WHO classification
     * @param signs          the danger signs found
     */
    record Request(
            Encounter encounter,
            SignProfile profile,
            TrafficLight ruleLight,
            Classification classification,
            List<DangerSign> signs) {

        public Request {
            signs = List.copyOf(signs);
        }

        public java.util.Locale locale() {
            return com.kangaroo.i18n.Messages.parse(encounter.locale());
        }

        /** True when this encounter must never leave the device, whatever the settings say. */
        public boolean localOnly() {
            return encounter.privacyLocal();
        }
    }
}
