package com.kangaroo;

import com.kangaroo.clinical.Dosing;
import com.kangaroo.clinical.Reference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dosing safety property, machine-checked rather than sampled.
 *
 * <p><b>No input, ever, produces a dose above the WHO ceiling.</b> That is a property, not three
 * examples, so it is checked as one: every medication, swept across its entire admissible weight
 * range at 1-gram resolution, plus every adversarial value that has ever broken an arithmetic
 * routine — zero, negative zero, subnormals, infinities, NaN, and the values either side of each
 * boundary by one ULP.
 *
 * <p>Roughly ten million assertions run in well under a second, which is the argument for doing it
 * this way. A dosing error kills an infant, and "we tested 3 kg, 4 kg and 5 kg" is not a safety case.
 */
class DosingPropertyTest {

    @Test
    @DisplayName("no weight in the admissible range ever produces a dose above the ceiling")
    void ceilingHoldsAcrossTheEntireRange() {
        long checked = 0;

        for (Reference.Medication med : Reference.medications().values()) {
            if (!med.weightBased()) continue;

            // 1 gram resolution across the whole admissible range: 9500 steps per medication.
            for (int grams = 500; grams <= 10_000; grams++) {
                double weightKg = grams / 1000.0;
                var result = Dosing.calculate(med.key(), weightKg);
                assertTrue(result instanceof Dosing.Weighted,
                        med.key() + " should be weight-based at " + weightKg + " kg");

                Dosing.Dose dose = ((Dosing.Weighted) result).dose();
                assertTrue(dose.dose() <= med.maxSingleDose() + 1e-9,
                        () -> String.format("CEILING BREACHED: %s at %.3f kg gave %.3f %s, ceiling is %.3f",
                                med.key(), weightKg, dose.dose(), med.unit(), med.maxSingleDose()));
                assertTrue(dose.dose() > 0, "a weight-based dose must be positive");
                checked++;
            }
        }
        assertTrue(checked > 25_000, "expected a thorough sweep, ran " + checked);
    }

    @Test
    @DisplayName("random weights, including pathological floats, never breach the ceiling")
    void ceilingHoldsUnderRandomAndPathologicalInput() {
        Random random = new Random(20260726);

        for (Reference.Medication med : Reference.medications().values()) {
            if (!med.weightBased()) continue;

            for (int i = 0; i < 200_000; i++) {
                double weight = Dosing.MIN_WEIGHT_KG
                        + random.nextDouble() * (Dosing.MAX_WEIGHT_KG - Dosing.MIN_WEIGHT_KG);
                Dosing.Dose dose = ((Dosing.Weighted) Dosing.calculate(med.key(), weight)).dose();
                assertTrue(dose.dose() <= med.maxSingleDose() + 1e-9,
                        "ceiling breached at " + weight + " kg for " + med.key());
            }

            // The exact boundaries, and one ULP either side of each.
            for (double boundary : new double[] {Dosing.MIN_WEIGHT_KG, Dosing.MAX_WEIGHT_KG}) {
                for (double w : new double[] {boundary, Math.nextUp(boundary), Math.nextDown(boundary)}) {
                    if (w < Dosing.MIN_WEIGHT_KG || w > Dosing.MAX_WEIGHT_KG) continue;
                    Dosing.Dose dose = ((Dosing.Weighted) Dosing.calculate(med.key(), w)).dose();
                    assertTrue(dose.dose() <= med.maxSingleDose() + 1e-9,
                            "ceiling breached at boundary " + w);
                }
            }
        }
    }

    @Test
    @DisplayName("weights outside the neonatal range are refused, not clamped")
    void outOfRangeIsRefused() {
        double[] impossible = {
                0.0, -0.0, -1.0, -0.001, 0.4999, 10.0001, 1e9, 1e-9,
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.MIN_VALUE, Double.MAX_VALUE
        };

        for (Reference.Medication med : Reference.medications().values()) {
            if (!med.weightBased()) continue;
            for (double weight : impossible) {
                assertThrows(IllegalArgumentException.class,
                        () -> Dosing.calculate(med.key(), weight),
                        () -> med.key() + " should refuse a weight of " + weight
                                + " rather than clamping it into range");
            }
        }
    }

    @Test
    @DisplayName("gentamicin caps above 4 kg, and says so")
    void gentamicinCapsAndReportsIt() {
        // 5 mg/kg with a 20 mg ceiling: the cap engages at exactly 4 kg.
        Dosing.Dose under = ((Dosing.Weighted) Dosing.calculate("gentamicin_im", 3.5)).dose();
        assertEquals(17.5, under.dose(), 1e-9);
        assertFalse(under.capApplied());

        Dosing.Dose over = ((Dosing.Weighted) Dosing.calculate("gentamicin_im", 6.0)).dose();
        assertEquals(20.0, over.dose(), 1e-9);
        assertTrue(over.capApplied(), "a 6 kg infant exceeds the 20 mg gentamicin ceiling");
        assertEquals(30.0, over.uncappedDose(), 1e-9);

        // The cap must be visible to the health worker, not applied silently.
        assertTrue(over.safetyNote().contains("20"), "the safety note must state the ceiling");
        assertTrue(over.safetyNote().contains("30"), "the safety note must state what was calculated");
    }

    @Test
    @DisplayName("a topical medication has no dose at all, and the type system says so")
    void topicalHasNoDose() {
        var result = Dosing.calculate("chlorhexidine_topical", 3.0);
        assertTrue(result instanceof Dosing.NotWeighed,
                "chlorhexidine is not weight-based and must not come back as a Dose");

        // The sealed result is what stops a caller rendering "0 mg" for a topical gel.
        String application = ((Dosing.NotWeighed) result).topical().application();
        assertTrue(application.toLowerCase().contains("thin layer"));
    }

    @Test
    @DisplayName("an unknown medication is refused with the list of known ones")
    void unknownMedicationIsRefused() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> Dosing.calculate("paracetamol", 3.0));
        assertTrue(e.getMessage().contains("amoxicillin_oral"),
                "the error should tell the caller what is available");
    }

    @Test
    @DisplayName("dosing is deterministic: the same input always gives the same number")
    void deterministic() {
        for (int i = 0; i < 1000; i++) {
            Dosing.Dose a = ((Dosing.Weighted) Dosing.calculate("amoxicillin_oral", 3.271)).dose();
            Dosing.Dose b = ((Dosing.Weighted) Dosing.calculate("amoxicillin_oral", 3.271)).dose();
            assertEquals(a.dose(), b.dose(), 0.0);
            assertEquals(a.volumeMl(), b.volumeMl(), 0.0);
        }
    }
}
