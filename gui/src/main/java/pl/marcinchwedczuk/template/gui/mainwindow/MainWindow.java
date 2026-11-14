package pl.marcinchwedczuk.template.gui.mainwindow;

import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import pl.marcinchwedczuk.template.domain.image.RgbImage;
import pl.marcinchwedczuk.template.domain.protocol.PictureMode;
import pl.marcinchwedczuk.template.domain.protocol.TestReport;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ResourceBundle;

public class MainWindow implements Initializable {
    /** The camera is mounted rotated, pictures are displayed rotated 90° clockwise by default. */
    private static final int DEFAULT_QUARTER_TURNS = 1;

    public static MainWindow showOn(Stage window) {
        try {
            FXMLLoader loader = new FXMLLoader(MainWindow.class.getResource("MainWindow.fxml"));

            Scene scene = new Scene(loader.load());
            MainWindow controller = (MainWindow) loader.getController();

            window.setTitle("Arducam");
            window.setScene(scene);
            window.setResizable(true);
            window.setOnCloseRequest(e -> {
                e.consume();
                controller.exitApp();
            });

            window.show();

            return controller;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @FXML private BorderPane mainWindow;

    @FXML private ComboBox<SerialPortItem> serialPortComboBox;
    @FXML private Button refreshSerialPortsButton;
    @FXML private Button connectButton;
    @FXML private Button disconnectButton;

    @FXML private ComboBox<PictureMode> pictureModeComboBox;
    @FXML private ToggleButton loopToggleButton;
    @FXML private Button takePictureButton;
    @FXML private ImageView takePictureIcon;
    @FXML private Button memoryTestButton;
    @FXML private Button cameraTestButton;

    @FXML private Button rotateCcwButton;
    @FXML private Button rotateCwButton;
    @FXML private Button saveButton;

    @FXML private StackPane picturePane;
    @FXML private ImageView pictureView;
    @FXML private Label noPictureLabel;
    @FXML private TextArea logTextArea;

    @FXML private Label deviceInfoLabel;
    @FXML private Label pictureInfoLabel;
    @FXML private Label activityLabel;
    @FXML private Label transferLabel;
    @FXML private ProgressBar transferProgressBar;

    private final ArduinoConnection arduinoConnection = new ArduinoConnection();

    /** Last received picture, in sensor orientation. */
    private final ObjectProperty<Picture> picture = new SimpleObjectProperty<>(this, "picture", null);
    private int quarterTurns = DEFAULT_QUARTER_TURNS;
    /** {@link #picture} rotated as displayed, this is what gets saved. */
    private RgbImage displayedImage = null;

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        var connectedOrBusy = arduinoConnection.isConnected.or(arduinoConnection.isBusy);
        BooleanBinding cannotRunCommand = arduinoConnection.isConnected.not().or(arduinoConnection.isBusy);
        var noPicture = picture.isNull();

        serialPortComboBox.disableProperty().bind(connectedOrBusy);
        refreshSerialPortsButton.disableProperty().bind(
                arduinoConnection.isDiscoveringPorts.or(connectedOrBusy));
        connectButton.disableProperty().bind(connectedOrBusy.or(
                serialPortComboBox.getSelectionModel().selectedItemProperty().isNull()));
        disconnectButton.disableProperty().bind(cannotRunCommand);

        pictureModeComboBox.disableProperty().bind(cannotRunCommand);
        loopToggleButton.disableProperty().bind(cannotRunCommand);
        // While looping the button is "Stop", after clicking it "Stopping..." (disabled).
        takePictureButton.disableProperty().bind(
                cannotRunCommand.and(arduinoConnection.isLooping.not())
                        .or(arduinoConnection.isStopping));
        memoryTestButton.disableProperty().bind(cannotRunCommand);
        cameraTestButton.disableProperty().bind(cannotRunCommand);

        rotateCcwButton.disableProperty().bind(noPicture);
        rotateCwButton.disableProperty().bind(noPicture);
        saveButton.disableProperty().bind(noPicture);

        serialPortComboBox.itemsProperty().bind(arduinoConnection.discoveredSerialPorts.map(l ->
                FXCollections.observableArrayList(
                    l.stream().map(SerialPortItem::new).toList())));
        arduinoConnection.serialPort.bind(serialPortComboBox.getSelectionModel().selectedItemProperty().map(SerialPortItem::serialPort));

        loopToggleButton.textProperty().bind(
                loopToggleButton.selectedProperty().map(loop -> loop ? "Loop" : "Single"));
        Image takePictureImage = takePictureIcon.getImage();
        Image stopImage = new Image(MainWindow.class.getResource("process-stop.png").toExternalForm());
        InvalidationListener updateTakePictureButton = obs -> {
            boolean looping = arduinoConnection.isLooping.get();
            takePictureButton.setText(!looping ? "Take picture"
                    : arduinoConnection.isStopping.get() ? "Stopping..." : "Stop");
            takePictureIcon.setImage(looping ? stopImage : takePictureImage);
        };
        arduinoConnection.isLooping.addListener(updateTakePictureButton);
        arduinoConnection.isStopping.addListener(updateTakePictureButton);

        pictureModeComboBox.setItems(FXCollections.observableArrayList(PictureMode.values()));
        pictureModeComboBox.getSelectionModel().select(PictureMode.QVGA_YUYV);

        // Scale the picture to the available space, keeping the aspect ratio.
        pictureView.fitWidthProperty().bind(picturePane.widthProperty().subtract(16));
        pictureView.fitHeightProperty().bind(picturePane.heightProperty().subtract(16));
        noPictureLabel.visibleProperty().bind(noPicture);

        deviceInfoLabel.textProperty().bind(arduinoConnection.deviceInfo);
        activityLabel.textProperty().bind(arduinoConnection.activity);
        transferLabel.textProperty().bind(arduinoConnection.transferText);
        transferProgressBar.progressProperty().bind(arduinoConnection.transferProgress);
        transferProgressBar.visibleProperty().bind(arduinoConnection.isBusy);

        arduinoConnection.setLogListener(this::appendLog);
        arduinoConnection.setErrorListener(ex -> UiService.errorDialog("Error: " + ex.getMessage()));

        arduinoConnection.discoverSerialPortsAsync();
    }

    /** Loop mode logs every frame, keep the log from growing without bounds. */
    private static final int MAX_LOG_CHARS = 2_000_000;

    private void appendLog(String line) {
        if (logTextArea.getLength() > MAX_LOG_CHARS) {
            String text = logTextArea.getText();
            int cut = text.indexOf('\n', text.length() / 2);
            logTextArea.deleteText(0, cut < 0 ? text.length() / 2 : cut + 1);
        }
        logTextArea.appendText(line + System.lineSeparator());
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

    public void takePicture(ActionEvent event) {
        if (arduinoConnection.isLooping.get()) {
            arduinoConnection.stopLoop();
            return;
        }

        PictureMode mode = pictureModeComboBox.getValue();
        if (mode == null) return;

        if (loopToggleButton.isSelected()) {
            arduinoConnection.startLoopAsync(mode, this::onPicture);
        } else {
            arduinoConnection.takePictureAsync(mode, this::onPicture);
        }
    }

    private void onPicture(Picture p) {
        picture.set(p);
        showPicture();
    }

    public void memoryTest(ActionEvent event) {
        arduinoConnection.memoryTestAsync(this::showTestReport);
    }

    public void cameraTest(ActionEvent event) {
        arduinoConnection.cameraTestAsync(this::showTestReport);
    }

    private void showTestReport(TestReport report) {
        if (report.passed()) {
            UiService.infoDialog(report.summary());
        } else {
            UiService.errorDialog(report.summary());
        }
    }

    public void rotateClockwise(ActionEvent event) {
        quarterTurns++;
        showPicture();
    }

    public void rotateCounterClockwise(ActionEvent event) {
        quarterTurns--;
        showPicture();
    }

    private void showPicture() {
        Picture p = picture.get();
        if (p == null) return;

        displayedImage = p.rgb().rotate(quarterTurns);
        int w = displayedImage.width(), h = displayedImage.height();

        WritableImage fxImage = new WritableImage(w, h);
        fxImage.getPixelWriter().setPixels(0, 0, w, h,
                javafx.scene.image.PixelFormat.getIntArgbInstance(),
                displayedImage.argbPixels(), 0, w);
        pictureView.setImage(fxImage);

        int degrees = Math.floorMod(quarterTurns, 4) * 90;
        pictureInfoLabel.setText(String.format("Picture %s, rotated %d°", p.raw(), degrees));
    }

    public void savePicture(ActionEvent event) {
        if (displayedImage == null) return;

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save picture");
        var pngFilter = new FileChooser.ExtensionFilter("PNG image (*.png)", "*.png");
        var bmpFilter = new FileChooser.ExtensionFilter("BMP image (*.bmp)", "*.bmp");
        fileChooser.getExtensionFilters().addAll(pngFilter, bmpFilter);
        fileChooser.setInitialFileName("arducam-" +
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".png");

        File file = fileChooser.showSaveDialog(mainWindow.getScene().getWindow());
        if (file == null) return;

        // The extension typed by the user wins, then the selected filter.
        ImageFiles.Format format = ImageFiles.formatOf(file);
        if (format == null) {
            format = fileChooser.getSelectedExtensionFilter() == bmpFilter
                    ? ImageFiles.Format.BMP
                    : ImageFiles.Format.PNG;
            file = ImageFiles.withExtension(file, format);
        }

        try {
            ImageFiles.save(displayedImage, file, format);
        } catch (Exception e) {
            UiService.errorDialog("Cannot save picture: " + e.getMessage());
        }
    }

    public void closeApp(ActionEvent event) {
        exitApp();
    }

    /**
     * Powers the camera off and exits. When a command is still running (a picture can take
     * up to 35 s), exits immediately instead: there is no way to abort a command.
     */
    private void exitApp() {
        if (arduinoConnection.isBusy.get()) {
            Platform.exit();
            return;
        }

        arduinoConnection.disconnectAsync(true, Platform::exit);
    }
}
