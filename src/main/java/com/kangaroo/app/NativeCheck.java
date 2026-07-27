package com.kangaroo.app;

import com.kangaroo.ffm.NativeRuntime;
import com.kangaroo.ffm.llama.Llama;
import com.kangaroo.ffm.llama.LlamaLayouts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * A diagnostic that proves the native layer works on this machine, end to end.
 *
 * <p>Run it before filing a bug about the on-device model. It reports where the libraries were
 * found, verifies the struct layouts, loads the model, tokenises, generates, and — when a projector
 * and an image are supplied — runs a real multimodal pass. Every step prints what it did, so a
 * failure names the rung it failed on rather than producing a stack trace from four layers down.
 *
 * <pre>
 *   java --enable-preview --enable-native-access=ALL-UNNAMED \
 *        -cp target/kangaroo.jar com.kangaroo.app.NativeCheck &lt;model.gguf&gt; [mmproj.gguf] [image.jpg]
 * </pre>
 */
public final class NativeCheck {

    private NativeCheck() {}

    public static void main(String[] args) throws Exception {
        System.out.println("Kangaroo native check");
        System.out.println("=====================");
        System.out.println("Java        : " + Runtime.version());
        System.out.println("Platform    : " + NativeRuntime.Platform.current()
                + " (" + System.getProperty("os.arch") + ")");

        Optional<Path> dir = NativeRuntime.directory();
        System.out.println("Native dir  : " + dir.map(Path::toString).orElse("NOT FOUND"));
        if (dir.isEmpty()) {
            System.out.println();
            System.out.println(NativeRuntime.unavailableReason());
            System.out.println();
            System.out.println("This is not a failure. The deterministic WHO engine needs none of it.");
            return;
        }
        System.out.println("Vision libs : " + (NativeRuntime.visionAvailable() ? "present" : "absent"));

        LlamaLayouts.verify();
        System.out.println("Struct sizes: model_params=" + LlamaLayouts.MODEL_PARAMS.byteSize()
                + " context_params=" + LlamaLayouts.CONTEXT_PARAMS.byteSize()
                + " batch=" + LlamaLayouts.BATCH.byteSize() + "  [verified]");

        if (args.length == 0) {
            System.out.println();
            System.out.println("Pass a .gguf model path to load one.");
            return;
        }

        Path model = Path.of(args[0]);
        if (!Files.isRegularFile(model)) {
            System.err.println("No such model file: " + model);
            System.exit(2);
        }
        Optional<Path> mmproj = args.length > 1 ? Optional.of(Path.of(args[1])) : Optional.empty();

        Llama.Options options = Llama.Options.clinicalDefaults().withMaxTokens(64);

        long t0 = System.nanoTime();
        try (Llama llama = Llama.open(model, mmproj, options)) {
            long loadMs = (System.nanoTime() - t0) / 1_000_000;
            System.out.println();
            System.out.println("Model       : " + llama.describe());
            System.out.println("Parameters  : " + String.format("%.2fB", llama.parameterCount() / 1e9));
            System.out.println("Vocabulary  : " + llama.vocabularySize() + " tokens");
            System.out.println("Context     : " + llama.contextSize() + " tokens");
            System.out.println("Vision      : " + (llama.visionEnabled() ? "enabled" : "text only"));
            System.out.println("Load time   : " + loadMs + " ms");

            System.out.println();
            System.out.print("Generating  : ");
            long t1 = System.nanoTime();
            String out = llama.chat(
                    "You are helping a community health worker. Answer in one short sentence.",
                    "Name one WHO danger sign in a newborn.",
                    piece -> System.out.print(piece));
            long genMs = (System.nanoTime() - t1) / 1_000_000;
            System.out.println();
            System.out.println("Generated   : " + out.length() + " chars in " + genMs + " ms");

            if (args.length > 2 && llama.visionEnabled()) {
                Path image = Path.of(args[2]);
                System.out.println();
                System.out.print("Vision pass : ");
                long t2 = System.nanoTime();
                String vout = llama.chatWithImages(
                        "You are helping a community health worker.",
                        "Describe this photograph in one sentence.",
                        List.of(Files.readAllBytes(image)),
                        piece -> System.out.print(piece));
                System.out.println();
                System.out.println("Vision      : " + vout.length() + " chars in "
                        + (System.nanoTime() - t2) / 1_000_000 + " ms");
            }
        }

        System.out.println();
        System.out.println("Native layer OK. No JNI, no subprocess, no model server.");
    }
}
