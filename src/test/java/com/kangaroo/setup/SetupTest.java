package com.kangaroo.setup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The setup path, tested without touching the network.
 *
 * <p>What is worth asserting here is not "can it download" — that is the network's job and it is
 * exercised by hand and in the README — but the parts that are silently wrong if they are wrong:
 * the platform decision, the pins being well-formed, and above all that an archive cannot write
 * outside the directory it is being unpacked into.
 */
class SetupTest {

    @Nested
    @DisplayName("the pinned manifest")
    class ManifestTests {

        @Test
        @DisplayName("every pin is a well-formed digest and a plausible size")
        void pinsAreWellFormed() {
            for (Artifact a : Manifest.all()) {
                assertTrue(a.sha256().matches("[0-9a-f]{64}"),
                        a.name() + " must pin a 64-character lower-case sha256");
                assertTrue(a.sizeBytes() > 1_000_000, a.name() + " size looks wrong: " + a.sizeBytes());
                assertTrue(a.uri().getScheme().equals("https"),
                        a.name() + " must be fetched over https, not " + a.uri().getScheme());
            }
        }

        @Test
        @DisplayName("a malformed digest is rejected at construction, not at download time")
        void malformedDigestRejected() {
            assertThrows(IllegalArgumentException.class, () -> new Artifact(
                    "bad", URI.create("https://example.invalid/x"), Path.of("x"),
                    "not-a-digest", 10, Artifact.Kind.FILE));

            // Upper case is a real trap: HexFormat produces lower case, so an upper-case pin would
            // never match anything and would fail only after a five-gigabyte download.
            assertThrows(IllegalArgumentException.class, () -> new Artifact(
                    "bad", URI.create("https://example.invalid/x"), Path.of("x"),
                    "C932975BBC2F16AC87BAE60078D3C7190871A3E8468566166103E0924981F183",
                    10, Artifact.Kind.FILE));
        }

        @Test
        @DisplayName("each platform selects the archive format its release actually uses")
        void platformSelection() {
            assertEquals(Artifact.Kind.ZIP,
                    Manifest.nativeRuntime("Windows 11", "amd64").orElseThrow().kind());
            assertEquals(Artifact.Kind.ZIP,
                    Manifest.nativeRuntime("Windows 11", "aarch64").orElseThrow().kind());
            assertEquals(Artifact.Kind.TAR_GZ,
                    Manifest.nativeRuntime("Mac OS X", "aarch64").orElseThrow().kind());
            assertEquals(Artifact.Kind.TAR_GZ,
                    Manifest.nativeRuntime("Linux", "amd64").orElseThrow().kind());
        }

        @Test
        @DisplayName("architecture is honoured, not assumed to be x64")
        void architectureIsHonoured() {
            assertTrue(Manifest.nativeRuntime("Linux", "aarch64").orElseThrow()
                    .uri().toString().contains("arm64"));
            assertTrue(Manifest.nativeRuntime("Linux", "amd64").orElseThrow()
                    .uri().toString().contains("x64"));
            // The Pi and an Apple laptop are both arm64 and must not be handed each other's build.
            assertTrue(Manifest.nativeRuntime("Mac OS X", "aarch64").orElseThrow()
                    .uri().toString().contains("macos-arm64"));
            assertTrue(Manifest.nativeRuntime("Linux", "aarch64").orElseThrow()
                    .uri().toString().contains("ubuntu-arm64"));
        }

        @Test
        @DisplayName("an unknown platform is empty rather than a wrong guess")
        void unknownPlatformIsEmpty() {
            assertTrue(Manifest.nativeRuntime("Haiku", "sparc").isEmpty());
        }

        @Test
        @DisplayName("the artifacts land where the application looks for them")
        void targetsMatchWhereTheAppLooks() {
            assertTrue(Manifest.model().target().startsWith(Manifest.MODEL_DIR));
            assertTrue(Manifest.projector().target().startsWith(Manifest.MODEL_DIR));
            assertTrue(Manifest.nativeRuntime("Linux", "amd64").orElseThrow()
                    .target().startsWith(Manifest.NATIVE_DIR));
        }
    }

    @Nested
    @DisplayName("archive extraction")
    class ArchiveTests {

        @Test
        @DisplayName("a zip round-trips, flattened into the destination")
        void zipRoundTrip(@TempDir Path dir) throws Exception {
            Path archive = dir.resolve("bin.zip");
            try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
                zip.putNextEntry(new ZipEntry("build/bin/libllama.so"));
                zip.write("ELF-ish".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("build/bin/"));   // a directory entry
                zip.closeEntry();
            }

            Path out = dir.resolve("out");
            List<Path> written = Archives.extract(archive, Artifact.Kind.ZIP, out);

