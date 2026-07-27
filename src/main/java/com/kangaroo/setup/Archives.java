package com.kangaroo.setup;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Unpacks the llama.cpp release archives.
 *
 * <p>Two formats, because that is what the upstream project publishes: a zip for Windows and a
 * gzipped tar everywhere else. The JDK reads zip and gzip; it has no tar reader, so there is a
 * small one here. Tar is a sequence of 512-byte headers each followed by its content padded to 512
 * bytes, which is about sixty lines to read and is a better trade than adding a dependency to a
 * project whose entire claim is that it has none.
 *
 * <h2>Path traversal</h2>
 * Every entry is resolved against the destination and checked to still be inside it. An archive
 * containing {@code ../../../.ssh/authorized_keys} is a real and old attack, and the fact that
 * these archives come from a reputable project is not the same as having checked. This is a
 * clinical tool that unpacks a download onto a health worker's laptop; it verifies.
 */
public final class Archives {

    private Archives() {}

    /** Unpack into {@code destination}, flattening the archive's own top-level directory. */
    public static List<Path> extract(Path archive, Artifact.Kind kind, Path destination)
            throws IOException {

        Files.createDirectories(destination);
        return switch (kind) {
            case ZIP -> unzip(archive, destination);
            case TAR_GZ -> untar(archive, destination);
            case FILE -> List.of(archive);
        };
    }

    private static List<Path> unzip(Path archive, Path destination) throws IOException {
        List<Path> written = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                Path out = safeResolve(destination, flatten(entry.getName()));
                if (out == null) continue;
                Files.createDirectories(out.getParent());
                Files.copy(zip, out, StandardCopyOption.REPLACE_EXISTING);
                written.add(out);
            }
        }
        return written;
    }

    private static List<Path> untar(Path archive, Path destination) throws IOException {
        List<Path> written = new ArrayList<>();
        try (InputStream in = new GZIPInputStream(Files.newInputStream(archive), 1 << 16)) {
            byte[] header = new byte[512];
            while (true) {
                if (!readFully(in, header)) break;

                // Two consecutive zero blocks mark the end of the archive; one is enough to stop on.
                if (isAllZero(header)) break;

                String name = cString(header, 0, 100);
                long size = octal(header, 124, 12);
                char type = (char) header[156];

                // '0' and NUL are regular files. Directories, symlinks and the long-name extensions
                // are skipped: the release tarballs are a flat set of binaries, and honouring a
                // symlink out of an archive is the same traversal problem as a "../" path.
                boolean regular = type == '0' || type == '\0';

                if (regular && !name.isEmpty()) {
                    Path out = safeResolve(destination, flatten(name));
                    if (out != null) {
                        Files.createDirectories(out.getParent());
                        copyExactly(in, out, size);
                        written.add(out);
                    } else {
                        skip(in, size);
                    }
                } else {
                    skip(in, size);
                }

                // Content is padded to a 512-byte boundary.
                long padding = (512 - (size % 512)) % 512;
                skip(in, padding);
            }
        }
        return written;
    }

    /**
     * Drop the archive's own top-level directory, so {@code build/bin/libllama.so} lands as
     * {@code libllama.so} in {@code runtime/bin} rather than three directories down where
     * {@code NativeRuntime} would not find it.
     */
    private static String flatten(String entryName) {
        String normalised = entryName.replace('\\', '/');
        int slash = normalised.lastIndexOf('/');
        return slash < 0 ? normalised : normalised.substring(slash + 1);
    }

    /**
     * Resolve a name inside the destination, or {@code null} if it would escape.
     *
     * <p>Returns null rather than throwing because a single odd entry in an otherwise good archive
     * should be skipped and reported, not abort an unpack that is most of the way done.
     */
    static Path safeResolve(Path destination, String name) {
        if (name.isEmpty() || name.equals(".") || name.equals("..")) return null;
        Path base = destination.toAbsolutePath().normalize();
        Path resolved = base.resolve(name).normalize();
        return resolved.startsWith(base) ? resolved : null;
    }

    private static void copyExactly(InputStream in, Path out, long size) throws IOException {
        try (var os = Files.newOutputStream(out)) {
            byte[] buffer = new byte[1 << 16];
            long remaining = size;
            while (remaining > 0) {
                int want = (int) Math.min(buffer.length, remaining);
                int n = in.read(buffer, 0, want);
                if (n < 0) throw new EOFException("truncated tar entry: " + out.getFileName());
                os.write(buffer, 0, n);
                remaining -= n;
            }
        }
    }

    private static void skip(InputStream in, long n) throws IOException {
        long remaining = n;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) return;
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }

    private static boolean readFully(InputStream in, byte[] block) throws IOException {
        int off = 0;
        while (off < block.length) {
            int n = in.read(block, off, block.length - off);
            if (n < 0) return off != 0 ? throwTruncated() : false;
            off += n;
        }
        return true;
    }

    private static boolean throwTruncated() throws IOException {
        throw new EOFException("truncated tar header");
    }

    private static boolean isAllZero(byte[] block) {
        for (byte b : block) {
            if (b != 0) return false;
        }
        return true;
    }

    /** A NUL-terminated fixed-width field. */
    private static String cString(byte[] block, int offset, int length) {
        int end = offset;
        int limit = offset + length;
        while (end < limit && block[end] != 0) end++;
        return new String(block, offset, end - offset, StandardCharsets.UTF_8).trim();
    }

    /** Tar stores sizes as NUL- or space-terminated octal text. */
    private static long octal(byte[] block, int offset, int length) {
        long value = 0;
        for (int i = offset; i < offset + length; i++) {
            byte b = block[i];
            if (b == 0 || b == ' ') {
                if (value != 0) break;
                continue;
            }
            if (b < '0' || b > '7') break;
            value = value * 8 + (b - '0');
        }
        return value;
    }
}
