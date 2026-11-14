package pl.marcinchwedczuk.template.domain.protocol;

import java.time.Duration;

/** Supported {@code PICTURE} configurations, see PROTOCOL.md section 6.7. */
public enum PictureMode {
    QQVGA_YUYV("QQVGA 160×120 YUYV", "fmt=yuyv res=qqvga", Duration.ofSeconds(15)),
    QVGA_YUYV("QVGA 320×240 YUYV", "fmt=yuyv res=qvga", Duration.ofSeconds(25)),
    // Longer settle time, with the default 1000 ms pictures are over exposed.
    // Timeout: 35 s suggested by PROTOCOL.md for settle=1000, plus 7 s extra settle time.
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
