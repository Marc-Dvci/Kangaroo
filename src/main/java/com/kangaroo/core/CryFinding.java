package com.kangaroo.core;

/**
 * What the ten-second cry recording sounded like.
 *
 * <p>The counterpart to {@link JaundiceGrade}, and it carries the same two honesties. It reports an
 * <em>extent</em> measurement rather than a verdict — the fundamental frequency and how much
 * phonation was found — and it can {@link #graded() decline to grade} when the recording cannot
 * support a reading, which is a different answer from "the baby did not cry" and is never allowed
 * to be confused with it.
 *
 * <p>It lives in {@code core} rather than beside the analyser for the same reason
 * {@code JaundiceGrade} does: the domain model is what everything else agrees on, and pointing it
 * at an analysis package would put a cycle in the package graph.
 *
 * @param graded        false when the recording was refused
 * @param summary       one line for the audit grid, in words the user can act on
 * @param medianF0Hz    median fundamental over the voiced frames; 0 when nothing was voiced
 * @param voicedSeconds how much phonation was found in the clip
 */
public record CryFinding(boolean graded, String summary, double medianF0Hz, double voicedSeconds) {

    public CryFinding {
        if (summary == null) summary = "";
    }

    /** A recording that could not be read or graded, with the reason to show the user. */
    public static CryFinding refused(String reason) {
        return new CryFinding(false, reason, 0, 0);
    }
}
