package pl.marcinchwedczuk.template.domain.protocol;

import java.time.Duration;

/** Supported camera configurations, see PROTOCOL.md section 6.7. */
public enum PictureMode {
    // After CONFIG auto exposure starts from zero and needs ~5 frames to converge,
    // so the first PICTURE after CONFIG waits 'settle' ms. Later pictures need no settling.
    // Timeouts: PROTOCOL.md section 8, "settle + N s".

    /** Frame 400 ms. */
    QQVGA_YUYV("QQVGA 160×120 YUYV", "fmt=yuyv res=qqvga", 2000, Duration.ofSeconds(10)),
    /** Frame 800 ms. */
    QVGA_YUYV("QVGA 320×240 YUYV", "fmt=yuyv res=qvga", 4000, Duration.ofSeconds(15)),
    /** Frame 800 ms. */
    VGA_BAYER("VGA 640×480 Bayer", "fmt=bayer res=vga", 4000, Duration.ofSeconds(25)),
    /** Auto exposure is off, no settling needed. */
    COLOR_BARS("Color bars 160×120", "fmt=yuyv res=qqvga pattern=bars auto=off", 0, Duration.ofSeconds(10));

    private final String displayName;
    private final String configArguments;
    private final int firstPictureSettleMs;
    private final Duration pictureTimeoutWithoutSettle;

    PictureMode(String displayName, String configArguments, int firstPictureSettleMs,
                Duration pictureTimeoutWithoutSettle) {
        this.displayName = displayName;
        this.configArguments = configArguments;
        this.firstPictureSettleMs = firstPictureSettleMs;
        this.pictureTimeoutWithoutSettle = pictureTimeoutWithoutSettle;
    }

    public String configCommand() {
        return "CONFIG " + configArguments;
    }

    /** {@code PICTURE} command; {@code firstAfterConfig} adds the settle time. */
    public String pictureCommand(boolean firstAfterConfig) {
        return firstAfterConfig && firstPictureSettleMs > 0
                ? "PICTURE settle=" + firstPictureSettleMs
                : "PICTURE";
    }

    public Duration pictureTimeout(boolean firstAfterConfig) {
        return firstAfterConfig
                ? pictureTimeoutWithoutSettle.plusMillis(firstPictureSettleMs)
                : pictureTimeoutWithoutSettle;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
