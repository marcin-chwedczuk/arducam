package pl.marcinchwedczuk.template.gui.mainwindow;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;
import java.util.ResourceBundle;

public class MainWindow implements Initializable {
    public static MainWindow showOn(Stage window) {
        try {
            FXMLLoader loader = new FXMLLoader(MainWindow.class.getResource("MainWindow.fxml"));

            Scene scene = new Scene(loader.load());
            MainWindow controller = (MainWindow) loader.getController();

            window.setTitle("Arducam");
            window.setScene(scene);
            window.setResizable(true);
            window.setOnHidden(e -> controller.arduinoConnection.disconnectAsync());

            window.show();

            return controller;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @FXML
    private BorderPane mainWindow;

    @FXML
    private ComboBox<SerialPortItem> serialPortComboBox;

    @FXML
    private Button refreshSerialPortsButton;

    @FXML
    private Button connectButton;

    @FXML
    private Button disconnectButton;

    @FXML
    private TextArea logTextArea;

    private final ArduinoConnection arduinoConnection = new ArduinoConnection();

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        var connectedOrBusy = arduinoConnection.isConnected.or(arduinoConnection.isBusy);

        serialPortComboBox.disableProperty().bind(connectedOrBusy);
        refreshSerialPortsButton.disableProperty().bind(
                arduinoConnection.isDiscoveringPorts.or(connectedOrBusy));
        connectButton.disableProperty().bind(connectedOrBusy.or(
                serialPortComboBox.getSelectionModel().selectedItemProperty().isNull()));
        disconnectButton.disableProperty().bind(
                arduinoConnection.isConnected.not().or(arduinoConnection.isBusy));

        serialPortComboBox.itemsProperty().bind(arduinoConnection.discoveredSerialPorts.map(l ->
                FXCollections.observableArrayList(
                    l.stream().map(SerialPortItem::new).toList())));
        arduinoConnection.serialPort.bind(serialPortComboBox.getSelectionModel().selectedItemProperty().map(SerialPortItem::serialPort));

        arduinoConnection.debugLogs.addListener(observable -> {
            // Binding results in text not scrolling to the bottom
            logTextArea.setText(arduinoConnection.debugLogs.get());
            logTextArea.setScrollTop(Double.MAX_VALUE);
        });

        arduinoConnection.addErrorListener(ex -> {
            UiService.errorDialog("Error: " + ex.getMessage());
        });

        arduinoConnection.discoverSerialPortsAsync();
    }

    public void refreshSerialPorts(ActionEvent event) {
        arduinoConnection.discoverSerialPortsAsync();
    }

    public void connect(ActionEvent event) {
        arduinoConnection.connectAsync();
    }

    public void disconnect(ActionEvent event) {
        arduinoConnection.disconnectAsync();
    }

    public void closeApp(ActionEvent event) {
        arduinoConnection.disconnectAsync();
        Platform.exit();
    }
}
