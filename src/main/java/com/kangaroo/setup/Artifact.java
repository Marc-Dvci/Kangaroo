package com.kangaroo.setup;

import java.net.URI;
import java.nio.file.Path;

/**
 * One pinned, verifiable download.
 *
 * <p>Everything Kangaroo fetches is pinned twice over: to an immutable revision, so the URL cannot
 * start serving different bytes tomorrow, and to a SHA-256, so it is detectable if it does. A
 * clinical tool that pulls "whatever is at latest" from the internet on first run is a clinical
 * tool whose behaviour nobody can reproduce after the fact.
 *
 * @param name        what to call it in the progress output
 * @param uri         the pinned download location
 * @param target      where it lands, relative to the repository root
 * @param sha256      the expected digest, lower-case hex
 * @param sizeBytes   the expected size, used for progress and as a cheap early mismatch check
 * @param kind        what to do with it once it has arrived
 */
public record Artifact(String name, URI uri, Path target, String sha256, long sizeBytes, Kind kind) {

    /** What happens to the file after it is verified. */
    public enum Kind {
        /** Left where it landed. Model weights. */
        FILE,
        /** Unpacked into its target directory, then the archive is deleted. */
        ZIP,
        /** Same, for a gzipped tarball. */
        TAR_GZ
    }

    public Artifact {
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be 64 lower-case hex characters: " + sha256);
        }
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("size must be positive: " + sizeBytes);
        }
    }

    /** Human-readable size, for the plan the user is asked to approve. */
    public String humanSize() {
        double mb = sizeBytes / 1_000_000.0;
        return mb >= 1000 ? "%.1f GB".formatted(mb / 1000) : "%.0f MB".formatted(mb);
    }

    /** Where an interrupted download parks its bytes so the next run can resume from them. */
    public Path partFile() {
        return target.resolveSibling(target.getFileName() + ".part");
    }
}
