package com.kangaroo.core;

import java.util.Locale;

/** Required for weight-for-age z-scores: the WHO LMS tables are sex-specific. */
public enum Sex {
    MALE, FEMALE;

    public static Sex parse(String s) {
        if (s == null || s.isBlank()) return MALE;
        return s.trim().toLowerCase(Locale.ROOT).startsWith("f") ? FEMALE : MALE;
    }

    public String tableName() {
        return this == MALE ? "who_zscore_male.json" : "who_zscore_female.json";
    }
}
