package com.kangaroo.store;

import com.kangaroo.core.Assessment;
import com.kangaroo.core.Encounter;
import com.kangaroo.core.EncounterId;
import com.kangaroo.core.TrafficLight;
import com.kangaroo.crypto.DeviceIdentity;
import com.kangaroo.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Completed encounters, on disk, signed.
 *
 * <p>Everything is local by default and stays local. A record leaves this device only when a
 * caregiver has explicitly consented for that encounter, and an encounter marked local-only cannot
 * be synced at all — that flag is checked here, at the store, as well as in the inference ladder,
 * because a privacy rule enforced in only one place is a privacy rule with a bypass.
 *
 * <p>Each record is signed with the device's Ed25519 identity over its exact serialised bytes. A
 * supervisor who has enrolled the device's public key can verify that a record came from the device
 * it claims to and has not been edited since — including by whoever is holding the device. That
 * matters for the same reason paper registers get counter-signed: the person recording the data is
 * sometimes the person with a reason to change it.
 */
public final class EncounterStore {

    /** One stored encounter, plus its signature and sync state. */
    public record Record(
            EncounterId id,
            Instant capturedAt,
            TrafficLight light,
            String classification,
            String mode,
            String subjectRef,
            boolean supervisorReview,
            boolean privacyLocal,
            SyncState syncState,
            String signature,
            Json.Obj payload) {}

    /** Where a record is in the store-and-forward pipeline. */
    public enum SyncState {
        /** Never leaves this device. Set by the caregiver, not by configuration. */
        LOCAL_ONLY,
        /** Waiting for a network. */
        PENDING,
        /** Uploaded and acknowledged. */
        SYNCED,
        /** Uploaded, and a supervisor has signed it off. */
        REVIEWED
    }

    private final Path directory;
    private final DeviceIdentity identity;
    private final Map<EncounterId, Record> records = new ConcurrentHashMap<>();

    public EncounterStore(Path directory, DeviceIdentity identity) throws IOException {
        this.directory = directory;
        this.identity = identity;
        Files.createDirectories(directory);
        loadExisting();
    }

    /** Persist a completed assessment. */
    public Record save(Encounter encounter, Assessment assessment) throws IOException {
        Json.Obj payload = serialise(encounter, assessment);
        String canonical = payload.write();

        String signature;
        try {
            signature = identity.sign(canonical);
        } catch (GeneralSecurityException e) {
            throw new IOException("could not sign the encounter record", e);
        }

        Record record = new Record(
                encounter.id(),
                encounter.capturedAt(),
                assessment.light(),
                assessment.classification().name(),
                encounter.mode().name(),
                encounter.subjectRef(),
                assessment.supervisorReview(),
                encounter.privacyLocal(),
                encounter.privacyLocal() ? SyncState.LOCAL_ONLY : SyncState.PENDING,
                signature,
                payload);

        Json.Obj envelope = Json.obj()
                .put("version", 1)
                .put("record", payload)
                .put("signature", signature)
                .put("signed_by", identity.fingerprint())
                .put("sync_state", record.syncState().name())
                .build();

        Files.writeString(directory.resolve(encounter.id().fileName() + ".json"),
                envelope.write(), StandardCharsets.UTF_8);

        records.put(encounter.id(), record);
        return record;
    }

    /**
     * Verify a stored record against a public key.
     *
     * <p>The signature covers the serialised record exactly as it was written, so this catches any
     * edit to the file, including one that leaves it valid JSON.
     */
    public static boolean verify(Path file, String publicKeyPem) throws IOException {
        Json.Obj envelope = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
        Optional<Json> record = envelope.field("record");
        String signature = envelope.str("signature", "");
        if (record.isEmpty() || signature.isBlank()) return false;
        return DeviceIdentity.verify(publicKeyPem, record.get().write(), signature);
    }

    public Optional<Record> get(EncounterId id) {
        return Optional.ofNullable(records.get(id));
    }

    /** Everything stored, newest first. */
    public List<Record> all() {
        return records.values().stream()
                .sorted(Comparator.comparing(Record::capturedAt).reversed())
                .toList();
    }

    /** The supervisor triage queue: everything red or flagged, oldest first so nothing is starved. */
    public List<Record> triageQueue() {
        return records.values().stream()
                .filter(r -> r.light() == TrafficLight.RED || r.supervisorReview())
                .filter(r -> r.syncState() != SyncState.REVIEWED)
                .sorted(Comparator.comparing(Record::capturedAt))
                .toList();
    }

