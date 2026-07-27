package com.kangaroo.core;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Everything gathered about one infant at one moment: who, when, what was captured, what was
 * measured, what was said, and under which front door.
 *
 * <p>An {@code Encounter} is the input to the assessment. It is never mutated after capture — a
 * follow-up produces a new encounter linked by {@code subjectRef}, which is what turns a series of
 * snapshots into the trajectory that actually predicts deterioration.
 *
 * @param id           opaque, random, not derived from the infant
 * @param subjectRef   the caregiver-chosen local label linking encounters for the same baby
 * @param subject      age, weight, sex, preterm
 * @param capturedAt   when the capture happened, not when it was processed
 * @param mode         which front door
 * @param captures     images and audio, raw
 * @param vitals       measured numbers
 * @param intakeText   what the caregiver said, transcribed or typed, in their own words
 * @param locale       the caregiver's language tag, for the action plan
 * @param privacyLocal when true this encounter may never leave the device, whatever the settings say
 */
public record Encounter(
        EncounterId id,
        String subjectRef,
        Subject subject,
        Instant capturedAt,
        Mode mode,
        List<Capture> captures,
        Vitals vitals,
        String intakeText,
        String locale,
        boolean privacyLocal) {

    public Encounter {
        if (id == null) throw new IllegalArgumentException("id is required");
        if (subject == null) subject = Subject.unknown();
        if (capturedAt == null) capturedAt = Instant.now();
        if (mode == null) mode = Mode.CHW;
        captures = captures == null ? List.of() : List.copyOf(captures);
        if (vitals == null) vitals = Vitals.none();
        if (intakeText == null) intakeText = "";
        if (locale == null || locale.isBlank()) locale = "en";
        if (subjectRef == null) subjectRef = "";
    }

    /** A minimal encounter — the shape parent mode starts from. */
    public static Encounter of(Subject subject, String intakeText, Mode mode) {
        return new Encounter(EncounterId.random(), "", subject, Instant.now(), mode,
                List.of(), Vitals.none(), intakeText, "en", false);
    }

    public Optional<Capture> capture(Capture.Kind kind) {
        return captures.stream().filter(c -> c.kind() == kind).findFirst();
    }

    public List<Capture> images() {
        return captures.stream().filter(Capture::isImage).toList();
    }

    public Optional<Capture> cryAudio() {
        return capture(Capture.Kind.CRY);
    }

    public boolean hasImages() {
        return captures.stream().anyMatch(Capture::isImage);
    }

    public Encounter withCapture(Capture c) {
        var next = new java.util.ArrayList<>(captures);
        next.removeIf(existing -> existing.kind() == c.kind());
        next.add(c);
        return new Encounter(id, subjectRef, subject, capturedAt, mode, next, vitals,
                intakeText, locale, privacyLocal);
    }

    public Encounter withVitals(Vitals v) {
        return new Encounter(id, subjectRef, subject, capturedAt, mode, captures, v,
                intakeText, locale, privacyLocal);
    }

    public Encounter withIntake(String text) {
        return new Encounter(id, subjectRef, subject, capturedAt, mode, captures, vitals,
                text, locale, privacyLocal);
    }

    /**
     * Total bytes held. Used by the Pod to decide when to spill captures to disk — a Raspberry Pi
     * serving a dozen phones cannot hold every frame in the heap.
     */
    public long captureBytes() {
        return captures.stream().mapToLong(Capture::sizeBytes).sum();
    }
}
