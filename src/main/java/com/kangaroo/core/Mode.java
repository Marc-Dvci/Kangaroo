package com.kangaroo.core;

import java.util.Locale;

/**
 * Two front doors, one clinical engine.
 *
 * <p>They share the protocol, the deterministic tools, the audit trail and the models. They differ
 * in vocabulary, in pacing, and — importantly — in how readily they escalate. A parent has less
 * signal and more at stake than a trained health worker, so {@link #PARENT} refers on a lower
 * threshold. See {@link #escalateBorderline()}.
 */
public enum Mode {

    /** A first-time parent at 3 a.m. Plain language, warm, deliberately conservative. */
    PARENT,

    /** A community health worker running the full WHO IMNCI young-infant assessment. */
    CHW;

    /**
     * Parent mode promotes a borderline GREEN to YELLOW ("worth someone looking at today").
     * CHW mode does not, because a trained observer has already excluded the things a parent cannot.
     */
    public boolean escalateBorderline() {
        return this == PARENT;
    }

    public static Mode parse(String s) {
        if (s == null) return CHW;
        return s.trim().toLowerCase(Locale.ROOT).startsWith("p") ? PARENT : CHW;
    }
}