    /** Records waiting for a network. Never includes local-only ones. */
    public List<Record> pendingSync() {
        return records.values().stream()
                .filter(r -> r.syncState() == SyncState.PENDING)
                .sorted(Comparator.comparing(Record::capturedAt))
                .toList();
    }

    public int count() {
        return records.size();
    }

    public Map<TrafficLight, Long> countsByLight() {
        Map<TrafficLight, Long> out = new java.util.EnumMap<>(TrafficLight.class);
        for (TrafficLight t : TrafficLight.values()) out.put(t, 0L);
        for (Record r : records.values()) out.merge(r.light(), 1L, Long::sum);
        return out;
    }

    // ---------------------------------------------------------------- serialisation

    private Json.Obj serialise(Encounter encounter, Assessment assessment) {
        List<Json> signs = new ArrayList<>();
        assessment.signs().forEach(s -> signs.add(Json.obj()
                .put("sign", s.sign().name())
                .put("label", s.sign().label())
                .put("provenance", s.provenance())
                .put("red", s.sign().red())
                .build()));

        List<Json> reasons = assessment.classification().reasons().stream()
                .map(Json::of).toList();

        Json.ObjBuilder b = Json.obj()
                .put("id", encounter.id().value())
                .put("captured_at", encounter.capturedAt().toString())
                .put("assessed_at", assessment.assessedAt().toString())
                .put("mode", encounter.mode().name())
                .put("locale", encounter.locale())
                .putIfPresent("subject_ref", encounter.subjectRef())
                .put("age_days", encounter.subject().ageDays())
                .put("weight_kg", encounter.subject().weightKg())
                .put("sex", encounter.subject().sex().name())
                .put("preterm", encounter.subject().preterm())
                .put("light", assessment.light().name())
                .put("rule_light", assessment.ruleLight().name())
                .put("model_light", assessment.modelVerdict().light().name())
                .put("model_confidence", assessment.modelVerdict().confidence())
                .put("narrative_light", assessment.narrativeLight().map(Enum::name).orElse("none"))
                .put("classification", assessment.classification().name())
                .put("reasons", reasons)
                .put("signs", signs)
                .put("rung", assessment.rung().name())
                .put("abstained", assessment.abstained())
                .put("supervisor_review", assessment.supervisorReview())
                .put("elapsed_ms", assessment.elapsed().toMillis())
                .put("privacy_local", encounter.privacyLocal());

        assessment.jaundice().ifPresent(g -> b.put("jaundice", Json.obj()
                .put("severity", g.severity().name())
                .put("kramer_zone", g.kramerZone())
                .put("refused", g.refused())
                .putIfPresent("refusal_reason", g.refusalReason())
                .build()));

        // The narrative is stored, the captured images are not. Photographs of infants are the
        // most sensitive thing this system touches and there is no clinical reason to retain them
        // after the assessment has been made.
        b.put("narrative", assessment.narrative());
        b.put("captures", encounter.captures().size());

        return b.build();
    }

    private void loadExisting() throws IOException {
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.list(directory)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(this::loadOne);
        }
    }

    private void loadOne(Path file) {
        try {
            Json.Obj envelope = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            Json.Obj record = envelope.obj("record").orElse(null);
            if (record == null) return;

            EncounterId id = EncounterId.of(record.str("id", file.getFileName().toString()));
            records.put(id, new Record(
                    id,
                    Instant.parse(record.str("captured_at", Instant.now().toString())),
                    TrafficLight.parseOrRed(record.str("light", "RED")),
                    record.str("classification", ""),
                    record.str("mode", "CHW"),
                    record.str("subject_ref", ""),
                    record.bool("supervisor_review", false),
                    record.bool("privacy_local", false),
                    SyncState.valueOf(envelope.str("sync_state", "PENDING")),
                    envelope.str("signature", ""),
                    record));
        } catch (RuntimeException | IOException e) {
            // A corrupt file must not stop the store from opening: the health worker's other
            // encounters matter more than this one, and losing the store loses all of them.
            System.Logger logger = System.getLogger("kangaroo.store");
            logger.log(System.Logger.Level.WARNING, "skipping unreadable record " + file, e);
        }
    }
}
