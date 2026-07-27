package com.kangaroo.ml.features;

import com.kangaroo.core.Feature;
import com.kangaroo.core.SignProfile;
import com.kangaroo.core.Subject;
import com.kangaroo.core.Vitals;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns what a caregiver actually said into the structured danger-sign profile.
 *
 * <p>Intake is free text — typed, or transcribed from speech in the caregiver's own language and
 * then translated. It arrives as "she's been really sleepy since yesterday and won't take the
 * breast", not as a checklist. This class is what closes that gap, deterministically, with no model
 * involved: a set of term matchers with a negation window around each hit, so that "no jaundice",
 * "denies fever" and "the cord is not red" do not set the flags that "jaundice", "fever" and "the
 * cord is red" set.
 *
 * <p>Doing this deterministically rather than asking a model matters more than it looks. The
 * extraction feeds both the deterministic rule and the trained head, so if it were itself a model
 * output, the "independent cross-check" in the safety architecture would be checking a model
 * against itself. It also means the bottom rung of the inference ladder — no network, no model at
 * all — still has real structured evidence to reason over rather than nothing.
 *
 * <p>It is deliberately conservative in one direction: it prefers to miss a sign that was phrased
 * unusually over inventing one that was negated, because a missed sign is caught by the seven
 * guided captures and the twenty-one explicit checks, while an invented one silently poisons both
 * heads at once.
 */
public final class ClinicalFeatures {

    private ClinicalFeatures() {}

    /** How far either side of a term to look for a negation. */
    private static final int NEGATION_WINDOW = 32;

    private static final Pattern NEG_BEFORE = Pattern.compile(
            "\\b(no|not|without|denies?|negative for|absent|never|none)\\b[\\w\\s'\\-,]{0,30}$");

    private static final Pattern NEG_AFTER = Pattern.compile(
            "^[\\w\\s'\\-,]{0,30}\\b(normal|absent|not (present|yellow|red|swollen|affected)|"
                    + "are not|is not|are normal|is normal|unaffected|clear|fine|negative|denied)\\b");

    private static final Pattern AGE_DAYS = Pattern.compile("(\\d+)\\s*[- ]?day");
    private static final Pattern WEIGHT_KG = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*kg");
    private static final Pattern RR_UNITS = Pattern.compile(
            "(\\d{2,3})\\s*(?:breaths?\\s*per\\s*minute|/\\s*min|per\\s*minute|bpm)");
    private static final Pattern RR_LABELLED = Pattern.compile("respiratory rate[^\\d]{0,12}(\\d{2,3})");
    private static final Pattern TEMPERATURE = Pattern.compile("(\\d{2}(?:\\.\\d)?)\\s*(?:c|°c|degrees|celsius)");

    // "yellow pus" and "yellow discharge" are infection, not jaundice. This is the single most
    // consequential negative lookahead in the file.
    private static final Pattern YELLOW_SKIN = Pattern.compile(
            "yellow(?:ish)?(?!\\s+(?:pus|discharge|mucus|fluid|crust|stool|secretion))");

    private static final Pattern JAUNDICE_FACE_ONLY = Pattern.compile(
            "(face|head|eyes?)[\\w\\s/]{0,12}only|only[\\w\\s]{0,12}(face|head|eyes?)|"
                    + "limited to (the )?(face|head|eyes?)|confined to (the )?(face|head|eyes?)");

    private static final Pattern OMPHALITIS_SIZE = Pattern.compile("\\b([2-9]|[1-9]\\d)\\s*cm\\b");
    private static final Pattern OMPHALITIS_SMALL = Pattern.compile(
            "(less than|under|about|around)\\s*1\\s*cm|0\\.\\d+\\s*cm|1\\s*cm wide");
    private static final Pattern PUSTULE_COUNT = Pattern.compile("(\\d+)\\s*(?:small\\s*)?pustule");
    private static final Pattern MORE_THAN_PUSTULES = Pattern.compile("more than\\s*(\\d+)\\s*pustule");
    private static final Pattern PRETERM_WEEKS = Pattern.compile("\\b3[0-6]\\s*weeks?\\b");

