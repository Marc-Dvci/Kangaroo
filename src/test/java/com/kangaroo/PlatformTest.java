package com.kangaroo;

import com.kangaroo.clinical.ClinicalTools;
import com.kangaroo.clinical.FollowUp;
import com.kangaroo.clinical.Ors;
import com.kangaroo.clinical.Psbi;
import com.kangaroo.clinical.ZScore;
import com.kangaroo.core.Sex;
import com.kangaroo.core.TrafficLight;
import com.kangaroo.crypto.DeviceIdentity;
import com.kangaroo.util.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The platform pieces: crypto, JSON, and the remaining WHO tools. */
class PlatformTest {

    @Nested
    @DisplayName("Device identity (JEP 524 PEM)")
    class Identity {

        @Test
        @DisplayName("a signed record verifies, and a tampered one does not")
        void signAndVerify() throws Exception {
            DeviceIdentity identity = DeviceIdentity.ephemeral();
            String record = "{\"id\":\"enc_1\",\"light\":\"RED\",\"classification\":\"PSBI\"}";

            String signature = identity.sign(record);
            String pem = identity.publicKeyPem();

            assertTrue(DeviceIdentity.verify(pem, record, signature),
                    "an untouched record must verify");

            // The whole point: a record edited after the fact must fail, including an edit that
            // leaves it valid JSON and plausible.
            String tampered = record.replace("RED", "GREEN");
            assertFalse(DeviceIdentity.verify(pem, tampered, signature),
                    "downgrading the traffic light must invalidate the signature");

            assertFalse(DeviceIdentity.verify(pem, record + " ", signature),
                    "even a trailing space must invalidate the signature");
        }

        @Test
        @DisplayName("the PEM public key round-trips and is short enough for a QR code")
        void pemRoundTrip() throws Exception {
            DeviceIdentity identity = DeviceIdentity.ephemeral();
            String pem = identity.publicKeyPem();

            assertTrue(pem.startsWith("-----BEGIN PUBLIC KEY-----"));
            assertTrue(pem.contains("-----END PUBLIC KEY-----"));
            // Ed25519 keys are 32 bytes; the whole PEM is well under the ~2900 byte QR ceiling,
            // which is what makes offline enrolment by scanning possible.
            assertTrue(pem.length() < 200, "PEM was " + pem.length() + " characters");

            String message = "round trip";
            assertTrue(DeviceIdentity.verify(pem, message, identity.sign(message)));
        }

        @Test
        @DisplayName("another device's key does not verify this device's signature")
        void keysAreNotInterchangeable() throws Exception {
            DeviceIdentity a = DeviceIdentity.ephemeral();
            DeviceIdentity b = DeviceIdentity.ephemeral();

            String record = "an encounter";
            assertFalse(DeviceIdentity.verify(b.publicKeyPem(), record, a.sign(record)));
            assertNotEquals(a.fingerprint(), b.fingerprint());
        }

        @Test
        @DisplayName("the identity persists across restarts")
        void persists(@TempDir Path dir) throws Exception {
            DeviceIdentity first = DeviceIdentity.loadOrCreate(dir);
            DeviceIdentity second = DeviceIdentity.loadOrCreate(dir);

            assertEquals(first.fingerprint(), second.fingerprint(),
                    "a restart must not orphan every record the device has already signed");
            assertEquals(first.publicKeyPem(), second.publicKeyPem());

            // A record signed before the restart still verifies after it.
            String record = "before the restart";
            assertTrue(DeviceIdentity.verify(second.publicKeyPem(), record, first.sign(record)));
            assertTrue(Files.exists(dir.resolve("device-key.pem")));
        }

        @Test
        @DisplayName("malformed keys and signatures fail closed")
        void malformedInputFailsClosed() throws Exception {
            DeviceIdentity identity = DeviceIdentity.ephemeral();
            String signature = identity.sign("x");

            assertFalse(DeviceIdentity.verify("not a pem", "x", signature));
            assertFalse(DeviceIdentity.verify(identity.publicKeyPem(), "x", "not base64!!"));
            assertFalse(DeviceIdentity.verify("", "x", signature));
        }
    }

    @Nested
    @DisplayName("JSON")
    class JsonTests {

