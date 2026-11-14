package pl.marcinchwedczuk.template.gui.mainwindow;

import com.fazecast.jSerialComm.SerialPort;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import pl.marcinchwedczuk.template.domain.image.ImageDecoder;
import pl.marcinchwedczuk.template.domain.protocol.PictureMode;
import pl.marcinchwedczuk.template.domain.protocol.ProtocolException;
import pl.marcinchwedczuk.template.domain.protocol.ProtocolListener;
import pl.marcinchwedczuk.template.domain.protocol.RawImage;
import pl.marcinchwedczuk.template.domain.protocol.TestReport;
import pl.marcinchwedczuk.template.gui.mainwindow.SerialInterface.CloseSerialPort;
import pl.marcinchwedczuk.template.gui.mainwindow.SerialInterface.Connection;
import pl.marcinchwedczuk.template.gui.mainwindow.SerialInterface.DeviceAction;
import pl.marcinchwedczuk.template.gui.mainwindow.SerialInterface.GetSerialPorts;
import pl.marcinchwedczuk.template.gui.mainwindow.SerialInterface.OpenSerialPort;
import pl.marcinchwedczuk.template.gui.mainwindow.SerialInterface.RunOnDevice;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/**
 * Manages the connection to the Arduino: discovers serial ports, opens/closes the selected
 * port and runs camera operations. Logs all sent commands and received lines (but not
 * binary image data).
 * All public methods and properties must be accessed from the JavaFX Application Thread.
 */
public class ArduinoConnection {
    private final SerialInterface serialInterface = new SerialInterface();
    private final SerialPortLogger logger;
    private ErrorListener errorListener = e -> { };
    private Consumer<String> logListener = line -> { };

    // Null when disconnected.
    private Connection connection = null;

    public final ObjectProperty<SerialPort> serialPort = new SimpleObjectProperty<>(this, "serialPort", null);
    public final SimpleObjectProperty<ObservableList<SerialPort>> discoveredSerialPorts = new SimpleObjectProperty<>(this, "discoveredSerialPorts", FXCollections.observableArrayList());
    public final BooleanProperty isDiscoveringPorts = new SimpleBooleanProperty(this, "isDiscoveringPorts", false);

    public final BooleanProperty isConnected = new SimpleBooleanProperty(this, "isConnected", false);
    public final BooleanProperty isBusy = new SimpleBooleanProperty(this, "isBusy", false);
    /** Continuous mode: pictures are taken one after another until {@link #stopLoop()}. */
    public final BooleanProperty isLooping = new SimpleBooleanProperty(this, "isLooping", false);
    /** Stop was requested, the loop ends after the current frame. */
    public final BooleanProperty isStopping = new SimpleBooleanProperty(this, "isStopping", false);
    // Read by the worker thread, isStopping is for the UI.
    private volatile boolean stopLoopRequested = false;

    /** What the device is doing, e.g. "Taking picture...". Empty when idle. */
    public final StringProperty activity = new SimpleStringProperty(this, "activity", "");
    /** Device description shown in the status bar. */
    public final StringProperty deviceInfo = new SimpleStringProperty(this, "deviceInfo", "Not connected");

    /** Progress of a binary transfer, 0..1, or -1 when there is no transfer. */
    public final DoubleProperty transferProgress = new SimpleDoubleProperty(this, "transferProgress", -1);
    public final StringProperty transferText = new SimpleStringProperty(this, "transferText", "");

    // Progress is reported very often from the worker thread, coalesce UI updates.
    private final AtomicBoolean progressUpdatePending = new AtomicBoolean(false);
    private volatile int transferReceived, transferTotal;

    public ArduinoConnection() {
        logger = new SerialPortLogger(line -> logListener.accept(line));
        serialInterface.setLogger(logger);
        serialInterface.startWorkerThread();
    }

    public void setErrorListener(ErrorListener l) {
        this.errorListener = requireNonNull(l);
    }

    /** Receives log lines, on the JavaFX Application Thread. */
    public void setLogListener(Consumer<String> l) {
        this.logListener = requireNonNull(l);
    }

    public void discoverSerialPortsAsync() {
        isDiscoveringPorts.set(true);

        serialInterface.sent(new GetSerialPorts()
                .setOnSuccess(ports -> discoveredSerialPorts.set(FXCollections.observableArrayList(ports)))
                .setOnFailure(e -> errorListener.onError(e))
                .setAtExit(() -> isDiscoveringPorts.set(false)));
    }

    public void connectAsync() {
        SerialPort port = serialPort.get();
        if (port == null || isConnected.get() || isBusy.get()) return;

        startActivity("Connecting...");
        serialInterface.sent(new OpenSerialPort(port, protocolListener)
                .setOnSuccess(c -> {
                    connection = c;
                    isConnected.set(true);
                    deviceInfo.set(String.format("%s — firmware %s %s, protocol %s",
                            c.port().getSystemPortName(), c.status().firmware(),
                            c.status().version(), c.status().protocol()));
                    if (c.powerOnError() != null) {
                        errorListener.onError(new RuntimeException(
                                "Connected, but powering the camera on failed: " + c.powerOnError().getMessage()
                                + "\nCamera commands will try again.", c.powerOnError()));
                    }
                })
                .setOnFailure(e -> errorListener.onError(e))
                .setAtExit(this::endActivity));
    }

