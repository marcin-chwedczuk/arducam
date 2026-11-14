package pl.marcinchwedczuk.template.domain.protocol;

import org.junit.jupiter.api.Test;
import pl.marcinchwedczuk.template.domain.protocol.CameraConfig.Effect;
import pl.marcinchwedczuk.template.domain.protocol.CameraConfig.Resolution;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CameraConfigTest {
    private final CameraConfig qqvga = CameraConfig.of(PixelFormat.YUYV, Resolution.QQVGA);

    @Test
    void presets_send_the_same_commands_as_before() {
        assertThat(PictureMode.QQVGA_YUYV.config().configCommand()).isEqualTo("CONFIG fmt=yuyv res=qqvga");
        assertThat(PictureMode.QVGA_YUYV.config().configCommand()).isEqualTo("CONFIG fmt=yuyv res=qvga");
        assertThat(PictureMode.VGA_BAYER.config().configCommand()).isEqualTo("CONFIG fmt=bayer res=vga");
        assertThat(PictureMode.COLOR_BARS.config().configCommand())
                .isEqualTo("CONFIG fmt=yuyv res=qqvga pattern=bars auto=off");
        for (PictureMode m : PictureMode.values()) {
            assertThat(m.config().needsProtocolV3()).as(m.name()).isFalse();
        }
    }

    @Test
    void only_given_adjustments_are_sent() {
        var config = qqvga.withEffect(Effect.SEPIA).withSaturation(0).withContrast(80).withBrightness(-20);

        assertThat(config.configCommand())
                .isEqualTo("CONFIG fmt=yuyv res=qqvga effect=sepia brightness=-20 contrast=80 saturation=0");
        assertThat(config.needsProtocolV3()).isTrue();
    }

    @Test
    void longest_possible_command_fits_firmware_line_limit() {
        var config = CameraConfig.of(PixelFormat.YUYV, Resolution.QQVGA)
                .withPattern(true).withAuto(false).withEffect(Effect.NEGATIVE)
                .withBrightness(-127).withContrast(255).withSaturation(255)
                .withExposure(65535).withGain(255);

        // 131 chars with fmt=yuyv, the default format is dropped to fit.
        assertThat(config.configCommand())
                .hasSizeLessThanOrEqualTo(CameraConfig.MAX_COMMAND_LENGTH)
                .startsWith("CONFIG res=qqvga pattern=bars")
                .doesNotContain("fmt=");

        // Shorter commands keep the explicit format.
        assertThat(config.withPattern(false).configCommand()).startsWith("CONFIG fmt=yuyv res=qqvga");
    }

    @Test
    void settle_time_is_five_frames_with_auto_exposure() {
        assertThat(qqvga.firstPictureSettleMs()).isEqualTo(2000);
        assertThat(CameraConfig.of(PixelFormat.Y8, Resolution.QVGA).firstPictureSettleMs()).isEqualTo(4000);
        assertThat(CameraConfig.of(PixelFormat.BAYER, Resolution.VGA).firstPictureSettleMs()).isEqualTo(4000);
        assertThat(qqvga.withAuto(false).firstPictureSettleMs()).isZero();

        assertThat(qqvga.pictureCommand(true)).isEqualTo("PICTURE settle=2000");
        assertThat(qqvga.pictureCommand(false)).isEqualTo("PICTURE");
        assertThat(qqvga.pictureTimeout(true)).isEqualTo(Duration.ofSeconds(12));
        assertThat(qqvga.pictureTimeout(false)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void image_sizes_match_protocol_table() {
        assertThat(CameraConfig.of(PixelFormat.YUYV, Resolution.QVGA).imageBytes()).isEqualTo(153_600);
        assertThat(CameraConfig.of(PixelFormat.Y8, Resolution.QQVGA).imageBytes()).isEqualTo(19_200);
        assertThat(CameraConfig.of(PixelFormat.Y8, Resolution.QVGA).imageBytes()).isEqualTo(76_800);
        assertThat(CameraConfig.of(PixelFormat.BAYER, Resolution.VGA).imageBytes()).isEqualTo(307_200);
    }

    @Test
    void unsupported_format_resolution_combinations_are_rejected() {
        assertThatThrownBy(() -> CameraConfig.of(PixelFormat.YUYV, Resolution.VGA))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported");
        assertThatThrownBy(() -> CameraConfig.of(PixelFormat.Y8, Resolution.VGA))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CameraConfig.of(PixelFormat.BAYER, Resolution.QVGA))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dsp_adjustments_are_rejected_for_bayer() {
        var bayer = CameraConfig.of(PixelFormat.BAYER, Resolution.VGA);

        assertThatThrownBy(() -> bayer.withEffect(Effect.MONO)).hasMessageContaining("DSP");
        assertThatThrownBy(() -> bayer.withBrightness(0)).hasMessageContaining("DSP");
        assertThatThrownBy(() -> bayer.withContrast(64)).hasMessageContaining("DSP");
        assertThatThrownBy(() -> bayer.withSaturation(128)).hasMessageContaining("DSP");
        // Exposure works with Bayer.
        assertThat(bayer.withAuto(false).withExposure(100).configCommand())
                .isEqualTo("CONFIG fmt=bayer res=vga auto=off exposure=100");
    }

    @Test
    void exposure_and_gain_need_auto_off() {
        assertThatThrownBy(() -> qqvga.withExposure(100)).hasMessageContaining("auto=off");
        assertThatThrownBy(() -> qqvga.withGain(10)).hasMessageContaining("auto=off");
        // Turning auto back on with manual values set is also rejected.
        var manual = qqvga.withAuto(false).withExposure(100);
        assertThatThrownBy(() -> manual.withAuto(true)).hasMessageContaining("auto=off");
    }

    @Test
    void ranges_are_checked() {
        assertThatThrownBy(() -> qqvga.withBrightness(128)).hasMessageContaining("brightness");
        assertThatThrownBy(() -> qqvga.withBrightness(-128)).hasMessageContaining("brightness");
        assertThatThrownBy(() -> qqvga.withContrast(256)).hasMessageContaining("contrast");
        assertThatThrownBy(() -> qqvga.withSaturation(-1)).hasMessageContaining("saturation");
        var manual = qqvga.withAuto(false);
        assertThatThrownBy(() -> manual.withExposure(65536)).hasMessageContaining("exposure");
        assertThatThrownBy(() -> manual.withGain(256)).hasMessageContaining("gain");

        // Bounds are inclusive.
        qqvga.withBrightness(-127).withBrightness(127).withContrast(0).withSaturation(255);
        manual.withExposure(0).withExposure(65535).withGain(255);
    }

    @Test
    void configurations_with_same_values_are_equal() {
        assertThat(qqvga.withBrightness(10)).isEqualTo(CameraConfig.of(PixelFormat.YUYV, Resolution.QQVGA).withBrightness(10));
        assertThat(qqvga.withBrightness(10)).isNotEqualTo(qqvga);
    }
}