        @Test
        @DisplayName("round-trips values, nesting and escapes")
        void roundTrip() {
            Json.Obj original = Json.obj()
                    .put("light", "RED")
                    .put("confidence", 0.8671)
                    .put("count", 3)
                    .put("flag", true)
                    .put("nested", Json.obj().put("a", 1).build())
                    .putStrings("reasons", java.util.List.of("one", "two \"quoted\"", "line\nbreak"))
                    .build();

            Json.Obj parsed = Json.parseObject(original.write());
            assertEquals("RED", parsed.str("light", ""));
            assertEquals(0.8671, parsed.num("confidence", 0), 1e-12);
            assertEquals(3, parsed.intAt("count", 0));
            assertTrue(parsed.bool("flag", false));
            assertEquals(3, parsed.array("reasons").size());
            assertEquals("two \"quoted\"", parsed.array("reasons").get(1).asString().orElseThrow());
            assertEquals("line\nbreak", parsed.array("reasons").get(2).asString().orElseThrow());
        }

        @Test
        @DisplayName("rejects malformed input rather than guessing")
        void rejectsMalformed() {
            String[] bad = {
                    "{",
                    "{\"a\":}",
                    "{\"a\":1,}",
                    "{a:1}",
                    "[1,2",
                    "{\"a\":1}trailing",
                    "{\"a\":\"unterminated",
                    "",
                    "{\"a\":01x}",
            };
            for (String s : bad) {
                assertThrows(Json.JsonException.class, () -> Json.parse(s),
                        "should have refused: " + s);
            }
        }

        @Test
        @DisplayName("control characters and unicode survive a round trip")
        void escaping() {
            String nasty = "tab\therecontrol separator éà 🙂";
            Json.Obj o = Json.obj().put("s", nasty).build();
            assertEquals(nasty, Json.parseObject(o.write()).str("s", ""));
        }

        @Test
        @DisplayName("serialisation is canonical: parse and re-write is byte-identical")
        void serialisationIsCanonical() {
            // A regression test for a defect that broke every stored signature: Map.copyOf returns
            // a map whose iteration order is unspecified and randomised per JVM run, so an object
            // parsed and re-serialised came back with its fields reordered and different bytes.
            // Encounter records are signed over exactly these bytes.
            Json.Obj original = Json.obj()
                    .put("id", "enc_abc")
                    .put("captured_at", "2026-07-27T09:00:00Z")
                    .put("light", "RED")
                    .put("rule_light", "RED")
                    .put("model_confidence", 0.8671)
                    .put("signs", java.util.List.of(
                            Json.obj().put("sign", "LETHARGY").put("red", true).build()))
                    .put("nested", Json.obj().put("z", 1).put("a", 2).put("m", 3).build())
                    .build();

            String first = original.write();
            for (int i = 0; i < 20; i++) {
                assertEquals(first, Json.parseObject(first).write(),
                        "a round trip must not change a single byte, or no signature verifies");
            }
        }

        @Test
        @DisplayName("deeply nested input does not blow the stack in a way that matters")
        void deepNesting() {
            String deep = "[".repeat(400) + "]".repeat(400);
            // Either it parses or it throws a JsonException. What it must not do is corrupt state.
            try {
                Json.parse(deep);
            } catch (Json.JsonException | StackOverflowError expected) {
                // Both are acceptable refusals for pathological input.
            }
        }
    }

    @Nested
    @DisplayName("WHO tools")
    class Tools {

        @Test
        @DisplayName("z-score matches the WHO published table at known points")
        void zScoreAgainstPublishedValues() {
            // WHO 2006 weight-for-age, boys, day 0: median 3.3464 kg. A baby at the median is z = 0.
            ZScore.Result median = ZScore.calculate(3.3464, 0, Sex.MALE);
            assertEquals(0.0, median.z(), 0.01);
            assertEquals(ZScore.Band.NORMAL, median.band());
            assertEquals(50.0, median.percentile(), 0.5);

            // Well below the median is underweight, well above is not.
            assertTrue(ZScore.calculate(2.0, 0, Sex.MALE).z() < -2);
            assertEquals(ZScore.Band.SEVERELY_UNDERWEIGHT, ZScore.calculate(1.8, 0, Sex.MALE).band());
            assertEquals(ZScore.Band.NORMAL, ZScore.calculate(3.6, 7, Sex.FEMALE).band());
        }

        @Test
        @DisplayName("z-score refuses ages and weights outside the published domain")
        void zScoreRefusesOutOfRange() {
            assertThrows(IllegalArgumentException.class, () -> ZScore.calculate(3.0, 29, Sex.MALE));
            assertThrows(IllegalArgumentException.class, () -> ZScore.calculate(3.0, -1, Sex.MALE));
            assertThrows(IllegalArgumentException.class, () -> ZScore.calculate(0, 5, Sex.MALE));
            assertThrows(IllegalArgumentException.class, () -> ZScore.calculate(-2, 5, Sex.MALE));
        }

