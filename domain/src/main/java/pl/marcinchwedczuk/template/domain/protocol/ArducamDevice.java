package pl.marcinchwedczuk.template.domain.protocol;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * High level operations on the arducam firmware. Implements the "smart host" part of the
 * protocol: images are validated and re-fetched with {@code READ} on CRC errors.
 *
 * <p>The camera should stay powered while connected: powering it on for each picture
 * gives blurry pictures, because auto exposure and white balance have no time to settle.
 * Use {@link #powerOn()} after the handshake and {@link #powerOff()} before disconnecting.
 * Camera commands power the camera on if needed (e.g. after a failed power on, or when
 * the firmware was reset and reports {@code POWER_OFF}).
 *
 * <p>Protocol v2 separates {@code CONFIG} from {@code PICTURE}. {@link #takePicture} sends
 * {@code CONFIG} only when the mode changes or the configuration was lost (power on/off,
 * {@code CAMTEST}, firmware reset), and settles only on the first picture after {@code CONFIG}.
 * Consecutive pictures in the same mode are just {@code PICTURE}, which makes a loop of
 * pictures (continuous mode) fast and keeps the auto exposure state.
 *
 * <p>Not thread safe, use from a single thread.
 */
public class ArducamDevice {
    public static final String EXPECTED_FIRMWARE = "arducam";
    public static final String EXPECTED_PROTOCOL = "2";
    /** How many times {@code READ} is used to re-fetch an image with a bad CRC. */
    public static final int MAX_READ_RETRIES = 2;

    private static final Duration STATUS_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration POWER_ON_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration POWER_OFF_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration MEMTEST_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration CAMTEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration CONFIG_TIMEOUT = Duration.ofSeconds(3);
    /** About 25,000 bytes/s at 250000 baud. */
    private static final int BYTES_PER_SECOND = 25_000;

    private final ArducamClient client;
    /** What we believe the camera power state is. */
    private boolean powered = false;
    /** Current camera configuration, null when not configured. */
    private PictureMode configuredMode = null;
    /** The next picture is the first one after CONFIG and needs settling. */
    private boolean settlePending = false;

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

    public boolean isPowered() {
        return powered;
    }

    /** Current camera configuration, or null when not configured. */
    public PictureMode configuredMode() {
        return configuredMode;
    }

    public void powerOn() throws IOException, DeviceException {
        // Powering on clears the camera configuration.
        configuredMode = null;
        // On ERR POWER SCCB the camera stays powered but is unusable, keep 'powered' false
        // so that the next camera command tries again (POWER on when on is not an error).
        executeOk("POWER on", POWER_ON_TIMEOUT);
        powered = true;
    }

    public void powerOff() throws IOException, DeviceException {
        powered = false;
        configuredMode = null;
        executeOk("POWER off", POWER_OFF_TIMEOUT);
    }

    public TestReport cameraTest() throws IOException, DeviceException {
        try {
            return TestReport.from("Camera test", executeCameraCommand("CAMTEST", CAMTEST_TIMEOUT));
        } finally {
            // CAMTEST rewrites the registers.
            configuredMode = null;
        }
    }

    /** Writes the camera configuration; the next picture will wait for auto exposure to settle. */
    public void configure(PictureMode mode) throws IOException, DeviceException {
        if (!powered) {
            powerOn();
        }

        // On error the firmware clears the configuration.
        configuredMode = null;
        executeOk(mode.configCommand(), CONFIG_TIMEOUT);
        configuredMode = mode;
        settlePending = true;
    }

    /**
     * Takes a picture, configuring the camera first when needed.
     * If the configuration or power was lost (e.g. the firmware was reset), restores it and retries once.
     */
    public RawImage takePicture(PictureMode mode) throws IOException, DeviceException {
        CommandResult result;
        try {
            result = capture(mode);
        } catch (DeviceException e) {
            if ("POWER_OFF".equals(e.errorCode())) {
                powered = false;
                configuredMode = null;
            } else if ("NOT_CONFIGURED".equals(e.errorCode())) {
                configuredMode = null;
            } else {
                throw e;
            }
            result = capture(mode);
        }

        DataBlock block = singleWholeImageBlock(result);
        // READ does not need the camera, it reads the image from SRAM.
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

    private CommandResult capture(PictureMode mode) throws IOException, DeviceException {
        if (configuredMode != mode) {
            configure(mode);
        }

        boolean first = settlePending;
        CommandResult result = executeOk(mode.pictureCommand(first), mode.pictureTimeout(first));
        settlePending = false;
        return result;
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

    /** Runs a command that needs the camera powered on. */
    private CommandResult executeCameraCommand(String command, Duration timeout) throws IOException, DeviceException {
        if (!powered) {
            powerOn();
        }

        try {
            return executeOk(command, timeout);
        } catch (DeviceException e) {
            if (!"POWER_OFF".equals(e.errorCode())) throw e;

            // Firmware was reset (e.g. reset button), the camera is off again.
            powered = false;
            powerOn();
            return executeOk(command, timeout);
        }
    }

    private CommandResult executeOk(String command, Duration timeout) throws IOException, DeviceException {
        CommandResult result = client.execute(command, timeout);
        if (!result.isOk()) {
            throw DeviceException.fromErr(result.finalLine());
        }
        return result;
    }
}
