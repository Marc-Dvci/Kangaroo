package com.kangaroo.infer;

import com.kangaroo.core.Capture;
import com.kangaroo.core.DangerSign;
import com.kangaroo.core.Encounter;
import com.kangaroo.core.Feature;
import com.kangaroo.core.Mode;
import com.kangaroo.core.SignProfile;
import com.kangaroo.core.TrafficLight;
import com.kangaroo.i18n.Messages;
import com.kangaroo.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The prompts, and the grammar that makes the model's output parseable by construction.
 *
 * <h2>Calibration</h2>
 * The single most consequential thing in this file is the instruction not to escalate on suspicion.
 * A model asked to help with a sick newborn, given no calibration, refers essentially everything —
 * which sounds safe and is not: a tool that says "go to the clinic" to every caregiver is a tool
 * that is ignored within a week, at which point it protects nobody. The block in
 * {@link #CALIBRATION} is the counterweight, and it is expressed as explicit boundaries the model
 * can check itself against rather than as an exhortation to be careful.
 *
 * <h2>Grammar-constrained decoding</h2>
 * {@link #ASSESSMENT_GRAMMAR} is applied at the sampler, not checked afterwards. The model is
 * physically unable to emit a token that would break the schema, so parse failure stops being a
 * failure mode rather than becoming a rarer one. Everything downstream can therefore treat the
 * model's structured output as well-formed without a defensive re-parse.
 */
public final class Prompts {

    private Prompts() {}

    /**
     * The danger-sign calibration block.
     *
     * <p>Every threshold in here is a WHO IMNCI boundary rather than a judgement call, so the model
     * is being told where the lines are, not asked to guess where they should be.
     */
    public static final String CALIBRATION = """
            CALIBRATION - read before classifying.

            Your job is to explain and to advise. The traffic light is decided by a deterministic
            WHO rule engine that has already run; your classification is recorded as a second
            opinion and any disagreement is escalated to a human supervisor. Do not try to guess
            what the rule engine said - report what you actually observe.

            Do not escalate on suspicion. These are the WHO IMNCI boundaries for an infant 0-59 days:

            URGENT REFERRAL requires at least one of:
              convulsions; not feeding at all; lethargic, floppy or unrousable; severe chest
              indrawing; grunting or stridor; respiratory rate 60 or more counted over a full
              minute; weak, absent or high-pitched cry; blue lips or tongue; marked pallor;
              temperature 38.0 C or above, or below 35.5 C; umbilical redness spreading onto the
              abdominal skin; bleeding from the cord; ten or more skin pustules; bulging fontanelle;
              severe dehydration; jaundice reaching the forearms, legs, palms or soles; jaundice on
              the first day of life; jaundice together with poor feeding.

            TREATMENT NEEDED, not referral, covers:
              localised umbilical redness or discharge that is not spreading; fewer than ten skin
              pustules; pus draining from the eye; jaundice reaching the trunk but no further;
              diarrhoea with some dehydration; feeding less than usual with no other danger sign.

            HOME CARE is correct when none of the above is present. These do NOT on their own
            justify referral: mild jaundice limited to the face; a respiratory rate between 40 and
            59; occasional posseting or spitting up; a single small pustule; a dry cord stump about
            to separate; a sleepy but rousable baby who feeds well; hiccups; sneezing; startling;
            a red mark or stork bite on the skin.

            Absence matters as much as presence. If the caregiver says a sign is not there, treat it
            as not there. Do not infer a danger sign from the fact that a caregiver is worried.
            """;

    /** The system prompt for the CHW front door. */
    public static String chwSystem(Locale locale) {
        return """
            You are assisting a community health worker carrying out a WHO IMNCI young-infant
            assessment on a newborn aged 0-28 days, offline, far from a clinic.

            %s

            Be brief and concrete. The health worker is standing in someone's home with a baby in
            their arms. Write what they should do, in order, in short sentences.

            Never state a medication dose, a volume, or a weight-for-age figure. Those are computed
            by the deterministic tools and will be shown alongside your text. If a dose is needed,
            say which medication is indicated and stop there.

            Write the action plan in %s.
            """.formatted(CALIBRATION, Messages.languageName(locale));
    }

    /** The system prompt for the parent front door. */
    public static String parentSystem(Locale locale) {
        return """
            You are speaking to a parent of a newborn baby, at home, possibly in the middle of the
            night, possibly frightened. They are not a clinician and they have no equipment.

            %s

            Rules for how you speak:
              - Short sentences. No medical jargon. No abbreviations.
              - Never say the baby is fine. Say what you can and cannot tell from a photograph, and
                say what would mean going to a clinic.
              - Never state a dose, a volume or a measurement.
              - Do not diagnose. Describe what you see and what it would mean.
              - Acknowledge that they are worried before you explain anything.

            Write in %s.
            """.formatted(CALIBRATION, Messages.languageName(locale));
    }

    /**
     * A GBNF grammar that forces the model's structured verdict to be well-formed JSON with exactly
     * the fields we read, and with the classification drawn from a closed set.
     *
     * <p>This is why the assessment path has no "the model returned something unparseable" branch.
     * The constraint is applied at sampling time, so a malformed response is not rare — it is
     * unreachable.
     */
    public static final String ASSESSMENT_GRAMMAR = """
            root        ::= "{" ws "\\"classification\\"" ws ":" ws class ws ","
                            ws "\\"observations\\"" ws ":" ws obslist ws ","
                            ws "\\"reasoning\\"" ws ":" ws string ws ","
                            ws "\\"action_plan\\"" ws ":" ws string ws "}"
            class       ::= "\\"URGENT_REFERRAL\\"" | "\\"TREATMENT_NEEDED\\"" | "\\"HOME_CARE\\""
            obslist     ::= "[" ws (string (ws "," ws string)*)? ws "]"
            string      ::= "\\"" char* "\\""
            char        ::= [^"\\\\] | "\\\\" ["\\\\/bfnrt]
            ws          ::= [ \\t\\n]*
            """;

    /**
     * The user turn: everything known about the encounter, laid out so the model does not have to
     * infer structure from prose.
     */
    public static String assessmentRequest(Encounter encounter, SignProfile profile,
                                           TrafficLight ruleLight, List<DangerSign> signs) {
        StringBuilder sb = new StringBuilder();

        sb.append("INFANT\n");
        sb.append("  Age: ").append(encounter.subject().ageKnown()
                ? encounter.subject().ageDays() + " days" : "not known").append('\n');
        sb.append("  Weight: ").append(encounter.subject().weightKnown()
                ? encounter.subject().weightKg() + " kg" : "not weighed").append('\n');
        sb.append("  Sex: ").append(encounter.subject().sex().name().toLowerCase(Locale.ROOT)).append('\n');
        if (encounter.subject().preterm()) sb.append("  Born preterm\n");

        sb.append("\nMEASURED\n");
        boolean anyMeasured = false;
        if (encounter.vitals().respiratoryRateOpt().isPresent()) {
            sb.append("  Respiratory rate: ")
              .append(encounter.vitals().respiratoryRate()).append(" per minute (counted)\n");
            anyMeasured = true;
        }
        if (encounter.vitals().temperatureOpt().isPresent()) {
            sb.append("  Temperature: ").append(encounter.vitals().temperatureC()).append(" C\n");
            anyMeasured = true;
        }
        if (encounter.vitals().spo2Opt().isPresent()) {
            sb.append("  Oxygen saturation: ").append(encounter.vitals().spo2()).append(" %\n");
            anyMeasured = true;
        }
        if (!anyMeasured) sb.append("  Nothing was measured.\n");

        sb.append("\nWHAT THE CAREGIVER SAID\n  ");
        sb.append(encounter.intakeText().isBlank() ? "(nothing recorded)" : encounter.intakeText());
        sb.append('\n');

        if (!signs.isEmpty()) {
            sb.append("\nSTRUCTURED FINDINGS (extracted deterministically, not by you)\n");
            for (DangerSign s : signs) {
                sb.append("  - ").append(s.sign().label()).append(" [").append(s.provenance()).append("]\n");
            }
        }

        List<String> absent = absentSigns(profile);
        if (!absent.isEmpty()) {
            sb.append("\nEXPLICITLY ABSENT\n  ").append(String.join(", ", absent)).append('\n');
        }

        if (!encounter.images().isEmpty()) {
            sb.append("\nIMAGES ATTACHED, in order\n");
            for (Capture c : encounter.images()) {
                sb.append("  - ").append(describeCapture(c.kind())).append('\n');
            }
        }

        sb.append("""

                Reply with a single JSON object and nothing else:
                  classification : one of URGENT_REFERRAL, TREATMENT_NEEDED, HOME_CARE
                  observations   : what you can actually see or infer, as short strings
                  reasoning      : two or three sentences of clinical reasoning
                  action_plan    : what the person in front of this baby should do, in order
                """);
        return sb.toString();
    }

    private static List<String> absentSigns(SignProfile profile) {
        List<String> out = new ArrayList<>();
        // Telling the model what was ruled out is what stops it re-raising a sign the extraction
        // already found negated. Only the high-value ones, to keep the prompt short.
        for (Feature f : List.of(Feature.FEVER, Feature.CONVULSION, Feature.LETHARGY,
                Feature.CHEST_INDRAWING, Feature.JAUNDICE_PRESENT, Feature.FEEDING_UNABLE)) {
            if (!profile.flag(f)) out.add(f.modelName().replace('_', ' '));
        }
        return out;
    }

    private static String describeCapture(Capture.Kind kind) {
        return switch (kind) {
            case FACE -> "face and eyes - look for yellow in the whites of the eyes, pallor, blue around the mouth";
            case CHEST -> "chest and abdomen - look for indrawing between or below the ribs, and skin colour";
            case UMBILICUS -> "umbilical stump - look for redness, discharge, swelling, or bleeding";
            case SKIN -> "skin - count any pustules";
            case PALMS_SOLES -> "palms and soles - look for yellow, which indicates advanced jaundice";
            case FONTANELLE -> "soft spot on the head - look for bulging or tension";
            case COLOUR_CARD -> "the printed colour reference card, for colour correction";
            case CRY -> "cry recording";
            case VOICE_INTAKE -> "voice intake recording";
        };
    }

    /** Parse the model's structured verdict. Grammar-constrained output makes this total. */
    public static Optional<Parsed> parse(String modelOutput) {
        String text = modelOutput == null ? "" : modelOutput.strip();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return Optional.empty();

        try {
            Json.Obj o = Json.parseObject(text.substring(start, end + 1));
            TrafficLight light = switch (o.str("classification", "")) {
                case "URGENT_REFERRAL" -> TrafficLight.RED;
                case "TREATMENT_NEEDED" -> TrafficLight.YELLOW;
                case "HOME_CARE" -> TrafficLight.GREEN;
                default -> null;
            };
            List<String> observations = o.array("observations").stream()
                    .flatMap(j -> j.asString().stream())
                    .toList();
            return Optional.of(new Parsed(Optional.ofNullable(light),
                    o.str("reasoning", ""), o.str("action_plan", ""), observations));
        } catch (Json.JsonException e) {
            return Optional.empty();
        }
    }

    public record Parsed(Optional<TrafficLight> light, String reasoning, String actionPlan,
                         List<String> observations) {}

    /** Choose the system prompt for the front door in use. */
    public static String systemFor(Mode mode, Locale locale) {
        return switch (mode) {
            case PARENT -> parentSystem(locale);
            case CHW -> chwSystem(locale);
        };
    }
}
