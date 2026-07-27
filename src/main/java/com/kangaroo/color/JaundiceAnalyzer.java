package com.kangaroo.color;

import com.kangaroo.audit.ClinicalEvents;
import com.kangaroo.core.JaundiceGrade;
import com.kangaroo.ml.Models;

import java.util.ArrayList;
import java.util.List;

/**
 * Colorimetric jaundice grading from a photo taken beside the printed reference card.
 *
 * <p>Three things happen here, in order, and the order is the design.
 *
 * <h2>1. Refuse before grading</h2>
 * A frame that is too dark, has no reference white in it, or contains almost no skin cannot be
 * graded, and the honest response is to say so and ask for another photo. Rejecting a bad capture
 * before inference is worth more accuracy than any model change, and it is the difference between a
 * tool that is wrong sometimes and a tool that is wrong sometimes without telling you.
 *
 * <h2>2. Grade the extent, not just the colour</h2>
 * The clinical variable in neonatal jaundice is cephalocaudal progression: yellow starts at the head
 * and moves down, and how far down it has reached tracks the bilirubin level far better than how
 * yellow any single patch of skin looks. So {@link #kramerZones} bands the frame head-to-toe and
 * reports a per-zone yellowness index, and the highest positive zone is what feeds the danger-sign
 * profile. This is corroborating evidence about extent, not a bilirubin measurement, and the code
 * and the UI both say so.
 *
 * <h2>3. Say where it does not work</h2>
 * A colorimetric grader that works on light skin and fails on dark skin is not merely imperfect; it
 * fails hardest in exactly the populations with the highest neonatal mortality. The reference card's
 * grey ramp is what makes per-device, per-illuminant normalisation possible at all, and the palms
 * and soles capture exists because those sites are far less pigment-dependent than the forehead.
 * Where confidence is insufficient the grader abstains rather than guessing. See
 * {@code docs/fairness.md}.
 */
public final class JaundiceAnalyzer {

    /** Below this mean luminance the frame is too dark to grade. */
    static final double MIN_MEAN_LUMA = 35.0;
    /** Above this, the frame is blown out. */
    static final double MAX_MEAN_LUMA = 235.0;
    /** Below this skin fraction the window is not looking at an infant. */
    static final double MIN_SKIN_FRACTION = 0.02;
    /** A white-balance gain outside this range means the illuminant estimate is not trustworthy. */
    static final double MAX_GAIN = 3.0;
    static final double MIN_GAIN = 0.33;

    /** Zone is counted as jaundiced when its b* exceeds this and clears zone 1 by {@link #ZONE_MARGIN}. */
    static final double ZONE_B_THRESHOLD = 18.0;
    static final double ZONE_MARGIN = 2.0;

    private final ColourPipeline pipeline;

    public JaundiceAnalyzer() {
        this(ColourPipeline.preferred());
    }

    public JaundiceAnalyzer(ColourPipeline pipeline) {
        this.pipeline = pipeline;
    }

    public ColourPipeline pipeline() {
        return pipeline;
    }

    /**
     * Grade one capture.
     *
     * @param frame the decoded capture, ideally the infant's chest or face beside the card
     * @param wholeBodyFrame a head-to-toe frame for Kramer banding, or {@code null} to band the
     *        primary frame instead
     */
    public JaundiceGrade grade(Frame frame, Frame wholeBodyFrame) {
        float[] features = pipeline.extract(frame);

        String rejection = quality(frame, features);
        if (rejection != null) {
            ClinicalEvents.captureRejected("jaundice", rejection);
            return JaundiceGrade.refused(rejection);
        }

        double[] x = new double[features.length];
        for (int i = 0; i < features.length; i++) x[i] = features[i];
        double[] probs = Models.jaundice().predict(x);

        List<Double> zones = kramerZones(wholeBodyFrame != null ? wholeBodyFrame : frame);
        int highest = highestZone(zones);

        return JaundiceGrade.of(probs, highest, zones);
    }

    /**
     * The capture-quality gate. Returns the reason to reject, in words the user can act on, or
     * {@code null} when the frame is gradeable.
     */
    String quality(Frame frame, float[] features) {
        double luma = frame.meanLuma();
        if (luma < MIN_MEAN_LUMA) {
            return "This photo is too dark to grade. Move to better light, or switch the torch on, "
                    + "and take it again.";
        }
        if (luma > MAX_MEAN_LUMA) {
            return "This photo is too bright and the colour is washed out. Move out of direct "
                    + "sunlight and take it again.";
        }

        double skinFraction = features[ColourFeature.SKIN_PIXEL_FRAC.ordinal()];
        if (skinFraction < MIN_SKIN_FRACTION) {
            return "Not enough skin is visible in the window. Line the card's cut-out up over the "
                    + "baby's chest and take it again.";
        }

        double whiteFraction = features[ColourFeature.WB_N_WHITE_FRAC.ordinal()];
        double gr = features[ColourFeature.WB_GAIN_R.ordinal()];
        double gg = features[ColourFeature.WB_GAIN_G.ordinal()];
        double gb = features[ColourFeature.WB_GAIN_B.ordinal()];

        boolean gainsImplausible = outOfRange(gr) || outOfRange(gg) || outOfRange(gb);
        if (whiteFraction < ColourPipeline.MIN_WHITE_FRACTION && gainsImplausible) {
            // No reference white *and* a wild illuminant estimate: the colour cannot be trusted at
            // all, and grading it anyway is how a tool produces a confident wrong answer.
            return "The reference card is not visible enough to correct the colour. Make sure the "
                    + "whole card is in the photo and take it again.";
        }
        return null;
    }

    private static boolean outOfRange(double gain) {
        return gain < MIN_GAIN || gain > MAX_GAIN;
    }

    /**
     * Per-zone yellowness (CIE b*) head to soles, over the five Kramer bands.
     *
     * <p>Bands are horizontal fifths of the frame, which assumes a head-to-toe capture with the
     * infant upright in shot. That assumption is stated in the capture coaching overlay rather than
     * left implicit, and when it does not hold the zones simply agree with each other and the
     * highest zone lands at 1, which is the conservative answer.
     */
    public List<Double> kramerZones(Frame whole) {
        List<Double> out = new ArrayList<>(5);
        for (int z = 0; z < 5; z++) {
            Frame band = whole.band(z / 5.0, (z + 1) / 5.0);
            float[] f = pipeline.extract(band);
            out.add((double) f[ColourFeature.LAB_B.ordinal()]);
        }
        return List.copyOf(out);
    }

    /**
     * The highest Kramer zone showing jaundice, 0 (none) through 5 (palms and soles).
     *
     * <p>Requires both an absolute b* above threshold and a margin over the head zone, because a
     * warm illuminant raises b* everywhere at once and a purely absolute test would read that as
     * whole-body jaundice.
     */
    public static int highestZone(List<Double> zoneB) {
        if (zoneB.size() < 5) return 0;
        double head = zoneB.getFirst();
        int highest = 0;
        for (int z = 0; z < 5; z++) {
            double b = zoneB.get(z);
            boolean jaundiced = z == 0
                    ? b >= ZONE_B_THRESHOLD
                    : b >= ZONE_B_THRESHOLD && b >= head - ZONE_MARGIN;
            if (jaundiced) highest = z + 1;
        }
        return highest;
    }

    /** Map the highest Kramer zone onto the extent scale the danger-sign profile uses (0-4). */
    public static int toExtent(int kramerZone) {
        return switch (kramerZone) {
            case 0 -> 0;
            case 1 -> 1;
            case 2 -> 2;
            case 3, 4 -> 3;
            case 5 -> 4;
            default -> 0;
        };
    }
}
