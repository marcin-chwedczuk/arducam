package pl.marcinchwedczuk.template.domain.protocol;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Camera configuration, everything the {@code CONFIG} command accepts (PROTOCOL.md section 6.7).
 * Immutable; use {@link #of} and the {@code with...} methods. The constructor validates the
 * rules of the protocol, so an invalid configuration fails before anything is sent.
 *
 * <p>Adjustments ({@code effect}, {@code brightness}, ...) are optional: {@code null} means
 * "not sent", the camera keeps its default.
 *
 * @param pattern    colour bar test pattern instead of the image
 * @param auto       automatic exposure, gain and white balance (AEC/AGC/AWB)
 * @param brightness -127..127, DSP, not with Bayer
 * @param contrast   0..255 (raw CONTRAS register), DSP, not with Bayer
 * @param saturation 0..255 (128 = normal, 0 = grey), DSP, not with Bayer
 * @param exposure   0..65535 line periods, only with {@code auto=false}
 * @param gain       0..255 (raw GAIN register), only with {@code auto=false}
 */
public record CameraConfig(PixelFormat format, Resolution resolution, boolean pattern, boolean auto,
                           Effect effect, Integer brightness, Integer contrast, Integer saturation,
                           Integer exposure, Integer gain) {

    public enum Resolution {
        QQVGA(160, 120, Duration.ofMillis(400), Duration.ofSeconds(10)),
        QVGA(320, 240, Duration.ofMillis(800), Duration.ofSeconds(15)),
        VGA(640, 480, Duration.ofMillis(800), Duration.ofSeconds(25));

        private final int width;
        private final int height;
        private final Duration framePeriod;
        private final Duration pictureTimeout;

        Resolution(int width, int height, Duration framePeriod, Duration pictureTimeout) {
            this.width = width;
            this.height = height;
            this.framePeriod = framePeriod;
            this.pictureTimeout = pictureTimeout;
        }

        public int width() { return width; }

        public int height() { return height; }

        public Duration framePeriod() { return framePeriod; }

        /** Suggested {@code PICTURE} timeout without settling, PROTOCOL.md section 8. */
        public Duration pictureTimeout() { return pictureTimeout; }

        public String configValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Effect {
        NONE, MONO, NEGATIVE, SEPIA;

        public String configValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Firmware limit for a command line, PROTOCOL.md section 3. */
    public static final int MAX_COMMAND_LENGTH = 159;

    /** Auto exposure needs about 5 frames to converge after {@code CONFIG}. */
    private static final int SETTLE_FRAMES = 5;

    public CameraConfig {
        if (format == null || resolution == null) {
            throw new IllegalArgumentException("format and resolution are required.");
        }

        boolean supported = switch (format) {
            case YUYV, Y8 -> resolution == Resolution.QQVGA || resolution == Resolution.QVGA;
            case BAYER -> resolution == Resolution.VGA;
        };
        if (!supported) {
            throw new IllegalArgumentException("Unsupported combination: " + format + " " + resolution
                    + " (supported: YUYV/Y8 QQVGA or QVGA, BAYER VGA).");
        }

        boolean dspAdjustment = effect != null || brightness != null || contrast != null || saturation != null;
        if (dspAdjustment && format == PixelFormat.BAYER) {
            throw new IllegalArgumentException(
                    "effect, brightness, contrast and saturation are DSP functions, not available with BAYER.");
        }
        if ((exposure != null || gain != null) && auto) {
            throw new IllegalArgumentException("exposure and gain need auto=off.");
        }

        checkRange("brightness", brightness, -127, 127);
        checkRange("contrast", contrast, 0, 255);
        checkRange("saturation", saturation, 0, 255);
        checkRange("exposure", exposure, 0, 65535);
        checkRange("gain", gain, 0, 255);
    }

    private static void checkRange(String name, Integer value, int min, int max) {
        if (value != null && (value < min || value > max)) {
            throw new IllegalArgumentException(name + " must be in " + min + ".." + max + ", got " + value + ".");
        }
    }

    /** Plain configuration: auto exposure on, no pattern, camera default adjustments. */
    public static CameraConfig of(PixelFormat format, Resolution resolution) {
        return new CameraConfig(format, resolution, false, true, null, null, null, null, null, null);
    }

    public CameraConfig withPattern(boolean pattern) {
        return new CameraConfig(format, resolution, pattern, auto, effect, brightness, contrast, saturation, exposure, gain);
    }

    public CameraConfig withAuto(boolean auto) {
        return new CameraConfig(format, resolution, pattern, auto, effect, brightness, contrast, saturation, exposure, gain);
    }

    public CameraConfig withEffect(Effect effect) {
        return new CameraConfig(format, resolution, pattern, auto, effect, brightness, contrast, saturation, exposure, gain);
    }

    public CameraConfig withBrightness(Integer brightness) {
        return new CameraConfig(format, resolution, pattern, auto, effect, brightness, contrast, saturation, exposure, gain);
    }

    public CameraConfig withContrast(Integer contrast) {
        return new CameraConfig(format, resolution, pattern, auto, effect, brightness, contrast, saturation, exposure, gain);
    }

    public CameraConfig withSaturation(Integer saturation) {
        return new CameraConfig(format, resolution, pattern, auto, effect, brightness, contrast, saturation, exposure, gain);
    }

    /** Manual exposure; requires {@code withAuto(false)} first. */
    public CameraConfig withExposure(Integer exposure) {
        return new CameraConfig(format, resolution, pattern, auto, effect, brightness, contrast, saturation, exposure, gain);
    }

    /** Manual gain; requires {@code withAuto(false)} first. */
    public CameraConfig withGain(Integer gain) {
        return new CameraConfig(format, resolution, pattern, auto, effect, brightness, contrast, saturation, exposure, gain);
    }

    /** True when the configuration uses arguments added in protocol v3. */
    public boolean needsProtocolV3() {
        return format == PixelFormat.Y8 || effect != null || brightness != null || contrast != null
                || saturation != null || exposure != null || gain != null;
    }

    /**
     * The {@code CONFIG} command. Adjustments are sent only when set. The longest possible
     * command is 131 characters, within {@link #MAX_COMMAND_LENGTH}.
     */
    public String configCommand() {
        List<String> args = new ArrayList<>();
        args.add("CONFIG");
        args.add("fmt=" + format.configValue());
        args.add("res=" + resolution.configValue());
        if (pattern) args.add("pattern=bars");
        if (!auto) args.add("auto=off");
        if (effect != null) args.add("effect=" + effect.configValue());
        if (brightness != null) args.add("brightness=" + brightness);
        if (contrast != null) args.add("contrast=" + contrast);
        if (saturation != null) args.add("saturation=" + saturation);
        if (exposure != null) args.add("exposure=" + exposure);
        if (gain != null) args.add("gain=" + gain);
        return String.join(" ", args);
    }

    /**
     * Settle time for the first picture after {@code CONFIG}: about 5 frames for auto exposure
     * to converge (QQVGA 2000 ms, QVGA/VGA 4000 ms), 0 when auto exposure is off.
     */
    public int firstPictureSettleMs() {
        return auto ? (int) resolution.framePeriod().toMillis() * SETTLE_FRAMES : 0;
    }

    public int imageBytes() {
        return resolution.width() * resolution.height() * format.bytesPerPixel();
    }

    /** {@code PICTURE} command; {@code firstAfterConfig} adds the settle time. */
    public String pictureCommand(boolean firstAfterConfig) {
        int settle = firstPictureSettleMs();
        return firstAfterConfig && settle > 0 ? "PICTURE settle=" + settle : "PICTURE";
    }

    public Duration pictureTimeout(boolean firstAfterConfig) {
        Duration base = resolution.pictureTimeout();
        return firstAfterConfig ? base.plusMillis(firstPictureSettleMs()) : base;
    }
}
