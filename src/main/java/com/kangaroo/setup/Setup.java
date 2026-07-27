package com.kangaroo.setup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code kangaroo --setup} — fetch the optional on-device model and the native runtime.
 *
 * <p>Kangaroo is usable the moment it is built, with none of this. What setup adds is the top of
 * the inference ladder: a language model that writes the action plan in the caregiver's own
 * language, and the vision projector that lets it read the photographs the health worker just took
 * instead of only the boxes they ticked.
 *
 * <p>It is written in Java, using the JDK's own HTTP client, for the same reason the rest of the
 * project is: a setup step that needs Python installed is a setup step that fails on the device it
 * matters on. One JAR, one JDK, no bootstrap language.
 *
 * <h2>What it guarantees</h2>
 * <ul>
 *   <li>Everything is pinned to an immutable revision and a SHA-256, so two people running this a
 *       month apart get identical bytes.</li>
 *   <li>An interrupted download resumes rather than restarting, because the connection this is
 *       designed for drops.</li>
 *   <li>Nothing is placed where the application looks for it until it has been verified.</li>
 *   <li>It is re-runnable. A second run downloads nothing and re-verifies what is there.</li>
 * </ul>
 */
public final class Setup {

    /** What the user asked for. */
    public enum Scope {
        /** The native libraries only — enough to run a model you already have. */
        RUNTIME,
        /** The weights only — for a machine that already has llama.cpp. */
        MODEL,
        /** Both. The default. */
        ALL
    }

    private Setup() {}

    /**
     * Run the setup, reporting to standard output.
     *
     * @param root  the directory the relative paths in {@link Manifest} are resolved against
     * @return 0 on success, non-zero if anything failed to arrive and verify
     */
    public static int run(Path root, Scope scope, boolean dryRun) {
        List<Artifact> wanted = select(scope);

        System.out.println();
        System.out.println("  Kangaroo setup");
        System.out.println();

        if (wanted.isEmpty()) {
            System.out.println("  No published llama.cpp CPU build matches this platform ("
                    + System.getProperty("os.name") + " " + System.getProperty("os.arch") + ").");
            System.out.println("  This is not a failure. The deterministic WHO engine and the");
            System.out.println("  gradient-boosted head need nothing but the JDK, and they are what");
            System.out.println("  decides the traffic light. Build llama.cpp yourself and drop the");
            System.out.println("  libraries in runtime/bin to enable the optional model rung.");
            System.out.println();
            return 0;
        }

        long total = wanted.stream().mapToLong(Artifact::sizeBytes).sum();
        System.out.println("  This will download " + Downloader.human(total) + " into "
                + root.resolve("runtime") + ":");
        System.out.println();
        for (Artifact a : wanted) {
            System.out.printf("    %-50s %8s   %s%n", a.name(), a.humanSize(), a.target());
        }
        System.out.println();
        System.out.println("  Everything is pinned to a fixed revision and checked against a");
        System.out.println("  SHA-256. An interrupted download resumes; re-running is safe.");
        System.out.println();

        if (dryRun) {
            System.out.println("  --dry-run: nothing was downloaded.");
            System.out.println();
            return 0;
        }

        List<String> failures = new ArrayList<>();
        try (Downloader downloader = new Downloader(System.out::println)) {
            for (Artifact artifact : wanted) {
                try {
                    if (alreadyUnpacked(artifact, root)) {
                        System.out.println("  ok        %s - already unpacked".formatted(artifact.name()));
                        continue;
                    }
                    downloader.fetch(artifact, root);
                    unpackIfArchive(artifact, root);
                } catch (IOException e) {
                    failures.add(artifact.name() + ": " + e.getMessage());
                    System.out.println("  FAILED    " + artifact.name());
                    System.out.println("            " + e.getMessage());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println();
            System.out.println("  Interrupted. Progress is kept - run --setup again to resume.");
            return 130;
        }

        System.out.println();
        if (!failures.isEmpty()) {
            System.out.println("  " + failures.size() + " item(s) did not complete:");
            failures.forEach(f -> System.out.println("    - " + f));
            System.out.println();
            System.out.println("  Kangaroo still runs. The deterministic rung needs none of this.");
            System.out.println();
            return 1;
        }

        printHowToRun(root, scope);
        return 0;
    }

    /** Archives are unpacked beside themselves and then removed; the weights are left as they are. */
    private static void unpackIfArchive(Artifact artifact, Path root) throws IOException {
        if (artifact.kind() == Artifact.Kind.FILE) return;

        Path archive = root.resolve(artifact.target());
        if (!Files.exists(archive)) return;

        Path destination = archive.getParent();
        List<Path> written = Archives.extract(archive, artifact.kind(), destination);
        Files.deleteIfExists(archive);
        Files.writeString(stamp(artifact, root), artifact.sha256());
        System.out.println("  unpacked  " + written.size() + " files into " + destination);
    }

    /**
     * Whether this archive has already been unpacked here.
     *
     * <p>An archive is deleted once it has been extracted, so "is the file there?" cannot answer
     * this — without a stamp, every run re-downloads an archive it already has. The stamp holds the
     * digest that was unpacked, so changing the pinned build in {@link Manifest} correctly
     * invalidates it rather than leaving a stale runtime in place.
     */
    private static boolean alreadyUnpacked(Artifact artifact, Path root) throws IOException {
        if (artifact.kind() == Artifact.Kind.FILE) return false;
        Path stamp = stamp(artifact, root);
        return Files.isRegularFile(stamp)
                && Files.readString(stamp).strip().equals(artifact.sha256());
    }

    private static Path stamp(Artifact artifact, Path root) {
        Path archive = root.resolve(artifact.target());
        return archive.resolveSibling(".installed-" + archive.getFileName() + ".sha256");
    }

    private static List<Artifact> select(Scope scope) {
        List<Artifact> out = new ArrayList<>();
        if (scope != Scope.MODEL) {
            Manifest.nativeRuntime().ifPresent(out::add);
        }
        if (scope != Scope.RUNTIME) {
            out.add(Manifest.projector());
            out.add(Manifest.model());
        }
        return out;
    }

    private static void printHowToRun(Path root, Scope scope) {
        System.out.println("  Done. Everything verified.");
        System.out.println();
        if (scope == Scope.RUNTIME) {
            System.out.println("  The native runtime is in place. Point Kangaroo at a GGUF model you");
            System.out.println("  already have with --model, or run 'kangaroo --setup model' to fetch");
            System.out.println("  the pinned one.");
            System.out.println();
            return;
        }
        System.out.println("  Start Kangaroo with the on-device model:");
        System.out.println();
        System.out.println("    java --enable-preview --add-modules jdk.incubator.vector \\");
        System.out.println("         --enable-native-access=ALL-UNNAMED -jar target/kangaroo.jar \\");
        System.out.println("         --model  " + Manifest.model().target() + " \\");
        System.out.println("         --mmproj " + Manifest.projector().target());
        System.out.println();
        System.out.println("  Or let it find them itself - those are the paths it looks in:");
        System.out.println();
        System.out.println("    java ... -jar target/kangaroo.jar --open");
        System.out.println();
    }
}
