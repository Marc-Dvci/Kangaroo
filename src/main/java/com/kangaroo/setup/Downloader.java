package com.kangaroo.setup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Consumer;

/**
 * Fetches a pinned {@link Artifact}, resuming where it left off and refusing to accept bytes that
 * do not hash to what was pinned.
 *
 * <p>The deployment this is written for is a laptop on a connection that drops. So a partial
 * download is parked in a {@code .part} file and the next run continues it with a {@code Range}
 * request rather than starting the five-gigabyte model again — and the running digest is recomputed
 * over the bytes already on disk, because a digest that only covers the second half of a file
 * proves nothing about the first.
 *
 * <p>Nothing is written to the final path until the digest matches. A half-written model that looks
 * complete is worse than no model: the deterministic rung is always there, and an absent file
 * degrades to it cleanly while a corrupt one fails at load time in a much less obvious way.
 */
public final class Downloader implements AutoCloseable {

    /** How much to read at a time. Large enough that the digest is not the bottleneck. */
    private static final int BUFFER = 1 << 16;

    private final HttpClient http;
    private final Consumer<String> log;

    public Downloader(Consumer<String> log) {
        this.log = log;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)   // Hugging Face and GitHub both redirect to a CDN
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * Ensure the artifact is present and verified.
     *
     * @return {@code true} if anything was downloaded, {@code false} if it was already there
     */
    public boolean fetch(Artifact artifact, Path root) throws IOException, InterruptedException {
        Path target = root.resolve(artifact.target());
        Path part = root.resolve(artifact.partFile());
        Files.createDirectories(target.getParent());

        if (Files.exists(target) && verified(target, artifact)) {
            log.accept("  ok        %s - already present and verified".formatted(artifact.name()));
            return false;
        }

        long resumeFrom = Files.exists(part) ? Files.size(part) : 0;
        if (resumeFrom > artifact.sizeBytes()) {
            // Longer than the pinned size means it is not the file we think it is.
            Files.delete(part);
            resumeFrom = 0;
        }
        if (resumeFrom > 0) {
            log.accept("  resuming  %s from %s".formatted(artifact.name(), human(resumeFrom)));
        } else {
            log.accept("  fetching  %s (%s)".formatted(artifact.name(), artifact.humanSize()));
        }

        MessageDigest digest = sha256();
        if (resumeFrom > 0) {
            digestExisting(part, digest);
        }

        download(artifact.uri(), part, resumeFrom, artifact, digest);

        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equals(artifact.sha256())) {
            Files.deleteIfExists(part);
            throw new IOException("""
                    %s failed verification.
                      expected sha256 %s
                      actual   sha256 %s
                    The partial file has been removed. This means the download was corrupted, or the \
                    pinned digest in Manifest.java is stale. Re-run --setup to try again."""
                    .formatted(artifact.name(), artifact.sha256(), actual));
        }

        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        log.accept("  verified  %s".formatted(artifact.name()));
        return true;
    }

    /** Stream the remainder into the part file, hashing and reporting progress as it goes. */
    private void download(URI uri, Path part, long from, Artifact artifact, MessageDigest digest)
            throws IOException, InterruptedException {

        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(30))
                .header("User-Agent", "Kangaroo/1.0 (+https://github.com/Marc-Dvci/Kangaroo)")
                .GET();
        if (from > 0) {
            request.header("Range", "bytes=" + from + "-");
        }

        HttpResponse<InputStream> response =
                http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());

        int status = response.statusCode();
        if (status == 200 && from > 0) {
            // The server ignored the range and is sending the whole file. Start the digest over
            // rather than concatenating a fresh copy onto the bytes already on disk.
            log.accept("  note      server did not honour the resume request; starting over");
            Files.deleteIfExists(part);
            digest.reset();
            from = 0;
        } else if (status != 200 && status != 206) {
            throw new IOException("download of " + artifact.name() + " failed: HTTP " + status
                    + " from " + uri);
        }

        var openOptions = from > 0
                ? new StandardOpenOption[] {StandardOpenOption.WRITE, StandardOpenOption.APPEND}
                : new StandardOpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING};

        Progress progress = new Progress(artifact.sizeBytes(), from, log);
        try (InputStream in = response.body();
             OutputStream raw = Files.newOutputStream(part, openOptions);
             DigestOutputStream out = new DigestOutputStream(raw, digest)) {

            byte[] buffer = new byte[BUFFER];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                progress.advance(n);
            }
        }
        progress.done();
    }

    /** Re-hash bytes already on disk so a resumed digest covers the whole file. */
    private static void digestExisting(Path part, MessageDigest digest) throws IOException {
        try (InputStream in = Files.newInputStream(part)) {
            byte[] buffer = new byte[BUFFER];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
        }
    }

    /** Whether a file already on disk is the pinned artifact. */
    static boolean verified(Path file, Artifact artifact) throws IOException {
        if (Files.size(file) != artifact.sizeBytes()) {
            return false;                      // cheap check first; a wrong size cannot be right
        }
        MessageDigest digest = sha256();
        digestExisting(file, digest);
        return HexFormat.of().formatHex(digest.digest()).equals(artifact.sha256());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JDK", e);
        }
    }

    static String human(long bytes) {
        double mb = bytes / 1_000_000.0;
        return mb >= 1000 ? "%.1f GB".formatted(mb / 1000) : "%.0f MB".formatted(mb);
    }

    @Override
    public void close() {
        http.close();
    }

    /** A one-line progress report that does not scroll a terminal off its own screen. */
    private static final class Progress {
        private final long total;
        private final long start = System.nanoTime();
        private final long alreadyHad;
        private final Consumer<String> log;
        private long done;
        private long lastReport;

        Progress(long total, long alreadyHad, Consumer<String> log) {
            this.total = total;
            this.alreadyHad = alreadyHad;
            this.done = alreadyHad;
            this.log = log;
            // Start the clock now, so the first line appears after a real interval rather than
            // immediately at 0% with a meaningless rate.
            this.lastReport = start;
        }

        void advance(int n) {
            done += n;
            long now = System.nanoTime();
            if (now - lastReport > Duration.ofSeconds(2).toNanos()) {
                lastReport = now;
                report(now);
            }
        }

        void done() {
            report(System.nanoTime());
        }

        private void report(long now) {
            double seconds = (now - start) / 1e9;
            double rate = seconds > 0 ? (done - alreadyHad) / seconds : 0;
            int percent = total > 0 ? (int) (100 * done / total) : 0;
            // ASCII only. This prints to a Windows console and to an ssh session on a Pi, and a
            // multiplication sign or a middle dot comes out as a question mark on at least one.
            String eta = rate > 0 && done < total
                    ? ", %d s left".formatted((long) ((total - done) / rate))
                    : "";
            log.accept("            %3d%%  %s / %s  at %.1f MB/s%s"
                    .formatted(percent, human(done), human(total), rate / 1e6, eta));
        }
    }
}
