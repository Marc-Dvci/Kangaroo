package com.kangaroo.infer;

import com.kangaroo.core.Capture;
import com.kangaroo.core.Rung;
import com.kangaroo.ffm.NativeRuntime;
import com.kangaroo.ffm.llama.Llama;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The on-device rung: the model runs in this process, through the Foreign Function and Memory API.
 *
 * <p>This is the rung the whole product is designed around. It works with the network cable pulled
 * out and airplane mode on, it never sends an infant's photograph anywhere, and because it is
 * in-process it can do two things a model server cannot: hand a captured frame to the vision
 * projector by address with no copy, and constrain the sampler with a grammar so the structured
 * output is well-formed by construction rather than by hope.
 *
 * <p>The model is loaded lazily on first use and held for the life of the engine. Loading a
 * multi-gigabyte GGUF takes seconds; doing it per encounter would make the on-device rung slower
 * than the cloud, which would defeat the point.
 */
public final class NativeEngine implements InferenceEngine {

    private static final System.Logger LOG = System.getLogger("kangaroo.infer.native");

    private final Path modelPath;
    private final Optional<Path> projectorPath;
    private final Llama.Options options;

    private volatile Llama llama;
    private volatile String failureReason;

    public NativeEngine(Path modelPath, Optional<Path> projectorPath, Llama.Options options) {
        this.modelPath = modelPath;
        this.projectorPath = projectorPath;
        this.options = options.withGrammar(Prompts.ASSESSMENT_GRAMMAR);
    }

    /**
     * Build from configuration, if a model is configured and present.
     *
     * @param modelPath     path to a GGUF model, or null
     * @param projectorPath path to a multimodal projector, or null
     */
    public static Optional<NativeEngine> ifConfigured(Path modelPath, Path projectorPath) {
        if (modelPath == null || !Files.isRegularFile(modelPath)) return Optional.empty();
        if (!NativeRuntime.available()) return Optional.empty();

        Optional<Path> projector = projectorPath != null && Files.isRegularFile(projectorPath)
                ? Optional.of(projectorPath)
                : Optional.empty();

        return Optional.of(new NativeEngine(modelPath, projector, Llama.Options.clinicalDefaults()));
    }

    @Override
    public Rung rung() {
        return Rung.NATIVE;
    }

    @Override
    public boolean available() {
        return failureReason == null
                && NativeRuntime.available()
                && Files.isRegularFile(modelPath);
    }

    @Override
    public String describe() {
        Llama l = llama;
        if (l != null) {
            return "On-device " + l.describe() + (l.visionEnabled() ? " with vision" : "");
        }
        return "On-device " + modelPath.getFileName() + " (not loaded yet)";
    }

    @Override
    public Narrative explain(Request request) {
        Llama l = ensureLoaded();
        long t0 = System.nanoTime();

        String system = Prompts.systemFor(request.encounter().mode(), request.locale());
        String user = Prompts.assessmentRequest(request.encounter(), request.profile(),
                request.ruleLight(), request.signs());

        List<byte[]> images = request.encounter().images().stream()
                .map(Capture::bytes)
                .toList();

        String raw = l.visionEnabled() && !images.isEmpty()
                ? l.chatWithImages(system, user, images, null)
                : l.chat(system, user, null);

        long elapsed = (System.nanoTime() - t0) / 1_000_000;
        return toNarrative(raw, elapsed);
    }

    Narrative toNarrative(String raw, long elapsedMs) {
        return Prompts.parse(raw)
                .map(p -> new Narrative(p.light(), p.reasoning(), p.actionPlan(), p.observations(),
                        Rung.NATIVE, raw, elapsedMs))
                // Grammar-constrained sampling makes this branch unreachable in practice, but an
                // unparseable reply must degrade to "no opinion", never to a guessed traffic light.
                .orElseGet(() -> new Narrative(Optional.empty(), raw, "", List.of(),
                        Rung.NATIVE, raw, elapsedMs));
    }

    /**
     * Load on first use, once, and remember a failure so the ladder stops retrying a model that is
     * not going to load.
     */
    private Llama ensureLoaded() {
        Llama existing = llama;
        if (existing != null) return existing;

        synchronized (this) {
            if (llama != null) return llama;
            try {
                long t0 = System.nanoTime();
                llama = Llama.open(modelPath, projectorPath, options);
                LOG.log(System.Logger.Level.INFO, () -> "loaded " + modelPath.getFileName()
                        + " in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
                return llama;
            } catch (RuntimeException e) {
                failureReason = e.getMessage();
                throw e;
            }
        }
    }

    /** Why this rung is unavailable, for the diagnostics screen. */
    public String unavailableReason() {
        if (failureReason != null) return failureReason;
        if (!NativeRuntime.available()) return NativeRuntime.unavailableReason();
        if (!Files.isRegularFile(modelPath)) return "No model file at " + modelPath;
        return "";
    }

    @Override
    public void close() {
        Llama l = llama;
        llama = null;
        if (l != null) l.close();
    }
}
