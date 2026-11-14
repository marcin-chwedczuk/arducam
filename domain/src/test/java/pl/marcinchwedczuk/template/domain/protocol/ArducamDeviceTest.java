package pl.marcinchwedczuk.template.domain.protocol;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pl.marcinchwedczuk.template.domain.protocol.FakeArduino.text;

class ArducamDeviceTest {
    private FakeFirmware firmware;
    private FakeArduino arduino;
    private ArducamDevice device;

    @BeforeEach
    void setUp() {
        firmware = new FakeFirmware();
        arduino = new FakeArduino(firmware);
        device = new ArducamDevice(new ArducamClient(arduino.input(), arduino.output()));
    }

    @Test
    void handshake_checks_firmware_and_protocol() throws Exception {
        DeviceStatus status = device.handshake();

        assertThat(status.version()).isEqualTo("0.2.0");
        assertThat(status.protocol()).isEqualTo("3");
    }

    @Test
    void handshake_rejects_old_protocol() {
        firmware.protocol = "1";

        assertThatThrownBy(device::handshake)
                .isInstanceOf(DeviceException.class)
                .hasMessageContaining("protocol version: 1");
    }

    @Test
    void handshake_accepts_protocol_v2_and_v3() throws Exception {
        firmware.protocol = "2";
        assertThat(device.handshake().protocol()).isEqualTo("2");

        firmware.protocol = "3";
        assertThat(device.handshake().protocol()).isEqualTo("3");
    }

    @Test
    void v3_options_are_refused_on_v2_device_without_sending_anything() throws Exception {
        firmware.protocol = "2";
        device.handshake();
        int sent = arduino.receivedCommands().size();

        var y8 = CameraConfig.of(PixelFormat.Y8, CameraConfig.Resolution.QQVGA);
        assertThatThrownBy(() -> device.takePicture(y8))
                .isInstanceOf(DeviceException.class)
                .hasMessageContaining("protocol v3");
        assertThat(arduino.receivedCommands()).hasSize(sent);

        // Plain v2 configurations still work.
        device.takePicture(PictureMode.QQVGA_YUYV);
    }

    @Test
    void y8_picture_is_grayscale_one_byte_per_pixel() throws Exception {
        device.handshake();
        var config = CameraConfig.of(PixelFormat.Y8, CameraConfig.Resolution.QVGA);

        RawImage image = device.takePicture(config);

        assertThat(image.format()).isEqualTo(PixelFormat.Y8);
        assertThat(image.data()).hasSize(320 * 240);
        assertThat(arduino.receivedCommands()).containsSubsequence("CONFIG fmt=y8 res=qvga", "PICTURE settle=4000");
    }

    @Test
    void changed_adjustment_reconfigures_same_adjustment_does_not() throws Exception {
        device.handshake();
        var base = CameraConfig.of(PixelFormat.YUYV, CameraConfig.Resolution.QQVGA);

        device.takePicture(base.withBrightness(20));
        device.takePicture(base.withBrightness(20));
        device.takePicture(base.withBrightness(40));

        assertThat(arduino.receivedCommands()).filteredOn(c -> c.startsWith("CONFIG")).containsExactly(
                "CONFIG fmt=yuyv res=qqvga brightness=20",
                "CONFIG fmt=yuyv res=qqvga brightness=40");
    }

    @Test
    void manual_exposure_needs_no_settling() throws Exception {
        device.handshake();
        var manual = CameraConfig.of(PixelFormat.YUYV, CameraConfig.Resolution.QVGA)
                .withAuto(false).withExposure(300).withGain(16);

        device.takePicture(manual);

        assertThat(arduino.receivedCommands()).containsSubsequence(
                "CONFIG fmt=yuyv res=qvga auto=off exposure=300 gain=16", "PICTURE");
        assertThat(arduino.receivedCommands()).noneMatch(c -> c.contains("settle"));
    }

    @Test
    void handshake_rejects_other_firmware() {
        var other = new FakeArduino(cmd -> text("OK STATUS fw=other proto=2"));
        var otherDevice = new ArducamDevice(new ArducamClient(other.input(), other.output()));

        assertThatThrownBy(otherDevice::handshake)
                .isInstanceOf(DeviceException.class)
                .hasMessageContaining("other");
    }

