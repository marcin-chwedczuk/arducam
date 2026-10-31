package pl.marcinchwedczuk.template.gui.mainwindow;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortEvent;
import com.fazecast.jSerialComm.SerialPortMessageListener;
import javafx.application.Platform;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

public class SerialInterface {
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

        abstract R innerExecute(SerialPortLogger logger);

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

        protected void sleep(long millis) {
            try { Thread.sleep(millis); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static class GetSerialPorts extends Message<List<SerialPort>> {
        @Override
        List<SerialPort> innerExecute(SerialPortLogger logger) {
            SerialPort[] ports = SerialPort.getCommPorts();

            logger.logEvent("Discovered serial ports:");
            for (SerialPort p : ports) {
                logger.logEvent(String.format("- %s, baud rate: %s, path: %s", p.getManufacturer(), p.getBaudRate(), p.getSystemPortPath()));
            }

            return Arrays.asList(ports);
        }
    }

    public static class OpenSerialPort extends Message<SerialPort> {
        private static final int TIMEOUT = 15000;
        private final SerialPort port;

        public OpenSerialPort(SerialPort port) {
            this.port = requireNonNull(port);
        }

        @Override
        SerialPort innerExecute(SerialPortLogger logger) {
            if (port.openPort()) {
                port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING, TIMEOUT, TIMEOUT);
                port.setComPortParameters(9600, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
                port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);

                logger.logEvent("Port open.");

                // Opening the port resets Arduino, we need to give it some time to initialize
                sleep(2000);

                return port;
            }

            throw new RuntimeException("Opening port failed. Make sure that the port is not used by other apps, including Arduino IDE Serial Monitor.");
        }
    }

    public static class CloseSerialPort extends Message<SerialPort> {
        private final SerialPort port;

        public CloseSerialPort(SerialPort port) {
            this.port = requireNonNull(port);
        }

        @Override
        SerialPort innerExecute(SerialPortLogger logger) {
            logger.logEvent("Closing port.");
            port.removeDataListener();
            port.closePort();
            return port;
        }
    }

    /**
     * Registers a listener that reports every line (terminated by '\n') received from the port.
     * Lines are delivered on the jSerialComm event thread, without line endings.
     */
    public static class StartListening extends Message<SerialPort> {
        private final SerialPort port;
        private final Runnable onPortDisconnected;

        public StartListening(SerialPort port, Runnable onPortDisconnected) {
            this.port = requireNonNull(port);
            this.onPortDisconnected = requireNonNull(onPortDisconnected);
        }

        @Override
        SerialPort innerExecute(SerialPortLogger logger) {
            boolean added = port.addDataListener(new SerialPortMessageListener() {
                @Override
                public int getListeningEvents() {
                    return SerialPort.LISTENING_EVENT_DATA_RECEIVED | SerialPort.LISTENING_EVENT_PORT_DISCONNECTED;
                }

                @Override
                public byte[] getMessageDelimiter() {
                    return new byte[] { '\n' };
                }

                @Override
                public boolean delimiterIndicatesEndOfMessage() {
                    return true;
                }

                @Override
                public void serialEvent(SerialPortEvent event) {
                    if (event.getEventType() == SerialPort.LISTENING_EVENT_PORT_DISCONNECTED) {
                        logger.logEvent("Port disconnected.");
                        Platform.runLater(onPortDisconnected);
                        return;
                    }

                    String line = new String(event.getReceivedData(), StandardCharsets.US_ASCII)
                            .replace("\r", "")
                            .replace("\n", "");
                    logger.logRecv(line);
                }
            });

            if (!added) throw new RuntimeException("Cannot register serial port listener.");

            logger.logEvent("Listening for messages.");
            return port;
        }
    }

    public static class SendCommand extends Message<String> {
        private final SerialPort port;
        private final String message;

        public SendCommand(SerialPort port, String message) {
            this.port = port;
            this.message = message;
        }

        @Override
        String innerExecute(SerialPortLogger logger) {
            String msg = message;
            if (!msg.endsWith("\n")) {
                msg += "\n";
            }

            logger.logSent(message);
            byte[] bytes = msg.getBytes(StandardCharsets.US_ASCII);
            int result = port.writeBytes(bytes, bytes.length);
            if (result == -1) throw new RuntimeException("Write to serial port failed.");

            port.flushIOBuffers();

            StringBuilder sb = new StringBuilder();
            byte[] singleChar = new byte[1];
            do {
                if (port.readBytes(singleChar, 1) != 1) {
                    throw new RuntimeException("Reading from serial port failed.");
                }

                // Do not include line endings
                if (singleChar[0] != '\r' && singleChar[0] != '\n') {
                    sb.append((char) singleChar[0]);
                }
            } while (singleChar[0] != '\n');

            logger.logRecv(sb.toString());

            return sb.toString();
        }
    }
}