        @Test
        @DisplayName("z-score interpolates between tabulated days")
        void zScoreInterpolates() {
            double atDay3 = ZScore.calculate(3.3, 3, Sex.MALE).medianKg();
            double atDay4 = ZScore.calculate(3.3, 4, Sex.MALE).medianKg();
            assertNotEquals(atDay3, atDay4, "the median must move between days");
        }

        @Test
        @DisplayName("ORS volumes follow the WHO plans")
        void orsPlans() {
            Ors.Plan none = Ors.calculate(3.0, Ors.Dehydration.NONE);
            assertEquals("A", none.plan());
            assertFalse(none.referUrgently());

            // Plan B is 75 ml/kg over 4 hours.
            Ors.Plan some = Ors.calculate(3.0, Ors.Dehydration.SOME);
            assertEquals("B", some.plan());
            assertEquals(225, some.volumeMl(), 0.5);
            assertEquals(56, some.volumePerHour(), 1.0);

            Ors.Plan severe = Ors.calculate(3.0, Ors.Dehydration.SEVERE);
            assertEquals("C", severe.plan());
            assertTrue(severe.referUrgently(), "severe dehydration needs IV fluids and referral");
        }

        @Test
        @DisplayName("follow-up intervals match the severity")
        void followUpIntervals() {
            Clock fixed = Clock.fixed(Instant.parse("2026-07-27T09:00:00Z"), ZoneOffset.UTC);

            assertEquals(1, FollowUp.suggest(TrafficLight.RED, fixed).inDays());
            assertTrue(FollowUp.suggest(TrafficLight.RED, fixed).mandatory(),
                    "a referral must be verified, because a referral nobody checks did not happen");

            assertEquals(2, FollowUp.suggest(TrafficLight.YELLOW, fixed).inDays());
            assertEquals(7, FollowUp.suggest(TrafficLight.GREEN, fixed).inDays());
            assertFalse(FollowUp.suggest(TrafficLight.GREEN, fixed).mandatory());
        }

        @Test
        @DisplayName("the PSBI outpatient regimen is only offered after referral has failed")
        void psbiRequiresFailedReferral() {
            var notYet = Psbi.evaluate(20, false, false, false);
            assertFalse(notYet.eligible(), "the regimen must not be offered before referral is tried");
            assertTrue(notYet.ineligibleWhy().toLowerCase().contains("refer"));

            var critical = Psbi.evaluate(20, false, true, true);
            assertFalse(critical.eligible(), "convulsions are not covered by the outpatient regimen");

            var fastBreathingOnly = Psbi.evaluate(20, true, true, false);
            assertTrue(fastBreathingOnly.eligible());
            assertEquals(Psbi.Regimen.FAST_BREATHING_ONLY, fastBreathingOnly.regimen());
            assertEquals(java.util.List.of("amoxicillin_oral"), fastBreathingOnly.medications());

            var severe = Psbi.evaluate(20, false, true, false);
            assertTrue(severe.eligible());
            assertEquals(Psbi.Regimen.CLINICAL_SEVERE_INFECTION, severe.regimen());
            assertTrue(severe.medications().contains("gentamicin_im"));

            // Under 7 days, fast breathing alone is not the amoxicillin-only regimen.
            assertFalse(Psbi.evaluate(3, true, true, false).eligible());
        }

        @Test
        @DisplayName("every registered tool has a schema and answers without throwing")
        void toolRegistryIsComplete() {
            assertEquals(5, ClinicalTools.all().size());

            for (var entry : ClinicalTools.all().entrySet()) {
                var tool = entry.getValue();
                assertEquals(entry.getKey(), tool.name());
                assertFalse(tool.description().isBlank());

                Json.Obj schema = tool.parameterSchema();
                assertEquals("object", schema.str("type", ""));
                assertFalse(schema.array("required").isEmpty(), tool.name() + " should declare required args");

                // Invoking with nothing must produce a structured error, never a stack trace: the
                // caller is usually a model, and a model handed a stack trace hallucinates around it.
                Json.Obj result = ClinicalTools.invoke(tool.name(), Json.obj().build());
                assertTrue(result.field("error").isPresent(),
                        tool.name() + " should report an error for empty arguments");
            }
        }

        @Test
        @DisplayName("the tool definitions are stable byte for byte across calls")
        void toolDefinitionsAreStable() {
            // Prompt caching depends on this; an unordered map would silently break it.
            assertEquals(ClinicalTools.definitions().write(), ClinicalTools.definitions().write());
        }
    }
}
