package com.kangaroo.core;

/**
 * Which rung of the inference ladder actually served an encounter.
 *
 * <p>Kangaroo shows this to the user as an honest badge rather than hiding it. The invariant that
 * makes degradation safe is that the traffic light is decided the same way at every rung — what
 * changes as you descend is the quality of the explanation, not the safety of the answer.
 *
 * <pre>
 *   CLOUD_HTTP3 -> CLOUD_HTTP2 -> CLOUD_HTTP1 -> LOCAL_SERVER -> NATIVE -> DETERMINISTIC
 * </pre>
 *
 * The bottom rung needs no model, no network and no native library, and still produces a valid WHO
 * classification. That is what "it never goes dark" means.
 */
public enum Rung {

    CLOUD_HTTP3("Cloud model over HTTP/3", 0),
    CLOUD_HTTP2("Cloud model over HTTP/2", 1),
    CLOUD_HTTP1("Cloud model over HTTP/1.1", 2),
    LOCAL_SERVER("Model server on the local network", 3),
    NATIVE("On-device model, in-process", 4),
    DETERMINISTIC("Deterministic WHO rules only", 5);

    private final String label;
    private final int depth;

    Rung(String label, int depth) {
        this.label = label;
        this.depth = depth;
    }

    public String label() { return label; }

    public int depth() { return depth; }

    /** True when this rung ran entirely on the device, with no network involved. */
    public boolean offline() {
        return this == NATIVE || this == DETERMINISTIC;
    }

    /** True when the encounter left the device. Drives the consent indicator in the UI. */
    public boolean leftTheDevice() {
        return switch (this) {
            case CLOUD_HTTP3, CLOUD_HTTP2, CLOUD_HTTP1, LOCAL_SERVER -> true;
            case NATIVE, DETERMINISTIC -> false;
        };
    }

    /** True when a language model contributed reasoning. The bottom rung has none. */
    public boolean hasNarrative() {
        return this != DETERMINISTIC;
    }
}
