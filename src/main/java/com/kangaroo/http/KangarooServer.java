package com.kangaroo.http;

import com.kangaroo.audit.Jeps;
import com.kangaroo.clinical.ClinicalTools;
import com.kangaroo.clinical.Referral;
import com.kangaroo.color.Bench;
import com.kangaroo.core.Assessment;
import com.kangaroo.core.Capture;
import com.kangaroo.core.Encounter;
import com.kangaroo.core.EncounterId;
import com.kangaroo.core.Mode;
import com.kangaroo.core.Sex;
import com.kangaroo.core.Subject;
import com.kangaroo.core.Vitals;
import com.kangaroo.crypto.DeviceIdentity;
import com.kangaroo.ffm.NativeRuntime;
import com.kangaroo.i18n.Messages;
import com.kangaroo.ml.Models;
import com.kangaroo.orchestrate.AssessmentOrchestrator;
import com.kangaroo.store.EncounterStore;
import com.kangaroo.store.PatientMemory;
import com.kangaroo.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

/**
 * The whole server surface: the installable web client, the API, and the console.
 *
 * <p>{@code jdk.httpserver} with a virtual-thread-per-request executor, and nothing else. There is
 * no framework here and that is the design, not a shortcut. A request that spends eight seconds
 * inside a language model is a request that occupies a thread for eight seconds; with platform
 * threads a Pod serving a dozen phones would need a carefully tuned pool and a queue, and with
 * virtual threads it needs neither — each request gets its own thread, blocks as much as it likes,
 * and costs a few hundred bytes while it waits.
 *
 * <p>The client is a progressive web app served straight out of the JAR. No Node, no npm, no
 * bundler, no second toolchain: building this project requires a JDK and nothing else, and
 * installing it on a phone requires opening a URL.
 */
