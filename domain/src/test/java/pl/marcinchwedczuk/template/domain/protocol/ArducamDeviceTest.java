package pl.marcinchwedczuk.template.domain.protocol;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pl.marcinchwedczuk.template.domain.protocol.FakeArduino.*;

class ArducamDeviceTest {
    private static final byte[] IMAGE = new byte[160 * 120 * 2];
    static {
        for (int i = 0; i < IMAGE.length; i++) IMAGE[i] = (byte) i;
    }

    private static byte[] pictureResponse(int crc) {
        return concat(
                text("INFO configured 160x120 YUYV", "INFO settling 1000 ms"),
                dataBlock(IMAGE, 160, 120, "YUYV", crc),
                text("OK PICTURE w=160 h=120 fmt=YUYV bytes=38400"));
    }

    private static byte[] standardResponse(String cmd) {
        return switch (cmd) {
            case "POWER on" -> text("OK POWER state=on pid=0x76 ver=0x73");
            case "POWER off" -> text("OK POWER state=off");
            case "STATUS" -> text("OK STATUS fw=arducam ver=0.1.0 proto=1 power=off cam=unknown image=none");
            default -> text("ERR ? UNKNOWN_CMD " + cmd);
        };
    }

    @Test
    void handshake_checks_firmware() throws Exception {
        var arduino = new FakeArduino(ArducamDeviceTest::standardResponse);
        var device = new ArducamDevice(new ArducamClient(arduino.input(), arduino.output()));

        DeviceStatus status = device.handshake();

        assertThat(status.version()).isEqualTo("0.1.0");
        assertThat(status.power()).isEqualTo("off");
    }

    @Test
    void handshake_rejects_other_firmware() {
        var arduino = new FakeArduino(cmd -> text("OK STATUS fw=other proto=1"));
        var device = new ArducamDevice(new ArducamClient(arduino.input(), arduino.output()));

        assertThatThrownBy(device::handshake)
                .isInstanceOf(DeviceException.class)
                .hasMessageContaining("other");
    }

    @Test
    void picture_powers_camera_on_once_and_leaves_it_on() throws Exception {
        var arduino = new FakeArduino(cmd -> cmd.startsWith("PICTURE")
                ? pictureResponse(Crc16Xmodem.compute(IMAGE))
                : standardResponse(cmd));
        var device = new ArducamDevice(new ArducamClient(arduino.input(), arduino.output()));

        RawImage image = device.takePicture(PictureMode.QQVGA_YUYV);
        device.takePicture(PictureMode.QQVGA_YUYV);

        assertThat(arduino.receivedCommands()).containsExactly(
                "POWER on", "PICTURE fmt=yuyv res=qqvga settle=2000", "PICTURE fmt=yuyv res=qqvga settle=2000");
        assertThat(device.isPowered()).isTrue();
        assertThat(image.width()).isEqualTo(160);
        assertThat(image.format()).isEqualTo(PixelFormat.YUYV);
        assertThat(image.data()).isEqualTo(IMAGE);
    }

    @Test
    void explicit_power_on_and_off() throws Exception {
        var arduino = new FakeArduino(cmd -> cmd.startsWith("PICTURE")
                ? pictureResponse(Crc16Xmodem.compute(IMAGE))
                : standardResponse(cmd));
        var device = new ArducamDevice(new ArducamClient(arduino.input(), arduino.output()));

        device.powerOn();
        device.takePicture(PictureMode.QQVGA_YUYV);
        device.powerOff();

        assertThat(arduino.receivedCommands()).containsExactly(
                "POWER on", "PICTURE fmt=yuyv res=qqvga settle=2000", "POWER off");
        assertThat(device.isPowered()).isFalse();
    }

    @Test
    void camera_stays_on_when_picture_fails() throws Exception {
        var arduino = new FakeArduino(cmd -> cmd.startsWith("PICTURE")
                ? text("ERR PICTURE NO_VSYNC")
                : standardResponse(cmd));
        var device = new ArducamDevice(new ArducamClient(arduino.input(), arduino.output()));
        device.powerOn();

        assertThatThrownBy(() -> device.takePicture(PictureMode.QVGA_YUYV))
                .isInstanceOf(DeviceException.class)
                .hasMessageContaining("NO_VSYNC");
        assertThat(arduino.receivedCommands()).containsExactly("POWER on", "PICTURE fmt=yuyv res=qvga settle=4000");
        assertThat(device.isPowered()).isTrue();
    }

