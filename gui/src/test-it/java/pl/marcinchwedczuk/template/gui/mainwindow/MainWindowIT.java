package pl.marcinchwedczuk.template.gui.mainwindow;

import javafx.scene.control.ComboBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxRobot;
import org.testfx.api.FxToolkit;
import org.testfx.framework.junit5.ApplicationTest;
import pl.marcinchwedczuk.template.domain.protocol.PictureMode;
import pl.marcinchwedczuk.template.gui.App;

import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.testfx.assertions.api.Assertions.assertThat;

public class MainWindowIT {
    private FxRobot robot = new FxRobot();

    @BeforeEach
    public void setup() throws Exception {
        ApplicationTest.launch(App.class);
    }

    @AfterEach
    public void cleanup() throws TimeoutException {
        FxToolkit.cleanupStages();
    }

    @Test
    public void application_starts_disconnected() {
        // Visit https://github.com/TestFX/TestFX
        // to learn how to test.
        assertThat(robot.lookup("#connectButton").queryButton())
                .hasText("Connect")
                .isDisabled(); // no port selected

        assertThat(robot.lookup("#disconnectButton").queryButton()).isDisabled();

        // Commands need a connection.
        assertThat(robot.lookup("#takePictureButton").queryButton())
                .hasText("Take picture")
                .isDisabled();
        assertThat(robot.lookup("#memoryTestButton").queryButton()).isDisabled();
        assertThat(robot.lookup("#cameraTestButton").queryButton()).isDisabled();

        // Picture operations need a picture.
        assertThat(robot.lookup("#saveButton").queryButton()).isDisabled();
        assertThat(robot.lookup("#rotateCwButton").queryButton()).isDisabled();
        assertThat(robot.lookup("#rotateCcwButton").queryButton()).isDisabled();
        assertThat(robot.lookup("#noPictureLabel").queryLabeled()).hasText("No picture yet");

        assertThat(robot.lookup("#exitButton").queryButton())
                .hasText("Exit")
                .isEnabled();
    }

    @Test
    public void picture_modes_default_to_qvga() {
        ComboBox<PictureMode> modes = robot.lookup("#pictureModeComboBox").queryComboBox();

        assertThat(modes.getItems()).containsExactly(PictureMode.values());
        assertThat(modes.getValue()).isEqualTo(PictureMode.QVGA_YUYV);
    }
}
