package com.kangaroo.clinical;

import com.kangaroo.core.DangerSign;
import com.kangaroo.core.Subject;
import com.kangaroo.core.TrafficLight;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The referral letter that travels with the family.
 *
 * <p>Structured rather than free text, for a reason: the receiving clinician needs to know within
 * five seconds what was found, what was measured versus reported, what was already given, and — the
 * part most AI-assisted tools omit — that the assessment was machine-assisted and is decision
 * support, not a diagnosis. That disclaimer is not boilerplate. A clinician who assumes a referral
 * letter came from a trained examiner will anchor on it.
 */
public final class Referral {

    private Referral() {}

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /**
     * @param urgency        URGENT or ROUTINE
     * @param findings       what was found, each line carrying its provenance
     * @param measured       the numbers, separated out so they are impossible to miss
     * @param given          treatment already administered before transfer
     */
    public record Letter(
            String reference,
            Instant issuedAt,
            String urgency,
            String from,
            String to,
            String classification,
            int ageDays,
            double weightKg,
            String sex,
            List<String> findings,
            List<String> measured,
            List<String> given,
            String disclaimer) {

        public Letter {
            findings = List.copyOf(findings);
            measured = List.copyOf(measured);
            given = List.copyOf(given);
        }

        /** The printable letter, plain text so it renders identically on any device or printer. */
        public String render() {
            StringBuilder sb = new StringBuilder();
            sb.append("=".repeat(64)).append('\n');
            sb.append(urgency).append(" REFERRAL - YOUNG INFANT (0-28 DAYS)").append('\n');
            sb.append("=".repeat(64)).append('\n');
            sb.append("Reference : ").append(reference).append('\n');
            sb.append("Issued    : ").append(STAMP.format(issuedAt)).append('\n');
            sb.append("From      : ").append(from).append('\n');
            sb.append("To        : ").append(to).append('\n');
            sb.append('\n');
            sb.append("INFANT\n");
            sb.append("  Age    : ").append(ageDays >= 0 ? ageDays + " days" : "not known").append('\n');
            sb.append("  Weight : ").append(weightKg > 0 ? weightKg + " kg" : "not weighed").append('\n');
            sb.append("  Sex    : ").append(sex).append('\n');
            sb.append('\n');
            sb.append("CLASSIFICATION\n  ").append(classification).append('\n');
            sb.append('\n');
            section(sb, "FINDINGS", findings);
            section(sb, "MEASURED", measured);
            section(sb, "TREATMENT GIVEN BEFORE TRANSFER", given);
            sb.append("NOTE TO THE RECEIVING FACILITY\n");
            for (String line : wrap(disclaimer, 62)) sb.append("  ").append(line).append('\n');
            sb.append("=".repeat(64)).append('\n');
            return sb.toString();
        }

        private static void section(StringBuilder sb, String title, List<String> lines) {
            sb.append(title).append('\n');
            if (lines.isEmpty()) {
                sb.append("  (none recorded)\n");
            } else {
                for (String l : lines) {
                    List<String> wrapped = wrap(l, 60);
                    sb.append("  - ").append(wrapped.getFirst()).append('\n');
                    for (int i = 1; i < wrapped.size(); i++) sb.append("    ").append(wrapped.get(i)).append('\n');
                }
            }
            sb.append('\n');
        }

        private static List<String> wrap(String text, int width) {
            List<String> out = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : text.split("\\s+")) {
                if (line.length() + word.length() + 1 > width && !line.isEmpty()) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                if (!line.isEmpty()) line.append(' ');
                line.append(word);
            }
            if (!line.isEmpty()) out.add(line.toString());
            return out.isEmpty() ? List.of("") : out;
        }
    }

    public static final String DISCLAIMER =
            "This assessment was carried out by a community health worker using Kangaroo, an "
            + "offline decision-support tool that follows the WHO IMNCI young-infant protocol. "
            + "The classification is decision support and is not a diagnosis. Please perform a "
            + "complete clinical evaluation. Signs marked as reported were stated by the caregiver "
            + "and not verified by an examiner.";

    public static Letter generate(String reference,
                                  TrafficLight light,
                                  String classification,
                                  Subject subject,
                                  List<DangerSign> signs,
                                  List<String> treatmentGiven) {

        List<String> findings = new ArrayList<>();
        List<String> measured = new ArrayList<>();

        for (DangerSign s : signs) {
            // Exhaustive over the sealed hierarchy: a new kind of danger sign cannot be silently
            // dropped from a referral letter.
            switch (s) {
                case DangerSign.Measured m ->
                        measured.add(m.sign().label() + ": " + trim(m.value()) + " " + m.unit());
                case DangerSign.Visual v ->
                        findings.add(v.sign().label() + " (seen on the captured image)");
                case DangerSign.Auditory a ->
                        findings.add(a.sign().label() + " (heard in the cry recording)");
                case DangerSign.Reported r ->
                        findings.add(r.sign().label() + " (reported by caregiver)");
            }
        }

        return new Letter(
                reference,
                Instant.now(),
                light == TrafficLight.RED ? "URGENT" : "ROUTINE",
                "Community health worker, Kangaroo-assisted IMNCI assessment",
                "Receiving health facility",
                classification,
                subject.ageDays(),
                subject.weightKnown() ? subject.weightKg() : -1,
                subject.sex().name().toLowerCase(java.util.Locale.ROOT),
                findings,
                measured,
                treatmentGiven,
                DISCLAIMER);
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
