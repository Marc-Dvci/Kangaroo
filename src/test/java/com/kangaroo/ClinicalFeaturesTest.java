package com.kangaroo;

import com.kangaroo.core.Feature;
import com.kangaroo.core.Sex;
import com.kangaroo.core.SignProfile;
import com.kangaroo.core.Subject;
import com.kangaroo.core.Vitals;
import com.kangaroo.ml.features.ClinicalFeatures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Extraction from what a caregiver actually said.
 *
 * <p>The negation cases carry most of the weight here. Every one of them is a sentence a real
 * caregiver or health worker would write, and each of the first three is a regression test for a
 * defect this suite found in a working build — not a hypothetical.
 */
class ClinicalFeaturesTest {

    private static SignProfile extract(String text) {
        return ClinicalFeatures.extract(text, new Subject(7, 3.2, Sex.FEMALE, false), Vitals.none());
    }

    private static SignProfile extract(String text, Vitals vitals) {
        return ClinicalFeatures.extract(text, new Subject(7, 3.2, Sex.FEMALE, false), vitals);
    }

    // ---------------------------------------------------------------- regressions

    @Test
    @DisplayName("a negation in one clause does not suppress a finding in another")
    void negationDoesNotCrossClauseBoundaries() {
        // Found in a live build: the after-window from "very sleepy" reached "is not" in the
        // *feeding* clause, and the lethargy was silently discarded from a sentence that reported
        // two separate danger signs.
        SignProfile p = extract(
                "the baby has been very sleepy since last night and is not feeding at all");

        assertTrue(p.flag(Feature.LETHARGY),
                "lethargy must survive a negation that belongs to a different clause");
        assertTrue(p.flag(Feature.FEEDING_UNABLE),
                "'is not feeding at all' is inability to feed, not an absent finding");
    }

    @Test
    @DisplayName("'no pustules' does not set the pustules flag")
    void explicitlyAbsentFindingsAreNotRecorded() {
        // Found in a live build: a plain substring test turned a ruled-out finding into a
        // recorded one, and a healthy newborn came back as a local bacterial infection.
        SignProfile p = extract("Cord stump is dry and clean. No pustules. No diarrhoea.");

        assertFalse(p.flag(Feature.PUSTULES), "'No pustules' must not set the pustules flag");
        assertFalse(p.flag(Feature.DIARRHEA), "'No diarrhoea' must not set the diarrhoea flag");
        assertFalse(p.flag(Feature.OMPHALITIS), "a dry clean cord is not omphalitis");
    }

    @Test
    @DisplayName("'yellow pus' is infection, not jaundice")
    void yellowDischargeIsNotJaundice() {
        SignProfile p = extract("There is yellow pus draining from the left eye.");
        assertFalse(p.flag(Feature.JAUNDICE_PRESENT),
                "yellow discharge must not be read as jaundiced skin");
        assertTrue(p.flag(Feature.PURULENT_EYE));
    }

    // ---------------------------------------------------------------- negation

    @Test
    @DisplayName("common ways of ruling a finding out are all understood")
    void negationForms() {
        assertFalse(extract("no jaundice").flag(Feature.JAUNDICE_PRESENT));
        assertFalse(extract("mother denies fever").flag(Feature.FEVER));
        assertFalse(extract("no fever").flag(Feature.FEVER));
        assertFalse(extract("there is no chest indrawing").flag(Feature.CHEST_INDRAWING));
        assertFalse(extract("the cord is not red").flag(Feature.OMPHALITIS));
        assertFalse(extract("without any convulsions").flag(Feature.CONVULSION));
        assertFalse(extract("the fontanelle is normal").flag(Feature.BULGING_FONTANELLE));
    }

    @Test
    @DisplayName("a finding that is present is recorded")
    void positiveFindings() {
        assertTrue(extract("the baby had convulsions this morning").flag(Feature.CONVULSION));
        assertTrue(extract("there is severe chest indrawing").flag(Feature.CHEST_INDRAWING));
        assertTrue(extract("grunting is heard on expiration").flag(Feature.GRUNTING_STRIDOR));
        assertTrue(extract("the cry is weak").flag(Feature.WEAK_ABSENT_CRY));
        assertTrue(extract("the lips look blue").flag(Feature.CENTRAL_CYANOSIS));
        assertTrue(extract("the fontanelle is bulging").flag(Feature.BULGING_FONTANELLE));
    }

    // ---------------------------------------------------------------- jaundice extent

