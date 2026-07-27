package com.kangaroo.infer;

import com.kangaroo.audit.ClinicalEvents;
import com.kangaroo.core.Rung;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The ladder.
 *
 * <pre>
 *   cloud (HTTP/3 -&gt; HTTP/2 -&gt; HTTP/1.1)  -&gt;  local model server  -&gt;  on-device  -&gt;  deterministic
 * </pre>
 *
 * <p>Descends on connectivity, on latency, on cost policy, and on the privacy flag. The bottom rung
 * cannot fail, so this class always returns a narrative and never throws.
 *
 * <p>The invariant that makes the whole arrangement safe is stated once, here, and enforced by
 * construction: <b>no rung decides the traffic light.</b> Every engine returns a
 * {@link Narrative} whose {@code suggested} light is an opinion recorded for comparison. The
 * decision is made by the deterministic WHO rule and the calibrated head, which run before this
 * class is called and do not depend on it. Descending the ladder therefore degrades the prose and
 * nothing else — which is exactly the property that lets us pull the network cable on stage.
 *
 * <p>The order is deliberately cloud-first rather than device-first. A larger model writes a better
 * action plan, and when a network exists there is no reason not to use it. When one does not, the
 * user finds out from a badge rather than from a spinner.
 */
public final class FailoverEngine implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger("kangaroo.infer");

    private final List<InferenceEngine> ladder;
    private final DeterministicEngine floor;

    public FailoverEngine(List<InferenceEngine> ladder) {
        List<InferenceEngine> rungs = new ArrayList<>(ladder);
        rungs.sort(java.util.Comparator.comparingInt(e -> e.rung().depth()));
        this.ladder = List.copyOf(rungs);
        this.floor = rungs.stream()
                .filter(e -> e instanceof DeterministicEngine)
                .map(e -> (DeterministicEngine) e)
                .findFirst()
                .orElseGet(DeterministicEngine::new);
    }

    /** A ladder with nothing on it but the floor. Always valid, always works. */
    public static FailoverEngine deterministicOnly() {
        return new FailoverEngine(List.of(new DeterministicEngine()));
    }

    /**
     * Run the encounter down the ladder until a rung answers.
     *
     * <p>Never throws. A failure at every rung above the floor is a degraded experience, not an
     * error the caller has to handle — which is why there is no {@code throws} clause and no
     * {@code Optional} in the return type.
     */
    public Narrative explain(InferenceEngine.Request request) {
        List<String> attempted = new ArrayList<>();

        for (InferenceEngine engine : ladder) {
            if (engine instanceof DeterministicEngine) continue;

            // A privacy-flagged encounter never touches a network rung. This is enforced here,
            // before the engine is asked, rather than inside each engine -- a rule that has to be
            // re-implemented per engine is a rule that will eventually be forgotten in one.
            if (request.localOnly() && engine.rung().leftTheDevice()) {
                attempted.add(engine.rung().name() + " (skipped: encounter marked local-only)");
                continue;
            }

            if (!engine.available()) {
                attempted.add(engine.rung().name() + " (unavailable)");
                continue;
            }

            long t0 = System.nanoTime();
            try {
                Narrative n = engine.explain(request);
                if (!attempted.isEmpty()) {
                    LOG.log(System.Logger.Level.DEBUG,
                            () -> "served by " + n.rung() + " after skipping " + attempted);
                }
                return n;

            } catch (Exception e) {
                long ms = (System.nanoTime() - t0) / 1_000_000;
                String reason = e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage());
                attempted.add(engine.rung().name() + " (" + reason + ")");
                ClinicalEvents.failover(engine.rung().name(), nextRungName(engine), reason, "", ms);
                LOG.log(System.Logger.Level.DEBUG, () -> engine.rung() + " failed: " + reason);
            }
        }

        // The floor. No model, no network, no native library, and still a complete WHO answer.
        Narrative n = floor.explain(request);
        if (!attempted.isEmpty()) {
            LOG.log(System.Logger.Level.INFO,
                    () -> "descended to the deterministic rung after: " + attempted);
        }
        return n;
    }

    private String nextRungName(InferenceEngine current) {
        int i = ladder.indexOf(current);
        return i >= 0 && i + 1 < ladder.size()
                ? ladder.get(i + 1).rung().name()
                : Rung.DETERMINISTIC.name();
    }

    /** Every rung, in descent order, with its current availability — for the diagnostics screen. */
    public List<RungStatus> status() {
        List<RungStatus> out = new ArrayList<>();
        for (InferenceEngine e : ladder) {
            out.add(new RungStatus(e.rung(), e.describe(), e.available(),
                    e instanceof NativeEngine ne ? ne.unavailableReason() : ""));
        }
        if (ladder.stream().noneMatch(e -> e instanceof DeterministicEngine)) {
            out.add(new RungStatus(Rung.DETERMINISTIC, floor.describe(), true, ""));
        }
        return List.copyOf(out);
    }

    public record RungStatus(Rung rung, String description, boolean available, String reason) {}

    /** The highest rung that would be tried right now. */
    public Optional<Rung> preferredRung() {
        return ladder.stream()
                .filter(InferenceEngine::available)
                .map(InferenceEngine::rung)
                .findFirst();
    }

    @Override
    public void close() {
        for (InferenceEngine e : ladder) {
            try {
                e.close();
            } catch (RuntimeException ex) {
                LOG.log(System.Logger.Level.WARNING, "failed to close " + e.rung(), ex);
            }
        }
    }
}
