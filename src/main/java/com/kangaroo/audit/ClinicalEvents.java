package com.kangaroo.audit;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * The clinical audit trail, as JDK Flight Recorder events.
 *
 * <p>Most projects would write this to a log file and then build a parser for it. Making the audit
 * trail a set of JFR events instead buys four things that matter for clinical software and are hard
 * to get any other way:
 *
 * <ul>
 *   <li><b>It is free.</b> A disabled event costs a predicate; an enabled one costs tens of
 *       nanoseconds. It can be left on permanently, in production, on a Raspberry Pi.</li>
 *   <li><b>It needs no bespoke tooling.</b> A supervisor can be handed a {@code .jfr} file and open
 *       it in JDK Mission Control, which is free and which they may already have.</li>
 *   <li><b>It is replayable.</b> A recorded encounter can be pushed back through a later build to
 *       prove that a fix changed the outcome, or that a refactor did not. See {@code Replay}.</li>
 *   <li><b>It streams.</b> The supervisor console reads the live recording stream rather than
 *       polling a database.</li>
 * </ul>
 *
 * <p>Nothing here carries an image, an identifier that leaves the device, or an API key. The
 * recording is safe to hand to a reviewer.
 */
public final class ClinicalEvents {

    private ClinicalEvents() {}

    // ------------------------------------------------------------------ events

    @Name("kangaroo.ClinicalDecision")
    @Label("Clinical Decision")
    @Category({"Kangaroo", "Clinical"})
    @Description("A completed WHO IMNCI assessment: the final traffic light and how it was reached")
    @StackTrace(false)
    @Enabled
    public static final class ClinicalDecision extends Event {
        @Label("Encounter") public String encounterId;
        @Label("Mode") public String mode;
        @Label("Final light") public String finalLight;
        @Label("Rule light") public String ruleLight;
        @Label("Model light") public String modelLight;
        @Label("Narrative light") public String narrativeLight;
        @Label("Classification") public String classification;
        @Label("Inference rung") public String rung;
        @Label("Danger signs") public int signCount;
        @Label("Abstained") public boolean abstained;
        @Label("Needs supervisor review") public boolean supervisorReview;
        @Label("Model confidence") public double confidence;
    }

    @Name("kangaroo.DoseCapped")
    @Label("Dose Capped")
    @Category({"Kangaroo", "Clinical"})
    @Description("A weight-based dose calculation hit its non-negotiable neonatal ceiling")
    @StackTrace(true)
    @Enabled
    public static final class DoseCapped extends Event {
        @Label("Medication") public String medication;
        @Label("Weight (kg)") public double weightKg;
        @Label("Calculated dose") public double calculated;
        @Label("Ceiling") public double ceiling;
        @Label("Unit") public String unit;
    }

    @Name("kangaroo.ModelDisagreement")
    @Label("Model Disagreement")
    @Category({"Kangaroo", "Clinical"})
    @Description("The deterministic rule, the gradient-boosted head and the language model did not agree")
    @StackTrace(false)
    @Enabled
    public static final class ModelDisagreement extends Event {
        @Label("Encounter") public String encounterId;
        @Label("Rule light") public String ruleLight;
        @Label("Model light") public String modelLight;
        @Label("Narrative light") public String narrativeLight;
        @Label("Resolved to") public String resolved;
    }

    @Name("kangaroo.Abstention")
    @Label("Abstention")
    @Category({"Kangaroo", "Clinical"})
    @Description("The conformal prediction set was not a singleton, so the system referred upward instead of guessing")
    @StackTrace(false)
    @Enabled
    public static final class Abstention extends Event {
        @Label("Encounter") public String encounterId;
        @Label("Prediction set") public String predictionSet;
        @Label("Escalated to") public String escalatedTo;
        @Label("Reason") public String reason;
    }

    @Name("kangaroo.Failover")
    @Label("Inference Failover")
    @Category({"Kangaroo", "Inference"})
    @Description("The inference ladder descended a rung")
    @StackTrace(false)
    @Enabled
    public static final class Failover extends Event {
        @Label("From rung") public String from;
        @Label("To rung") public String to;
        @Label("Reason") public String reason;
        @Label("Negotiated HTTP version") public String httpVersion;
        @Label("Latency (ms)") public long latencyMs;
    }