    /**
     * Extract the profile from an encounter's intake text plus whatever was measured.
     *
     * <p>Measured values always win over text: a counted respiratory rate of 52 overrides
     * "breathing fast", because the counting is the thing the protocol asks for and the impression
     * is what it asks the counting to replace.
     */
    public static SignProfile extract(String intakeText, Subject subject, Vitals vitals) {
        String t = intakeText == null ? "" : intakeText.toLowerCase(Locale.ROOT);
        SignProfile.Builder b = SignProfile.builder();

        b.subject(subject);

        // ---- age, weight, respiratory rate: measured first, text only as a fallback
        int age = subject.ageKnown() ? subject.ageDays() : firstInt(AGE_DAYS, t, -1);
        if (age >= 0) b.set(Feature.AGE_DAYS, age);

        double weight = subject.weightKnown() ? subject.weightKg() : firstDouble(WEIGHT_KG, t, -1);
        if (weight > 0) {
            b.set(Feature.WEIGHT_KG, weight);
            b.raise(Feature.LOW_WEIGHT, weight < 2.5, "weight " + weight + " kg");
        }

        int rr = vitals.respiratoryRateOpt().orElse(-1);
        if (rr < 0) {
            rr = firstInt(RR_UNITS, t, -1);
            if (rr < 0) rr = firstInt(RR_LABELLED, t, -1);
        }
        if (rr > 0) {
            b.set(Feature.RESP_RATE, rr);
            b.raise(Feature.RR_GE_60, rr >= 60, "respiratory rate " + rr + "/min");
        }

        // ---- jaundice and its extent
        boolean yellowSkin = YELLOW_SKIN.matcher(t).find();
        boolean jaundice = (unnegated(t, "jaundice") || unnegated(t, "icteric")
                || unnegated(t, "scleral icterus") || unnegated(t, "icterus")
                || (yellowSkin && containsAny(t, "skin", "face", "eye", "body", "trunk", "chest", "sclera",
                        "palm", "sole", "limb", "arm", "leg", "head", "discolor", "tinge", "tint", "hue")))
                && !t.contains("no jaundice");

        b.flag(Feature.JAUNDICE_PRESENT, jaundice, jaundice ? quoteAround(t, "yellow", "jaundice") : null);

        if (jaundice) {
            int extent;
            if (JAUNDICE_FACE_ONLY.matcher(t).find()) {
                // An explicit "only on the face" caps the extent however the rest of the text reads.
                extent = 1;
            } else if (unnegatedAny(t, "palm", "sole", "entire body", "whole body", "all over",
                    "generaliz", "zone 5")) {
                extent = 4;
            } else if (unnegatedAny(t, "leg", "arm", "limb", "thigh", "zone 3", "zone 4")) {
                extent = 3;
            } else if (unnegatedAny(t, "trunk", "chest", "abdomen", "belly", "torso", "zone 2")) {
                extent = 2;
            } else {
                extent = 1;
            }
            b.set(Feature.JAUNDICE_EXTENT, extent);
        }

        // ---- respiratory
        b.flag(Feature.CHEST_INDRAWING,
                unnegatedAny(t, "indrawing", "retraction", "subcostal", "intercostal recess"),
                quoteAround(t, "indrawing", "retraction"));
        b.flag(Feature.GRUNTING_STRIDOR, unnegatedAny(t, "grunting", "stridor"),
                quoteAround(t, "grunting", "stridor"));
        b.flag(Feature.NASAL_FLARING, unnegatedAny(t, "nasal flaring", "flaring"),
                quoteAround(t, "flaring"));

        // ---- cry, colour
        // Both orderings. A health worker writes "weak cry"; a parent writes "the cry is weak" or
        // "she cries weakly". Matching only the adjective-first form misses the way most people
        // actually describe it, which is the sort of gap that makes a tool feel unreliable long
        // before anyone works out why.
        b.flag(Feature.WEAK_ABSENT_CRY,
                unnegatedAny(t, "weak cry", "absent cry", "no cry", "high-pitched cry", "high pitched cry",
                        "very weak cry", "feeble cry", "barely crie", "not crying",
                        "cry is weak", "cry was weak", "cry is feeble", "cry sounds weak",
                        "cries weakly", "crying weakly", "weak-sounding cry",
                        "cry is high pitched", "cry is high-pitched"),
                quoteAround(t, "cry"));

        boolean cyanosis = (unnegatedAny(t, "blue", "bluish", "cyanos")
                && containsAny(t, "lip", "tongue", "mouth", "central", "around the mouth"))
                || t.contains("central cyanosis");
        b.flag(Feature.CENTRAL_CYANOSIS, cyanosis, quoteAround(t, "cyanos", "blue"));

        b.flag(Feature.PALLOR, unnegatedAny(t, "pale", "pallor"), quoteAround(t, "pale", "pallor"));

        // ---- temperature: a measured value beats an impression, but either can set the flag
        boolean fever = false;
        boolean cold = false;
        Matcher tempMatcher = TEMPERATURE.matcher(t);
        while (tempMatcher.find()) {
            try {
                double c = Double.parseDouble(tempMatcher.group(1));
                if (c >= 38.0) fever = true;
                if (c < 35.5) cold = true;
            } catch (NumberFormatException ignored) {
                // A malformed number is simply not a temperature reading.
            }
        }
        fever = (fever || unnegatedAny(t, "fever", "febrile", "feels hot", "hot to touch"))
                && !t.contains("no fever");
        cold = cold || unnegatedAny(t, "cold to the touch", "cold to touch", "feels cold", "hypotherm",
                "low temperature", "temperature is low")
                || (unnegated(t, "cold") && containsAny(t, "hand", "feet", "skin", "mottled"))
                || (t.contains("mottled") && unnegated(t, "cold"));

        b.flag(Feature.FEVER, fever, quoteAround(t, "fever", "hot"));
        b.flag(Feature.MEASURED_COLD, cold, quoteAround(t, "cold", "hypotherm"));
        b.vitals(vitals);   // measured values raise the same flags and record the number

        // ---- umbilicus
        boolean cord = containsAny(t, "umbilic", "cord stump", "cord is", "umbilicus", "stump");
        boolean cordRed = unnegated(t, "redness") || unnegated(t, "red,") || unnegated(t, "red ")
                || unnegated(t, "is red") || unnegated(t, "reddened") || unnegated(t, "reddish");
        boolean omphalitis = cord && (cordRed || unnegatedAny(t, "discharge", "pus", "swollen",
                "swelling", "foul-smelling", "foul smelling", "purulent"));

        boolean omphalitisSevere = omphalitis
                && (t.contains("foul")
                    || containsAny(t, "onto the abdomen", "onto abdomen", "spreading onto", "periumbilical")
                    || OMPHALITIS_SIZE.matcher(t).find());
        // An explicitly small area of redness is local, not spreading, whatever else the text says.
        if (omphalitis && OMPHALITIS_SMALL.matcher(t).find()) {
            omphalitisSevere = false;
        }

        b.flag(Feature.OMPHALITIS, omphalitis, quoteAround(t, "cord", "umbilic"));
        b.flag(Feature.OMPHALITIS_SEVERE, omphalitisSevere, quoteAround(t, "spreading", "foul", "cm"));
        b.flag(Feature.UMBILICAL_BLEEDING,
                (cord && unnegatedAny(t, "bleeding", "bleed"))
                        || containsAny(t, "cord is bleeding", "umbilical bleeding",
                            "bleeding from the cord", "bleeding from cord", "umbilical cord is bleeding"),
                quoteAround(t, "bleed"));

        // ---- skin
        //
        // Negation-aware, not a plain substring test. "No pustules." contains the word "pustule",
        // and a health worker who writes down what they ruled out should not have it recorded as
        // what they found. The same applies to every finding below that a caregiver would plausibly
        // mention in order to deny it.
        boolean pustules = unnegatedAny(t, "pustule", "boils");
        boolean manyPustules = pustules && (countedAtLeast(PUSTULE_COUNT, t, 10)
                || containsAny(t, "many", "multiple", "numerous", "covering", "widespread"));
        Matcher moreThan = MORE_THAN_PUSTULES.matcher(t);
        if (pustules && moreThan.find()) {
            // "more than 12 pustules" is many; "more than 1" is not.
            manyPustules = Integer.parseInt(moreThan.group(1)) >= 10;
        }
        b.flag(Feature.PUSTULES, pustules, quoteAround(t, "pustule", "boil"));
        b.flag(Feature.PUSTULES_MANY, manyPustules, quoteAround(t, "pustule"));

        // ---- neurological
        b.flag(Feature.BULGING_FONTANELLE,
                unnegatedAny(t, "bulging fontanelle", "fontanelle appears tense", "tense and bulging",
                        "fontanelle is bulging")
                        || (t.contains("fontanelle") && unnegatedAny(t, "bulg", "tense")
                            && !t.contains("sunken fontanelle")),
                quoteAround(t, "fontanelle"));

        b.flag(Feature.LETHARGY,
                unnegatedAny(t, "lethargic", "lethargy", "no spontaneous movement", "almost no movement",
                        "no movement", "floppy", "very sleepy", "difficult to wake", "harder to wake",
                        "hard to wake", "only wakes briefly", "wakes briefly", "barely responsive",
                        "unconscious", "comatose", "drowsy", "unresponsive", "poor tone", "hypotonia",
                        "decreased activity", "less active than"),
                quoteAround(t, "sleepy", "lethargic", "floppy", "wake"));

        b.flag(Feature.CONVULSION,
                unnegatedAny(t, "convuls", "seizure", "twitching", "trembling", "went stiff", "stiffen",
                        "jerking", "spasm", "fits ", "fitting", "rolling eyes"),
                quoteAround(t, "convuls", "seizure", "fit", "twitch"));

        // ---- gastrointestinal
        boolean diarrhoea = unnegatedAny(t, "diarrh", "loose stool", "watery stool");
        boolean dehydrationSigns = diarrhoea && containsAny(t, "sunken", "dry mouth", "fewer wet",
                "less urine", "no urine", "reduced urine", "no wet diaper", "fewer diapers",
                "dehydrat", "drinks eagerly", "thirsty");
        boolean dehydrationSevere = dehydrationSigns && t.contains("sunken eye")
                && containsAny(t, "very sleepy", "lethargic", "unconscious", "no urine",
                        "almost no urine", "unable to drink");

        b.flag(Feature.DIARRHEA, diarrhoea, quoteAround(t, "diarrh", "stool"));
        b.flag(Feature.DEHYDRATION_SIGNS, dehydrationSigns, quoteAround(t, "sunken", "urine"));
        b.flag(Feature.DEHYDRATION_SEVERE, dehydrationSevere, quoteAround(t, "sunken eye"));

        // ---- feeding: the three states are mutually exclusive and ordered by severity
        boolean unable = containsAny(t, "cannot suck", "cannot breastfeed", "unable to feed",
                "not able to feed", "stopped feeding", "not feeding", "cannot feed", "refuses to feed",
                "not interested in feeding", "will not feed");
        boolean poor = !unable && containsAny(t, "poor feeding", "feeds poorly", "feeding poorly",
                "feeding less", "feeding has decreased", "feeding is reduced", "feeds reluctantly",
                "feeding is weaker", "weaker than yesterday", "feeding difficulty",
                "breastfeeding difficulty", "less than usual", "slightly reduced", "feeds less");
        boolean ok = !unable && !poor && containsAny(t, "feeds well", "feeding well", "breastfeeds well",
                "breastfeeds often", "breastfeeds 10", "feeds often");

        b.flag(Feature.FEEDING_UNABLE, unable, quoteAround(t, "feed"));
        b.flag(Feature.FEEDING_POOR, poor, quoteAround(t, "feed"));
        b.flag(Feature.FEEDING_OK, ok, null);

        // ---- eye
        b.flag(Feature.PURULENT_EYE,
                (unnegated(t, "pus") && t.contains("eye"))
                        || unnegatedAny(t, "purulent eye", "eye discharge", "ophthalmia",
                            "conjunctivitis", "pus from both eyes", "yellow pus"),
                quoteAround(t, "eye"));

        // ---- gestation
        b.raise(Feature.PRETERM,
                containsAny(t, "preterm", "premature") || PRETERM_WEEKS.matcher(t).find(),
                quoteAround(t, "preterm", "premature", "weeks"));

        return b.build();
    }

