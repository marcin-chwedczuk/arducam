package pl.marcinchwedczuk.template.domain.protocol;

public enum PixelFormat {
    /** 2 bytes/pixel, Y0 U0 Y1 V0. */
    YUYV,
    /** Grayscale, 1 byte/pixel (the Y bytes of YUYV). Protocol v3. */
    Y8,
    /** Raw sensor data, 1 byte/pixel, BGGR pattern. */
    BAYER;

    public int bytesPerPixel() {
        return this == YUYV ? 2 : 1;
    }

    /** Value of the {@code fmt=} argument of {@code CONFIG}. */
    public String configValue() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static PixelFormat parse(String s) {
        return switch (s.toUpperCase()) {
            case "YUYV" -> YUYV;
            case "Y8" -> Y8;
            case "BAYER" -> BAYER;
            default -> throw new IllegalArgumentException("Unknown pixel format: " + s);
        };
    }
}
