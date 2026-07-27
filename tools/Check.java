/// # Kangaroo - Java 26 environment check
///
/// A single file that runs with **no build tool at all**:
///
/// ```
/// java --enable-preview tools/Check.java
/// ```
///
/// It exists because the contest asks for verifiable proof that a project is really built on
/// Java 26, and because "it compiles" is a weaker claim than "here are the release's features,
/// executing, on your machine, printing their results".
///
/// The file itself demonstrates three of the newer language features it is checking for:
///
///   * **JEP 458** multi-file source launcher — this runs straight from source, uncompiled.
///   * **JEP 512** compact source files and instance `main` — there is no class declaration and
///     no `String[] args` below, just a `void main()`.
///   * **JEP 511** module import declarations — one line imports every package in a module.
///
/// Exits non-zero if anything essential is missing, so it works as a CI gate as well as a
/// diagnostic.

import module java.base;
import module java.net.http;

/// Importing a whole module can produce genuine ambiguities, and this is one: `java.base` exports
/// both `java.security.Signature` and `java.lang.classfile.Signature`, so an unqualified use of the
/// name does not compile. A single-type import wins over an on-demand module import, which is the
/// documented way to resolve it.
import java.security.Signature;

void main() {
    IO.println("Kangaroo - Java 26 environment check");
    IO.println("=".repeat(56));
    IO.println("Runtime : " + Runtime.version());
    IO.println("Vendor  : " + System.getProperty("java.vendor"));
    IO.println("Platform: " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
    IO.println("");

    var failures = new ArrayList<String>();

    // The release itself. Everything below assumes this.
    int feature = Runtime.version().feature();
    report("Java 26 or newer", feature >= 26, "found Java " + feature, failures);

    // JEP 526 — Lazy Constants. Kangaroo holds the WHO tables and both models in these.
    check("JEP 526  Lazy Constants", failures, () -> {
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        LazyConstant<String> constant = LazyConstant.of(() -> {
            counter.incrementAndGet();
            return "computed";
        });
        if (constant.isInitialized()) throw new AssertionError("initialised before first use");
        String first = constant.get();
        String second = constant.get();
        if (!first.equals(second) || counter.get() != 1) {
            throw new AssertionError("supplier ran " + counter.get() + " times, expected once");
        }
        return "initialised exactly once, on first use";
    });

    // JEP 524 — PEM encodings. Kangaroo signs every clinical record with an Ed25519 identity.
    check("JEP 524  PEM encodings", failures, () -> {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String pem = PEMEncoder.of().encodeToString(pair.getPublic());
        if (!pem.startsWith("-----BEGIN PUBLIC KEY-----")) {
            throw new AssertionError("unexpected PEM header");
        }
        var decoded = PEMDecoder.of().decode(pem, PublicKey.class);

        var signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update("an encounter".getBytes());
        byte[] signature = signer.sign();

        var verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(decoded);
        verifier.update("an encounter".getBytes());
        if (!verifier.verify(signature)) throw new AssertionError("signature did not verify");

        return "Ed25519 keypair round-tripped through PEM, " + pem.length() + " characters";
    });

    // JEP 525 — Structured Concurrency. Kangaroo fans out four evidence passes under one deadline.
    check("JEP 525  Structured Concurrency", failures, () -> {
        try (var scope = StructuredTaskScope.open(
                StructuredTaskScope.Joiner.<String>allSuccessfulOrThrow(),
                cfg -> cfg.withName("check").withTimeout(Duration.ofSeconds(5)))) {

            scope.fork(() -> "visual");
            scope.fork(() -> "audio");
            scope.fork(() -> "vitals");
            var results = scope.join();
            long n = results.stream().count();
            if (n != 3) throw new AssertionError("expected 3 results, got " + n);
            return "3 subtasks joined under one deadline and one cancellation domain";
        }
    });

    // JEP 530 — Primitive types in patterns. Kangaroo uses this in the LightGBM tree walker.
    check("JEP 530  Primitive patterns", failures, () -> {
        // The checked narrowing: this succeeds only when the double is exactly a float.
        double exact = 0.5;
        double inexact = 59.000000000000007;

        boolean exactFits = exact instanceof float _;
        boolean inexactFits = inexact instanceof float _;

        if (!exactFits) throw new AssertionError("0.5 should be exactly representable as a float");
        if (inexactFits) throw new AssertionError("59.000000000000007 should not be");

        // And a switch over a primitive with guarded arms, as the decision-type unpacker uses.
        byte packed = 0b0010;
        String missing = switch ((packed & 0b1100) >> 2) {
            case int t when t == 0 -> "none";
            case int t when t == 1 -> "zero";
            case int t -> "other(" + t + ")";
        };
        return "checked narrowing works; packed byte decoded as missing=" + missing;
    });

    // JEP 517 — HTTP/3. Kangaroo's cloud rung runs over QUIC where it is available.
    check("JEP 517  HTTP/3 client", failures, () -> {
        var version = HttpClient.Version.HTTP_3;
        try (var client = HttpClient.newBuilder().version(version).build()) {
            var request = HttpRequest.newBuilder(URI.create("https://example.invalid/"))
                    .version(version)
                    .setOption(HttpOption.H3_DISCOVERY, HttpOption.Http3DiscoveryMode.ALT_SVC)
                    .GET()
                    .build();
            if (request.version().orElse(null) != version) {
                throw new AssertionError("request did not retain HTTP_3");
            }
        }
        return "HttpClient.Version.HTTP_3 and H3_DISCOVERY(ALT_SVC) available";
    });

    // Foreign Function & Memory — final since 22, and the whole of Kangaroo's native layer.
    check("FFM      Foreign memory", failures, () -> {
        try (var arena = Arena.ofConfined()) {
            var segment = arena.allocate(64);
            segment.set(java.lang.foreign.ValueLayout.JAVA_INT, 0, 26);
            int read = segment.get(java.lang.foreign.ValueLayout.JAVA_INT, 0);
            if (read != 26) throw new AssertionError("read back " + read);
        }
        return "arena-scoped off-heap memory allocated and released";
    });

    // Virtual threads — thread-per-request across Kangaroo's whole HTTP surface.
    check("JEP 444  Virtual threads", failures, () -> {
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 10_000; i++) executor.submit(counter::incrementAndGet);
        }
        if (counter.get() != 10_000) throw new AssertionError("only " + counter.get() + " ran");
        return "10,000 virtual threads ran to completion";
    });

    IO.println("");

    // The Vector API is an incubator module and needs an extra flag, so its absence is reported
    // rather than treated as a failure. Kangaroo falls back to the scalar pipeline without it.
    boolean vector = ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent();
    IO.println(vector
            ? "  note   JEP 529 Vector API module is present."
            : "  note   JEP 529 Vector API not resolved. Add --add-modules jdk.incubator.vector.");
    IO.println("         Kangaroo runs without it, on the scalar colorimetry pipeline.");

    IO.println("");
    IO.println("=".repeat(56));
    if (failures.isEmpty()) {
        IO.println("All checks passed. This machine can build and run Kangaroo.");
    } else {
        IO.println("FAILED: " + failures.size() + " check(s)");
        failures.forEach(f -> IO.println("  - " + f));
        System.exit(1);
    }
}

/// Run one check, printing a pass or fail line and collecting failures.
void check(String name, List<String> failures, Check body) {
    try {
        String detail = body.run();
        IO.println(String.format("  ok    %-32s %s", name, detail));
    } catch (Throwable t) {
        IO.println(String.format("  FAIL  %-32s %s", name, t));
        failures.add(name + " - " + t);
    }
}

void report(String name, boolean ok, String detail, List<String> failures) {
    IO.println(String.format("  %-5s %-32s %s", ok ? "ok" : "FAIL", name, detail));
    if (!ok) failures.add(name + " - " + detail);
}

@FunctionalInterface
interface Check {
    String run() throws Exception;
}