    // ---------------------------------------------------------------- negation-aware matching

    /**
     * Clause boundaries. A negation does not reach across one of these.
     *
     * <p>This matters more than it looks, and getting it wrong is a patient-safety defect rather
     * than a parsing nicety. Take a real intake sentence:
     *
     * <blockquote>"the baby has been very sleepy since last night and is not feeding at all"</blockquote>
     *
     * <p>A fixed thirty-two character window after "very sleepy" reaches "and is not", the
     * after-negation pattern matches "is not", and the lethargy is silently discarded — in a
     * sentence that reports two separate danger signs, one of them disappears because the
     * <em>other</em> one was phrased negatively. Cutting the window at "and" fixes it: "is not"
     * belongs to the feeding clause and has nothing to say about the sleepiness.
     */
    private static final Pattern CLAUSE_BREAK =
            Pattern.compile("\\s+(and|but|although|though|however|while|whereas)\\s+|[.;,]\\s+|\\n");

    /**
     * True when {@code term} appears at least once outside an obvious negation context.
     *
     * <p>The window is thirty-two characters either side, clipped at the nearest clause boundary.
     * Wider and "no fever, but the cord is red" starts suppressing the redness; narrower and
     * "the baby does not have any jaundice" stops suppressing the jaundice.
     */
    static boolean unnegated(String text, String term) {
        int from = 0;
        while (true) {
            int at = text.indexOf(term, from);
            if (at < 0) return false;

            String before = clipToLastClause(
                    text.substring(Math.max(0, at - NEGATION_WINDOW), at));

            int termEnd = Math.min(text.length(), at + term.length());
            String after = clipToFirstClause(
                    text.substring(termEnd, Math.min(text.length(), termEnd + NEGATION_WINDOW)));

            if (!NEG_BEFORE.matcher(before).find() && !NEG_AFTER.matcher(after).find()) {
                return true;
            }
            from = at + 1;
        }
    }

