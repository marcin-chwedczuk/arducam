package pl.marcinchwedczuk.template.domain.protocol;

import java.time.Duration;

/** Supported {@code PICTURE} configurations, see PROTOCOL.md section 6.7. */
public enum PictureMode {
    // PICTURE resets all registers, including auto exposure. Settle for at least ~5 frames,
    // otherwise exposure varies from shot to shot. Frame periods: PROTOCOL.md section 9.
    // Timeouts: values suggested by PROTOCOL.md section 8 (for settle=1000) plus the extra settle time.

    /** Frame 400 ms, 5 frames. */
    QQVGA_YUYV("QQVGA 160×120 YUYV", "fmt=yuyv res=qqvga settle=2000", Duration.ofSeconds(16)),
    /** Frame 800 ms, 5 frames. */
    QVGA_YUYV("QVGA 320×240 YUYV", "fmt=yuyv res=qvga settle=4000", Duration.ofSeconds(28)),
    /** Frame ~800 ms, 10 frames (can be lowered to ~4 s after the firmware clock change). */
    VGA_BAYER("VGA 640×480 Bayer", "fmt=bayer res=vga settle=8000", Duration.ofSeconds(45)),
    COLOR_BARS("Color bars 160×120", "fmt=yuyv res=qqvga pattern=bars auto=off settle=0", Duration.ofSeconds(15));

    private final String displayName;
    private final String arguments;
    private final Duration timeout;

    PictureMode(String displayName, String arguments, Duration timeout) {
        this.displayName = displayName;
        this.arguments = arguments;
        this.timeout = timeout;
    }

    public String command() {
        return "PICTURE " + arguments;
    }

    public Duration timeout() {
        return timeout;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