    @Test
    void first_picture_configures_and_settles_next_pictures_only_capture() throws Exception {
        device.powerOn();

        RawImage first = device.takePicture(PictureMode.QVGA_YUYV);
        device.takePicture(PictureMode.QVGA_YUYV);
        device.takePicture(PictureMode.QVGA_YUYV);

        assertThat(arduino.receivedCommands()).containsExactly(
                "POWER on",
                "CONFIG fmt=yuyv res=qvga",
                "PICTURE settle=4000",
                "PICTURE",
                "PICTURE");
        assertThat(first.width()).isEqualTo(320);
        assertThat(first.height()).isEqualTo(240);
        assertThat(first.format()).isEqualTo(PixelFormat.YUYV);
        assertThat(first.data()).isEqualTo(firmware.image);
    }

    @Test
    void mode_change_reconfigures() throws Exception {
        device.powerOn();

        device.takePicture(PictureMode.QQVGA_YUYV);
        RawImage vga = device.takePicture(PictureMode.VGA_BAYER);
        device.takePicture(PictureMode.COLOR_BARS);

        assertThat(arduino.receivedCommands()).containsExactly(
                "POWER on",
                "CONFIG fmt=yuyv res=qqvga", "PICTURE settle=2000",
                "CONFIG fmt=bayer res=vga", "PICTURE settle=4000",
                "CONFIG fmt=yuyv res=qqvga pattern=bars auto=off", "PICTURE");
        assertThat(vga.format()).isEqualTo(PixelFormat.BAYER);
        assertThat(vga.width()).isEqualTo(640);
    }

    @Test
    void picture_without_power_powers_camera_on() throws Exception {
        device.takePicture(PictureMode.QQVGA_YUYV);

        assertThat(arduino.receivedCommands()).containsExactly(
                "POWER on", "CONFIG fmt=yuyv res=qqvga", "PICTURE settle=2000");
        assertThat(device.isPowered()).isTrue();
    }

    @Test
    void camera_test_clears_configuration() throws Exception {
        device.powerOn();
        device.takePicture(PictureMode.QQVGA_YUYV);

        device.cameraTest();
        device.takePicture(PictureMode.QQVGA_YUYV);

        assertThat(arduino.receivedCommands()).containsExactly(
                "POWER on", "CONFIG fmt=yuyv res=qqvga", "PICTURE settle=2000",
                "CAMTEST",
                "CONFIG fmt=yuyv res=qqvga", "PICTURE settle=2000");
    }

    @Test
    void firmware_reset_is_recovered_by_power_on_and_config() throws Exception {
        device.powerOn();
        device.takePicture(PictureMode.QQVGA_YUYV);

        firmware.reset();
        device.takePicture(PictureMode.QQVGA_YUYV);

        assertThat(arduino.receivedCommands()).containsExactly(
                "POWER on", "CONFIG fmt=yuyv res=qqvga", "PICTURE settle=2000",
                "PICTURE",                                     // ERR POWER_OFF
                "POWER on", "CONFIG fmt=yuyv res=qqvga", "PICTURE settle=2000");
    }

    @Test
    void lost_configuration_is_recovered() throws Exception {
        device.powerOn();
        device.takePicture(PictureMode.QQVGA_YUYV);

        firmware.config = null;   // e.g. someone sent RESET
        device.takePicture(PictureMode.QQVGA_YUYV);

        assertThat(arduino.receivedCommands()).containsExactly(
                "POWER on", "CONFIG fmt=yuyv res=qqvga", "PICTURE settle=2000",
                "PICTURE",                                     // ERR NOT_CONFIGURED
                "CONFIG fmt=yuyv res=qqvga", "PICTURE settle=2000");
    }

    @Test
    void failed_power_on_is_retried_by_next_camera_command() throws Exception {
        firmware.failPowerOn = 1;

        assertThatThrownBy(device::powerOn)
                .isInstanceOf(DeviceException.class)
                .hasMessageContaining("SCCB");
        assertThat(device.isPowered()).isFalse();

        device.cameraTest();

        assertThat(arduino.receivedCommands()).containsExactly("POWER on", "POWER on", "CAMTEST");
        assertThat(device.isPowered()).isTrue();
    }