public final class KangarooServer implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger("kangaroo.http");

    private final HttpServer server;
    private final AssessmentOrchestrator orchestrator;
    private final EncounterStore store;
    private final PatientMemory memory;
    private final DeviceIdentity identity;
    private final StaticFiles statics = new StaticFiles();
    private final int port;

    public KangarooServer(int port, String bindAddress, AssessmentOrchestrator orchestrator,
                          EncounterStore store, PatientMemory memory, DeviceIdentity identity)
            throws IOException {
        this.orchestrator = orchestrator;
        this.store = store;
        this.memory = memory;
        this.identity = identity;

        this.server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        this.port = server.getAddress().getPort();

        // One virtual thread per request. The blocking style below is the point: an assessment that
        // waits on a model reads as straight-line code and costs almost nothing while it waits.
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        route("/", this::serveStatic);
        route("/api/status", this::status);
        route("/api/assess", this::assess);
        route("/api/tools", this::tools);
        route("/api/bench", this::bench);
        route("/api/i18n", this::i18n);
        route("/api/encounters", this::encounters);
        route("/api/triage", this::triage);
        route("/api/identity", this::deviceIdentity);
        route("/api/jeps", this::jeps);
    }

    /**
     * A handler that may throw anything. {@link HttpHandler} only permits {@link IOException}, which
     * would force every handler here to wrap the exceptions it genuinely can throw — an assessment
     * can be interrupted, a tool can reject its arguments — into an IOException that says nothing.
     * Catching them centrally in {@link #route} and mapping each to the right status code is both
     * shorter and produces better errors.
     */
    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws Exception;
    }

    private void route(String path, Handler handler) {
        server.createContext(path, exchange -> {
            long t0 = System.nanoTime();
            try {
                handler.handle(exchange);
            } catch (Json.JsonException e) {
                Http.error(exchange, 400, "malformed JSON: " + e.getMessage());
            } catch (IllegalArgumentException e) {
                Http.error(exchange, 400, e.getMessage() == null ? "bad request" : e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Http.error(exchange, 503, "assessment was interrupted");
            } catch (Exception e) {
                LOG.log(System.Logger.Level.ERROR, "unhandled error on " + path, e);
                Http.error(exchange, 500, "internal error");
            } finally {
                LOG.log(System.Logger.Level.TRACE, () -> exchange.getRequestMethod() + " " + path
                        + " in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
            }
        });
    }

    public KangarooServer start() {
        server.start();
        return this;
    }

    public int port() {
        return port;
    }

    @Override
    public void close() {
        server.stop(1);
    }

    // ------------------------------------------------------------------ handlers

    private void serveStatic(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.startsWith("/api/")) {
            Http.error(exchange, 404, "no such endpoint: " + path);
            return;
        }
        statics.serve(exchange, path);
    }

    /**
     * Everything a client needs to render honestly: which rung would serve, what is loaded, what is
     * missing and why.
     */
    private void status(HttpExchange exchange) throws IOException {
        List<Json> rungs = new ArrayList<>();
        // Ladder status comes from the orchestrator so the badge reflects what would actually
        // happen rather than what is configured.
        orchestrator.preferredRung().ifPresent(r -> rungs.add(Json.obj()
                .put("preferred", r.name())
                .put("label", r.label())
                .put("offline", r.offline())
                .build()));

        Http.json(exchange, 200, Json.obj()
                .put("product", "Kangaroo")
                .put("version", Jeps.VERSION)
                .put("java", Runtime.version().toString())
                .put("preferred_rung", orchestrator.preferredRung().map(Enum::name).orElse("DETERMINISTIC"))
                .put("rungs", rungs)
                .put("native_available", NativeRuntime.available())
                .put("native_reason", NativeRuntime.unavailableReason())
                .put("vision_available", NativeRuntime.visionAvailable())
                .put("clinical_model", Models.clinical().toString())
                .put("jaundice_model", Models.jaundice().toString())
                .put("encounters_stored", store.count())
                .put("subjects_followed", memory.subjectCount())
                .put("device_fingerprint", identity.fingerprint())
                .put("languages", Json.arr(Messages.SUPPORTED.stream()
                        .map(l -> (Json) Json.obj()
                                .put("tag", l.tag())
                                .put("name", l.englishName())
                                .put("endonym", l.endonym())
                                .put("rtl", l.rightToLeft())
                                .build())
                        .toList()))
                .build());
    }

    /** Run a full assessment. The one endpoint that matters. */
    private void assess(HttpExchange exchange) throws Exception {
        if (!Http.isMethod(exchange, "POST")) {
            Http.error(exchange, 405, "POST an encounter to this endpoint");
            return;
        }

        Json.Obj body = Http.readJson(exchange);
        Encounter encounter = parseEncounter(body);

        Assessment assessment = orchestrator.assess(encounter);
        store.save(encounter, assessment);

        Http.json(exchange, 200, toJson(encounter, assessment));
    }

    /** The five deterministic WHO tools, as an API and as OpenAI-shaped definitions. */
    private void tools(HttpExchange exchange) throws IOException {
        String name = Http.lastSegment(exchange);

        if ("tools".equals(name)) {
            Http.json(exchange, 200, Json.obj()
                    .put("tools", ClinicalTools.definitions())
                    .build());
            return;
        }
        if (!Http.isMethod(exchange, "POST")) {
            Http.error(exchange, 405, "POST arguments to invoke a tool");
            return;
        }
        Http.json(exchange, 200, ClinicalTools.invoke(name, Http.readJson(exchange)));
    }

    /** Measure the vectorised colorimetry pipeline against the scalar one, here, now. */
    private void bench(HttpExchange exchange) throws IOException {
        int size = Integer.parseInt(Http.query(exchange).getOrDefault("size", "512"));
        size = Math.max(64, Math.min(2048, size));

        Bench.Comparison c = Bench.run(size);
        Http.json(exchange, 200, Json.obj()
                .put("frame", c.width() + "x" + c.height())
                .put("vector_available", c.vectorAvailable())
                .put("vector_description", c.vectorDescription())
                .put("scalar", Json.obj()
                        .put("pipeline", c.scalar().pipeline())
                        .put("ms_per_frame", round(c.scalar().millisPerFrame()))
                        .put("frames_per_second", round(c.scalar().framesPerSec()))
                        .build())
                .put("vector", Json.obj()
                        .put("pipeline", c.vector().pipeline())
                        .put("ms_per_frame", round(c.vector().millisPerFrame()))
                        .put("frames_per_second", round(c.vector().framesPerSec()))
                        .build())
                .put("speedup", round(c.speedup()))
                .put("kernels", Json.obj()
                        .put("scalar_ms", round(c.scalarKernels().millisPerFrame()))
                        .put("vector_ms", round(c.vectorKernels().millisPerFrame()))
                        .put("speedup", round(c.kernelSpeedup()))
                        .build())
                .put("note", "Plain timing harness, best of 7 batches after warm-up. Not JMH. "
                        + "The kernel figure is the elementwise work SIMD applies to; the "
                        + "end-to-end figure also includes the percentile sort, which does not "
                        + "vectorise and dominates the remainder.")
                .build());
    }

    private void i18n(HttpExchange exchange) throws IOException {
        Locale locale = Messages.parse(Http.lastSegment(exchange));
        var builder = Json.obj()
                .put("locale", locale.toLanguageTag())
                .put("rtl", Messages.rightToLeft(locale));
        var catalogue = Json.obj();
        Messages.catalogue(locale).forEach(catalogue::put);
        Http.json(exchange, 200, builder.put("strings", catalogue.build()).build());
    }

    private void encounters(HttpExchange exchange) throws IOException {
        String tail = Http.lastSegment(exchange);

        if (!"encounters".equals(tail)) {
            var record = store.get(EncounterId.of(tail));
            if (record.isEmpty()) {
                Http.error(exchange, 404, "no such encounter");
                return;
            }
            Http.json(exchange, 200, Json.obj()
                    .put("record", record.get().payload())
                    .put("signature", record.get().signature())
                    .put("sync_state", record.get().syncState().name())
                    .build());
            return;
        }

        Http.json(exchange, 200, Json.obj()
                .put("count", store.count())
                .put("encounters", Json.arr(store.all().stream()
                        .limit(200)
                        .map(r -> (Json) Json.obj()
                                .put("id", r.id().value())
                                .put("captured_at", r.capturedAt().toString())
                                .put("light", r.light().name())
                                .put("classification", r.classification())
                                .put("mode", r.mode())
                                .put("supervisor_review", r.supervisorReview())
                                .put("sync_state", r.syncState().name())
                                .build())
                        .toList()))
                .build());
    }

    private void triage(HttpExchange exchange) throws IOException {
        var counts = Json.obj();
        store.countsByLight().forEach((light, n) -> counts.put(light.name(), n));

        Http.json(exchange, 200, Json.obj()
                .put("counts", counts.build())
                .put("pending_sync", store.pendingSync().size())
                .put("queue", Json.arr(store.triageQueue().stream()
                        .map(r -> (Json) Json.obj()
                                .put("id", r.id().value())
                                .put("captured_at", r.capturedAt().toString())
                                .put("light", r.light().name())
                                .put("classification", r.classification())
                                .put("supervisor_review", r.supervisorReview())
                                .build())
                        .toList()))
                .build());
    }

    /** The device's public key, for enrolment with a supervisor. Small enough for a QR code. */
    private void deviceIdentity(HttpExchange exchange) throws IOException {
        Http.json(exchange, 200, Json.obj()
                .put("fingerprint", identity.fingerprint())
                .put("public_key_pem", identity.publicKeyPem())
                .put("algorithm", "Ed25519")
                .put("note", "Enrol this key with a supervisor. Every encounter this device "
                        + "produces is signed with the matching private key, which never leaves it.")
                .build());
    }

    /** Where each JDK 26 JEP is used, served from the running application. */
    private void jeps(HttpExchange exchange) throws IOException {
        Http.json(exchange, 200, Jeps.asJson());
    }

    // ------------------------------------------------------------------ parsing and rendering

    private Encounter parseEncounter(Json.Obj body) {
        Subject subject = new Subject(
                body.intAt("age_days", Subject.UNKNOWN_AGE),
                body.field("weight_kg").flatMap(Json::asDouble).orElse(Subject.UNKNOWN_WEIGHT),
                Sex.parse(body.str("sex", "male")),
                body.bool("preterm", false));

        Vitals vitals = new Vitals(
                body.intAt("respiratory_rate", Vitals.ABSENT_INT),
                body.field("temperature_c").flatMap(Json::asDouble).orElse(Vitals.ABSENT_DOUBLE),
                body.intAt("spo2", Vitals.ABSENT_INT),
                body.intAt("heart_rate", Vitals.ABSENT_INT));

        List<Capture> captures = new ArrayList<>();
        for (Json c : body.array("captures")) {
            c.asObj().ifPresent(o -> {
                String data = o.str("data", "");
                if (data.isBlank()) return;
                // Browsers send data: URLs; strip the prefix rather than making the client do it.
                int comma = data.indexOf(',');
                String base64 = data.startsWith("data:") && comma > 0 ? data.substring(comma + 1) : data;
                try {
                    captures.add(new Capture(
                            Capture.Kind.parse(o.str("kind", "FACE")),
                            o.str("media_type", "image/jpeg"),
                            Base64.getDecoder().decode(base64)));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("capture '" + o.str("kind", "?")
                            + "' was not valid base64");
                }
            });
        }

        return new Encounter(
                EncounterId.random(),
                body.str("subject_ref", ""),
                subject,
                java.time.Instant.now(),
                Mode.parse(body.str("mode", "chw")),
                captures,
                vitals,
                body.str("intake_text", ""),
                body.str("locale", "en"),
                body.bool("privacy_local", false));
    }

    private Json.Obj toJson(Encounter encounter, Assessment assessment) {
        List<Json> signs = assessment.signs().stream()
                .map(s -> (Json) Json.obj()
                        .put("sign", s.sign().name())
                        .put("label", s.sign().label())
                        .put("provenance", s.provenance())
                        .put("red", s.sign().red())
                        .build())
                .toList();

        var builder = Json.obj()
                .put("id", assessment.encounterId().value())
                .put("light", assessment.light().name())
                .put("headline", assessment.headline(encounter.mode()))
                .put("classification", assessment.classification().name())
                .putStrings("reasons", assessment.classification().reasons())
                .put("signs", signs)
                .put("narrative", assessment.narrative())
                .put("rung", assessment.rung().name())
                .put("rung_label", assessment.rung().label())
                .put("offline", assessment.rung().offline())
                .put("rule_light", assessment.ruleLight().name())
                .put("model_light", assessment.modelVerdict().light().name())
                .put("model_confidence", round(assessment.modelVerdict().confidence()))
                .put("narrative_light", assessment.narrativeLight().map(Enum::name).orElse("none"))
                .put("heads_agree", assessment.headsAgree())
                .put("abstained", assessment.abstained())
                .put("supervisor_review", assessment.supervisorReview())
                .put("elapsed_ms", assessment.elapsed().toMillis());

        assessment.jaundice().ifPresent(g -> builder.put("jaundice", Json.obj()
                .put("severity", g.severity().name())
                .put("kramer_zone", g.kramerZone())
                .put("refused", g.refused())
                .putIfPresent("refusal_reason", g.refusalReason())
                .put("zones", Json.arr(g.kramerZones().stream().map(Json::of).toList()))
                .build()));

        var tools = Json.obj();
        assessment.toolResults().forEach((k, v) -> {
            switch (v) {
                case Number n -> tools.put(k, n.doubleValue());
                case Boolean b -> tools.put(k, b);
                default -> tools.put(k, String.valueOf(v));
            }
        });
        builder.put("tools", tools.build());

        // The referral letter travels with the family, so it is generated with the result rather
        // than fetched separately -- a health worker with no signal cannot come back for it.
        if (assessment.light() == com.kangaroo.core.TrafficLight.RED) {
            var letter = Referral.generate("REF-" + assessment.encounterId().value(),
                    assessment.light(), assessment.classification().name(), encounter.subject(),
                    assessment.signs(), List.of());
            builder.put("referral_letter", letter.render());
        }

        return builder.build();
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /** How long the server has been up, for the console. */
    public Duration uptime() {
        return Duration.ofMillis(java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime());
    }
}
