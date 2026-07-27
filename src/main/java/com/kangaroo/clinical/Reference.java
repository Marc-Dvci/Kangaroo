package com.kangaroo.clinical;

import com.kangaroo.core.Sex;
import com.kangaroo.util.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WHO reference data, loaded lazily and exactly once.
 *
 * <p>Every table here is held in a {@link LazyConstant} (JEP 526). That is not a micro-optimisation:
 * Kangaroo ships several megabytes of clinical reference data and two gradient-boosted models, and
 * the Pod and Desktop launchers publish a cold-start number that would be impossible if all of it
 * were parsed at class-initialisation time. A lazy constant is initialised at most once, is safe
 * under concurrent access with no locking in the steady state, and — unlike a plain
 * double-checked-locking field — is treated by the JIT as genuinely constant after the first read,
 * so the LMS lookup below constant-folds into the caller.
 *
 * <p>The practical effect: a device that only ever runs the deterministic path never pays to parse
 * the jaundice model, and a device that never weighs a baby never parses the LMS tables.
 */
public final class Reference {

    private Reference() {}

    /** One row of a WHO LMS growth table. */
    public record Lms(int ageDays, double l, double m, double s) {}

    /** A medication with its non-negotiable ceiling. */
    public record Medication(
            String key,
            String displayName,
            String indication,
            double perKgPerDose,
            int dosesPerDay,
            double concentrationPerMl,
            double maxSingleDose,
            int durationDays,
            String unit,
            String route,
            String instruction,
            String source,
            boolean weightBased) {

        /** Human-readable ceiling, e.g. "250 mg" or "200000 IU". */
        public String ceiling() {
            return weightBased ? formatAmount(maxSingleDose) + " " + unit : "not weight-based";
        }

        static String formatAmount(double v) {
            return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 10) / 10.0);
        }
    }

    private static final java.lang.LazyConstant<List<Lms>> MALE_LMS =
            java.lang.LazyConstant.of(() -> loadLms("who_zscore_male.json"));

    private static final java.lang.LazyConstant<List<Lms>> FEMALE_LMS =
            java.lang.LazyConstant.of(() -> loadLms("who_zscore_female.json"));

    private static final java.lang.LazyConstant<Map<String, Medication>> MEDICATIONS =
            java.lang.LazyConstant.of(Reference::loadMedications);

    /** The WHO 2006 weight-for-age LMS table for the given sex. Parsed on first use, never again. */
    public static List<Lms> lms(Sex sex) {
        return sex == Sex.MALE ? MALE_LMS.get() : FEMALE_LMS.get();
    }

    /** The medication table, keyed by the identifiers the tool API accepts. */
    public static Map<String, Medication> medications() {
        return MEDICATIONS.get();
    }

    public static Medication medication(String key) {
        Medication m = medications().get(key);
        if (m == null) {
            throw new IllegalArgumentException(
                    "unknown medication '" + key + "'; known: " + medications().keySet());
        }
        return m;
    }

    /** True once a table has actually been touched — used by the startup benchmark. */
    public static boolean lmsLoaded() {
        return MALE_LMS.isInitialized() || FEMALE_LMS.isInitialized();
    }

    // ---------------------------------------------------------------- loading

    private static List<Lms> loadLms(String resource) {
        Json.Arr rows = Json.parseArray(read("/data/" + resource));
        List<Lms> out = new ArrayList<>(rows.items().size());
        for (Json row : rows.items()) {
            Json.Obj o = row.asObj().orElseThrow(() -> new IllegalStateException("malformed LMS row in " + resource));
            out.add(new Lms(o.intAt("age_days", -1), o.num("L", 0), o.num("M", 0), o.num("S", 0)));
        }
        if (out.isEmpty()) throw new IllegalStateException("empty LMS table: " + resource);
        return List.copyOf(out);
    }

    private static Map<String, Medication> loadMedications() {
        Json.Obj root = Json.parseObject(read("/data/medication_doses.json"));
        Map<String, Medication> out = new LinkedHashMap<>();
        for (var e : root.fields().entrySet()) {
            String key = e.getKey();
            Json.Obj m = e.getValue().asObj()
                    .orElseThrow(() -> new IllegalStateException("malformed medication entry: " + key));

            // Penicillin is dosed in international units, everything else in milligrams. The unit
            // travels with the medication so no call site can mix them up.
            boolean iu = m.field("iu_per_kg_per_dose").isPresent();
            boolean weightBased = iu || m.field("mg_per_kg_per_dose").isPresent();
            String unit = iu ? "IU" : "mg";

            out.put(key, new Medication(
                    key,
                    m.str("display_name", key),
                    m.str("indication", ""),
                    iu ? m.num("iu_per_kg_per_dose", 0) : m.num("mg_per_kg_per_dose", 0),
                    m.intAt("doses_per_day", 1),
                    iu ? m.num("concentration_iu_per_ml", 0) : m.num("concentration_mg_per_ml", 0),
                    iu ? m.num("max_single_dose_iu", 0) : m.num("max_single_dose_mg", 0),
                    m.intAt("duration_days", 0),
                    unit,
                    m.str("route", ""),
                    m.str("instruction", ""),
                    m.str("source", ""),
                    weightBased));
        }
        return Map.copyOf(out);
    }

    private static String read(String resource) {
        try (InputStream in = Reference.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing bundled resource: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not read bundled resource: " + resource, e);
        }
    }
}
