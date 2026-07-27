package com.kangaroo.ml;

import com.kangaroo.core.Feature;
import com.kangaroo.util.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The shipped gradient-boosted heads, loaded lazily and verified on load.
 *
 * <p>Both models sit behind {@link LazyConstant} (JEP 526) for the same reason the WHO tables do:
 * they are together about 1.5 MB of text, and a device that only runs the deterministic path — or
 * only ever grades jaundice — should not pay to parse the other one. Because a lazy constant is
 * constant-folded after the first read, the {@code clinical()} call below compiles away to a direct
 * reference in the hot path rather than a repeated volatile read.
 *
 * <p>Loading is where the model file is checked against the code's assumptions: feature names in
 * order, feature count, class count, and the early-stopping iteration recorded alongside it. Every
 * one of those is a load-time failure, because each has the same failure mode in production — a
 * model that keeps answering, confidently, and wrongly.
 */
public final class Models {

    private Models() {}

    /** Traffic-light class order as the clinical head emits it. */
    public static final List<String> TRAFFIC_ORDER = List.of("GREEN", "YELLOW", "RED");

    /** Severity class order as the jaundice head emits it. */
    public static final List<String> SEVERITY_ORDER = List.of("normal", "mild", "moderate", "high", "severe");

    private static final java.lang.LazyConstant<GbmModel> CLINICAL =
            java.lang.LazyConstant.of(Models::loadClinical);

    private static final java.lang.LazyConstant<GbmModel> JAUNDICE =
            java.lang.LazyConstant.of(Models::loadJaundice);

    /**
     * The IMNCI danger-sign head: 32 structured features in, calibrated GREEN/YELLOW/RED out.
     * Sub-microsecond per encounter once loaded.
     */
    public static GbmModel clinical() {
        return CLINICAL.get();
    }

    /** The colorimetric jaundice head: 33 colour features in, five severity grades out. */
    public static GbmModel jaundice() {
        return JAUNDICE.get();
    }

    public static boolean clinicalLoaded() { return CLINICAL.isInitialized(); }

    public static boolean jaundiceLoaded() { return JAUNDICE.isInitialized(); }

    // ---------------------------------------------------------------- loading

    private static GbmModel loadClinical() {
        Meta meta = meta("/models/clinical_gbm_meta.json");
        GbmModel m = read("/models/clinical_gbm.txt", meta.bestIteration());

        // The enum in the core module is the single source of truth for feature order; this is
        // where the shipped model file is held to it.
        m.requireFeatureOrder(Feature.modelNames());

        if (m.numClass() != 3) {
            throw new IllegalStateException("clinical head must have 3 classes, has " + m.numClass());
        }
        if (m.numFeatures() != Feature.COUNT) {
            throw new IllegalStateException("clinical head expects " + Feature.COUNT
                    + " features, model has " + m.numFeatures());
        }
        return m;
    }

    private static GbmModel loadJaundice() {
        Meta meta = meta("/models/jaundice_gbm_meta.json");
        GbmModel m = read("/models/jaundice_gbm.txt", meta.bestIteration());

        if (m.numClass() != 5) {
            throw new IllegalStateException("jaundice head must have 5 classes, has " + m.numClass());
        }
        if (m.numFeatures() != com.kangaroo.color.ColourFeature.COUNT) {
            throw new IllegalStateException("jaundice head expects "
                    + com.kangaroo.color.ColourFeature.COUNT + " features, model has " + m.numFeatures());
        }
        m.requireFeatureOrder(com.kangaroo.color.ColourFeature.modelNames());
        return m;
    }

    private record Meta(int bestIteration, List<String> classes) {}

    private static Meta meta(String resource) {
        Json.Obj o = Json.parseObject(readText(resource));
        return new Meta(o.intAt("best_iteration", 0), List.of());
    }

    private static GbmModel read(String resource, int bestIteration) {
        try (InputStream in = Models.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing bundled model: " + resource);
            return GbmModel.load(in, bestIteration);
        } catch (IOException e) {
            throw new IllegalStateException("could not load model: " + resource, e);
        }
    }

    private static String readText(String resource) {
        try (InputStream in = Models.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing bundled resource: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not read: " + resource, e);
        }
    }
}
