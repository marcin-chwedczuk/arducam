package pl.marcinchwedczuk.template.domain.protocol;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * High level operations on the arducam firmware. Implements the "smart host" part of the
 * protocol: the camera is powered on only for the duration of camera commands, images are
 * validated and re-fetched with {@code READ} on CRC errors.
 *
 * <p>Not thread safe, use from a single thread.
 */
public class ArducamDevice {
    public static final String EXPECTED_FIRMWARE = "arducam";
    public static final String EXPECTED_PROTOCOL = "1";
    /** How many times {@code READ} is used to re-fetch an image with a bad CRC. */
    public static final int MAX_READ_RETRIES = 2;

    private static final Duration STATUS_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration POWER_ON_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration POWER_OFF_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration MEMTEST_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration CAMTEST_TIMEOUT = Duration.ofSeconds(20);
    /** About 25,000 bytes/s at 250000 baud. */
    private static final int BYTES_PER_SECOND = 25_000;

    private final ArducamClient client;

    public ArducamDevice(ArducamClient client) {
        this.client = requireNonNull(client);
    }

    public ArducamClient client() {
        return client;
    }

    /**
     * Run after opening the port: flushes any partial command and checks that the
     * device runs a compatible firmware.
     */
    public DeviceStatus handshake() throws IOException, DeviceException {
        client.sendEmptyLine();
        DeviceStatus status = status();
        if (!EXPECTED_FIRMWARE.equals(status.firmware())) {
            throw new DeviceException("Unsupported device, firmware: " + status.firmware());
        }
        if (!EXPECTED_PROTOCOL.equals(status.protocol())) {
            throw new DeviceException("Unsupported protocol version: " + status.protocol()
                    + ", expected: " + EXPECTED_PROTOCOL);
        }
        return status;
    }

    public DeviceStatus status() throws IOException, DeviceException {
        return DeviceStatus.from(executeOk("STATUS", STATUS_TIMEOUT).finalLine());
    }

    public TestReport memoryTest() throws IOException, DeviceException {
        return TestReport.from("Memory test", executeOk("MEMTEST", MEMTEST_TIMEOUT));
    }

    public TestReport cameraTest() throws IOException, DeviceException {
        return withCameraPower(() ->
                TestReport.from("Camera test", executeOk("CAMTEST", CAMTEST_TIMEOUT)));
    }

    public RawImage takePicture(PictureMode mode) throws IOException, DeviceException {
        CommandResult result = withCameraPower(() -> executeOk(mode.command(), mode.timeout()));

        DataBlock block = singleWholeImageBlock(result);
        // The image stays in SRAM after power off, READ does not need the camera.
        for (int retry = 1; !block.crcOk() && retry <= MAX_READ_RETRIES; retry++) {
            int bytes = block.data().length;
            Duration timeout = Duration.ofSeconds(bytes / BYTES_PER_SECOND + 10);
            block = singleWholeImageBlock(executeOk("READ", timeout));
        }

        if (!block.crcOk()) {
            throw new DeviceException(String.format(
                    "Image CRC mismatch after %d retries (expected %04x, got %04x).",
                    MAX_READ_RETRIES, block.expectedCrc(), block.actualCrc()));
        }

        return new RawImage(block.width(), block.height(), block.format(), block.data());
    }

    private static DataBlock singleWholeImageBlock(CommandResult result) throws DeviceException {
        List<DataBlock> blocks = result.blocks();
        if (blocks.size() != 1) {
            throw new DeviceException(result.command() + ": expected one DATA block, got " + blocks.size() + ".");
        }

        DataBlock block = blocks.getFirst();
        int expected = RawImage.expectedBytes(block.width(), block.height(), block.format());
        if (block.offset() != 0 || block.data().length != expected) {
            throw new DeviceException(String.format("%s: expected whole %dx%d %s image (%d bytes), got %d bytes at offset %d.",
                    result.command(), block.width(), block.height(), block.format(),
                    expected, block.data().length, block.offset()));
        }
        return block;
    }

    private <R> R withCameraPower(CameraAction<R> action) throws IOException, DeviceException {
        R result;
        try {
            // On ERR POWER SCCB the camera stays powered, so power off is needed in that case too.
            executeOk("POWER on", POWER_ON_TIMEOUT);
            result = action.run();
        } catch (IOException | DeviceException | RuntimeException e) {
            try {
                powerOff();
            } catch (Exception suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }

        powerOff();
        return result;
    }

    private void powerOff() throws IOException, DeviceException {
        executeOk("POWER off", POWER_OFF_TIMEOUT);
    }

    private CommandResult executeOk(String command, Duration timeout) throws IOException, DeviceException {
        CommandResult result = client.execute(command, timeout);
        if (!result.isOk()) {
            throw DeviceException.fromErr(result.finalLine());
        }
        return result;
    }

    @FunctionalInterface
    private interface CameraAction<R> {
        R run() throws IOException, DeviceException;
    }
}
