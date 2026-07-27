package com.kangaroo.audit;

import com.kangaroo.util.Json;

import java.util.List;

/**
 * Where every JDK 26 JEP is used, reported by the running application.
 *
 * <p>This lives in the product rather than only in the README because a claim you can click on is
 * worth more than a claim you can read. {@code GET /api/jeps} answers from the process that is
 * actually running, on the machine it is actually running on, and the interface renders it.
 *
 * <p>JDK 26 reached general availability on 17 March 2026 with ten JEPs: four final, four in
 * preview, one incubating, one removal. All ten are accounted for below — including the removal,
 * which is accounted for by not being a problem.
 */
public final class Jeps {

    public static final String VERSION = "1.0.0";
    public static final String JDK = "26";

    private Jeps() {}

    /**
     * @param number    the JEP number
     * @param title     its title
     * @param status    Final, Preview, Incubator or Removal
     * @param where     the class or package it lives in
     * @param why       why it earns its place, in one sentence
     * @param loadBearing true when removing it would change what the product does, rather than only
     *                    how it is written
     */
    public record Use(int number, String title, String status, String where, String why,
                      boolean loadBearing) {}

    public static final List<Use> USES = List.of(

            new Use(517, "HTTP/3 for the HTTP Client API", "Final",
                    "com.kangaroo.infer.OpenAiCompatibleEngine",
                    "The cloud rung runs over QUIC with ALT_SVC discovery. On a rural mobile link, "
                    + "per-stream loss recovery and connection migration are what let an upload "
                    + "survive packet loss and a cell handover instead of restarting.",
                    true),

            new Use(525, "Structured Concurrency", "Preview",
                    "com.kangaroo.orchestrate.AssessmentOrchestrator",
                    "The four evidence passes fan out into one scope with one deadline and one "
                    + "cancellation domain, so a slow visual pass cannot eat the audio pass's "
                    + "budget and an abandoned encounter leaves no orphan threads.",
                    true),

            new Use(526, "Lazy Constants", "Preview",
                    "com.kangaroo.clinical.Reference, com.kangaroo.ml.Models",
                    "The WHO tables and both gradient-boosted models load on first use and are "
                    + "constant-folded afterwards. A device that only runs the deterministic path "
                    + "never pays to parse 1.5 MB of trees.",
                    true),

            new Use(530, "Primitive Types in Patterns, instanceof, and switch", "Preview",
                    "com.kangaroo.ml.Tree, com.kangaroo.ml.GbmModel",
                    "LightGBM's decision_type is a packed byte, unpacked as a switch over the "
                    + "primitive with checked coverage. And `threshold instanceof float` is a "
                    + "checked narrowing that tells us which trees are genuinely float-safe.",
                    false),

            new Use(529, "Vector API", "Incubator",
                    "com.kangaroo.color.VectorPipeline",
                    "The colorimetry kernels run over FloatVector.SPECIES_PREFERRED, with mask-and-"
                    + "reduce replacing a branch per pixel and Vector.compress packing the selected "
                    + "skin pixels. Measured live at /api/bench.",
                    true),

            new Use(524, "PEM Encodings of Cryptographic Objects", "Preview",
                    "com.kangaroo.crypto.DeviceIdentity",
                    "Ed25519 device identity and per-record signatures. PEM is what you paste into "
                    + "an enrolment form, and PEMEncoder makes reading and writing it a standard "
                    + "library call instead of a cryptography dependency.",
                    true),

            new Use(516, "Ahead-of-Time Object Caching with Any GC", "Final",
                    "packaging/aot.sh, packaging/aot.ps1",
                    "A two-run training and use cycle produces an AOT cache shipped alongside the "
                    + "installer. JEP 516 is precisely what lifts the earlier GC restriction, so the "
                    + "cache and the production collector can be used together.",
                    false),

            new Use(522, "G1 GC: Improve Throughput by Reducing Synchronization", "Final",
                    "packaging/gc-benchmark.sh",
                    "The Pod serves a dozen phones concurrently, which is the allocation pattern "
                    + "this JEP improves. Measured on identical hardware across JDK 25 and 26.",
                    false),

            new Use(500, "Prepare to Make Final Mean Final", "Final",
                    "pom.xml, packaging/run.sh",
                    "The application runs under --illegal-final-field-access=deny. For clinical "
                    + "software, 'no library can quietly mutate a final field in the dosing table' "
                    + "is a safety property rather than a checkbox.",
                    false),

            new Use(504, "Remove the Applet API", "Final (removal)",
                    "the whole dependency graph",
                    "Nothing in this project touches java.applet, because the runtime dependency "
                    + "set is exactly the JDK. Kangaroo builds on 26 unmodified.",
                    false)
    );

    /** The pre-26 language and platform features the design leans on throughout. */
    public static final List<Use> FOUNDATIONS = List.of(
            new Use(454, "Foreign Function & Memory API", "Final since 22",
                    "com.kangaroo.ffm.llama",
                    "The entire native layer. No JNI anywhere in the repository.", true),
            new Use(444, "Virtual Threads", "Final since 21",
                    "com.kangaroo.http.KangarooServer",
                    "Thread per request, so a request that blocks in a model for eight seconds "
                    + "costs a few hundred bytes rather than a pooled platform thread.", true),
            new Use(409, "Sealed Classes", "Final since 17",
                    "com.kangaroo.core",
                    "The clinical domain model. Adding a danger sign that a consumer forgets to "
                    + "handle is a compile error, not a field incident.", true),
            new Use(512, "Compact Source Files and Instance Main Methods", "Final since 25",
                    "tools/Check.java",
                    "The single-file diagnostic a reviewer can run with no build tool at all.", false),
            new Use(511, "Module Import Declarations", "Final since 25",
                    "tools/Check.java",
                    "`import module java.base;` in the compact diagnostic.", false)
    );

    public static Json.Obj asJson() {
        return Json.obj()
                .put("jdk", JDK)
                .put("running_on", Runtime.version().toString())
                .put("version", VERSION)
                .put("jeps", Json.arr(USES.stream().map(Jeps::toJson).toList()))
                .put("foundations", Json.arr(FOUNDATIONS.stream().map(Jeps::toJson).toList()))
                .put("total_jdk26_jeps", USES.size())
                .put("load_bearing", USES.stream().filter(Use::loadBearing).count())
                .build();
    }

    private static Json toJson(Use u) {
        return Json.obj()
                .put("jep", u.number())
                .put("title", u.title())
                .put("status", u.status())
                .put("where", u.where())
                .put("why", u.why())
                .put("load_bearing", u.loadBearing())
                .build();
    }
}
