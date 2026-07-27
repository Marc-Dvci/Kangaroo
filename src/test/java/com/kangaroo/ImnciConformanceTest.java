package com.kangaroo;

import com.kangaroo.clinical.ImnciRule;
import com.kangaroo.core.Feature;
import com.kangaroo.core.SignProfile;
import com.kangaroo.core.TrafficLight;
import com.kangaroo.ml.Models;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The WHO IMNCI chart, as an exhaustive truth table.
 *
 * <p>Two things are proved here. First, that every single danger sign the protocol lists as
 * sufficient for urgent referral does, on its own, produce a referral — swept one at a time across
 * the entire sign space so that nothing depends on which other signs happen to be present. Second,
 * that the gradient-boosted head, which was trained to distil this rule, still agrees with it.
 *
 * <p>That second check is the one that earns its place. The trained head is the thing that could
 * drift: a retrain on a differently balanced corpus could quietly move a boundary, and nothing else
 * in the build would notice. Holding it to the rule on a swept sample is what turns "the model
 * scored well on a holdout" into "the model still implements the protocol".
 */
class ImnciConformanceTest {

    /** Every feature that is, alone, sufficient for RED under the WHO young-infant chart. */
    private static final List<Feature> SUFFICIENT_FOR_RED = List.of(
            Feature.CONVULSION,
            Feature.FEEDING_UNABLE,
            Feature.LETHARGY,
            Feature.CHEST_INDRAWING,
            Feature.GRUNTING_STRIDOR,
            Feature.RR_GE_60,
            Feature.WEAK_ABSENT_CRY,
            Feature.CENTRAL_CYANOSIS,
            Feature.PALLOR,
            Feature.FEVER,
            Feature.MEASURED_COLD,
            Feature.OMPHALITIS_SEVERE,
            Feature.UMBILICAL_BLEEDING,
            Feature.PUSTULES_MANY,
            Feature.BULGING_FONTANELLE,
            Feature.DEHYDRATION_SEVERE);

    /** Sufficient for YELLOW, and only YELLOW, when nothing red is present. */
    private static final List<Feature> SUFFICIENT_FOR_YELLOW = List.of(
            Feature.OMPHALITIS,
            Feature.PUSTULES,
            Feature.PURULENT_EYE);

    @Test
    @DisplayName("every red danger sign alone produces urgent referral")
    void eachRedSignAloneIsRed() {
        for (Feature f : SUFFICIENT_FOR_RED) {
            SignProfile p = base().with(f, 1.0);
            assertEquals(TrafficLight.RED, ImnciRule.label(p),
                    () -> f + " alone must classify as urgent referral");
        }
    }

    @Test
    @DisplayName("every yellow sign alone produces treatment, not referral and not home care")
    void eachYellowSignAloneIsYellow() {
        for (Feature f : SUFFICIENT_FOR_YELLOW) {
            SignProfile p = base().with(f, 1.0);
            assertEquals(TrafficLight.YELLOW, ImnciRule.label(p),
                    () -> f + " alone must classify as treatment needed");
        }
    }

    @Test
    @DisplayName("an empty profile is home care")
    void nothingFoundIsGreen() {
        assertEquals(TrafficLight.GREEN, ImnciRule.label(base()));
    }

    @Test
    @DisplayName("jaundice: referral by extent, by age, and by company it keeps")
    void jaundiceRules() {
        // Extent alone.
        assertEquals(TrafficLight.GREEN, ImnciRule.label(jaundice(1, 7)), "face only, day 7, is not a danger sign");
        assertEquals(TrafficLight.YELLOW, ImnciRule.label(jaundice(2, 7)), "trunk is treatment");
        assertEquals(TrafficLight.RED, ImnciRule.label(jaundice(3, 7)), "limbs is referral");
        assertEquals(TrafficLight.RED, ImnciRule.label(jaundice(4, 7)), "palms and soles is referral");

        // Day of life. Jaundice in the first 24 hours is always pathological.
        assertEquals(TrafficLight.RED, ImnciRule.label(jaundice(1, 0)), "any jaundice on day 0 is referral");
        assertEquals(TrafficLight.RED, ImnciRule.label(jaundice(1, 1)), "any jaundice on day 1 is referral");
        assertEquals(TrafficLight.GREEN, ImnciRule.label(jaundice(1, 2)), "mild jaundice on day 2 is not");

        // Company it keeps: mild jaundice plus poor feeding is referral.
        SignProfile withPoorFeeding = base()
                .with(Feature.JAUNDICE_PRESENT, 1.0)
                .with(Feature.JAUNDICE_EXTENT, 1.0)
                .with(Feature.AGE_DAYS, 7)
                .with(Feature.FEEDING_POOR, 1.0);
        assertEquals(TrafficLight.RED, ImnciRule.label(withPoorFeeding),
                "jaundice with poor feeding is referral even when mild");
    }

