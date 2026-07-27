package com.kangaroo.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Finds and opens the optional native inference libraries.
 *
 * <p><b>Optional</b> is the operative word. Kangaroo produces a valid WHO classification with no
 * native library present at all — the deterministic rule and the pure-Java gradient-boosted head
 * need nothing but the JDK. This class exists so that when {@code libllama} <em>is</em> available,
 * the language model runs inside the same process as everything else, with no subprocess, no local
 * HTTP server, and no JNI shim to maintain.
 *
 * <p>The libraries are not vendored into the repository. They are ordinary llama.cpp build
 * artifacts, they are large, they are platform-specific, and pinning a copy of somebody else's
 * binaries into a clinical repository is a supply-chain liability rather than a convenience. The
 * README says where to get them; this class says where to put them.
 *
 * <h2>Load order matters on Windows</h2>
 * {@code llama.dll} imports {@code ggml.dll}, which imports {@code ggml-base.dll}. Windows resolves
 * an import by module name against what is already loaded in the process before it goes looking on
 * disk, so loading the dependencies explicitly, deepest first, is what makes an out-of-tree
 * directory work without touching {@code PATH} or the DLL search order.
 */
public final class NativeRuntime {

    /** System property, then environment variable, then the conventional locations. */
    public static final String DIR_PROPERTY = "kangaroo.native.dir";
    public static final String DIR_ENV = "KANGAROO_NATIVE_DIR";

    private static final List<String> CANDIDATE_DIRS = List.of(
            "runtime/bin",
            "runtime",
            "native");

    private NativeRuntime() {}

    /** Which platform naming convention applies. */
    public enum Platform {
        WINDOWS(".dll", ""),
        MAC(".dylib", "lib"),
        LINUX(".so", "lib");

        private final String extension;
        private final String prefix;

        Platform(String extension, String prefix) {
            this.extension = extension;
            this.prefix = prefix;
        }

        public String fileName(String base) {
            return prefix + base + extension;
        }

        public static Platform current() {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) return WINDOWS;
            if (os.contains("mac") || os.contains("darwin")) return MAC;
            return LINUX;
        }
    }

    /**
     * The directory holding the native libraries, if one can be found and it actually contains
     * a llama library.
     */
    public static Optional<Path> directory() {
        List<Path> candidates = new ArrayList<>();

        String prop = System.getProperty(DIR_PROPERTY);
        if (prop != null && !prop.isBlank()) candidates.add(Path.of(prop));

        String env = System.getenv(DIR_ENV);
        if (env != null && !env.isBlank()) candidates.add(Path.of(env));

        Path cwd = Path.of("").toAbsolutePath();
        for (String rel : CANDIDATE_DIRS) candidates.add(cwd.resolve(rel));

        String home = System.getProperty("user.home");
        if (home != null) {
            candidates.add(Path.of(home, ".kangaroo", "runtime"));
        }

        Platform p = Platform.current();
        for (Path c : candidates) {
            if (Files.isDirectory(c) && Files.isRegularFile(c.resolve(p.fileName("llama")))) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    /** True when an in-process language model could be started on this machine right now. */
    public static boolean available() {
        return directory().isPresent();
    }

    /** Why not, in words a user can act on. Empty when it is available. */
    public static String unavailableReason() {
        if (available()) return "";
        return "No native inference library found. Kangaroo works without one - the deterministic "
                + "WHO engine needs nothing. To enable the on-device language model, put the "
                + "llama.cpp shared libraries in ./runtime/bin or set -D" + DIR_PROPERTY + "=<dir>. "
                + "See README, 'Optional: the on-device model'.";
    }

    /**
     * Open the llama.cpp libraries in the given arena, loading dependencies deepest first.
     *
     * @return a lookup over every symbol in the loaded set, or empty when the libraries are absent
     */
    public static Optional<SymbolLookup> openLlama(Arena arena) {
        Optional<Path> dir = directory();
        if (dir.isEmpty()) return Optional.empty();

        Platform p = Platform.current();
        Path base = dir.get();
        List<SymbolLookup> lookups = new ArrayList<>();

        // Dependencies first, deepest first, so each library's imports are already satisfied
        // in-process by the time the next one loads.
        //
        // Every lookup is kept, not just llama's. ggml exports the backend loader that has to run
        // before a model will load at all, and ggml is not llama.dll -- composing only llama and
        // mtmd leaves that symbol invisible and produces a "no backends are loaded" failure that
        // looks like a model problem and is not.
        for (String dep : List.of("ggml-base", "ggml")) {
            Path path = base.resolve(p.fileName(dep));
            if (Files.isRegularFile(path)) {
                lookups.add(SymbolLookup.libraryLookup(path, arena));
            }
        }

        lookups.add(SymbolLookup.libraryLookup(base.resolve(p.fileName("llama")), arena));

        Path mtmd = base.resolve(p.fileName("mtmd"));
        if (Files.isRegularFile(mtmd)) {
            lookups.add(SymbolLookup.libraryLookup(mtmd, arena));
        }

        return Optional.of(compose(lookups));
    }

    /** Search the loaded libraries in order, last-loaded-wins ties going to the first match. */
    private static SymbolLookup compose(List<SymbolLookup> lookups) {
        List<SymbolLookup> frozen = List.copyOf(lookups);
        return name -> {
            for (SymbolLookup l : frozen) {
                var found = l.find(name);
                if (found.isPresent()) return found;
            }
            return Optional.empty();
        };
    }

    /** True when the multimodal projector library is present alongside llama. */
    public static boolean visionAvailable() {
        return directory()
                .map(d -> Files.isRegularFile(d.resolve(Platform.current().fileName("mtmd"))))
                .orElse(false);
    }
}
