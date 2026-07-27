package com.kangaroo.color;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * A decoded image in planar float form: three separate channel arrays rather than interleaved
 * pixels.
 *
 * <p>The layout is chosen for the Vector API. Interleaved RGB forces a gather or a shuffle to get
 * eight red values into a lane vector; planar makes {@code FloatVector.fromArray(SPECIES, r, i)} a
 * single aligned load, which is the difference between a vectorised pipeline that is faster and one
 * that is theatre.
 *
 * <p>Values are 0-255 floats, not 0-1, because that is the scale the shipped model was trained on
 * and changing it would silently invalidate every threshold in it.
 */
public record Frame(int width, int height, float[] r, float[] g, float[] b) {

    public Frame {
        int n = width * height;
        if (r.length != n || g.length != n || b.length != n) {
            throw new IllegalArgumentException(
                    "channel length does not match " + width + "x" + height);
        }
    }

    public int pixels() { return width * height; }

    /** Decode an encoded image (JPEG, PNG, ...) using only the JDK's own image support. */
    public static Frame decode(byte[] encoded) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(encoded));
        if (img == null) throw new IOException("unsupported or corrupt image data");
        return of(img);
    }

    public static Frame of(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int n = w * h;
        float[] r = new float[n];
        float[] g = new float[n];
        float[] b = new float[n];

        // getRGB into a row buffer rather than per pixel: one array copy per row instead of
        // w*h virtual calls through the ColorModel.
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            int base = y * w;
            for (int x = 0; x < w; x++) {
                int p = row[x];
                r[base + x] = (p >> 16) & 0xFF;
                g[base + x] = (p >> 8) & 0xFF;
                b[base + x] = p & 0xFF;
            }
        }
        return new Frame(w, h, r, g, b);
    }

    /**
     * A rectangular sub-frame, copied out so the crop is contiguous and stays vectorisable.
     * Fractions are of width and height, in [0,1].
     */
    public Frame crop(double x0f, double y0f, double x1f, double y1f) {
        int x0 = clamp((int) (width * x0f), 0, width - 1);
        int x1 = clamp((int) (width * x1f), x0 + 1, width);
        int y0 = clamp((int) (height * y0f), 0, height - 1);
        int y1 = clamp((int) (height * y1f), y0 + 1, height);

        int cw = x1 - x0;
        int ch = y1 - y0;
        float[] cr = new float[cw * ch];
        float[] cg = new float[cw * ch];
        float[] cb = new float[cw * ch];
        for (int y = 0; y < ch; y++) {
            int src = (y0 + y) * width + x0;
            int dst = y * cw;
            System.arraycopy(r, src, cr, dst, cw);
            System.arraycopy(g, src, cg, dst, cw);
            System.arraycopy(b, src, cb, dst, cw);
        }
        return new Frame(cw, ch, cr, cg, cb);
    }

    /** A horizontal band, as a fraction of height. Used to cut the five Kramer zones. */
    public Frame band(double y0f, double y1f) {
        return crop(0.0, y0f, 1.0, y1f);
    }

    /** Mean luminance, used by the capture-quality gate to reject frames that are too dark. */
    public double meanLuma() {
        double sum = 0;
        for (int i = 0; i < r.length; i++) {
            sum += 0.2126 * r[i] + 0.7152 * g[i] + 0.0722 * b[i];
        }
        return sum / r.length;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
