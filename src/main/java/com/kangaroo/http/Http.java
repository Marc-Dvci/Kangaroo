package com.kangaroo.http;

import com.kangaroo.util.Json;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Small helpers over {@code jdk.httpserver}: reading a request, writing a response, and the
 * security headers every response carries.
 *
 * <p>{@code jdk.httpserver} is a deliberately minimal server and this is deliberately a minimal
 * layer over it. Kangaroo has no servlet container, no framework and no dependencies, because the
 * whole product is one process with one artifact and adding a web stack would undo that for the
 * sake of routing a dozen endpoints.
 */
public final class Http {

    private Http() {}

    /** Requests larger than this are refused rather than buffered. Images arrive base64 in JSON. */
    public static final int MAX_BODY_BYTES = 24 * 1024 * 1024;

    public static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new IOException("request body exceeds " + MAX_BODY_BYTES + " bytes");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    public static Json.Obj readJson(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        return body.isBlank() ? Json.obj().build() : Json.parseObject(body);
    }

    public static void json(HttpExchange exchange, int status, Json.Obj payload) throws IOException {
        send(exchange, status, "application/json; charset=utf-8",
                payload.write().getBytes(StandardCharsets.UTF_8));
    }

    public static void json(HttpExchange exchange, int status, Json payload) throws IOException {
        send(exchange, status, "application/json; charset=utf-8",
                payload.write().getBytes(StandardCharsets.UTF_8));
    }

    public static void text(HttpExchange exchange, int status, String body) throws IOException {
        send(exchange, status, "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    public static void error(HttpExchange exchange, int status, String message) throws IOException {
        json(exchange, status, Json.obj().put("error", message).put("status", status).build());
    }

    public static void send(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);

        // The interface is served from the JAR and talks only to this origin, so the strictest
        // possible policy costs nothing and removes an entire class of risk from a clinical tool
        // that renders model output.
        headers.set("Content-Security-Policy",
                "default-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; "
                        + "script-src 'self'; style-src 'self' 'unsafe-inline'; "
                        + "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Permissions-Policy", "camera=(self), microphone=(self), geolocation=()");
        headers.set("Cache-Control", "no-store");

        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } else {
            exchange.close();
        }
    }

    /** Parse a query string into a map. Repeated keys keep the first value. */
    public static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> out = new HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            out.putIfAbsent(decode(key), decode(value));
        }
        return out;
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    /** The last path segment, e.g. the tool name in {@code /api/tools/calculate_zscore}. */
    public static String lastSegment(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    public static boolean isMethod(HttpExchange exchange, String method) {
        return method.equalsIgnoreCase(exchange.getRequestMethod());
    }
}
