package com.kangaroo.http;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;

/**
 * Serves the web client straight out of the JAR.
 *
 * <p>No Node, no npm, no bundler, no {@code node_modules}. The client is hand-written HTML, CSS and
 * JavaScript that a browser can run as-is, which means building this project requires a JDK and
 * nothing else, and it means the client cannot drift from the server it ships inside.
 *
 * <p>Path traversal is the one thing a static file handler must not get wrong, so the resolution
 * below normalises first and then refuses anything that is not a plain relative path under the
 * bundled web root.
 */
final class StaticFiles {

    private static final String ROOT = "/web";
    private static final String INDEX = "index.html";

    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("webmanifest", "application/manifest+json; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("pdf", "application/pdf"));

    void serve(HttpExchange exchange, String requestPath) throws IOException {
        String resource = resolve(requestPath);
        if (resource == null) {
            Http.error(exchange, 400, "invalid path");
            return;
        }

        byte[] body = read(resource);
        if (body == null) {
            // A single-page app: unknown paths that are not file requests fall back to the shell so
            // that a deep link opened from a home-screen icon still works offline.
            if (!requestPath.contains(".")) {
                body = read(ROOT + "/" + INDEX);
            }
            if (body == null) {
                Http.error(exchange, 404, "not found: " + requestPath);
                return;
            }
            resource = INDEX;
        }

        Http.send(exchange, 200, contentType(resource), body);
    }

    /** Normalise and reject anything that escapes the bundled web root. */
    static String resolve(String requestPath) {
        String path = requestPath == null || requestPath.isBlank() ? "/" : requestPath;
        if (path.endsWith("/")) path = path + INDEX;
        if (!path.startsWith("/")) path = "/" + path;

        // Reject before normalising as well as after: a normalised "/../x" is "/x", which would
        // otherwise look innocent by the time it is checked.
        if (path.contains("..") || path.contains("\\") || path.contains("\0")) return null;

        String normalised = java.net.URI.create("http://x" + path).normalize().getPath();
        if (normalised == null || !normalised.startsWith("/") || normalised.contains("..")) return null;

        return ROOT + normalised;
    }

    private static byte[] read(String resource) throws IOException {
        try (InputStream in = StaticFiles.class.getResourceAsStream(resource)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    static String contentType(String resource) {
        int dot = resource.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        String ext = resource.substring(dot + 1).toLowerCase(Locale.ROOT);
        return CONTENT_TYPES.getOrDefault(ext, "application/octet-stream");
    }
}
