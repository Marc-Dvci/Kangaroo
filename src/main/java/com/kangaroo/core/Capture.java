package com.kangaroo.core;

import java.util.Locale;

/**
 * One captured artifact — a still frame or an audio clip — plus where it came from.
 *
 * <p>The bytes are held raw and are never copied on the way to the colour pipeline or to the
 * multimodal model: the FFM layer decodes straight into an off-heap {@code MemorySegment} and
 * hands it to native code by address.
 *
 * @param kind      what the capture is meant to show
 * @param mediaType e.g. {@code image/jpeg}, {@code audio/wav}
 * @param bytes     the raw encoded artifact
 */
public record Capture(Kind kind, String mediaType, byte[] bytes) {

    public Capture {
        if (kind == null) throw new IllegalArgumentException("kind is required");
        if (bytes == null) bytes = new byte[0];
    }

    public int sizeBytes() { return bytes.length; }

    public boolean isImage() { return mediaType != null && mediaType.startsWith("image/"); }

    public boolean isAudio() { return mediaType != null && mediaType.startsWith("audio/"); }

    /** Records with array components need these written out to compare by content. */
    @Override public boolean equals(Object o) {
        return o instanceof Capture c
                && c.kind == kind
                && java.util.Objects.equals(c.mediaType, mediaType)
                && java.util.Arrays.equals(c.bytes, bytes);
    }

    @Override public int hashCode() {
        return java.util.Objects.hash(kind, mediaType) * 31 + java.util.Arrays.hashCode(bytes);
    }

    @Override public String toString() {
        return "Capture[" + kind + ", " + mediaType + ", " + bytes.length + " bytes]";
    }

    /**
     * The guided capture sequence. CHW mode walks all seven image captures; parent mode asks for
     * {@link #FACE}, {@link #CHEST} and {@link #CRY} only, because that is what a person holding a
     * baby one-handed at 3 a.m. can actually do.
     */
    public enum Kind {
        /** Face and sclera — jaundice, pallor, cyanosis around the mouth. */
        FACE(true),
        /** Chest and abdomen — indrawing, breathing pattern, colour. */
        CHEST(true),
        /** Umbilical stump — omphalitis, discharge, bleeding. */
        UMBILICUS(true),
        /** Skin survey — pustules. */
        SKIN(true),
        /** Palms and soles — the last Kramer zone, and far less skin-tone dependent. */
        PALMS_SOLES(true),
        /** Fontanelle. */
        FONTANELLE(true),
        /** The printed colour-reference card held beside the skin, for the colour transform. */
        COLOUR_CARD(true),
        /** Ten seconds of cry audio. */
        CRY(false),
        /** Voice intake in the caregiver's own language. */
        VOICE_INTAKE(false);

        private final boolean image;

        Kind(boolean image) { this.image = image; }

        public boolean isImageKind() { return image; }

        /** The three captures parent mode asks for. */
        public static java.util.List<Kind> parentSequence() {
            return java.util.List.of(FACE, CHEST, CRY);
        }

        /** The full CHW guided sequence. */
        public static java.util.List<Kind> chwSequence() {
            return java.util.List.of(FACE, CHEST, UMBILICUS, SKIN, PALMS_SOLES, FONTANELLE, COLOUR_CARD);
        }

        public static Kind parse(String s) {
            if (s == null) return FACE;
            try {
                return valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return FACE;
            }
        }
    }
}
