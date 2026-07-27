package com.kangaroo.clinical;

import com.kangaroo.core.DangerSign;
import com.kangaroo.core.Sex;
import com.kangaroo.core.Subject;
import com.kangaroo.core.TrafficLight;
import com.kangaroo.util.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The five deterministic WHO tools, as a callable registry.
 *
 * <p>One registry, three consumers: the HTTP API, the Model Context Protocol server (so any external
 * agent can call the same hard-capped clinical skills), and the language model's own tool-calling
 * loop. That matters more than it looks. If the tools were reachable only through the model's
 * prompt, an integrator could bypass them; because they are a first-class registry with schemas,
 * the model is one caller among several and has no privileged path to the arithmetic.
 *
 * <p><b>The model decides which tool. The tool decides the number. Always.</b>
 */
public final class ClinicalTools {

    private ClinicalTools() {}

    /**
     * A callable clinical tool. Sealed, so the registry below is provably complete and the MCP
     * server cannot advertise a tool that has no implementation.
     */
    public sealed interface Tool {
        String name();
        String description();
        /** JSON Schema for the arguments, in the shape both OpenAI-style tool calling and MCP expect. */
        Json.Obj parameterSchema();
        /** Execute. Argument validation failures throw; they are never answered with a guess. */
        Json.Obj invoke(Json.Obj args);

        record ZScoreTool() implements Tool {
            @Override public String name() { return "calculate_zscore"; }

            @Override public String description() {
                return "Calculate weight-for-age z-score using the WHO 2006 growth standards. "
                        + "Use whenever weight, age and sex are known. Do not compute this yourself.";
            }

            @Override public Json.Obj parameterSchema() {
                return schema(Map.of(
                        "weight_kg", prop("number", "Weight in kilograms"),
                        "age_days", prop("integer", "Age in days, 0-28"),
                        "sex", enumProp("Infant sex", List.of("male", "female"))),
                        List.of("weight_kg", "age_days", "sex"));
            }

            @Override public Json.Obj invoke(Json.Obj a) {
                var r = ZScore.calculate(
                        require(a, "weight_kg"),
                        (int) require(a, "age_days"),
                        Sex.parse(a.str("sex", "male")));
                return Json.obj()
                        .put("zscore", r.z())
                        .put("classification", r.band().name().toLowerCase(java.util.Locale.ROOT))
                        .put("band_label", r.band().label())
                        .put("percentile", r.percentile())
                        .put("who_median_kg", r.medianKg())
                        .put("weight_kg", r.weightKg())
                        .put("age_days", r.ageDays())
                        .put("concerning", r.concerning())
                        .put("plain_language", r.plainLanguage())
                        .build();
            }
        }

        record DoseTool() implements Tool {
            @Override public String name() { return "calculate_medication_dose"; }

            @Override public String description() {
                return "Calculate a neonatal medication dose with the WHO safety ceiling applied. "
                        + "ALWAYS use this tool. Never state a dose you calculated yourself.";
            }

            @Override public Json.Obj parameterSchema() {
                return schema(Map.of(
                        "medication", enumProp("Medication identifier",
                                List.copyOf(Reference.medications().keySet())),
                        "weight_kg", prop("number", "Weight in kilograms, 0.5-10")),
                        List.of("medication", "weight_kg"));
            }

            @Override public Json.Obj invoke(Json.Obj a) {
                String key = a.str("medication", "");
                var result = Dosing.calculate(key, a.num("weight_kg", -1));
                // Exhaustive: a topical medication has no dose, and the switch makes that explicit
                // rather than returning a null amount that a caller might render as "0 mg".
                return switch (result) {
                    case Dosing.Weighted w -> {
                        var d = w.dose();
                        yield Json.obj()
                                .put("medication", d.medication())
                                .put("dose", d.dose())
                                .put("unit", d.unit())
                                .put("volume_ml", d.volumeMl())
                                .put("doses_per_day", d.dosesPerDay())
                                .put("duration_days", d.durationDays())
                                .put("route", d.route())
                                .put("frequency", d.frequency())
                                .put("instruction", d.instruction())
                                .put("prescription", d.prescription())
                                .put("safety_cap_applied", d.capApplied())
                                .put("uncapped_dose", d.uncappedDose())
                                .put("ceiling", d.ceiling())
                                .put("safety_note", d.safetyNote())
                                .put("source", d.source())
                                .build();
                    }
                    case Dosing.NotWeighed n -> {
                        var t = n.topical();
                        yield Json.obj()
                                .put("medication", t.medication())
                                .put("weight_based", false)
                                .put("application", t.application())
                                .put("frequency", t.frequency())
                                .put("route", t.route())
                                .put("instruction", t.instruction())
                                .put("source", t.source())
                                .build();
                    }
                };
            }
        }