            assertEquals(1, written.size(), "the directory entry must not count as a file");
            assertEquals("ELF-ish", Files.readString(out.resolve("libllama.so")),
                    "the archive's own directory structure is flattened away");
        }

        @Test
        @DisplayName("a gzipped tar round-trips, including a file that is not a multiple of 512")
        void tarRoundTrip(@TempDir Path dir) throws Exception {
            // 700 bytes exercises the padding arithmetic: one full block plus a partial one.
            String content = "x".repeat(700);
            Path archive = dir.resolve("bin.tar.gz");
            Files.write(archive, tarGz(List.of(
                    new Entry("build/bin/libllama.so", content),
                    new Entry("build/bin/libggml.so", "small"))));

            Path out = dir.resolve("out");
            List<Path> written = Archives.extract(archive, Artifact.Kind.TAR_GZ, out);

            assertEquals(2, written.size());
            assertEquals(content, Files.readString(out.resolve("libllama.so")));
            assertEquals("small", Files.readString(out.resolve("libggml.so")));
        }

        @Test
        @DisplayName("an entry that escapes the destination resolves to nothing")
        void pathTraversalRefused(@TempDir Path dir) {
            Path base = dir.resolve("dest");

            // The guard is defence in depth: flatten() already strips directories, so these names
            // cannot normally reach safeResolve. It is asserted directly because the day someone
            // stops flattening is the day this becomes the only thing standing between a downloaded
            // archive and the user's home directory.
            assertNull(Archives.safeResolve(base, "../../evil.sh"));
            assertNull(Archives.safeResolve(base, ".."));
            assertNull(Archives.safeResolve(base, ""));
            assertNull(Archives.safeResolve(base, "."));
            assertNull(Archives.safeResolve(base, "a/../../../evil.sh"));

            // And a name that is genuinely inside must still resolve, or the guard is just a ban.
            Path ok = Archives.safeResolve(base, "libllama.so");
            assertNotNull(ok);
            assertTrue(ok.startsWith(base.toAbsolutePath().normalize()));
        }

        @Test
        @DisplayName("a traversing zip entry is skipped and the rest still unpacks")
        void traversingZipEntrySkipped(@TempDir Path dir) throws Exception {
            Path archive = dir.resolve("evil.zip");
            try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
                zip.putNextEntry(new ZipEntry("../../escaped.txt"));
                zip.write("nope".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("good.so"));
                zip.write("fine".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }

            Path out = dir.resolve("out");
            Archives.extract(archive, Artifact.Kind.ZIP, out);

            assertEquals("fine", Files.readString(out.resolve("good.so")),
                    "one bad entry must not abort the rest of the unpack");

            // The guarantee: nothing landed anywhere but the destination directory.
            assertFalse(Files.exists(dir.resolve("escaped.txt")));
            assertFalse(Files.exists(dir.getParent().resolve("escaped.txt")));
            try (var tree = Files.walk(out)) {
                assertTrue(tree.allMatch(p -> p.startsWith(out)),
                        "every extracted path must be under the destination");
            }
        }
    }

    // ------------------------------------------------------------------ a tiny tar writer

    private record Entry(String name, String content) {}

    /** Build a gzipped tar in memory, so the reader is tested against a real one. */
    private static byte[] tarGz(List<Entry> entries) throws Exception {
        var raw = new ByteArrayOutputStream();
        try (var gz = new GZIPOutputStream(raw)) {
            for (Entry e : entries) {
                byte[] body = e.content().getBytes(StandardCharsets.UTF_8);
                gz.write(header(e.name(), body.length));
                gz.write(body);
                int padding = (512 - (body.length % 512)) % 512;
                gz.write(new byte[padding]);
            }
            gz.write(new byte[1024]);          // two zero blocks end the archive
        }
        return raw.toByteArray();
    }

    private static byte[] header(String name, int size) {
        byte[] h = new byte[512];
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(n, 0, h, 0, Math.min(n.length, 100));

        put(h, 100, "0000644\0");                       // mode
        put(h, 108, "0000000\0");                       // uid
        put(h, 116, "0000000\0");                       // gid
        put(h, 124, "%011o\0".formatted(size));         // size, octal
        put(h, 136, "%011o\0".formatted(0));            // mtime
        h[156] = '0';                                   // type: regular file
        put(h, 257, "ustar\00000");

        // The checksum is computed with the checksum field itself read as spaces.
        for (int i = 148; i < 156; i++) h[i] = ' ';
        int sum = 0;
        for (byte b : h) sum += b & 0xff;
        put(h, 148, "%06o\0 ".formatted(sum));
        return h;
    }

    private static void put(byte[] target, int offset, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, target, offset, bytes.length);
    }
}
