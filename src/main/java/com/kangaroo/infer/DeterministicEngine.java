package com.kangaroo.infer;

import com.kangaroo.clinical.ImnciRule;
import com.kangaroo.core.Classification;
import com.kangaroo.core.DangerSign;
import com.kangaroo.core.Mode;
import com.kangaroo.core.Rung;

import java.util.ArrayList;
import java.util.List;

/**
 * The bottom rung: a complete, useful answer with no model, no network and no native library.
 *
 * <p>This engine is the reason the product can claim it never goes dark. It needs nothing that can
 * fail — no GPU, no GGUF file, no API key, no signal, no electricity beyond what the device already
 * has. It reads the WHO classification the rule engine produced and writes it out as instructions,
 * from templates, in the caregiver's language.
 *
 * <p>The prose is plainer than a model's. That is the entire cost of the bottom rung, and it is
 * the right thing to trade away: a health worker two hours from a road with a flat phone battery
 * and no signal still gets the correct WHO classification, the correct pre-referral actions, and
 * the correct return-immediately list.
 *
 * <p>Everything here is a template rather than generation, which also makes it the only rung whose
 * output is reviewable in advance by a clinician. It is checked into the repository, in full, and
 * it does not change between runs.
 */
public final class DeterministicEngine implements InferenceEngine {

    @Override
    public Rung rung() {
        return Rung.DETERMINISTIC;
    }

    @Override
    public boolean available() {
        // By construction. If this ever returns false the product has no floor left.
        return true;
    }

    @Override
    public String describe() {
        return "Deterministic WHO rules, no model";
    }

    @Override
    public Narrative explain(Request request) {
        Mode mode = request.encounter().mode();
        Classification c = request.classification();

        List<String> observations = new ArrayList<>();
        for (DangerSign s : request.signs()) {
            observations.add(s.sign().label() + " - " + s.provenance());
        }
        if (observations.isEmpty()) {
            observations.add("No danger signs were found in what was recorded.");
        }

        return Narrative.deterministic(reasoning(c, mode), actionPlan(c, mode), observations);
    }

    private String reasoning(Classification c, Mode mode) {
        String signs = c.reasons().isEmpty()
                ? "Nothing in this check matched a WHO danger sign."
                : "This is based on: " + String.join("; ", c.reasons()) + ".";

        // Exhaustive over the sealed classification: a new outcome type cannot be added without
        // deciding what the offline rung says about it.
        return switch (c) {
            case Classification.UrgentReferral u -> mode == Mode.PARENT
                    ? "Your baby has a sign that needs a health worker to look at them now, not later. "
                      + signs + " This is not something to wait on."
                    : "WHO IMNCI classification: " + u.name() + ". " + signs
                      + " This meets the criteria for urgent referral.";

            case Classification.TreatmentNeeded t -> mode == Mode.PARENT
                    ? "There is something here that should be treated, but it does not look like an "
                      + "emergency. " + signs + " Someone should see your baby today."
                    : "WHO IMNCI classification: " + t.name() + ". " + signs
                      + " Treat at this level of care and schedule the follow-up.";

            case Classification.HomeCare h -> mode == Mode.PARENT
                    ? "Nothing in this check needs a clinician today. " + signs
                      + " That is not the same as saying everything is fine - a photograph and a few "
                      + "questions cannot see everything, so read the list below carefully."
                    : "WHO IMNCI classification: " + h.name() + ". " + signs
                      + " Counsel on home care and confirm the caregiver knows the danger signs.";
        };
    }

    private String actionPlan(Classification c, Mode mode) {
        StringBuilder sb = new StringBuilder();

        switch (c) {
            case Classification.UrgentReferral u -> {
                sb.append(mode == Mode.PARENT ? "What to do now:\n" : "Pre-referral actions:\n");
                for (String step : u.preReferralTreatment()) {
                    sb.append("  - ").append(step).append('\n');
                }
            }
            case Classification.TreatmentNeeded t -> {
                sb.append(mode == Mode.PARENT ? "What to do today:\n" : "Treatment:\n");
                for (String step : t.treatments()) {
                    sb.append("  - ").append(step).append('\n');
                }
            }
            case Classification.HomeCare h -> {
                sb.append("Home care:\n");
                for (String step : h.advice()) {
                    sb.append("  - ").append(step).append('\n');
                }
            }
        }

        // The return-immediately list ships with every outcome, including the green one.
        sb.append('\n').append("Go to a clinic straight away if any of these happen:\n");
        for (String warning : ImnciRule.returnImmediatelyIf()) {
            sb.append("  - ").append(warning).append('\n');
        }
        return sb.toString();
    }

    @Override
    public void close() {
        // Nothing to release. That is the point of this rung.
    }
}
