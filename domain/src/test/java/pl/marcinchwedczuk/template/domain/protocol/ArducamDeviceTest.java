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
    void picture_powers_camera_on_and_off() throws Exception {
        var arduino = new FakeArduino(cmd -> cmd.startsWith("PICTURE")
                ? pictureResponse(Crc16Xmodem.compute(IMAGE))
                : standardResponse(cmd));
        var device = new ArducamDevice(new ArducamClient(arduino.input(), arduino.output()));

        RawImage image = device.takePicture(PictureMode.QQVGA_YUYV);

        assertThat(arduino.receivedCommands()).containsExactly(
                "POWER on", "PICTURE fmt=yuyv res=qqvga", "POWER off");
        assertThat(image.width()).isEqualTo(160);
        assertThat(image.format()).isEqualTo(PixelFormat.YUYV);
        assertThat(image.data()).isEqualTo(IMAGE);
    }

    @Test
    void camera_is_powered_off_when_picture_fails() {
        var arduino = new FakeArduino(cmd -> cmd.startsWith("PICTURE")
                ? text("ERR PICTURE NO_VSYNC")
                : standardResponse(cmd));
        var device = new ArducamDevice(new ArducamClient(arduino.input(), arduino.output()));

        assertThatThrownBy(() -> device.takePicture(PictureMode.QVGA_YUYV))
                .isInstanceOf(DeviceException.class)
                .hasMessageContaining("NO_VSYNC");
        assertThat(arduino.receivedCommands()).containsExactly(
                "POWER on", "PICTURE fmt=yuyv res=qvga", "POWER off");
    }

    @Test
    void camera_is_powered_off_when_power_on_fails() {
        var arduino = new FakeArduino(cmd -> cmd.equals("POWER on")
                ? text("ERR POWER SCCB no answer")
                : standardResponse(cmd));
        var device = new ArducamDevice(new ArducamClient(arduino.input(), arduino.output()));

        assertThatThrownBy(device::cameraTest)
                .isInstanceOf(DeviceException.class)
                .hasMessageContaining("SCCB");
        assertThat(arduino.receivedCommands()).containsExactly("POWER on", "POWER off");
    }

    @Test
    void bad_crc_is_fixed_by_reading_image_again() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        int goodCrc = Crc16Xmodem.compute(IMAGE);
        var arduino = new FakeArduino(cmd -> switch (cmd) {
            case "PICTURE fmt=yuyv res=qqvga" -> pictureResponse(goodCrc ^ 1);
            case "READ" -> concat(
                    dataBlock(IMAGE, 160, 120, "YUYV", reads.incrementAndGet() == 1 ? goodCrc ^ 1 : goodCrc),
                    text("OK READ"));
            default -> standardResponse(cmd);
        });
        var device = new ArducamDevice(new ArducamClient(arduino.input(), arduino.output()));

        RawImage image = device.takePicture(PictureMode.QQVGA_YUYV);

        assertThat(image.data()).isEqualTo(IMAGE);
        assertThat(arduino.receivedCommands()).containsExactly(
                "POWER on", "PICTURE fmt=yuyv res=qqvga", "POWER off", "READ", "READ");
    }

    @Test
    void gives_up_after_max_retries() {
        int badCrc = Crc16Xmodem.compute(IMAGE) ^ 1;
        var arduino = new FakeArduino(cmd -> switch (cmd) {
            case "PICTURE fmt=yuyv res=qqvga" -> pictureResponse(badCrc);
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
        assertThat(arduino.receivedCommands()).containsExactly("POWER on", "CAMTEST", "POWER off");
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