    @Name("kangaroo.SensorReading")
    @Label("Sensor Reading")
    @Category({"Kangaroo", "Devices"})
    @Description("A reading arrived from a paired BLE sensor")
    @StackTrace(false)
    @Enabled
    public static final class SensorReading extends Event {
        @Label("Sensor") public String sensor;
        @Label("Measurement") public String measurement;
        @Label("Value") public double value;
        @Label("Unit") public String unit;
    }

    @Name("kangaroo.NativeInference")
    @Label("Native Inference")
    @Category({"Kangaroo", "Inference"})
    @Description("An in-process inference call through the Foreign Function and Memory API")
    @StackTrace(false)
    @Enabled
    public static final class NativeInference extends Event {
        @Label("Model") public String model;
        @Label("Prompt tokens") public int promptTokens;
        @Label("Generated tokens") public int generatedTokens;
        @Label("Images") public int images;
        @Label("Grammar constrained") public boolean grammarConstrained;
    }

    @Name("kangaroo.CaptureRejected")
    @Label("Capture Rejected")
    @Category({"Kangaroo", "Clinical"})
    @Description("A photo was rejected before inference because it was not gradeable")
    @StackTrace(false)
    @Enabled
    public static final class CaptureRejected extends Event {
        @Label("Capture kind") public String kind;
        @Label("Reason") public String reason;
    }

    // ------------------------------------------------------------------ emitters
    //
    // Each emitter checks shouldCommit() before filling the event in, so that when the category is
    // disabled the cost is a single predicate and no string formatting happens at all.

    public static void doseCapped(String medication, double weightKg, double calculated,
                                  double ceiling, String unit) {
        DoseCapped e = new DoseCapped();
        if (!e.shouldCommit()) return;
        e.medication = medication;
        e.weightKg = weightKg;
        e.calculated = calculated;
        e.ceiling = ceiling;
        e.unit = unit;
        e.commit();
    }

    public static void failover(String from, String to, String reason, String httpVersion, long latencyMs) {
        Failover e = new Failover();
        if (!e.shouldCommit()) return;
        e.from = from;
        e.to = to;
        e.reason = reason;
        e.httpVersion = httpVersion;
        e.latencyMs = latencyMs;
        e.commit();
    }

    public static void abstention(String encounterId, String predictionSet, String escalatedTo, String reason) {
        Abstention e = new Abstention();
        if (!e.shouldCommit()) return;
        e.encounterId = encounterId;
        e.predictionSet = predictionSet;
        e.escalatedTo = escalatedTo;
        e.reason = reason;
        e.commit();
    }

    public static void disagreement(String encounterId, String rule, String model,
                                    String narrative, String resolved) {
        ModelDisagreement e = new ModelDisagreement();
        if (!e.shouldCommit()) return;
        e.encounterId = encounterId;
        e.ruleLight = rule;
        e.modelLight = model;
        e.narrativeLight = narrative;
        e.resolved = resolved;
        e.commit();
    }

    public static void sensorReading(String sensor, String measurement, double value, String unit) {
        SensorReading e = new SensorReading();
        if (!e.shouldCommit()) return;
        e.sensor = sensor;
        e.measurement = measurement;
        e.value = value;
        e.unit = unit;
        e.commit();
    }

    public static void nativeInference(String model, int promptTokens, int generatedTokens,
                                       int images, boolean grammarConstrained) {
        NativeInference e = new NativeInference();
        if (!e.shouldCommit()) return;
        e.model = model;
        e.promptTokens = promptTokens;
        e.generatedTokens = generatedTokens;
        e.images = images;
        e.grammarConstrained = grammarConstrained;
        e.commit();
    }

    public static void captureRejected(String kind, String reason) {
        CaptureRejected e = new CaptureRejected();
        if (!e.shouldCommit()) return;
        e.kind = kind;
        e.reason = reason;
        e.commit();
    }
}
