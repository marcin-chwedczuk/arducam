package pl.marcinchwedczuk.template.domain.image;

import pl.marcinchwedczuk.template.domain.protocol.RawImage;

/** Converts images received from the firmware to RGB. */
public final class ImageDecoder {
    private ImageDecoder() { }

    public static RgbImage decode(RawImage raw) {
        return switch (raw.format()) {
            case YUYV -> decodeYuyv(raw.width(), raw.height(), raw.data());
            case BAYER -> decodeBayerBggr(raw.width(), raw.height(), raw.data());
        };
    }

    /**
     * YUYV ({@code Y0 U0 Y1 V0}), BT.601 full range (OV7670 COM15 = 0xC0).
     */
    public static RgbImage decodeYuyv(int width, int height, byte[] data) {
        int[] pixels = new int[width * height];
        for (int i = 0, p = 0; p + 1 < pixels.length; i += 4, p += 2) {
            int y0 = data[i] & 0xff;
            int u = (data[i + 1] & 0xff) - 128;
            int y1 = data[i + 2] & 0xff;
            int v = (data[i + 3] & 0xff) - 128;

            pixels[p] = yuvToRgb(y0, u, v);
            pixels[p + 1] = yuvToRgb(y1, u, v);
        }
        return new RgbImage(width, height, pixels);
    }

    private static int yuvToRgb(int y, int u, int v) {
        int r = clamp(Math.round(y + 1.402f * v));
        int g = clamp(Math.round(y - 0.344f * u - 0.714f * v));
        int b = clamp(Math.round(y + 1.772f * u));
        return (r << 16) | (g << 8) | b;
    }

    /**
     * Raw Bayer, 1 byte/pixel, BGGR pattern: even rows {@code B G B G ...},
     * odd rows {@code G R G R ...}. Bilinear demosaicing, edges are clamped.
     */
    public static RgbImage decodeBayerBggr(int width, int height, byte[] data) {
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean evenRow = (y & 1) == 0;
                boolean evenCol = (x & 1) == 0;
                int r, g, b;

                if (evenRow && evenCol) {           // B
                    b = at(data, width, height, x, y);
                    g = avg4(at(data, width, height, x - 1, y), at(data, width, height, x + 1, y),
                            at(data, width, height, x, y - 1), at(data, width, height, x, y + 1));
                    r = avg4(at(data, width, height, x - 1, y - 1), at(data, width, height, x + 1, y - 1),
                            at(data, width, height, x - 1, y + 1), at(data, width, height, x + 1, y + 1));
                } else if (!evenRow && !evenCol) {  // R
                    r = at(data, width, height, x, y);
                    g = avg4(at(data, width, height, x - 1, y), at(data, width, height, x + 1, y),
                            at(data, width, height, x, y - 1), at(data, width, height, x, y + 1));
                    b = avg4(at(data, width, height, x - 1, y - 1), at(data, width, height, x + 1, y - 1),
                            at(data, width, height, x - 1, y + 1), at(data, width, height, x + 1, y + 1));
                } else if (evenRow) {               // G on a B row
                    g = at(data, width, height, x, y);
                    b = avg2(at(data, width, height, x - 1, y), at(data, width, height, x + 1, y));
                    r = avg2(at(data, width, height, x, y - 1), at(data, width, height, x, y + 1));
                } else {                            // G on an R row
                    g = at(data, width, height, x, y);
                    r = avg2(at(data, width, height, x - 1, y), at(data, width, height, x + 1, y));
                    b = avg2(at(data, width, height, x, y - 1), at(data, width, height, x, y + 1));
                }

                pixels[y * width + x] = (r << 16) | (g << 8) | b;
            }
        }
        return new RgbImage(width, height, pixels);
    }

    /**
     * Pixel value with coordinates mirrored at the edges (-1 -> 1, width -> width - 2),
     * which keeps the Bayer colour of the neighbour.
     */
    private static int at(byte[] data, int width, int height, int x, int y) {
        if (x < 0) x = Math.min(1, width - 1);
        if (x >= width) x = Math.max(width - 2, 0);
        if (y < 0) y = Math.min(1, height - 1);
        if (y >= height) y = Math.max(height - 2, 0);
        return data[y * width + x] & 0xff;
    }

    private static int avg2(int a, int b) {
        return (a + b + 1) / 2;
    }

    private static int avg4(int a, int b, int c, int d) {
        return (a + b + c + d + 2) / 4;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }
}
