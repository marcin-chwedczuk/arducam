package pl.marcinchwedczuk.template.gui.mainwindow;

import com.fazecast.jSerialComm.SerialPort;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import pl.marcinchwedczuk.template.gui.mainwindow.SerialInterface.CloseSerialPort;
import pl.marcinchwedczuk.template.gui.mainwindow.SerialInterface.GetSerialPorts;
import pl.marcinchwedczuk.template.gui.mainwindow.SerialInterface.OpenSerialPort;
import pl.marcinchwedczuk.template.gui.mainwindow.SerialInterface.StartListening;

import static java.util.Objects.requireNonNull;

/**
 * Manages a connection to the Arduino: discovers serial ports, opens/closes the selected port
 * and logs every line received from the device.
 * All public methods and properties must be accessed from the JavaFX Application Thread.
 */
public class ArduinoConnection {
    private final SerialInterface serialInterface = new SerialInterface();
    private ErrorListener errorListener = e -> { };

    // Port that is currently open, null when disconnected.
    private SerialPort connectedPort = null;

    public final ObjectProperty<SerialPort> serialPort = new SimpleObjectProperty<>(this, "serialPort", null);
    public final SimpleObjectProperty<ObservableList<SerialPort>> discoveredSerialPorts = new SimpleObjectProperty<>(this, "discoveredSerialPorts", FXCollections.observableArrayList());
    public final BooleanProperty isDiscoveringPorts = new SimpleBooleanProperty(this, "isDiscoveringPorts", false);

    public final BooleanProperty isConnected = new SimpleBooleanProperty(this, "isConnected", false);
    public final BooleanProperty isBusy = new SimpleBooleanProperty(this, "isBusy", false);

    public final StringProperty debugLogs = new SimpleStringProperty(this, "debugLogs", "");

    public ArduinoConnection() {
        serialInterface.setLogger(new SerialPortLogger(logLine -> {
            var sb = new StringBuilder(debugLogs.get());
            sb.append(logLine).append(System.lineSeparator());
            debugLogs.set(sb.toString());
        }));

        serialInterface.startWorkerThread();
    }

    public void addErrorListener(ErrorListener l) {
        this.errorListener = requireNonNull(l);
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

        isBusy.set(true);
        serialInterface.sent(new OpenSerialPort(port)
                .setOnSuccess(p -> {
                    connectedPort = p;
                    isConnected.set(true);
                    serialInterface.sent(new StartListening(p, this::disconnectAsync)
                            .setOnFailure(e -> {
                                errorListener.onError(e);
                                disconnectAsync();
                            })
                            .setAtExit(() -> isBusy.set(false)));
                })
                .setOnFailure(e -> {
                    errorListener.onError(e);
                    isBusy.set(false);
                }));
    }

    public void disconnectAsync() {
        if (connectedPort == null) return;

        SerialPort port = connectedPort;
        connectedPort = null;

        isBusy.set(true);
        serialInterface.sent(new CloseSerialPort(port)
                .setOnFailure(e -> errorListener.onError(e))
                .setAtExit(() -> {
                    isConnected.set(false);
                    isBusy.set(false);
                }));
    }

    public interface ErrorListener {
        void onError(Exception e);
    }
}
