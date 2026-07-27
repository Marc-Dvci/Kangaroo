package com.kangaroo.store;

import com.kangaroo.core.Assessment;
import com.kangaroo.core.Encounter;
import com.kangaroo.core.Feature;
import com.kangaroo.core.JaundiceGrade;
import com.kangaroo.core.TrafficLight;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The longitudinal record: what has happened to this baby across visits.
 *
 * <p>This is the part that turns a series of snapshots into a trajectory, and trajectory is what
 * actually predicts deterioration in a neonate. A bilirubin level is much less informative than the
 * rate it is climbing at; a weight is much less informative than whether it is going up. A tool
 * that assesses each visit in isolation is throwing away the most predictive signal it has.
 *
 * <p>Entries are keyed by a caregiver-chosen local label, never by a name, a date of birth or a
 * location. The label never leaves the device unless the caregiver explicitly consents to a sync,
 * and the handover between parent mode and CHW mode is an explicit, consented pairing rather than
 * an implicit lookup — which is why {@link #trendFor} takes a reference the caller must already
 * have been given.
 */
public final class PatientMemory {

    /** How far back a trend is considered relevant. Beyond this the neonatal period is over. */
    private static final Duration WINDOW = Duration.ofDays(35);

    /** One recorded visit, reduced to what the trend needs. */
    public record Entry(
            Instant at,
            TrafficLight light,
            String classification,
            int ageDays,
            double weightKg,
            int jaundiceExtent,
            JaundiceGrade.Severity jaundiceSeverity,
            int respiratoryRate,
            int signCount) {}

    /**
     * @param summary   human-readable lines for the narrative and the interface
     * @param worsening true when the trajectory is going the wrong way
     */
    public record Trend(List<Entry> entries, List<String> summary, boolean worsening) {
        public Trend {
            entries = List.copyOf(entries);
            summary = List.copyOf(summary);
        }

        public static Trend empty() {
            return new Trend(List.of(), List.of(), false);
        }
    }

    private final Map<String, List<Entry>> bySubject = new ConcurrentHashMap<>();

    /** Record a completed assessment against the subject's local reference, if it has one. */
    public void record(Encounter encounter, Assessment assessment) {
        String ref = encounter.subjectRef();
        if (ref == null || ref.isBlank()) return;

        Entry entry = new Entry(
                encounter.capturedAt(),
                assessment.light(),
                assessment.classification().name(),
                encounter.subject().ageDays(),
                encounter.subject().weightKnown() ? encounter.subject().weightKg() : -1,
                assessment.profile().ordinal(Feature.JAUNDICE_EXTENT),
                assessment.jaundice().map(JaundiceGrade::severity).orElse(null),
                encounter.vitals().respiratoryRate(),
                assessment.signs().size());

        bySubject.computeIfAbsent(ref, _ -> java.util.Collections.synchronizedList(new ArrayList<>()))
                .add(entry);
    }

    /** Everything known about a subject, most recent first. */
    public List<Entry> historyFor(String subjectRef) {
        List<Entry> raw = bySubject.get(subjectRef);
        if (raw == null) return List.of();
        Instant cutoff = Instant.now().minus(WINDOW);
        synchronized (raw) {
            return raw.stream()
                    .filter(e -> e.at().isAfter(cutoff))
                    .sorted(Comparator.comparing(Entry::at).reversed())
                    .toList();
        }
    }

    /**
     * Summarise the trajectory.
     *
     * <p>Three things count as worsening, and each is a real clinical signal rather than a
     * heuristic: the traffic light going up between visits; jaundice extending further down the
     * body; and weight falling. The last one is deliberately generous — a newborn losing weight in
     * the second week is not physiological weight loss, it is a feeding problem.
     */
    public Trend trendFor(String subjectRef) {
        List<Entry> history = historyFor(subjectRef);
        if (history.size() < 2) {
            return history.isEmpty() ? Trend.empty()
                    : new Trend(history, List.of("First recorded check for this baby."), false);
        }

        List<String> summary = new ArrayList<>();
        boolean worsening = false;

        Entry latest = history.getFirst();
        Entry previous = history.get(1);
        Entry earliest = history.getLast();

        long days = Duration.between(earliest.at(), latest.at()).toDays();
        summary.add(history.size() + " checks over " + Math.max(days, 1)
                + (days <= 1 ? " day" : " days") + ".");

        if (latest.light().ordinal() > previous.light().ordinal()) {
            summary.add("The result has gone up from " + previous.light() + " to " + latest.light()
                    + " since the last check.");
            worsening = true;
        } else if (latest.light().ordinal() < previous.light().ordinal()) {
            summary.add("The result has improved from " + previous.light() + " to " + latest.light() + ".");
        }

        if (latest.jaundiceExtent() > previous.jaundiceExtent()) {
            summary.add("Yellow colour has spread further down the body since the last check "
                    + "(zone " + previous.jaundiceExtent() + " to zone " + latest.jaundiceExtent()
                    + "). The rate of spread matters more than any single reading.");
            worsening = true;
        }

        if (latest.weightKg() > 0 && previous.weightKg() > 0) {
            double delta = latest.weightKg() - previous.weightKg();
            if (delta < -0.05) {
                summary.add(String.format(
                        "Weight has fallen by %.2f kg since the last check.", -delta));
                worsening = true;
            } else if (delta > 0.05) {
                summary.add(String.format("Weight is up by %.2f kg since the last check.", delta));
            }
        }

        if (!worsening && summary.size() == 1) {
            summary.add("No change since the last check.");
        }

        return new Trend(history, summary, worsening);
    }

    /** How many subjects are being followed. For the console. */
    public int subjectCount() {
        return bySubject.size();
    }

    /** Forget everything about one subject. The caregiver's right, exercised locally. */
    public void forget(String subjectRef) {
        bySubject.remove(subjectRef);
    }

    public void clear() {
        bySubject.clear();
    }
}
