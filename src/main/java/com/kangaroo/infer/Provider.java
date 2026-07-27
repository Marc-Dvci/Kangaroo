package com.kangaroo.infer;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A model provider, configured by pasting an API key into one field.
 *
 * <p>The prior art here is an app that asks a health programme to create a cloud account, provision
 * a project, generate service credentials and configure a region. That is a week of somebody's time
 * and a vendor lock-in, in exchange for a fallback path. Kangaroo asks for a key, works out who
 * issued it from its shape, and offers the models that key can reach.
 *
 * <p>{@link #LOCAL_SERVER} is deliberately first-class rather than a debug affordance. "My laptop
 * in the next room is the cloud" is a real deployment: a clinic with a mains socket and a Wi-Fi
 * router can serve a much larger model to a dozen phones than any of those phones could run, with
 * no internet connection and no data leaving the building. Anything speaking the OpenAI-compatible
 * chat completions shape works — llama.cpp's server, Ollama, LM Studio, text-generation-webui, vLLM.
 */
public record Provider(
        String id,
        String displayName,
        URI baseUrl,
        String apiKey,
        String model,
        Shape shape) {

    /** Which request and response shape the endpoint speaks. */
    public enum Shape {
        /** {@code POST /chat/completions} with {@code messages}. The de facto standard. */
        OPENAI,
        /** {@code POST /messages} with a top-level {@code system} field. */
        ANTHROPIC
    }

    public Provider {
        if (baseUrl == null) throw new IllegalArgumentException("baseUrl is required");
        if (apiKey == null) apiKey = "";
        if (model == null || model.isBlank()) model = "";
    }

    /** A local OpenAI-compatible server, needing no key at all. */
    public static Provider localServer(URI baseUrl, String model) {
        return new Provider("local", "Local model server", baseUrl, "", model, Shape.OPENAI);
    }

    /**
     * Work out the provider from the key's own shape.
     *
     * <p>Every major issuer uses a distinctive prefix, so a user can paste a key and be done rather
     * than being asked which vendor issued the key they are holding.
     */
    public static Optional<Provider> detect(String apiKey, String model) {
        if (apiKey == null || apiKey.isBlank()) return Optional.empty();
        String k = apiKey.strip();

        if (k.startsWith("sk-ant-")) {
            return Optional.of(new Provider("anthropic", "Anthropic",
                    URI.create("https://api.anthropic.com/v1"), k,
                    orDefault(model, "claude-sonnet-4-5"), Shape.ANTHROPIC));
        }
        if (k.startsWith("sk-or-")) {
            return Optional.of(new Provider("openrouter", "OpenRouter",
                    URI.create("https://openrouter.ai/api/v1"), k,
                    orDefault(model, "openai/gpt-4o-mini"), Shape.OPENAI));
        }
        if (k.startsWith("sk-")) {
            return Optional.of(new Provider("openai", "OpenAI",
                    URI.create("https://api.openai.com/v1"), k,
                    orDefault(model, "gpt-4o-mini"), Shape.OPENAI));
        }
        if (k.startsWith("AIza")) {
            // Gemini exposes an OpenAI-compatible surface, so one code path covers it.
            return Optional.of(new Provider("google", "Google Gemini",
                    URI.create("https://generativelanguage.googleapis.com/v1beta/openai"), k,
                    orDefault(model, "gemini-2.0-flash"), Shape.OPENAI));
        }
        return Optional.empty();
    }

    private static String orDefault(String model, String fallback) {
        return model == null || model.isBlank() ? fallback : model;
    }

    /** True when the endpoint is on this machine or this LAN, so no data leaves the premises. */
    public boolean isLocal() {
        String host = baseUrl.getHost();
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost")
                || h.equals("127.0.0.1")
                || h.equals("::1")
                || h.startsWith("192.168.")
                || h.startsWith("10.")
                || h.endsWith(".local")
                || h.matches("172\\.(1[6-9]|2\\d|3[01])\\..*");
    }

    /** The endpoint to POST to. */
    public URI endpoint() {
        String base = baseUrl.toString().replaceAll("/+$", "");
        return URI.create(base + (shape == Shape.ANTHROPIC ? "/messages" : "/chat/completions"));
    }

    public Provider withModel(String m) {
        return new Provider(id, displayName, baseUrl, apiKey, m, shape);
    }

    /** Never log or serialise the key. This is what {@code toString} exists to prevent. */
    @Override
    public String toString() {
        return "Provider[" + id + ", " + baseUrl + ", model=" + model
                + ", key=" + (apiKey.isBlank() ? "none" : "***redacted***") + "]";
    }

    /** The providers offered in the settings screen, for the "paste a key" hint text. */
    public static List<String> known() {
        return List.of("OpenAI (sk-...)", "Anthropic (sk-ant-...)", "OpenRouter (sk-or-...)",
                "Google Gemini (AIza...)", "Any local OpenAI-compatible server (no key)");
    }
}
