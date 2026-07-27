package com.kangaroo.app;

import com.kangaroo.audit.Jeps;
import com.kangaroo.crypto.DeviceIdentity;
import com.kangaroo.ffm.NativeRuntime;
import com.kangaroo.http.KangarooServer;
import com.kangaroo.infer.DeterministicEngine;
import com.kangaroo.infer.FailoverEngine;
import com.kangaroo.infer.InferenceEngine;
import com.kangaroo.infer.NativeEngine;
import com.kangaroo.infer.OpenAiCompatibleEngine;
import com.kangaroo.infer.Provider;
import com.kangaroo.orchestrate.AssessmentOrchestrator;
import com.kangaroo.store.EncounterStore;
import com.kangaroo.store.PatientMemory;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The entry point. One process, one artifact, no sidecars.
 *
 * <pre>
 *   java --enable-preview --add-modules jdk.incubator.vector \
 *        --enable-native-access=ALL-UNNAMED -jar kangaroo.jar [options]
 * </pre>
 *
 * <p>Every option is optional. With none of them, Kangaroo starts on port 8443, serves both front
 * doors, and produces valid WHO classifications using nothing but the deterministic rule engine and
 * the pure-Java gradient-boosted head. Adding a model, a projector or an API key adds better prose;
 * it does not add correctness, and it is not required for the product to work.
 */
public final class Kangaroo {

    private static final int DEFAULT_PORT = 8443;

    private Kangaroo() {}

