package pl.marcinchwedczuk.template.gui.mainwindow;

import com.fazecast.jSerialComm.SerialPort;
import javafx.application.Platform;
import pl.marcinchwedczuk.template.domain.protocol.ArducamClient;
import pl.marcinchwedczuk.template.domain.protocol.ArducamDevice;
import pl.marcinchwedczuk.template.domain.protocol.DeviceStatus;
import pl.marcinchwedczuk.template.domain.protocol.ProtocolListener;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/**
 * Executes serial port operations, one at a time, on a dedicated worker thread.
 * Results are delivered on the JavaFX Application Thread.
 */
public class SerialInterface {
    public static final int BAUD_RATE = 250_000;
    /** Short read timeout, the protocol client checks its own deadlines between reads. */
    private static final int READ_TIMEOUT_MS = 100;
    private static final int WRITE_TIMEOUT_MS = 2000;

    private final Thread workerThread = new Thread(this::work, "serial-port-thread");
    private final ConcurrentLinkedQueue<Message<?>> messageQueue = new ConcurrentLinkedQueue<>();

    private volatile SerialPortLogger logger = new SerialPortLogger(data -> { });
    public void setLogger(SerialPortLogger logger) {
        this.logger = requireNonNull(logger);
    }

    public void startWorkerThread() {
        if (workerThread.isAlive()) return;

        workerThread.setDaemon(true);
        workerThread.start();
    }

    private void work() {
        try {
            while(true) {
                Message<?> msg = messageQueue.poll();
                if (msg == null) {
                    Thread.sleep(20);
                    continue;
                }

                msg.execute(logger);
            }
        } catch (InterruptedException e) {
            // exit loop
        }
    }

    public void sent(Message<?> msg) {
        requireNonNull(msg);
        messageQueue.offer(msg);
    }

    public static abstract class Message<R> {
        private Consumer<R> onSuccess = p -> { };
        private Consumer<Exception> onFailure = p -> { };
        private Runnable atExit = () -> { };

        abstract R innerExecute(SerialPortLogger logger) throws Exception;

        void execute(SerialPortLogger logger) {
            R result;
            try {
                result = innerExecute(logger);
                Platform.runLater(() -> {
                    onSuccess.accept(result);
                });
            } catch (Exception e) {
                logger.logEvent(String.format("ERROR %s: %s", e.getClass().getSimpleName(), e.getMessage()));
                Platform.runLater(() -> {
                    onFailure.accept(e);
                });
            } finally {
                Platform.runLater(() -> {
                    atExit.run();
                });
            }
        }

        public Message<R> setOnSuccess(Consumer<R> onSuccess) {
            this.onSuccess = requireNonNull(onSuccess);
            return this;
        }

        public Message<R> setOnFailure(Consumer<Exception> onFailure) {
            this.onFailure = requireNonNull(onFailure);
            return this;
        }

        public Message<R> setAtExit(Runnable atExit) {
            this.atExit = requireNonNull(atExit);
            return this;
        }
    }

    public static class GetSerialPorts extends Message<List<SerialPort>> {
        @Override
        List<SerialPort> innerExecute(SerialPortLogger logger) {
            SerialPort[] ports = SerialPort.getCommPorts();

            logger.logEvent("Discovered serial ports:");
            for (SerialPort p : ports) {
                logger.logEvent(String.format("- %s, path: %s", p.getManufacturer(), p.getSystemPortPath()));
            }

            return Arrays.asList(ports);
        }
    }

    /**
     * @param powerOnError why the camera could not be powered on, null on success.
     *                     The connection is still usable (e.g. for the memory test).
     */
    public record Connection(SerialPort port, ArducamDevice device, DeviceStatus status, Exception powerOnError) { }

    /**
     * Opens the port, performs the protocol handshake and powers the camera on.
     * The camera stays on while connected, so that auto exposure and white balance can settle.
     * The Arduino is connected through an external USB-serial module, so opening
     * the port does not reset it and there is no need to wait for a boot.
     */
    public static class OpenSerialPort extends Message<Connection> {
        private final SerialPort port;
        private final ProtocolListener protocolListener;

        public OpenSerialPort(SerialPort port, ProtocolListener protocolListener) {
            this.port = requireNonNull(port);
            this.protocolListener = requireNonNull(protocolListener);
        }

        @Override
        Connection innerExecute(SerialPortLogger logger) throws Exception {
            port.setComPortParameters(BAUD_RATE, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
            port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
            port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING,
                    READ_TIMEOUT_MS, WRITE_TIMEOUT_MS);

            if (!port.openPort()) {
                throw new RuntimeException("Opening port failed. Make sure that the port is not used by other apps, including Arduino IDE Serial Monitor.");
            }
            logger.logEvent(String.format("Port open, %d baud.", BAUD_RATE));

            try {
                port.flushIOBuffers();

                ArducamClient client = new ArducamClient(port.getInputStream(), port.getOutputStream());
                client.setListener(protocolListener);
                ArducamDevice device = new ArducamDevice(client);

                DeviceStatus status = device.handshake();

                Exception powerOnError = null;
                try {
                    device.powerOn();
                } catch (Exception e) {
                    logger.logEvent("Camera power on failed: " + e.getMessage());
                    powerOnError = e;
                }

                return new Connection(port, device, status, powerOnError);
            } catch (Exception e) {
                port.closePort();
                logger.logEvent("Port closed.");
                throw e;
            }
        }
    }

    /** Powers the camera off (unless {@code device} is null) and closes the port. */
    public static class CloseSerialPort extends Message<SerialPort> {
        private final SerialPort port;
        private final ArducamDevice device;

        public CloseSerialPort(SerialPort port, ArducamDevice device) {
            this.port = requireNonNull(port);
            this.device = device;
        }

        @Override
        SerialPort innerExecute(SerialPortLogger logger) {
            if (device != null) {
                try {
                    device.powerOff();
                } catch (Exception e) {
                    // Close the port anyway.
                    logger.logEvent("Camera power off failed: " + e.getMessage());
                }
            }

            logger.logEvent("Closing port.");
            port.closePort();
            return port;
        }
    }

    @FunctionalInterface
    public interface DeviceAction<R> {
        R run(ArducamDevice device) throws Exception;
    }

    /** Runs an operation on an already connected device. */
    public static class RunOnDevice<R> extends Message<R> {
        private final ArducamDevice device;
        private final DeviceAction<R> action;

        public RunOnDevice(ArducamDevice device, DeviceAction<R> action) {
            this.device = requireNonNull(device);
            this.action = requireNonNull(action);
        }

        @Override
        R innerExecute(SerialPortLogger logger) throws Exception {
            return action.run(device);
        }
    }
}
