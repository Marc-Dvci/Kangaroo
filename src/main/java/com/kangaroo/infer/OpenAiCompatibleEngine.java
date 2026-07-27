package com.kangaroo.infer;

import com.kangaroo.audit.ClinicalEvents;
import com.kangaroo.core.Rung;
import com.kangaroo.util.Json;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpOption;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The network rungs, over HTTP/3 where it is available and degrading honestly where it is not.
 *
 * <h2>Why HTTP/3 is load-bearing here rather than decorative</h2>
 * The link this engine has to survive is a rural mobile connection: high loss, high latency, and a
 * NAT that rebinds when the handset moves between cells. TCP handles all three badly. A single lost
 * packet head-of-line-blocks an entire HTTP/2 connection; a NAT rebinding or a cell handover kills
 * the connection outright and restarts the upload from nothing. QUIC's per-stream loss recovery and
 * connection migration are the specific answers to those specific problems, which is why an
 * encounter uploaded from a moving vehicle survives a handover here and did not before.
 *
 * <p>{@link HttpOption#H3_DISCOVERY} with {@code ALT_SVC} is what makes the first request work
 * against an endpoint that may or may not speak HTTP/3: the client tries HTTP/2, reads the
 * {@code Alt-Svc} header, and upgrades subsequent requests. The negotiated version of every request
 * is recorded and reported, so the badge in the interface says what actually happened rather than
 * what was configured.
 *
 * <h2>Privacy</h2>
 * An encounter flagged local-only never reaches this class — {@link FailoverEngine} skips the
 * network rungs entirely rather than relying on this engine to refuse. The API key is never logged,
 * never serialised, and redacted from every flight-recorder event.
 */
public final class OpenAiCompatibleEngine implements InferenceEngine {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);

    private final Provider provider;
    private final HttpClient http3;
    private final HttpClient http2;
    private final HttpClient http11;

    /** The version actually negotiated on the last successful call. */
    private final AtomicReference<HttpClient.Version> lastNegotiated = new AtomicReference<>();

    public OpenAiCompatibleEngine(Provider provider) {
        this.provider = provider;
        this.http3 = client(HttpClient.Version.HTTP_3);
        this.http2 = client(HttpClient.Version.HTTP_2);
        this.http11 = client(HttpClient.Version.HTTP_1_1);
    }

    private static HttpClient client(HttpClient.Version version) {
        return HttpClient.newBuilder()
                .version(version)
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public Rung rung() {
        if (provider.isLocal()) return Rung.LOCAL_SERVER;
        return switch (lastNegotiated.get()) {
            case null -> Rung.CLOUD_HTTP3;
            case HTTP_3 -> Rung.CLOUD_HTTP3;
            case HTTP_2 -> Rung.CLOUD_HTTP2;
            case HTTP_1_1 -> Rung.CLOUD_HTTP1;
        };
    }

    @Override
    public boolean available() {
        // Configured is as far as we can check without a round trip. A dead endpoint surfaces as a
        // failure on the attempt, which the ladder handles by descending.
        return provider.isLocal() || !provider.apiKey().isBlank();
    }

    @Override
    public String describe() {
        HttpClient.Version v = lastNegotiated.get();
        String transport = v == null ? "not yet contacted" : switch (v) {
            case HTTP_3 -> "HTTP/3 over QUIC";
            case HTTP_2 -> "HTTP/2";
            case HTTP_1_1 -> "HTTP/1.1";
        };
        return provider.displayName() + " (" + provider.model() + ", " + transport + ")";
    }

    @Override
    public Narrative explain(Request request) throws Exception {
        String system = Prompts.systemFor(request.encounter().mode(), request.locale());
        String user = Prompts.assessmentRequest(request.encounter(), request.profile(),
                request.ruleLight(), request.signs());

        long t0 = System.nanoTime();
        HttpResponse<String> response = send(body(system, user));
        long elapsed = (System.nanoTime() - t0) / 1_000_000;

        if (response.statusCode() / 100 != 2) {
            throw new IOException(provider.displayName() + " returned HTTP " + response.statusCode()
                    + ": " + truncate(response.body()));
        }

        String content = extractContent(response.body());
        Rung servedBy = rung();

        return Prompts.parse(content)
                .map(p -> new Narrative(p.light(), p.reasoning(), p.actionPlan(), p.observations(),
                        servedBy, content, elapsed))
                .orElseGet(() -> new Narrative(Optional.empty(), content, "", List.of(),
                        servedBy, content, elapsed));
    }

    /**
     * Try HTTP/3, then HTTP/2, then HTTP/1.1.
     *
     * <p>The descent is per-request rather than sticky, because the reason HTTP/3 failed is usually
     * the network rather than the endpoint, and a network that was blocking UDP a minute ago may
     * not be now. Each transition is a flight-recorder event, so the ladder's behaviour on a real
     * link is measurable after the fact rather than anecdotal.
     */
    private HttpResponse<String> send(Json.Obj payload) throws Exception {
        List<Attempt> attempts = provider.isLocal()
                // A server on this LAN is not going to be speaking QUIC, and trying costs a
                // timeout on every single request.
                ? List.of(new Attempt(http11, HttpClient.Version.HTTP_1_1))
                : List.of(new Attempt(http3, HttpClient.Version.HTTP_3),
                          new Attempt(http2, HttpClient.Version.HTTP_2),
                          new Attempt(http11, HttpClient.Version.HTTP_1_1));

        Exception last = null;
        for (int i = 0; i < attempts.size(); i++) {
            Attempt attempt = attempts.get(i);
            long t0 = System.nanoTime();
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(provider.endpoint())
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .version(attempt.version())
                        .POST(HttpRequest.BodyPublishers.ofString(payload.write()));

                if (attempt.version() == HttpClient.Version.HTTP_3) {
                    // Let the endpoint advertise HTTP/3 via Alt-Svc rather than demanding it.
                    builder.setOption(HttpOption.H3_DISCOVERY, HttpOption.Http3DiscoveryMode.ALT_SVC);
                }
                authenticate(builder);

                HttpResponse<String> response =
                        attempt.client().send(builder.build(), HttpResponse.BodyHandlers.ofString());

                HttpClient.Version negotiated = response.version();
                HttpClient.Version previous = lastNegotiated.getAndSet(negotiated);
                if (previous != null && previous != negotiated) {
                    ClinicalEvents.failover(previous.name(), negotiated.name(),
                            "transport renegotiated", negotiated.name(),
                            (System.nanoTime() - t0) / 1_000_000);
                }
                return response;

            } catch (IOException | InterruptedException e) {
                last = e;
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                if (i + 1 < attempts.size()) {
                    ClinicalEvents.failover(attempt.version().name(), attempts.get(i + 1).version().name(),
                            e.getClass().getSimpleName() + ": " + e.getMessage(),
                            attempt.version().name(), (System.nanoTime() - t0) / 1_000_000);
                }
            }
        }
        throw last != null ? last : new IOException("no transport succeeded");
    }

    private record Attempt(HttpClient client, HttpClient.Version version) {}

    private void authenticate(HttpRequest.Builder builder) {
        if (provider.apiKey().isBlank()) return;
        switch (provider.shape()) {
            case ANTHROPIC -> builder
                    .header("x-api-key", provider.apiKey())
                    .header("anthropic-version", "2023-06-01");
            case OPENAI -> builder.header("Authorization", "Bearer " + provider.apiKey());
        }
    }

    private Json.Obj body(String system, String user) {
        return switch (provider.shape()) {
            case OPENAI -> Json.obj()
                    .put("model", provider.model())
                    .put("messages", List.of(
                            Json.obj().put("role", "system").put("content", system).build(),
                            Json.obj().put("role", "user").put("content", user).build()))
                    .put("temperature", 0)
                    .put("max_tokens", 900)
                    .put("stream", false)
                    .build();

            case ANTHROPIC -> Json.obj()
                    .put("model", provider.model())
                    .put("system", system)
                    .put("messages", List.of(
                            Json.obj().put("role", "user").put("content", user).build()))
                    .put("temperature", 0)
                    .put("max_tokens", 900)
                    .build();
        };
    }

    /** Pull the assistant text out of whichever response shape came back. */
    String extractContent(String responseBody) {
        Json.Obj root = Json.parseObject(responseBody);

        return switch (provider.shape()) {
            case OPENAI -> root.array("choices").stream()
                    .findFirst()
                    .flatMap(Json::asObj)
                    .flatMap(choice -> choice.obj("message"))
                    .map(m -> m.str("content", ""))
                    .orElse("");

            case ANTHROPIC -> root.array("content").stream()
                    .flatMap(j -> j.asObj().stream())
                    .filter(o -> "text".equals(o.str("type", "text")))
                    .map(o -> o.str("text", ""))
                    .reduce("", String::concat);
        };
    }

    /** The transport actually negotiated last, for the honest badge and the diagnostics screen. */
    public Optional<HttpClient.Version> negotiatedVersion() {
        return Optional.ofNullable(lastNegotiated.get());
    }

    public Provider provider() {
        return provider;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }

    @Override
    public void close() {
        http3.close();
        http2.close();
        http11.close();
    }
}