    /**
     * Command-line configuration.
     *
     * @param port        the HTTP port
     * @param bind        the interface to bind; loopback by default so nothing is exposed by accident
     * @param dataDir     where encounters, the device key and the store live
     * @param model       an optional GGUF model for the on-device rung
     * @param projector   an optional multimodal projector
     * @param apiKey      an optional API key for the cloud rung; the provider is detected from it
     * @param serverUrl   an optional OpenAI-compatible endpoint on this machine or LAN
     */
    record Config(int port, String bind, Path dataDir, Path model, Path projector,
                  String apiKey, String modelName, URI serverUrl, boolean open,
                  boolean warmupAndExit) {

        static Config parse(String[] args) {
            int port = DEFAULT_PORT;
            // Loopback by default. A clinical tool that binds every interface the moment it starts
            // is a clinical tool that ends up on a hotel Wi-Fi.
            String bind = "127.0.0.1";
            Path dataDir = Path.of(System.getProperty("user.home"), ".kangaroo");
            Path model = null;
            Path projector = null;
            String apiKey = System.getenv("KANGAROO_API_KEY");
            String modelName = "";
            URI serverUrl = null;
            boolean open = false;
            boolean warmupAndExit = false;

            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--port" -> port = Integer.parseInt(next(args, ++i, "--port"));
                    case "--bind" -> bind = next(args, ++i, "--bind");
                    case "--lan" -> bind = "0.0.0.0";
                    case "--data" -> dataDir = Path.of(next(args, ++i, "--data"));
                    case "--model" -> model = Path.of(next(args, ++i, "--model"));
                    case "--projector", "--mmproj" -> projector = Path.of(next(args, ++i, "--projector"));
                    case "--api-key" -> apiKey = next(args, ++i, "--api-key");
                    case "--model-name" -> modelName = next(args, ++i, "--model-name");
                    case "--server" -> serverUrl = URI.create(next(args, ++i, "--server"));
                    case "--open" -> open = true;
                    case "--warmup-and-exit" -> warmupAndExit = true;
                    case "--help", "-h" -> {
                        usage();
                        System.exit(0);
                    }
                    default -> throw new IllegalArgumentException("unknown option: " + args[i]
                            + " (try --help)");
                }
            }
            return new Config(port, bind, dataDir, model, projector,
                    apiKey == null ? "" : apiKey, modelName, serverUrl, open, warmupAndExit);
        }

        private static String next(String[] args, int i, String option) {
            if (i >= args.length) throw new IllegalArgumentException(option + " needs a value");
            return args[i];
        }
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);

        banner();

        DeviceIdentity identity = DeviceIdentity.loadOrCreate(config.dataDir());
        PatientMemory memory = new PatientMemory();
        EncounterStore store = new EncounterStore(config.dataDir().resolve("encounters"), identity);

        FailoverEngine engines = buildLadder(config);
        AssessmentOrchestrator orchestrator = new AssessmentOrchestrator(engines, memory);

        KangarooServer server = new KangarooServer(config.port(), config.bind(),
                orchestrator, store, memory, identity).start();

        // Shut down cleanly so the native arena is released and the store is consistent, whether
        // the operator pressed Ctrl-C or systemd sent a TERM.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down.");
            server.close();
            orchestrator.close();
        }, "kangaroo-shutdown"));

        report(config, server, engines, identity, store);

        // The AOT training mode (JEP 516). The cache is only written when the JVM exits normally,
        // and a process killed by a signal does not qualify -- on Windows a kill is a hard
        // TerminateProcess with no shutdown hooks at all. So rather than starting the server and
        // hoping a signal lands gracefully, this exercises the real startup path in-process and
        // returns from main, which is the one exit the cache writer can rely on.
        if (config.warmupAndExit()) {
            warmup(orchestrator, store);
            server.close();
            orchestrator.close();
            System.out.println("  Warm-up complete. Exiting cleanly so the AOT cache is written.");
            return;
        }

        if (config.open()) {
            openBrowser("http://localhost:" + server.port() + "/");
        }

        Thread.currentThread().join();
    }

    /**
     * Assemble the inference ladder from whatever this installation actually has.
     *
     * <p>The deterministic rung is added unconditionally and last. It is the only one that cannot
     * fail, and its presence is what makes {@link FailoverEngine#explain} total.
     */
    private static FailoverEngine buildLadder(Config config) {
        List<InferenceEngine> rungs = new ArrayList<>();

        Provider.detect(config.apiKey(), config.modelName())
                .ifPresent(p -> rungs.add(new OpenAiCompatibleEngine(p)));

        if (config.serverUrl() != null) {
            rungs.add(new OpenAiCompatibleEngine(
                    Provider.localServer(config.serverUrl(),
                            config.modelName().isBlank() ? "local-model" : config.modelName())));
        }

        NativeEngine.ifConfigured(config.model(), config.projector()).ifPresent(rungs::add);

        rungs.add(new DeterministicEngine());
        return new FailoverEngine(rungs);
    }

    /**
     * Exercise everything a real startup touches: the WHO reference tables, both gradient-boosted
     * heads, the rule engine, the colorimetry pipeline and the store. A cache trained on a startup
     * that never assessed anything records the wrong classes and buys nothing.
     */
    private static void warmup(AssessmentOrchestrator orchestrator, EncounterStore store)
            throws Exception {
        System.out.println("  Warming up...");

        String[] intakes = {
                "The baby is lethargic and not feeding at all. No fever.",
                "Baby feeds well. No fever. Cord is dry and clean. No pustules.",
                "Yellow colour reaching the trunk. Feeding is reduced since yesterday.",
                "Cord stump has redness about 1 cm wide with slight discharge.",
        };

        for (int i = 0; i < intakes.length; i++) {
            var encounter = com.kangaroo.core.Encounter.of(
                    new com.kangaroo.core.Subject(6 + i, 3.0 + i * 0.1,
                            com.kangaroo.core.Sex.FEMALE, false),
                    intakes[i],
                    i % 2 == 0 ? com.kangaroo.core.Mode.CHW : com.kangaroo.core.Mode.PARENT);
            var assessment = orchestrator.assess(encounter);
            store.save(encounter, assessment);
        }

        // The deterministic tools and the colorimetry pipeline, which the assessments above only
        // partly reach.
        com.kangaroo.clinical.ClinicalTools.definitions();
        com.kangaroo.clinical.Dosing.calculate("amoxicillin_oral", 3.2);
        com.kangaroo.clinical.ZScore.calculate(3.2, 7, com.kangaroo.core.Sex.FEMALE);
        com.kangaroo.color.ColourPipeline.preferred()
                .extract(com.kangaroo.color.Bench.syntheticFrame(128, 128));
        com.kangaroo.ml.Models.jaundice();
    }

    private static void banner() {
        System.out.println();
        System.out.println("  Kangaroo " + Jeps.VERSION);
        System.out.println("  Offline newborn watch for parents and community health workers.");
        System.out.println("  Built entirely in Java " + Jeps.JDK + ". Running on " + Runtime.version() + ".");
        System.out.println();
    }

    private static void report(Config config, KangarooServer server, FailoverEngine engines,
                               DeviceIdentity identity, EncounterStore store) {
        System.out.println("  Open        http://localhost:" + server.port() + "/");
        if (!"127.0.0.1".equals(config.bind())) {
            System.out.println("  On the LAN  http://<this machine>:" + server.port()
                    + "/   (phones can pair to this)");
        }
        System.out.println("  Data        " + config.dataDir().toAbsolutePath());
        System.out.println("  Device      " + identity.fingerprint());
        System.out.println("  Encounters  " + store.count() + " stored");
        System.out.println();

        System.out.println("  Inference ladder, highest first:");
        for (var rung : engines.status()) {
            String mark = rung.available() ? "  ok  " : "  --  ";
            System.out.println("   " + mark + pad(rung.rung().name(), 14) + rung.description());
            if (!rung.available() && !rung.reason().isBlank()) {
                System.out.println("            " + rung.reason());
            }
        }
        System.out.println();

        if (!NativeRuntime.available()) {
            System.out.println("  No on-device model configured. This is a supported configuration:");
            System.out.println("  the deterministic WHO engine and the gradient-boosted head need");
            System.out.println("  nothing but the JDK, and still produce a valid classification.");
            System.out.println();
        }
        System.out.println("  Ctrl-C to stop.");
        System.out.println();
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s + " " : s + " ".repeat(width - s.length());
    }

    private static void openBrowser(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception e) {
            // Headless, or no browser. Not worth mentioning: the URL is printed above.
        }
    }

    private static void usage() {
        System.out.println("""
                Usage: kangaroo [options]

                  --port <n>          HTTP port (default 8443)
                  --bind <addr>       interface to bind (default 127.0.0.1)
                  --lan               bind every interface, so phones on this network can pair
                  --data <dir>        data directory (default ~/.kangaroo)

                  --model <file.gguf>      on-device language model
                  --projector <file.gguf>  multimodal projector, enabling image understanding
                  --server <url>           an OpenAI-compatible endpoint on this machine or LAN,
                                           e.g. http://127.0.0.1:5000/v1
                  --api-key <key>          cloud fallback; the provider is detected from the key
                  --model-name <name>      model to request from the server or provider

                  --open              open a browser once started
                  --warmup-and-exit   run one of everything, then exit cleanly. Used by
                                      packaging/aot.sh to record the JEP 516 AOT cache.
                  --help              this

                Everything is optional. With no options at all Kangaroo runs the full WHO IMNCI
                assessment offline, using only the deterministic rule engine and the pure-Java
                gradient-boosted head.

                The native libraries for --model are found via ./runtime/bin or
                -Dkangaroo.native.dir=<dir>. See README, 'Optional: the on-device model'.
                """);
    }

    /** Exposed for tests, which build the same ladder without starting a server. */
    static FailoverEngine ladderFor(String[] args) {
        return buildLadder(Config.parse(args));
    }

    static Optional<Path> defaultDataDir() {
        return Optional.of(Path.of(System.getProperty("user.home"), ".kangaroo"));
    }
}