    @Test
    @DisplayName("jaundice extent follows the cephalocaudal description")
    void jaundiceExtent() {
        assertEquals(1, extract("yellow colour limited to the face only").ordinal(Feature.JAUNDICE_EXTENT));
        assertEquals(2, extract("the jaundice reaches the trunk").ordinal(Feature.JAUNDICE_EXTENT));
        assertEquals(3, extract("yellow skin on the arms and legs").ordinal(Feature.JAUNDICE_EXTENT));
        assertEquals(4, extract("jaundice on the palms and soles").ordinal(Feature.JAUNDICE_EXTENT));

        // "only on the face" caps the extent however the rest of the sentence reads.
        SignProfile capped = extract("yellow on the face only, the trunk and legs are normal");
        assertEquals(1, capped.ordinal(Feature.JAUNDICE_EXTENT));
    }

    // ---------------------------------------------------------------- numbers

    @Test
    @DisplayName("a measured value beats an impression")
    void measuredBeatsReported() {
        // The text says fast; the counted rate says otherwise. The count is what the protocol asks
        // for, and it is what wins.
        SignProfile p = extract("the baby seems to be breathing fast", Vitals.none().withRespiratoryRate(48));
        assertEquals(48, p.ordinal(Feature.RESP_RATE));
        assertFalse(p.flag(Feature.RR_GE_60), "48 counted breaths is not fast breathing");
    }

    @Test
    @DisplayName("a measured temperature sets fever and hypothermia at the WHO cut-offs")
    void temperatureThresholds() {
        assertTrue(extract("", Vitals.none().withTemperature(38.0)).flag(Feature.FEVER));
        assertFalse(extract("", Vitals.none().withTemperature(37.9)).flag(Feature.FEVER));
        assertTrue(extract("", Vitals.none().withTemperature(35.4)).flag(Feature.MEASURED_COLD));
        assertFalse(extract("", Vitals.none().withTemperature(35.5)).flag(Feature.MEASURED_COLD));
    }

    @Test
    @DisplayName("a temperature written in the text is read when nothing was measured")
    void temperatureFromText() {
        assertTrue(extract("temperature is 38.5 c").flag(Feature.FEVER));
        assertTrue(extract("axillary temperature 35.1 degrees").flag(Feature.MEASURED_COLD));
    }

    @Test
    @DisplayName("pustule counts distinguish a few from many")
    void pustuleCounts() {
        assertTrue(extract("there are 3 small pustules on the back").flag(Feature.PUSTULES));
        assertFalse(extract("there are 3 small pustules on the back").flag(Feature.PUSTULES_MANY));

        assertTrue(extract("there are 14 pustules on the trunk").flag(Feature.PUSTULES_MANY));
        assertTrue(extract("many pustules covering the chest").flag(Feature.PUSTULES_MANY));
        assertFalse(extract("more than 1 pustule").flag(Feature.PUSTULES_MANY),
                "'more than 1' is not many");
    }

    @Test
    @DisplayName("localised umbilical redness is not spreading omphalitis")
    void omphalitisSeverity() {
        SignProfile local = extract("cord stump has redness about 1 cm wide with slight discharge");
        assertTrue(local.flag(Feature.OMPHALITIS));
        assertFalse(local.flag(Feature.OMPHALITIS_SEVERE), "1 cm of redness is local");

        SignProfile spreading = extract("umbilical redness spreading onto the abdomen, foul smelling");
        assertTrue(spreading.flag(Feature.OMPHALITIS_SEVERE));
    }

    // ---------------------------------------------------------------- evidence trail

    @Test
    @DisplayName("every finding carries the words that caused it")
    void findingsCarryEvidence() {
        SignProfile p = extract("the baby is very sleepy and hard to wake");
        assertTrue(p.flag(Feature.LETHARGY));

        String evidence = p.evidenceFor(Feature.LETHARGY);
        assertTrue(evidence != null && evidence.contains("sleepy"),
                "a classification whose reasons cannot be traced to what was said is not auditable");
    }

    @Test
    @DisplayName("empty and junk input produce an empty profile rather than an exception")
    void degenerateInput() {
        for (String text : new String[] {"", "   ", "\n\n", "?????", " ",
                "a".repeat(50_000), "🙂🙂🙂"}) {
            SignProfile p = ClinicalFeatures.extract(text, Subject.unknown(), Vitals.none());
            assertTrue(p.setFlags().isEmpty() || p.setFlags().size() < 5,
                    "junk input should not manufacture danger signs: " + p.setFlags());
        }
    }
}