        record OrsTool() implements Tool {
            @Override public String name() { return "calculate_ors_volume"; }

            @Override public String description() {
                return "Calculate oral rehydration solution volume and mixing instructions from "
                        + "weight and WHO dehydration severity.";
            }

            @Override public Json.Obj parameterSchema() {
                return schema(Map.of(
                        "weight_kg", prop("number", "Weight in kilograms"),
                        "dehydration_severity", enumProp("WHO dehydration classification",
                                List.of("none", "some", "severe"))),
                        List.of("weight_kg", "dehydration_severity"));
            }

            @Override public Json.Obj invoke(Json.Obj a) {
                var p = Ors.calculate(require(a, "weight_kg"),
                        Ors.Dehydration.parse(a.str("dehydration_severity", "none")));
                return Json.obj()
                        .put("plan", p.plan())
                        .put("dehydration", p.dehydration().label())
                        .put("recommendation", p.recommendation())
                        .put("volume_ml", p.volumeMl())
                        .put("volume_ml_per_hour", p.volumePerHour())
                        .put("window", p.window())
                        .put("mixing", p.mixing())
                        .putStrings("instructions", p.instructions())
                        .put("refer_urgently", p.referUrgently())
                        .build();
            }
        }

        record ReferralTool() implements Tool {
            @Override public String name() { return "generate_referral_letter"; }

            @Override public String description() {
                return "Produce a structured, printable referral letter for hospital transfer. "
                        + "Use for any urgent referral classification.";
            }

            @Override public Json.Obj parameterSchema() {
                return schema(Map.of(
                        "classification", prop("string", "The IMNCI classification"),
                        "findings", arrayProp("Clinical findings, one per entry"),
                        "pre_referral_treatment", arrayProp("Treatment already given"),
                        "age_days", prop("integer", "Infant age in days"),
                        "weight_kg", prop("number", "Infant weight in kilograms"),
                        "sex", enumProp("Infant sex", List.of("male", "female"))),
                        List.of("classification", "findings"));
            }

            @Override public Json.Obj invoke(Json.Obj a) {
                // Both are declared required and both are enforced. A referral letter that reaches
                // a receiving clinician with no classification and no findings is worse than none:
                // it costs the family the journey and tells the facility nothing.
                String classification = requireString(a, "classification");
                if (a.array("findings").isEmpty()) {
                    throw new IllegalArgumentException(
                            "a referral letter needs at least one finding; the receiving facility "
                                    + "has to know what was seen");
                }

                int age = a.intAt("age_days", -1);
                double weight = a.num("weight_kg", -1);
                Subject subject = new Subject(age, weight <= 0 ? Subject.UNKNOWN_WEIGHT : weight,
                        Sex.parse(a.str("sex", "male")), false);

                List<DangerSign> signs = a.array("findings").stream()
                        .flatMap(j -> j.asString().stream())
                        .map(ClinicalTools::signFromLabel)
                        .flatMap(java.util.Optional::stream)
                        .map(s -> (DangerSign) new DangerSign.Reported(s))
                        .toList();

                List<String> given = a.array("pre_referral_treatment").stream()
                        .flatMap(j -> j.asString().stream())
                        .toList();

                var letter = Referral.generate(
                        "REF-" + Long.toHexString(System.currentTimeMillis()).toUpperCase(java.util.Locale.ROOT),
                        TrafficLight.RED, classification, subject, signs, given);

                return Json.obj()
                        .put("reference", letter.reference())
                        .put("urgency", letter.urgency())
                        .put("classification", letter.classification())
                        .putStrings("findings", letter.findings())
                        .putStrings("measured", letter.measured())
                        .putStrings("treatment_given", letter.given())
                        .put("disclaimer", letter.disclaimer())
                        .put("printable", letter.render())
                        .build();
            }
        }

        record FollowUpTool() implements Tool {
            @Override public String name() { return "suggest_followup_date"; }

            @Override public String description() {
                return "Suggest the next visit date from the classification severity, on the WHO "
                        + "postnatal schedule.";
            }

            @Override public Json.Obj parameterSchema() {
                return schema(Map.of(
                        "severity", enumProp("Severity level",
                                List.of("urgent_referral", "treatment", "home_care"))),
                        List.of("severity"));
            }