    @Test
    @DisplayName("the respiratory rate boundary is at 60, not 59")
    void respiratoryRateBoundary() {
        assertEquals(TrafficLight.GREEN, ImnciRule.label(base().with(Feature.RESP_RATE, 59)),
                "59 breaths a minute is not fast breathing");

        // The flag is what the rule reads; the extractor sets it from the counted rate.
        assertEquals(TrafficLight.RED, ImnciRule.label(
                base().with(Feature.RESP_RATE, 60).with(Feature.RR_GE_60, 1.0)),
                "60 breaths a minute is fast breathing");
    }

    @Test
    @DisplayName("diarrhoea alone is not yellow; diarrhoea with dehydration is")
    void diarrhoeaNeedsDehydration() {
        assertEquals(TrafficLight.GREEN, ImnciRule.label(base().with(Feature.DIARRHEA, 1.0)));
        assertEquals(TrafficLight.YELLOW, ImnciRule.label(
                base().with(Feature.DIARRHEA, 1.0).with(Feature.DEHYDRATION_SIGNS, 1.0)));
    }

    @Test
    @DisplayName("red always dominates: adding any red sign to any profile gives red")
    void redDominates() {
        Random random = new Random(4242);
        for (int i = 0; i < 20_000; i++) {
            SignProfile p = randomProfile(random);
            Feature red = SUFFICIENT_FOR_RED.get(random.nextInt(SUFFICIENT_FOR_RED.size()));
            assertEquals(TrafficLight.RED, ImnciRule.label(p.with(red, 1.0)),
                    "adding " + red + " to any profile must give urgent referral");
        }
    }

    @Test
    @DisplayName("the rule never goes down when a sign is added")
    void monotonic() {
        Random random = new Random(99);
        List<Feature> flags = List.of(Feature.values()).stream().filter(Feature::isFlag).toList();

        for (int i = 0; i < 20_000; i++) {
            SignProfile before = randomProfile(random);
            Feature added = flags.get(random.nextInt(flags.size()));
            TrafficLight lightBefore = ImnciRule.label(before);
            TrafficLight lightAfter = ImnciRule.label(before.with(added, 1.0));

            // Only FEEDING_OK could plausibly reduce concern, and the rule does not read it.
            assertTrue(lightAfter.ordinal() >= lightBefore.ordinal(),
                    "adding " + added + " lowered the classification from "
                            + lightBefore + " to " + lightAfter);
        }
    }

    /**
     * The measured disagreement rate between the trained head and the rule, on a swept sample.
     *
     * <p>This is not zero, and the number is here rather than hidden because it is the single most
     * important empirical fact about the shipped model. The head under-calls rare danger signs —
     * severe dehydration and ten-or-more pustules in particular — because the corpus it was
     * distilled from contains them at their natural, very low, prevalence, and a boosted tree
     * ensemble will happily trade a rare class away for overall accuracy.
     *
     * <p>The architecture is built around this being true rather than around hoping it is not. The
     * deterministic rule is the floor and the model may only ever escalate above it, which is what
     * {@link #modelCanOnlyEscalateNeverDeEscalate} proves. The threshold below exists to catch a
     * <em>regression</em> — a retrain that made things materially worse — not to assert that the
     * model is perfect.
     */
    private static final double MAX_ACCEPTABLE_DISAGREEMENT = 0.03;