    @Test
    void power_off_clears_configuration() throws Exception {
        device.powerOn();
        device.takePicture(PictureMode.QQVGA_YUYV);

        device.powerOff();
        device.powerOn();
        device.takePicture(PictureMode.QQVGA_YUYV);

        assertThat(arduino.receivedCommands()).containsExactly(
                "POWER on", "CONFIG fmt=yuyv res=qqvga", "PICTURE settle=2000",
                "POWER off", "POWER on",
                "CONFIG fmt=yuyv res=qqvga", "PICTURE settle=2000");
        assertThat(device.configuration()).isEqualTo(PictureMode.QQVGA_YUYV.config());
    }

    @Test
    void picture_error_is_reported_and_camera_stays_configured() throws Exception {
        var failing = new FakeArduino(cmd -> cmd.startsWith("PICTURE")
                ? text("ERR PICTURE NO_VSYNC")
                : firmware.apply(cmd));
        var d = new ArducamDevice(new ArducamClient(failing.input(), failing.output()));
        d.powerOn();

        assertThatThrownBy(() -> d.takePicture(PictureMode.QVGA_YUYV))
                .isInstanceOf(DeviceException.class)
                .hasMessageContaining("NO_VSYNC");
        assertThat(d.isPowered()).isTrue();
        assertThat(d.configuration()).isEqualTo(PictureMode.QVGA_YUYV.config());
    }

    @Test
    void settle_is_kept_for_next_picture_when_first_picture_fails() throws Exception {
        var fail = new boolean[] {true};
        var flaky = new FakeArduino(cmd -> {
            if (cmd.startsWith("PICTURE") && fail[0]) {
                fail[0] = false;
                return text("ERR PICTURE NO_VSYNC");
            }
            return firmware.apply(cmd);
        });
        var d = new ArducamDevice(new ArducamClient(flaky.input(), flaky.output()));

        assertThatThrownBy(() -> d.takePicture(PictureMode.QVGA_YUYV)).isInstanceOf(DeviceException.class);
        d.takePicture(PictureMode.QVGA_YUYV);

        assertThat(flaky.receivedCommands()).containsExactly(
                "POWER on", "CONFIG fmt=yuyv res=qvga", "PICTURE settle=4000", "PICTURE settle=4000");
    }

    @Test
    void bad_crc_is_fixed_by_reading_image_again() throws Exception {
        firmware.corruptCrc = 2;    // PICTURE and first READ

        RawImage image = device.takePicture(PictureMode.QQVGA_YUYV);

        assertThat(image.data()).isEqualTo(firmware.image);
        assertThat(arduino.receivedCommands()).containsExactly(
                "POWER on", "CONFIG fmt=yuyv res=qqvga", "PICTURE settle=2000", "READ", "READ");
    }

    @Test
    void gives_up_after_max_retries() {
        firmware.corruptCrc = 100;

        assertThatThrownBy(() -> device.takePicture(PictureMode.QQVGA_YUYV))
                .isInstanceOf(DeviceException.class)
                .hasMessageContaining("CRC");
        assertThat(arduino.receivedCommands()).filteredOn("READ"::equals).hasSize(ArducamDevice.MAX_READ_RETRIES);
    }

    @Test
    void camera_test_report_from_real_session() throws Exception {
        var a = new FakeArduino(cmd -> cmd.equals("CAMTEST")
                ? text("TEST sccb_id PASS pid=0x76 ver=0x73 midh=0x7f midl=0xa2",
                       "TEST sccb_rw PASS",
                       "TEST registers PASS checked=126 mismatched=0 critical=0 write_errors=0",
                       "TEST timing PASS frame_us=398736 vsync_us=2345 pclk_khz=501",
                       "TEST bars PASS edges=7 bad_lines=0 never1=0x00 never0=0x00",
                       "OK CAMTEST result=pass")
                : firmware.apply(cmd));
        var d = new ArducamDevice(new ArducamClient(a.input(), a.output()));

        TestReport report = d.cameraTest();

        assertThat(report.passed()).isTrue();
        assertThat(report.tests()).hasSize(5);
        assertThat(report.summary()).contains("Camera test: PASS", "timing PASS frame_us=398736");
    }

    @Test
    void failed_memory_test_is_reported_not_thrown() throws Exception {
        var a = new FakeArduino(cmd -> text("TEST data_path FAIL", "OK MEMTEST result=fail"));
        var d = new ArducamDevice(new ArducamClient(a.input(), a.output()));

        TestReport report = d.memoryTest();

        assertThat(report.passed()).isFalse();
        // MEMTEST does not need the camera.
        assertThat(a.receivedCommands()).containsExactly("MEMTEST");
    }
}
