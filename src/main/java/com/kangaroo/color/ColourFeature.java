package com.kangaroo.color;

import java.util.stream.Stream;

/**
 * The colorimetric feature space for jaundice grading, in the exact order the model was trained on.
 *
 * <p>Same contract as the clinical {@code Feature} enum: {@link #ordinal()} is the model's feature
 * index and the loader holds the shipped model file to these names, position for position.
 *
 * <p>The features are the ones smartphone transcutaneous-bilirubinometry pipelines use. Bilirubin
 * shifts skin along the blue-yellow axis, so the informative quantities are the ones that isolate
 * that axis after the illuminant has been divided out: {@code lab_b}, the red-minus-blue and
 * yellow-index differences, and the channel ratios. The white-balance gains are kept as features in
 * their own right because the illuminant the photo was taken under is itself evidence about how far
 * to trust the rest.
 */
public enum ColourFeature {

    // Illuminant estimate: the gains actually applied, and how much reference white was found.
    WB_GAIN_R("wb_gain_r"),
    WB_GAIN_G("wb_gain_g"),
    WB_GAIN_B("wb_gain_b"),
    WB_N_WHITE_FRAC("wb_n_white_frac"),

    // How confident the skin crop is.
    SKIN_PIXEL_FRAC("skin_pixel_frac"),

    // White-balanced skin channel summaries.
    SKIN_R_MEAN("skin_r_mean"),
    SKIN_G_MEAN("skin_g_mean"),
    SKIN_B_MEAN("skin_b_mean"),
    SKIN_R_MED("skin_r_med"),
    SKIN_G_MED("skin_g_med"),
    SKIN_B_MED("skin_b_med"),
    SKIN_R_P10("skin_r_p10"),
    SKIN_R_P90("skin_r_p90"),
    SKIN_B_P10("skin_b_p10"),
    SKIN_B_P90("skin_b_p90"),
    SKIN_R_STD("skin_r_std"),
    SKIN_G_STD("skin_g_std"),
    SKIN_B_STD("skin_b_std"),

    // Colour-opponent yellowness.
    RB_DIFF("rb_diff"),
    GB_DIFF("gb_diff"),
    RG_DIFF("rg_diff"),
    YELLOW_IDX("yellow_idx"),
    R_OVER_B("r_over_b"),
    G_OVER_B("g_over_b"),
    RG_OVER_B("rg_over_b"),

    // CIE-Lab of the mean skin colour. b* is the blue-yellow axis and does most of the work.
    LAB_L("lab_L"),
    LAB_A("lab_a"),
    LAB_B("lab_b"),

    // HSV of the mean skin colour.
    HSV_H("hsv_h"),
    HSV_S("hsv_s"),
    HSV_V("hsv_v"),

    // Chroma spread across the skin window.
    CHROMA_MEAN("chroma_mean"),
    CHROMA_STD("chroma_std");

    public static final int COUNT = values().length;

    private final String modelName;

    ColourFeature(String modelName) { this.modelName = modelName; }

    public String modelName() { return modelName; }

    public static String[] modelNames() {
        return Stream.of(values()).map(ColourFeature::modelName).toArray(String[]::new);
    }
}
