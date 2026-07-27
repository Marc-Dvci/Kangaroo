package com.kangaroo.core;

import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The canonical danger-sign feature space, in the exact order the clinical gradient-boosted model
 * was trained on.
 *
 * <p>This enum is the single source of truth for that ordering. {@link #ordinal()} <em>is</em> the
 * model's feature index, and {@code GbmModel} asserts at load time that the names embedded in the
 * model file match these names position for position. Reordering a constant here without retraining
 * is therefore a load-time failure rather than a silent accuracy collapse, which is the failure mode
 * this arrangement exists to prevent.
 */
public enum Feature {

    AGE_DAYS("age_days", Type.CONTINUOUS),
    WEIGHT_KG("weight_kg", Type.CONTINUOUS),
    SEX_FEMALE("sex_female", Type.FLAG),
    RESP_RATE("resp_rate", Type.CONTINUOUS),

    JAUNDICE_PRESENT("jaundice_present", Type.FLAG),
    /** 0 none, 1 face, 2 trunk, 3 limbs, 4 palms/soles — the Kramer progression. */
    JAUNDICE_EXTENT("jaundice_extent", Type.ORDINAL),

    CHEST_INDRAWING("chest_indrawing", Type.FLAG),
    GRUNTING_STRIDOR("grunting_stridor", Type.FLAG),
    NASAL_FLARING("nasal_flaring", Type.FLAG),
    RR_GE_60("rr_ge_60", Type.FLAG),

    WEAK_ABSENT_CRY("weak_absent_cry", Type.FLAG),
    CENTRAL_CYANOSIS("central_cyanosis", Type.FLAG),
    PALLOR("pallor", Type.FLAG),

    FEVER("fever", Type.FLAG),
    MEASURED_COLD("measured_cold", Type.FLAG),

    OMPHALITIS("omphalitis", Type.FLAG),
    OMPHALITIS_SEVERE("omphalitis_severe", Type.FLAG),
    UMBILICAL_BLEEDING("umbilical_bleeding", Type.FLAG),

    PUSTULES("pustules", Type.FLAG),
    PUSTULES_MANY("pustules_many", Type.FLAG),

    BULGING_FONTANELLE("bulging_fontanelle", Type.FLAG),

    DIARRHEA("diarrhea", Type.FLAG),
    DEHYDRATION_SIGNS("dehydration_signs", Type.FLAG),
    DEHYDRATION_SEVERE("dehydration_severe", Type.FLAG),

    FEEDING_UNABLE("feeding_unable", Type.FLAG),
    FEEDING_POOR("feeding_poor", Type.FLAG),
    FEEDING_OK("feeding_ok", Type.FLAG),

    LETHARGY("lethargy", Type.FLAG),
    CONVULSION("convulsion", Type.FLAG),

    PRETERM("preterm", Type.FLAG),
    LOW_WEIGHT("low_weight", Type.FLAG),

    PURULENT_EYE("purulent_eye", Type.FLAG);

    /** How a feature is encoded, which decides how the UI renders it and how the profile validates it. */
    public enum Type { FLAG, ORDINAL, CONTINUOUS }

    public static final int COUNT = values().length;

    private static final Map<String, Feature> BY_NAME =
            Stream.of(values()).collect(Collectors.toUnmodifiableMap(Feature::modelName, Function.identity()));

    private final String modelName;
    private final Type type;

    Feature(String modelName, Type type) {
        this.modelName = modelName;
        this.type = type;
    }

    /** The name as it appears in the model file's {@code feature_names=} line. */
    public String modelName() { return modelName; }

    public Type type() { return type; }

    public boolean isFlag() { return type == Type.FLAG; }

    public static Feature byModelName(String name) {
        Feature f = BY_NAME.get(name.toLowerCase(Locale.ROOT));
        if (f == null) throw new IllegalArgumentException("unknown feature: " + name);
        return f;
    }

    /** The full ordered name list, used to verify a loaded model against this enum. */
    public static String[] modelNames() {
        return Stream.of(values()).map(Feature::modelName).toArray(String[]::new);
    }
}