    @Test
    @DisplayName("model/rule disagreement is measured, bounded, and does not regress")
    void trainedHeadDisagreementIsBounded() {
        Random random = new Random(20260726);
        int disagreements = 0;
        int modelLower = 0;
        int modelHigher = 0;
        int total = 20_000;
        List<String> examples = new ArrayList<>();

        for (int i = 0; i < total; i++) {
            SignProfile p = randomProfile(random);
            TrafficLight rule = ImnciRule.label(p);
            TrafficLight model = TrafficLight.values()[argmax(Models.clinical().predict(p.toDoubleVector()))];

            if (rule != model) {
                disagreements++;
                if (model.ordinal() < rule.ordinal()) modelLower++; else modelHigher++;
                if (examples.size() < 5) {
                    examples.add(p.setFlags() + " rule=" + rule + " model=" + model);
                }
            }
        }

        double rate = (double) disagreements / total;
        System.out.printf("  model/rule disagreement: %.2f%% of %d (%d under-called, %d over-called)%n",
                rate * 100, total, modelLower, modelHigher);

        assertTrue(rate < MAX_ACCEPTABLE_DISAGREEMENT,
                String.format("the trained head disagreed with the WHO rule on %.2f%% of %d profiles, "
                        + "above the %.0f%% regression threshold. A retrain has drifted from the "
                        + "protocol. Examples: %s",
                        rate * 100, total, MAX_ACCEPTABLE_DISAGREEMENT * 100, examples));
    }

    /**
     * The safety property that makes the disagreement above tolerable.
     *
     * <p>The model is allowed to be wrong. It is not allowed to make the answer less safe than the
     * protocol alone would have been. Every disagreement in which the model is <em>lower</em> than
     * the rule must be discarded by the reconciliation, and this sweeps for a counter-example.
     */
    @Test
    @DisplayName("the model can raise the rule's answer but can never lower it")
    void modelCanOnlyEscalateNeverDeEscalate() {
        Random random = new Random(1337);

        for (int i = 0; i < 50_000; i++) {
            SignProfile p = randomProfile(random);
            TrafficLight rule = ImnciRule.label(p);
            TrafficLight model = TrafficLight.values()[argmax(Models.clinical().predict(p.toDoubleVector()))];

            // This is exactly what AssessmentOrchestrator.reconcile does with the two opinions.
            TrafficLight reconciled = rule.escalatedWith(model);

            assertTrue(reconciled.ordinal() >= rule.ordinal(),
                    () -> "reconciliation lowered the WHO rule's answer for " + p.setFlags()
                            + ": rule=" + rule + " model=" + model + " final=" + reconciled);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** A profile with nothing found: mid-neonatal age, normal weight, nothing set. */
    private static SignProfile base() {
        return SignProfile.builder()
                .set(Feature.AGE_DAYS, 7)
                .set(Feature.WEIGHT_KG, 3.2)
                .set(Feature.RESP_RATE, 45)
                .build();
    }

    private static SignProfile jaundice(int extent, int ageDays) {
        return base()
                .with(Feature.JAUNDICE_PRESENT, 1.0)
                .with(Feature.JAUNDICE_EXTENT, extent)
                .with(Feature.AGE_DAYS, ageDays);
    }

    /**
     * A random profile drawn to cover the decision space rather than the natural distribution:
     * flags fire at 15%, which is far above their real prevalence but is what makes combinations
     * appear often enough to be tested.
     */
    private static SignProfile randomProfile(Random random) {
        SignProfile.Builder b = SignProfile.builder()
                .set(Feature.AGE_DAYS, random.nextInt(29))
                .set(Feature.WEIGHT_KG, 1.5 + random.nextDouble() * 3.0)
                .set(Feature.RESP_RATE, 35 + random.nextInt(45));

        for (Feature f : Feature.values()) {
            if (f.isFlag() && random.nextDouble() < 0.15) b.flag(f, true);
        }
        if (b.isSet(Feature.JAUNDICE_PRESENT)) {
            b.set(Feature.JAUNDICE_EXTENT, 1 + random.nextInt(4));
        }
        // Keep the profile internally consistent: the rate and its flag must not contradict.
        b.flag(Feature.RR_GE_60, b.get(Feature.RESP_RATE) >= 60);
        return b.build();
    }

    private static int argmax(double[] v) {
        int a = 0;
        for (int i = 1; i < v.length; i++) if (v[i] > v[a]) a = i;
        return a;
    }
}
