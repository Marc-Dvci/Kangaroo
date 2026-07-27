package com.kangaroo;

import com.kangaroo.core.Assessment;
import com.kangaroo.core.Encounter;
import com.kangaroo.core.Mode;
import com.kangaroo.core.Rung;
import com.kangaroo.core.Sex;
import com.kangaroo.core.Subject;
import com.kangaroo.core.TrafficLight;
import com.kangaroo.core.Vitals;
import com.kangaroo.crypto.DeviceIdentity;
import com.kangaroo.infer.FailoverEngine;
import com.kangaroo.orchestrate.AssessmentOrchestrator;
import com.kangaroo.store.EncounterStore;
import com.kangaroo.store.PatientMemory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole pipeline, on the configuration that must always work: no model, no native library, no
 * network. Everything here runs on the deterministic rung.
 *
 * <p>This is the configuration a Raspberry Pi in a village with a flat battery and no signal is in,
 * and it is the one the product's central claim rests on — so it is the one the end-to-end suite
 * exercises rather than the fully-equipped laptop.
 */
class EndToEndTest {

    @TempDir Path dataDir;

    private AssessmentOrchestrator orchestrator;
    private EncounterStore store;
    private PatientMemory memory;
    private DeviceIdentity identity;

    @BeforeEach
    void setUp() throws Exception {
        identity = DeviceIdentity.loadOrCreate(dataDir);
        memory = new PatientMemory();
        store = new EncounterStore(dataDir.resolve("encounters"), identity);
        orchestrator = new AssessmentOrchestrator(FailoverEngine.deterministicOnly(), memory);
    }

    @AfterEach
    void tearDown() {
        orchestrator.close();
    }

    private Encounter encounter(String intake, int ageDays, double weight, Mode mode, Vitals vitals) {
        return encounter(intake, ageDays, weight, mode, vitals, "baby-1");
    }

    private Encounter encounter(String intake, int ageDays, double weight, Mode mode, Vitals vitals,
                                String subjectRef) {
        return new Encounter(
                com.kangaroo.core.EncounterId.random(), subjectRef,
                new Subject(ageDays, weight, Sex.FEMALE, false),
                Instant.now(), mode, List.of(), vitals, intake, "en", true);
    }

    @Test
    @DisplayName("with no model and no network, a red case is still a complete referral")
    void deterministicPathProducesACompleteReferral() throws Exception {
        Assessment a = orchestrator.assess(encounter(
                "The baby is lethargic and not feeding at all. Temperature feels low.",
                5, 2.8, Mode.CHW, Vitals.none().withTemperature(35.1)));

        assertEquals(TrafficLight.RED, a.light());
        assertEquals(Rung.DETERMINISTIC, a.rung());
        assertTrue(a.rung().offline(), "nothing may leave the device on this rung");

        // A complete answer, not a degraded one.
        assertFalse(a.classification().name().isBlank());
        assertFalse(a.classification().reasons().isEmpty(), "a referral must say why");
        assertFalse(a.narrative().isBlank(), "the offline rung must still say what to do");
        assertTrue(a.narrative().contains("Go to a clinic straight away"),
                "the return-immediately list ships with every outcome");
        assertFalse(a.signs().isEmpty());
        assertNotNull(a.toolResults().get("followup_date"));
    }

    @Test
    @DisplayName("a green case is never phrased as 'your baby is fine'")
    void greenIsHonestNotReassuring() throws Exception {
        Assessment a = orchestrator.assess(encounter(
                "Baby feeds well and is alert. No fever. Cord is dry and clean. No pustules.",
                12, 3.6, Mode.CHW, Vitals.none().withRespiratoryRate(44)));

        assertEquals(TrafficLight.GREEN, a.light());
        assertEquals("Nothing here needs a clinician today.", a.headline(Mode.PARENT));

        String narrative = a.narrative().toLowerCase();
        assertFalse(narrative.contains("your baby is fine"));
        assertFalse(narrative.contains("nothing to worry"));
        assertTrue(a.narrative().contains("Go to a clinic straight away"),
                "a green result must still carry the danger-sign list");
    }

    @Test
    @DisplayName("parent mode escalates a borderline case that CHW mode does not")
    void parentModeEscalatesBorderline() throws Exception {
        String intake = "Mild yellow colour on the face only. Baby feeds well. No fever.";

        Assessment chw = orchestrator.assess(encounter(intake, 10, 3.4, Mode.CHW, Vitals.none()));
        Assessment parent = orchestrator.assess(encounter(intake, 10, 3.4, Mode.PARENT, Vitals.none()));

        assertEquals(TrafficLight.GREEN, chw.light(),
                "a trained observer has already excluded what a parent cannot");
        assertEquals(TrafficLight.YELLOW, parent.light(),
                "a parent has less signal and more at stake, so the threshold is lower");
        assertEquals("Someone should see your baby today.", parent.headline(Mode.PARENT));
    }

    @Test
    @DisplayName("an assessment is stored, signed, and verifies")
    void storedRecordsAreSignedAndVerifiable() throws Exception {
        Encounter e = encounter("Baby is lethargic.", 5, 2.9, Mode.CHW, Vitals.none());
        Assessment a = orchestrator.assess(e);
        var record = store.save(e, a);

        assertFalse(record.signature().isBlank());
        assertEquals(EncounterStore.SyncState.LOCAL_ONLY, record.syncState(),
                "a privacy-flagged encounter must never be queued for sync");

        Path file = dataDir.resolve("encounters").resolve(e.id().fileName() + ".json");
        assertTrue(EncounterStore.verify(file, identity.publicKeyPem()),
                "a freshly written record must verify against the device key");

        // Tamper with it on disk and the signature must fail.
        String content = java.nio.file.Files.readString(file);
        java.nio.file.Files.writeString(file, content.replace("\"RED\"", "\"GREEN\""));
        assertFalse(EncounterStore.verify(file, identity.publicKeyPem()),
                "editing a stored record must be detectable");
    }

