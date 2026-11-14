package pl.marcinchwedczuk.template.domain.protocol;

import pl.marcinchwedczuk.template.domain.protocol.CameraConfig.Resolution;

/** Camera configurations offered in the GUI. Any other {@link CameraConfig} can be used directly. */
public enum PictureMode {
    QQVGA_YUYV("QQVGA 160×120 YUYV", CameraConfig.of(PixelFormat.YUYV, Resolution.QQVGA)),
    QVGA_YUYV("QVGA 320×240 YUYV", CameraConfig.of(PixelFormat.YUYV, Resolution.QVGA)),
    /** Black and white (Y8): half the transfer time of YUYV. Needs protocol v3 (firmware 0.3.0+). */
    QQVGA_BW("QQVGA 160×120 B&W", CameraConfig.of(PixelFormat.Y8, Resolution.QQVGA)),
    /** Black and white (Y8): half the transfer time of YUYV. Needs protocol v3 (firmware 0.3.0+). */
    QVGA_BW("QVGA 320×240 B&W", CameraConfig.of(PixelFormat.Y8, Resolution.QVGA)),
    VGA_BAYER("VGA 640×480 Bayer", CameraConfig.of(PixelFormat.BAYER, Resolution.VGA)),
    COLOR_BARS("Color bars 160×120",
            CameraConfig.of(PixelFormat.YUYV, Resolution.QQVGA).withPattern(true).withAuto(false));

    private final String displayName;
    private final CameraConfig config;

    PictureMode(String displayName, CameraConfig config) {
        this.displayName = displayName;
        this.config = config;
    }

    public CameraConfig config() {
        return config;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