    @Test
    void failed_power_on_is_retried_by_next_camera_command() throws Exception {
        AtomicInteger powerOns = new AtomicInteger();
        var arduino = new FakeArduino(cmd -> switch (cmd) {
            case "POWER on" -> powerOns.incrementAndGet() == 1
                    ? text("ERR POWER SCCB no answer")
                    : standardResponse(cmd);
            case "CAMTEST" -> text("OK CAMTEST result=pass");
            default -> standardResponse(cmd);
        });
        var device = new ArducamDevice(new ArducamClient(arduino.input(), arduino.output()));

        assertThatThrownBy(device::powerOn)
                .isInstanceOf(DeviceException.class)
                .hasMessageContaining("SCCB");
        assertThat(device.isPowered()).isFalse();

        device.cameraTest();

        assertThat(arduino.receivedCommands()).containsExactly("POWER on", "POWER on", "CAMTEST");
        assertThat(device.isPowered()).isTrue();
    }

    @Test
    void power_off_error_after_firmware_reset_powers_on_and_retries() throws Exception {
        AtomicInteger pictures = new AtomicInteger();
        var arduino = new FakeArduino(cmd -> cmd.startsWith("PICTURE")
                ? (pictures.incrementAndGet() == 1 ? text("ERR PICTURE POWER_OFF") : pictureResponse(Crc16Xmodem.compute(IMAGE)))
                : standardResponse(cmd));
        var device = new ArducamDevice(new ArducamClient(arduino.input(), arduino.output()));
        device.powerOn();

        device.takePicture(PictureMode.QQVGA_YUYV);

        assertThat(arduino.receivedCommands()).containsExactly(
                "POWER on", "PICTURE fmt=yuyv res=qqvga settle=2000", "POWER on", "PICTURE fmt=yuyv res=qqvga settle=2000");
    }

    @Test
    void bad_crc_is_fixed_by_reading_image_again() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        int goodCrc = Crc16Xmodem.compute(IMAGE);
        var arduino = new FakeArduino(cmd -> switch (cmd) {
            case "PICTURE fmt=yuyv res=qqvga settle=2000" -> pictureResponse(goodCrc ^ 1);
            case "READ" -> concat(
                    dataBlock(IMAGE, 160, 120, "YUYV", reads.incrementAndGet() == 1 ? goodCrc ^ 1 : goodCrc),
                    text("OK READ"));
            default -> standardResponse(cmd);
        });
        var device = new ArducamDevice(new ArducamClient(arduino.input(), arduino.output()));

        RawImage image = device.takePicture(PictureMode.QQVGA_YUYV);

        assertThat(image.data()).isEqualTo(IMAGE);
        assertThat(arduino.receivedCommands()).containsExactly(
                "POWER on", "PICTURE fmt=yuyv res=qqvga settle=2000", "READ", "READ");
    }

    @Test
    void gives_up_after_max_retries() {
        int badCrc = Crc16Xmodem.compute(IMAGE) ^ 1;
        var arduino = new FakeArduino(cmd -> switch (cmd) {
            case "PICTURE fmt=yuyv res=qqvga settle=2000" -> pictureResponse(badCrc);
            case "READ" -> concat(dataBlock(IMAGE, 160, 120, "YUYV", badCrc), text("OK READ"));
            default -> standardResponse(cmd);
        });
        var device = new ArducamDevice(new ArducamClient(arduino.input(), arduino.output()));

        assertThatThrownBy(() -> device.takePicture(PictureMode.QQVGA_YUYV))
                .isInstanceOf(DeviceException.class)
                .hasMessageContaining("CRC");
        assertThat(arduino.receivedCommands()).filteredOn("READ"::equals).hasSize(ArducamDevice.MAX_READ_RETRIES);
    }

    @Test
    void camera_test_report_from_real_session() throws Exception {
        var arduino = new FakeArduino(cmd -> cmd.equals("CAMTEST")
                ? text("TEST sccb_id PASS pid=0x76 ver=0x73 midh=0x7f midl=0xa2",
                       "TEST sccb_rw PASS",
                       "TEST registers PASS checked=126 mismatched=0 critical=0 write_errors=0",
                       "TEST timing PASS frame_us=398736 vsync_us=2345 pclk_khz=501",
                       "TEST bars PASS edges=7 bad_lines=0 never1=0x00 never0=0x00",
                       "OK CAMTEST result=pass")
                : standardResponse(cmd));
        var device = new ArducamDevice(new ArducamClient(arduino.input(), arduino.output()));

        TestReport report = device.cameraTest();

        assertThat(report.passed()).isTrue();
        assertThat(report.tests()).hasSize(5);
        assertThat(report.summary()).contains("Camera test: PASS", "timing PASS frame_us=398736");
        assertThat(arduino.receivedCommands()).containsExactly("POWER on", "CAMTEST");
    }

    @Test
    void failed_memory_test_is_reported_not_thrown() throws Exception {
        var arduino = new FakeArduino(cmd -> text("TEST data_path FAIL", "OK MEMTEST result=fail"));
        var device = new ArducamDevice(new ArducamClient(arduino.input(), arduino.output()));

        TestReport report = device.memoryTest();

        assertThat(report.passed()).isFalse();
        // MEMTEST does not need the camera.
        assertThat(arduino.receivedCommands()).containsExactly("MEMTEST");
    }
}
