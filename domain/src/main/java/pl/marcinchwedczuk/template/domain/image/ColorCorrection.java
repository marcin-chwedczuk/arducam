package pl.marcinchwedczuk.template.domain.image;

/**
 * Colour processing for raw Bayer images. YUYV images do not need it: the camera applies
 * gamma and white balance itself, but raw Bayer data is linear and unbalanced.
 */
public final class ColorCorrection {
    /** Standard display gamma, values are raised to 1/2.2. */
    public static final double DISPLAY_GAMMA = 2.2;
    /** White balance gains are limited, so that a scene dominated by one colour is not over-corrected. */
    public static final double MIN_WB_GAIN = 0.5;
    public static final double MAX_WB_GAIN = 4.0;

    private ColorCorrection() { }

    /** Gray-world white balance followed by display gamma. */
    public static RgbImage correctRawImage(RgbImage linear) {
        return applyGamma(grayWorldWhiteBalance(linear), DISPLAY_GAMMA);
    }

    /**
     * Gray-world white balance: scales R and B so that their averages match the
     * average of G. Must be applied to linear data, before gamma.
     */
    public static RgbImage grayWorldWhiteBalance(RgbImage image) {
        int[] pixels = image.pixels();

        long sumR = 0, sumG = 0, sumB = 0;
        for (int p : pixels) {
            sumR += (p >> 16) & 0xff;
            sumG += (p >> 8) & 0xff;
            sumB += p & 0xff;
        }

        double gainR = gain(sumG, sumR);
        double gainB = gain(sumG, sumB);

        int[] lutR = scaleLut(gainR);
        int[] lutB = scaleLut(gainB);
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int r = lutR[(p >> 16) & 0xff];
            int g = (p >> 8) & 0xff;
            int b = lutB[p & 0xff];
            pixels[i] = (r << 16) | (g << 8) | b;
        }

        return new RgbImage(image.width(), image.height(), pixels);
    }

    private static double gain(long sumG, long sumChannel) {
        if (sumG == 0 || sumChannel == 0) return 1.0;
        double g = (double) sumG / sumChannel;
        return Math.max(MIN_WB_GAIN, Math.min(MAX_WB_GAIN, g));
    }

    private static int[] scaleLut(double gain) {
        int[] lut = new int[256];
        for (int v = 0; v < 256; v++) {
            lut[v] = clamp((int) Math.round(v * gain));
        }
        return lut;
    }

    /** Raises every channel to {@code 1/gamma}: {@code out = 255 * (in / 255)^(1/gamma)}. */
    public static RgbImage applyGamma(RgbImage image, double gamma) {
        int[] lut = gammaLut(gamma);
        int[] pixels = image.pixels();
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            pixels[i] = (lut[(p >> 16) & 0xff] << 16) | (lut[(p >> 8) & 0xff] << 8) | lut[p & 0xff];
        }
        return new RgbImage(image.width(), image.height(), pixels);
    }

    static int[] gammaLut(double gamma) {
        int[] lut = new int[256];
        for (int v = 0; v < 256; v++) {
            lut[v] = clamp((int) Math.round(255.0 * Math.pow(v / 255.0, 1.0 / gamma)));
        }
        return lut;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }
}
