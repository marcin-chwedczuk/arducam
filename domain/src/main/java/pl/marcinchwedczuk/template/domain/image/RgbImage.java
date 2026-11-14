package pl.marcinchwedczuk.template.domain.image;

/**
 * Immutable RGB image, pixels stored row by row as {@code 0xRRGGBB}.
 */
public final class RgbImage {
    private final int width;
    private final int height;
    private final int[] pixels;

    public RgbImage(int width, int height, int[] pixels) {
        if (pixels.length != width * height) {
            throw new IllegalArgumentException("Expected " + width * height + " pixels, got " + pixels.length);
        }
        this.width = width;
        this.height = height;
        this.pixels = pixels;
    }

    public int width() { return width; }

    public int height() { return height; }

    public int rgb(int x, int y) {
        return pixels[y * width + x];
    }

    /** Copy of the pixels, row by row, {@code 0xRRGGBB}. */
    public int[] pixels() {
        return pixels.clone();
    }

    /** Copy of the pixels as opaque ARGB, {@code 0xFFRRGGBB}. */
    public int[] argbPixels() {
        int[] argb = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            argb[i] = 0xff000000 | pixels[i];
        }
        return argb;
    }

    public RgbImage rotateClockwise() {
        int[] out = new int[pixels.length];
        // New image is height x width, new(x, y) = old(y, height - 1 - x)
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int nx = height - 1 - y;
                int ny = x;
                out[ny * height + nx] = pixels[y * width + x];
            }
        }
        return new RgbImage(height, width, out);
    }

    public RgbImage rotateCounterClockwise() {
        int[] out = new int[pixels.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int nx = y;
                int ny = width - 1 - x;
                out[ny * height + nx] = pixels[y * width + x];
            }
        }
        return new RgbImage(height, width, out);
    }

    /** Rotates by {@code quarterTurns * 90} degrees clockwise (negative values rotate counter-clockwise). */
    public RgbImage rotate(int quarterTurns) {
        int turns = Math.floorMod(quarterTurns, 4);
        RgbImage result = this;
        for (int i = 0; i < turns; i++) {
            result = result.rotateClockwise();
        }
        return result;
    }
}
