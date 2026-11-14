package pl.marcinchwedczuk.template.domain.protocol;

public enum PixelFormat {
    /** 2 bytes/pixel, Y0 U0 Y1 V0. */
    YUYV,
    /** Raw sensor data, 1 byte/pixel, BGGR pattern. */
    BAYER;

    public static PixelFormat parse(String s) {
        return switch (s.toUpperCase()) {
            case "YUYV" -> YUYV;
            case "BAYER" -> BAYER;
            default -> throw new IllegalArgumentException("Unknown pixel format: " + s);
        };
    }
}
