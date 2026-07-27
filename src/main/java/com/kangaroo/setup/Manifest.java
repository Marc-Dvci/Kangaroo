package com.kangaroo.setup;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What {@code --setup} downloads, pinned.
 *
 * <p>Kangaroo runs with none of this: the deterministic WHO engine and the gradient-boosted head
 * need nothing but the JDK, and they are what decides the traffic light. These artifacts add the
 * optional top of the inference ladder — an on-device language model that writes better prose, and
 * a vision projector that lets it read the captured photographs directly.
 *
 * <h2>Why the binaries are fetched rather than vendored</h2>
 * They are large, platform-specific, and someone else's build. Committing a copy into a clinical
 * repository turns every future reader into someone trusting a binary blob that a stranger put in a
 * git history. Fetching them at an explicit, pinned, hash-checked version is the same convenience
 * with an audit trail.
 *
 * <h2>Updating a pin</h2>
 * The digests below are the SHA-256 that Hugging Face reports for the file at that revision; it is
 * exposed as {@code X-Linked-ETag} on a {@code HEAD} of the resolve URL, so a pin can be re-verified
 * in a second without downloading five gigabytes.
 */
public final class Manifest {

    private Manifest() {}

    /** The llama.cpp build these FFM bindings target. {@code Llama} verifies struct layouts at startup. */
    public static final String LLAMA_BUILD = "b9006";

    private static final String HF = "https://huggingface.co/unsloth/gemma-4-E4B-it-GGUF/resolve/";
    private static final String GH = "https://github.com/ggml-org/llama.cpp/releases/download/" + LLAMA_BUILD + "/";

    /** Where the on-device rung looks for weights, matching the README. */
    public static final Path MODEL_DIR = Path.of("runtime", "models");
    /** Where {@link com.kangaroo.ffm.NativeRuntime} looks for the shared libraries. */
    public static final Path NATIVE_DIR = Path.of("runtime", "bin");

    /**
     * The language model: Gemma 4 E4B instruction-tuned, quantised to IQ4_XS.
     *
     * <p>IQ4_XS is the point of the choice. It fits in about 4 GB, which means it loads into system
     * RAM on an 8 GB laptop with no GPU at all — the hardware a field clinic actually has, rather
     * than the hardware a benchmark is run on.
     */
    public static Artifact model() {
        return new Artifact(
                "Gemma 4 E4B (IQ4_XS)",
                URI.create(HF + "c9ef2c8ea20c9f870ca3e4085aebec77251ff4aa/gemma-4-E4B-it-IQ4_XS.gguf"),
                MODEL_DIR.resolve("gemma-4-E4B-it-IQ4_XS.gguf"),
                "c932975bbc2f16ac87bae60078d3c7190871a3e8468566166103e0924981f183",
                4_715_414_208L,
                Artifact.Kind.FILE);
    }

    /**
     * The multimodal projector, which is what makes the captured photographs legible to the model
     * rather than merely stored alongside the encounter.
     */
    public static Artifact projector() {
        return new Artifact(
                "Vision projector (mmproj BF16)",
                URI.create(HF + "51a9adf7d1add66b19832d04647cb647381f9294/mmproj-BF16.gguf"),
                MODEL_DIR.resolve("mmproj-BF16.gguf"),
                "6d521435bed84c9aade3685f4bc3bce5898dec2b1f1d17f7452ebfaeedc375fb",
                991_552_448L,
                Artifact.Kind.FILE);
    }

    /**
     * The llama.cpp shared libraries for the machine this is running on.
     *
     * <p>CPU builds only, deliberately. An accelerated build is a larger download that fails on a
     * machine without the matching driver stack, and the whole point of the deployment target is
     * that it has neither. Anyone with a GPU already knows how to drop a different build into
     * {@code runtime/bin}, and {@link com.kangaroo.ffm.NativeRuntime} will pick it up unchanged.
     *
     * @return empty when no published CPU build matches this platform, which is not an error --
     *         the deterministic rung still works and setup says so
     */
    public static Optional<Artifact> nativeRuntime() {
        return nativeRuntime(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    /** Visible for testing: the platform decision, without asking the JVM what it is running on. */
    static Optional<Artifact> nativeRuntime(String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = osArch.toLowerCase(Locale.ROOT);
        boolean arm = arch.contains("aarch64") || arch.contains("arm64");

        record Build(String asset, String sha256, long size, Artifact.Kind kind) {}

        Build build;
        if (os.contains("win")) {
            build = arm
                    ? new Build("llama-" + LLAMA_BUILD + "-bin-win-cpu-arm64.zip",
                            "e5b206a7ba4643c86c585e72261a908e79cbdac1a7813e9fab804bf706462821",
                            9_638_594L, Artifact.Kind.ZIP)
                    : new Build("llama-" + LLAMA_BUILD + "-bin-win-cpu-x64.zip",
                            "6a1f33708d4117288fa228f3365eb3a02d8598b1ff3b34b7a362829307d8a97a",
                            15_918_319L, Artifact.Kind.ZIP);
        } else if (os.contains("mac") || os.contains("darwin")) {
            build = arm
                    ? new Build("llama-" + LLAMA_BUILD + "-bin-macos-arm64.tar.gz",
                            "2fd30f76d8c4f669b9b4c7373539300fd187cae7fa8f850138cb6eb87e0252f5",
                            8_593_935L, Artifact.Kind.TAR_GZ)
                    : new Build("llama-" + LLAMA_BUILD + "-bin-macos-x64.tar.gz",
                            "1aad6b1f0483ef5c61f75ea3e1f724f745a135eadfad1325bf05b0d065d21755",
                            8_621_972L, Artifact.Kind.TAR_GZ);
        } else if (os.contains("linux")) {
            build = arm
                    ? new Build("llama-" + LLAMA_BUILD + "-bin-ubuntu-arm64.tar.gz",
                            "4caefac56322766047594092f4d697593dfc97e1775a3291d077d12abd0351ca",
                            11_025_312L, Artifact.Kind.TAR_GZ)
                    : new Build("llama-" + LLAMA_BUILD + "-bin-ubuntu-x64.tar.gz",
                            "f979aad67d18560d5066bf153cdeaa678e509b4422837bac375e5fd00d51b449",
                            13_939_695L, Artifact.Kind.TAR_GZ);
        } else {
            return Optional.empty();
        }

        return Optional.of(new Artifact(
                "llama.cpp " + LLAMA_BUILD + " (" + build.asset() + ")",
                URI.create(GH + build.asset()),
                NATIVE_DIR.resolve(build.asset()),
                build.sha256(),
                build.size(),
                build.kind()));
    }

    /**
     * The model weights, if {@code --setup} has already put them there.
     *
     * <p>This is what lets the documented start command have no {@code --model} flag in it. A
     * health worker does not type paths, and neither should a judge trying the project for the
     * first time: if the file is where setup puts it, Kangaroo finds it.
     */
    public static Optional<Path> installedModel(Path root) {
        return existing(root.resolve(model().target()));
    }

    /** The vision projector, likewise. Without it the model is text-only. */
    public static Optional<Path> installedProjector(Path root) {
        return existing(root.resolve(projector().target()));
    }

    private static Optional<Path> existing(Path path) {
        return java.nio.file.Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }

    /** Everything, in the order it should be fetched: small and useful first. */
    public static List<Artifact> all() {
        List<Artifact> out = new java.util.ArrayList<>();
        nativeRuntime().ifPresent(out::add);
        out.add(projector());
        out.add(model());
        return List.copyOf(out);
    }
}
