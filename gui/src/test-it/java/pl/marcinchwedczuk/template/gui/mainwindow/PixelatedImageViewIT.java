package pl.marcinchwedczuk.template.gui.mainwindow;

import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

public class PixelatedImageViewIT {
    @AfterEach
    public void cleanup() throws TimeoutException {
        FxToolkit.cleanupStages();
    }

    @Test
    public void scaled_image_has_no_interpolated_pixels() throws Exception {
        // 4x2 checkerboard, scaled 50x: any smoothing produces colours other than pure black/white.
        WritableImage image = new WritableImage(4, 2);
        for (int y = 0; y < 2; y++)
            for (int x = 0; x < 4; x++)
                image.getPixelWriter().setColor(x, y, (x + y) % 2 == 0 ? Color.BLACK : Color.WHITE);

        var stage = FxToolkit.registerPrimaryStage();
        AtomicReference<WritableImage> snapshot = new AtomicReference<>();
        FxToolkit.setupFixture(() -> {
            PixelatedImageView view = new PixelatedImageView();
            view.setImage(image);
            StackPane root = new StackPane(view);
            root.setStyle("-fx-background-color: black;");
            stage.setScene(new Scene(root, 200, 100));
            stage.show();
            root.applyCss();
            root.layout();
            snapshot.set(root.snapshot(null, null));
        });

        PixelReader pixels = snapshot.get().getPixelReader();
        Set<Integer> colours = new HashSet<>();
        for (int y = 0; y < 100; y++)
            for (int x = 0; x < 200; x++)
                colours.add(pixels.getArgb(x, y));

        assertThat(colours).containsExactlyInAnyOrder(0xff000000, 0xffffffff);
        // Each image pixel is a 50 px block. Block edges are at x = 50, 100, 150
        // (the software renderer used in headless mode puts them 1 px later).
        java.util.List<Integer> edges = new java.util.ArrayList<>();
        for (int x = 1; x < 200; x++) {
            if (pixels.getArgb(x, 10) != pixels.getArgb(x - 1, 10)) edges.add(x);
        }
        assertThat(edges).hasSize(3);
        for (int i = 0; i < 3; i++) {
            assertThat(edges.get(i)).isBetween(50 * (i + 1), 50 * (i + 1) + 1);
        }
    }
}