            @Override public Json.Obj invoke(Json.Obj a) {
                // Declared required, so enforced required. Defaulting a missing severity would mean
                // defaulting to the *least* urgent follow-up interval, which is precisely the wrong
                // direction to guess in: a caller that forgot to say "urgent referral" would get a
                // routine seven-day visit back and no indication that anything was missing.
                String severity = requireString(a, "severity");
                TrafficLight light = switch (severity) {
                    case "urgent_referral" -> TrafficLight.RED;
                    case "treatment" -> TrafficLight.YELLOW;
                    case "home_care" -> TrafficLight.GREEN;
                    default -> throw new IllegalArgumentException(
                            "severity must be one of urgent_referral, treatment, home_care; got '"
                                    + severity + "'");
                };
                var v = FollowUp.suggest(light);
                return Json.obj()
                        .put("followup_date", v.date().toString())
                        .put("in_days", v.inDays())
                        .put("visit_type", v.visitType())
                        .put("urgency", v.urgency())
                        .put("mandatory", v.mandatory())
                        .putStrings("instructions", v.instructions())
                        .build();
            }
        }
    }

    private static final Map<String, Tool> REGISTRY = registry();

    private static Map<String, Tool> registry() {
        Map<String, Tool> m = new LinkedHashMap<>();
        for (Tool t : List.of(new Tool.ZScoreTool(), new Tool.DoseTool(), new Tool.OrsTool(),
                new Tool.ReferralTool(), new Tool.FollowUpTool())) {
            m.put(t.name(), t);
        }
        return Map.copyOf(m);
    }

    public static Map<String, Tool> all() {
        return REGISTRY;
    }

    public static Tool get(String name) {
        Tool t = REGISTRY.get(name);
        if (t == null) throw new IllegalArgumentException("unknown tool '" + name + "'; known: " + REGISTRY.keySet());
        return t;
    }

    /**
     * Execute a tool by name. A failure is returned as a structured error rather than thrown,
     * because the caller is usually a model, and a model handed a stack trace will hallucinate
     * around it. A model handed {@code {"error": "weight 14 kg is outside the safe neonatal
     * dosing range"}} will say so.
     */
    public static Json.Obj invoke(String name, Json.Obj args) {
        try {
            return get(name).invoke(args);
        } catch (RuntimeException e) {
            return Json.obj()
                    .put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                    .put("tool", name)
                    .build();
        }
    }

    /** The tool definitions, in the OpenAI function-calling shape most model servers accept. */
    public static Json.Arr definitions() {
        return Json.arr(REGISTRY.values().stream()
                .map(t -> (Json) Json.obj()
                        .put("type", "function")
                        .put("function", Json.obj()
                                .put("name", t.name())
                                .put("description", t.description())
                                .put("parameters", t.parameterSchema())
                                .build())
                        .build())
                .toList());
    }

    // ---------------------------------------------------------------- schema helpers

    private static Json.Obj schema(Map<String, Json> properties, List<String> required) {
        var props = Json.obj();
        // Sorted so the emitted schema is byte-stable across runs, which keeps prompt caching warm.
        properties.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> props.put(e.getKey(), e.getValue()));
        return Json.obj()
                .put("type", "object")
                .put("properties", props.build())
                .putStrings("required", required)
                .build();
    }

    private static Json prop(String type, String description) {
        return Json.obj().put("type", type).put("description", description).build();
    }

    private static Json enumProp(String description, List<String> values) {
        return Json.obj()
                .put("type", "string")
                .put("description", description)
                .putStrings("enum", values)
                .build();
    }

    private static Json arrayProp(String description) {
        return Json.obj()
                .put("type", "array")
                .put("description", description)
                .put("items", Json.obj().put("type", "string").build())
                .build();
    }

    private static double require(Json.Obj a, String key) {
        return a.field(key).flatMap(Json::asDouble)
                .orElseThrow(() -> new IllegalArgumentException("missing required numeric argument: " + key));
    }

    private static String requireString(Json.Obj a, String key) {
        return a.field(key).flatMap(Json::asString)
                .filter(s -> !s.isBlank())
                .orElseThrow(() -> new IllegalArgumentException("missing required argument: " + key));
    }

    /** Best-effort mapping from a free-text finding back onto a known danger sign. */
    private static java.util.Optional<DangerSign.Sign> signFromLabel(String text) {
        String t = text.toLowerCase(java.util.Locale.ROOT);
        for (DangerSign.Sign s : DangerSign.Sign.values()) {
            if (t.contains(s.label().toLowerCase(java.util.Locale.ROOT))
                    || s.label().toLowerCase(java.util.Locale.ROOT).contains(t)) {
                return java.util.Optional.of(s);
            }
        }
        return java.util.Optional.empty();
    }
}
