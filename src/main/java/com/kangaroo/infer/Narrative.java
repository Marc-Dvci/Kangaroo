package com.kangaroo.infer;

import com.kangaroo.core.Rung;
import com.kangaroo.core.TrafficLight;
import com.kangaroo.util.Json;

import java.util.List;
import java.util.Optional;

/**
 * What a language model contributed to an assessment.
 *
 * <p>Note what is <em>not</em> here: the decision. The traffic light in {@link #suggested} is the
 * model's opinion, recorded so that it can be compared with the deterministic rule and the
 * calibrated head, and so that a disagreement can be surfaced to a supervisor. It is never the
 * answer on its own.
 *
 * <p>What the model is genuinely good at is everything else in this record: turning a list of
 * findings into an explanation a frightened parent can act on, in their own language, at 3 a.m.
 * That is a real contribution and it is why the model is here at all.
 *
 * @param suggested   the model's own traffic light, or empty when it could not be parsed
 * @param reasoning   why, in the model's words
 * @param actionPlan  what to do, in the caregiver's language
 * @param rung        which rung produced this
 * @param raw         the unedited model output, kept for the audit trail
 * @param elapsedMs   wall-clock time
 */
public record Narrative(
        Optional<TrafficLight> suggested,
        String reasoning,
        String actionPlan,
        List<String> observations,
        Rung rung,
        String raw,
        long elapsedMs) {

    public Narrative {
        observations = List.copyOf(observations);
        if (reasoning == null) reasoning = "";
        if (actionPlan == null) actionPlan = "";
        if (raw == null) raw = "";
        if (suggested == null) suggested = Optional.empty();
    }

    /** The narrative the deterministic rung produces: no model, no opinion, just the protocol. */
    public static Narrative deterministic(String reasoning, String actionPlan, List<String> observations) {
        return new Narrative(Optional.empty(), reasoning, actionPlan, observations,
                Rung.DETERMINISTIC, "", 0);
    }

    public boolean fromModel() {
        return rung.hasNarrative();
    }

    public Json.Obj toJson() {
        return Json.obj()
                .put("suggested", suggested.map(Enum::name).orElse("none"))
                .put("reasoning", reasoning)
                .put("action_plan", actionPlan)
                .putStrings("observations", observations)
                .put("rung", rung.name())
                .put("rung_label", rung.label())
                .put("elapsed_ms", elapsedMs)
                .build();
    }
}
