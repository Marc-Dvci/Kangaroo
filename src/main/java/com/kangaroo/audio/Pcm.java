package com.kangaroo.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * A decoded mono audio clip, normalised to floats in [-1, 1].
 *
 * <p>Kangaroo reads WAV and nothing else, and the client is written to record WAV for exactly that
 * reason. A browser's {@code MediaRecorder} produces Opus in a WebM container, which cannot be
 * decoded without pulling in a codec — and adding a codec dependency to a project whose entire
 * claim is that it has none is a bad trade for a format the client can simply avoid emitting. The
 * client captures raw samples through the Web Audio API and writes the RIFF header itself.
 *
 * @param samples    mono, normalised to [-1, 1]
 * @param sampleRate hertz
 * @param clipped    how many samples sat at full scale, which is what a too-close microphone
 *                   produces and the reason a recording may have to be refused
 */
public record Pcm(float[] samples, int sampleRate, int clipped) {

    /** Below this there is not enough bandwidth to place a newborn cry's fundamental. */
    public static final int MIN_SAMPLE_RATE = 8_000;

    public double durationSeconds() {
        return sampleRate <= 0 ? 0 : (double) samples.length / sampleRate;
    }

    /** The fraction of samples at full scale. */
    public double clippedFraction() {
        return samples.length == 0 ? 0 : (double) clipped / samples.length;
    }

    /**
     * Decode a RIFF/WAVE clip holding 16-bit or 8-bit integer PCM, or 32-bit float PCM.
     *
     * <p>Multi-channel input is averaged to mono: a phone's two microphones pointed at the same
     * infant are two views of one signal, and the analysis wants the signal.
     *
     * @throws IllegalArgumentException if this is not a WAV, or is a compressed one
     */
    public static Pcm decodeWav(byte[] bytes) {
        if (bytes == null || bytes.length < 44) {
            throw new IllegalArgumentException("not a WAV file: too short");
        }
        ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        if (!tag(in, 0).equals("RIFF") || !tag(in, 8).equals("WAVE")) {
            throw new IllegalArgumentException("not a WAV file: missing RIFF/WAVE header");
        }

        int format = -1;
        int channels = 0;
        int sampleRate = 0;
        int bitsPerSample = 0;
        int dataOffset = -1;
        int dataLength = 0;

        // Walk the chunk list rather than assuming the canonical 44-byte layout: recorders insert
        // LIST and fact chunks, and a fixed offset reads those as audio.
        int position = 12;
        while (position + 8 <= bytes.length) {
            String id = tag(in, position);
            int size = in.getInt(position + 4);
            if (size < 0) break;
            int body = position + 8;

            switch (id) {
                case "fmt " -> {
                    if (body + 16 > bytes.length) throw new IllegalArgumentException("truncated fmt chunk");
                    format = in.getShort(body) & 0xffff;
                    channels = in.getShort(body + 2) & 0xffff;
                    sampleRate = in.getInt(body + 4);
                    bitsPerSample = in.getShort(body + 14) & 0xffff;
                }
                case "data" -> {
                    dataOffset = body;
                    dataLength = Math.min(size, bytes.length - body);
                }
                default -> { /* skip */ }
            }
            // Chunks are padded to an even length.
            position = body + size + (size & 1);
        }

        if (dataOffset < 0 || channels <= 0 || sampleRate <= 0) {
            throw new IllegalArgumentException("WAV is missing a fmt or data chunk");
        }
        boolean integerPcm = format == 1;
        boolean floatPcm = format == 3;
        if (!integerPcm && !floatPcm) {
            throw new IllegalArgumentException(
                    "WAV is compressed (format " + format + "); only uncompressed PCM is read");
        }

        if (bitsPerSample == 32 && !floatPcm) {
            // 32-bit integer PCM exists but no browser produces it, and reading it wrong would be
            // silent rather than loud. Refusing is the safer of the two.
            throw new IllegalArgumentException("32-bit integer PCM is not read; record 16-bit or float");
        }
        return switch (bitsPerSample) {
            case 16 -> fromShorts(in, dataOffset, dataLength, channels, sampleRate);
            case 8 -> fromBytes(bytes, dataOffset, dataLength, channels, sampleRate);
            case 32 -> fromFloats(in, dataOffset, dataLength, channels, sampleRate);
            default -> throw new IllegalArgumentException(
                    "unsupported sample width: " + bitsPerSample + " bits");
        };
    }

    private static Pcm fromShorts(ByteBuffer in, int offset, int length, int channels, int rate) {
        int frames = length / (2 * channels);
        float[] out = new float[frames];
        int clipped = 0;
        for (int f = 0; f < frames; f++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) {
                short s = in.getShort(offset + (f * channels + c) * 2);
                if (s == Short.MAX_VALUE || s == Short.MIN_VALUE) clipped++;
                sum += s;
            }
            out[f] = (float) (sum / (double) channels / 32768.0);
        }
        return new Pcm(out, rate, clipped / channels);
    }

    private static Pcm fromBytes(byte[] bytes, int offset, int length, int channels, int rate) {
        int frames = length / channels;
        float[] out = new float[frames];
        int clipped = 0;
        for (int f = 0; f < frames; f++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) {
                int u = bytes[offset + f * channels + c] & 0xff;   // 8-bit WAV is unsigned
                if (u == 0 || u == 255) clipped++;
                sum += u - 128;
            }
            out[f] = (float) (sum / (double) channels / 128.0);
        }
        return new Pcm(out, rate, clipped / channels);
    }

    private static Pcm fromFloats(ByteBuffer in, int offset, int length, int channels, int rate) {
        int frames = length / (4 * channels);
        float[] out = new float[frames];
        int clipped = 0;
        for (int f = 0; f < frames; f++) {
            double sum = 0;
            for (int c = 0; c < channels; c++) {
                float s = in.getFloat(offset + (f * channels + c) * 4);
                if (Math.abs(s) >= 0.999f) clipped++;
                sum += s;
            }
            out[f] = (float) Math.clamp(sum / channels, -1.0, 1.0);
        }
        return new Pcm(out, rate, clipped / channels);
    }

    private static String tag(ByteBuffer in, int offset) {
        if (offset + 4 > in.capacity()) return "";
        byte[] t = new byte[4];
        for (int i = 0; i < 4; i++) t[i] = in.get(offset + i);
        return new String(t, java.nio.charset.StandardCharsets.US_ASCII);
    }

    /** Records with array components need these written out to compare by content. */
    @Override public boolean equals(Object o) {
        return o instanceof Pcm p && p.sampleRate == sampleRate && p.clipped == clipped
                && Arrays.equals(p.samples, samples);
    }

    @Override public int hashCode() {
        return Arrays.hashCode(samples) * 31 + sampleRate;
    }

    @Override public String toString() {
        return "Pcm[%.2f s at %d Hz, %d samples]".formatted(durationSeconds(), sampleRate, samples.length);
    }
}