    @Test
    @DisplayName("captured photographs are not retained after the assessment")
    void imagesAreNotStored() throws Exception {
        Encounter e = encounter("Baby looks yellow on the chest.", 6, 3.0, Mode.CHW, Vitals.none())
                .withCapture(new com.kangaroo.core.Capture(
                        com.kangaroo.core.Capture.Kind.CHEST, "image/jpeg", new byte[] {1, 2, 3, 4}));

        Assessment a = orchestrator.assess(e);
        store.save(e, a);

        String stored = java.nio.file.Files.readString(
                dataDir.resolve("encounters").resolve(e.id().fileName() + ".json"));

        assertTrue(stored.contains("\"captures\":1"), "the count is kept for the audit trail");
        assertFalse(stored.contains("AQID"), "the image bytes themselves must not be persisted");
    }

    @Test
    @DisplayName("a worsening trend across visits escalates a case that would otherwise be green")
    void trendEscalates() throws Exception {
        // Visit one: jaundice on the trunk.
        Encounter first = encounter("Yellow colour reaching the trunk. Feeds well.",
                4, 3.2, Mode.CHW, Vitals.none());
        orchestrator.assess(first);

        // Visit two: it has spread to the limbs. The trend itself is evidence.
        Encounter second = encounter("Yellow colour now on the arms and legs. Feeds well.",
                5, 3.2, Mode.CHW, Vitals.none());
        Assessment a = orchestrator.assess(second);

        assertEquals(TrafficLight.RED, a.light());
        assertEquals(2, memory.historyFor("baby-1").size());
        assertTrue(memory.trendFor("baby-1").worsening(),
                "spreading jaundice between visits is the signal a single snapshot cannot see");
    }

    @Test
    @DisplayName("the deterministic rung never leaves the device, whatever the encounter says")
    void deterministicRungIsAlwaysOffline() throws Exception {
        for (Mode mode : Mode.values()) {
            Assessment a = orchestrator.assess(encounter(
                    "Baby feeds well.", 10, 3.4, mode, Vitals.none()));
            assertTrue(a.rung().offline());
            assertFalse(a.rung().leftTheDevice());
        }
    }

    @Test
    @DisplayName("an assessment completes well inside the field latency budget")
    void latency() throws Exception {
        // Warm up the lazily loaded model and reference tables first, so this measures the
        // steady-state path rather than one-off class loading.
        orchestrator.assess(encounter("Baby feeds well.", 10, 3.4, Mode.CHW, Vitals.none()));

        long start = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            orchestrator.assess(encounter(
                    "The baby is lethargic and feeding poorly. Cord stump is red.",
                    6, 3.0, Mode.CHW, Vitals.none().withRespiratoryRate(58)));
        }
        long perAssessment = (System.nanoTime() - start) / 50 / 1_000_000;

        assertTrue(perAssessment < 250,
                "the offline path took " + perAssessment + " ms per assessment; "
                        + "a health worker holding a baby will not wait");
        System.out.println("  deterministic assessment: " + perAssessment + " ms");
    }

    @Test
    @DisplayName("concurrent assessments of different babies do not interfere")
    void concurrency() throws Exception {
        int threads = 24;
        var results = java.util.Collections.synchronizedList(new java.util.ArrayList<TrafficLight>());
        var failures = java.util.Collections.synchronizedList(new java.util.ArrayList<Throwable>());

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                final boolean red = i % 2 == 0;
                // Each concurrent encounter is a *different* baby. Sharing one subject reference
                // across all of them would couple them through the longitudinal record on purpose
                // -- a second visit for the same infant is meant to see the first one -- and the
                // test would then be measuring that feature rather than thread safety.
                final String subject = "baby-" + i;
                executor.submit(() -> {
                    try {
                        Assessment a = orchestrator.assess(encounter(
                                red ? "The baby is lethargic and not feeding at all."
                                    : "Baby feeds well. No fever. Cord is clean. No pustules.",
                                8, 3.1, Mode.CHW, Vitals.none(), subject));
                        results.add(a.light());
                    } catch (Throwable t) {
                        // Captured rather than swallowed by the Future, so a crash in one
                        // assessment fails the test loudly instead of silently shrinking the list.
                        failures.add(t);
                    }
                });
            }
        }

        assertTrue(failures.isEmpty(), () -> "assessments failed: " + failures);
        assertEquals(threads, results.size());
        assertEquals(threads / 2, results.stream().filter(l -> l == TrafficLight.RED).count(),
                "an assessment must not be contaminated by one running beside it");
        assertEquals(threads / 2, results.stream().filter(l -> l == TrafficLight.GREEN).count(),
                "and a green assessment must stay green");
    }

    @Test
    @DisplayName("repeat visits for the same baby are coupled, deliberately")
    void repeatVisitsShareTheLongitudinalRecord() throws Exception {
        // The mirror image of the test above, and the reason it has to use distinct subjects:
        // encounters that share a subject reference are *supposed* to see each other, because a
        // trajectory predicts deterioration in a way a single snapshot cannot.
        for (int visit = 0; visit < 3; visit++) {
            orchestrator.assess(encounter("Yellow colour reaching the trunk. Feeds well.",
                    4 + visit, 3.2, Mode.CHW, Vitals.none(), "shared-baby"));
        }
        assertEquals(3, memory.historyFor("shared-baby").size());
        assertTrue(memory.historyFor("baby-1").isEmpty(),
                "a different subject reference must not pick up another baby's history");
    }
}