    /** Powers the camera off and closes the port. */
    public void disconnectAsync() {
        disconnectAsync(true, () -> { });
    }

    /**
     * @param powerOff send {@code POWER off} before closing; pointless when the connection is lost.
     * @param onClosed called when the port is closed, or immediately when not connected.
     */
    public void disconnectAsync(boolean powerOff, Runnable onClosed) {
        if (connection == null) {
            onClosed.run();
            return;
        }

        Connection c = connection;
        connection = null;
        isConnected.set(false);
        deviceInfo.set("Not connected");

        // Queued after any running operation, the worker executes messages in order.
        serialInterface.sent(new CloseSerialPort(c.port(), powerOff ? c.device() : null)
                .setOnFailure(e -> errorListener.onError(e))
                .setAtExit(onClosed));
    }

    public void takePictureAsync(PictureMode mode, Consumer<Picture> onPicture) {
        runOnDevice("Taking picture...", device -> {
            RawImage raw = device.takePicture(mode);
            return new Picture(raw, ImageDecoder.decode(raw));
        }, onPicture);
    }

    /**
     * Continuous mode: takes pictures one after another, like a (very slow) movie camera,
     * until {@link #stopLoop()} is called or an error occurs. The camera is configured once,
     * so only the first frame waits for auto exposure to settle.
     */
    public void startLoopAsync(PictureMode mode, Consumer<Picture> onFrame) {
        if (connection == null || isBusy.get()) return;

        stopLoopRequested = false;
        runOnDevice("Loop: starting...", device -> {
            int frames = 0;
            while (!stopLoopRequested) {
                long start = System.nanoTime();
                RawImage raw = device.takePicture(mode);
                Picture picture = new Picture(raw, ImageDecoder.decode(raw));

                frames++;
                int frame = frames;
                double seconds = (System.nanoTime() - start) / 1e9;
                Platform.runLater(() -> {
                    onFrame.accept(picture);
                    if (!stopLoopRequested) {
                        activity.set(String.format("Loop: frame %d (%.1f s)", frame, seconds));
                    }
                });
            }
            return frames;
        }, frames -> { });
        isLooping.set(true);
    }

    /** Stops continuous mode after the current frame (the protocol has no abort command). */
    public void stopLoop() {
        if (!isLooping.get()) return;

        stopLoopRequested = true;
        isStopping.set(true);
        activity.set("Loop: stopping after the current frame...");
    }

    public void memoryTestAsync(Consumer<TestReport> onReport) {
        runOnDevice("Running memory test...", device -> device.memoryTest(), onReport);
    }

    public void cameraTestAsync(Consumer<TestReport> onReport) {
        runOnDevice("Running camera test...", device -> device.cameraTest(), onReport);
    }

    private <R> void runOnDevice(String description, DeviceAction<R> action, Consumer<R> onSuccess) {
        if (connection == null || isBusy.get()) return;

        startActivity(description);
        serialInterface.sent(new RunOnDevice<>(connection.device(), action)
                .setOnSuccess(onSuccess)
                .setOnFailure(this::onDeviceFailure)
                .setAtExit(this::endActivity));
    }

    private void onDeviceFailure(Exception e) {
        if (e instanceof ProtocolException pe && pe.kind() == ProtocolException.Kind.DISCONNECTED) {
            disconnectAsync(false, () -> { });
        }
        errorListener.onError(e);
    }

    private void startActivity(String description) {
        isBusy.set(true);
        activity.set(description);
    }

    private void endActivity() {
        isBusy.set(false);
        isLooping.set(false);
        isStopping.set(false);
        activity.set("");
        transferProgress.set(-1);
        transferText.set("");
    }

    // Called on the worker thread.
    private final ProtocolListener protocolListener = new ProtocolListener() {
        @Override
        public void onSent(String command) {
            logger.logSent(command);
        }

        @Override
        public void onReceived(String line) {
            logger.logRecv(line);
        }

        @Override
        public void onBinaryReceived(int bytes) {
            logger.logEvent(String.format("   [%d bytes of binary data]", bytes));
        }

        @Override
        public void onEvent(String event) {
            logger.logEvent(event);
        }

        @Override
        public void onBinaryProgress(int received, int total) {
            transferReceived = received;
            transferTotal = total;
            if (progressUpdatePending.compareAndSet(false, true)) {
                Platform.runLater(() -> {
                    progressUpdatePending.set(false);
                    int r = transferReceived, t = transferTotal;
                    transferProgress.set(t == 0 ? 1 : (double) r / t);
                    transferText.set(String.format("%,d / %,d bytes", r, t));
                });
            }
        }
    };

    public interface ErrorListener {
        void onError(Exception e);
    }
}