    /** Keep only the text after the last clause boundary — the clause the term is actually in. */
    private static String clipToLastClause(String before) {
        Matcher m = CLAUSE_BREAK.matcher(before);
        int start = 0;
        while (m.find()) {
            start = m.end();
        }
        return before.substring(start);
    }

    /** Keep only the text up to the first clause boundary. */
    private static String clipToFirstClause(String after) {
        Matcher m = CLAUSE_BREAK.matcher(after);
        return m.find() ? after.substring(0, m.start()) : after;
    }

    static boolean unnegatedAny(String text, String... terms) {
        for (String term : terms) {
            if (unnegated(text, term)) return true;
        }
        return false;
    }

    static boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- extraction helpers

    private static int firstInt(Pattern p, String text, int fallback) {
        Matcher m = p.matcher(text);
        if (!m.find()) return fallback;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double firstDouble(Pattern p, String text, double fallback) {
        Matcher m = p.matcher(text);
        if (!m.find()) return fallback;
        try {
            return Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean countedAtLeast(Pattern p, String text, int threshold) {
        Matcher m = p.matcher(text);
        while (m.find()) {
            try {
                if (Integer.parseInt(m.group(1)) >= threshold) return true;
            } catch (NumberFormatException ignored) {
                // Not a count.
            }
        }
        return false;
    }

    /**
     * The clause around the first matching term, for the audit trail.
     *
     * <p>A classification whose reasons cannot be traced back to something the caregiver actually
     * said is not auditable, and "the model thought so" is not a reason a supervisor can act on.
     */
    private static String quoteAround(String text, String... terms) {
        for (String term : terms) {
            int at = text.indexOf(term);
            if (at < 0) continue;
            int start = Math.max(0, at - 30);
            int end = Math.min(text.length(), at + term.length() + 30);
            String quote = text.substring(start, end).strip();
            return (start > 0 ? "..." : "") + quote + (end < text.length() ? "..." : "");
        }
        return null;
    }
}
