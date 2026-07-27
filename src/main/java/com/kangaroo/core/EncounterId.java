package com.kangaroo.core;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * An opaque encounter identifier.
 *
 * <p>Deliberately not derived from anything about the infant. Kangaroo records are meant to be
 * linkable across visits by an explicit, consented pairing step — never by an identifier that
 * leaks a name, a date of birth or a location into a filename.
 */
public record EncounterId(String value) implements Comparable<EncounterId> {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    public EncounterId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("encounter id must not be blank");
        }
    }

    public static EncounterId random() {
        byte[] b = new byte[12];
        RANDOM.nextBytes(b);
        return new EncounterId("enc_" + HEX.formatHex(b));
    }

    public static EncounterId of(String value) {
        return new EncounterId(value);
    }

    /** Safe to use as a filename on every platform Kangaroo targets. */
    public String fileName() {
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    @Override public int compareTo(EncounterId o) { return value.compareTo(o.value); }

    @Override public String toString() { return value; }
}
