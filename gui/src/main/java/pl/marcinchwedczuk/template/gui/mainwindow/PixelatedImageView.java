package pl.marcinchwedczuk.template.gui.mainwindow;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.layout.Region;

/**
 * Displays an image scaled to fit (keeping the aspect ratio, centered) without any
 * smoothing: every image pixel becomes a sharp block of screen pixels.
 *
 * <p>{@code ImageView} is not used on purpose: it interpolates linearly when scaling up,
 * even with {@code smooth=false}, which blurs small camera pictures (e.g. a 160×120
 * colour bar pattern shown at 4× had 4 pixel wide gradients between the bars).
 */
public class PixelatedImageView extends Region {
    private final Canvas canvas = new Canvas();
    private final ObjectProperty<Image> image = new SimpleObjectProperty<>(this, "image", null);

    public PixelatedImageView() {
        getChildren().add(canvas);
        image.addListener(obs -> redraw());
        // Small minimum so that the view can shrink with the window.
        setMinSize(0, 0);
    }

    public ObjectProperty<Image> imageProperty() {
        return image;
    }

    public Image getImage() {
        return image.get();
    }

    public void setImage(Image image) {
        this.image.set(image);
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth() - snappedLeftInset() - snappedRightInset();
        double h = getHeight() - snappedTopInset() - snappedBottomInset();
        canvas.relocate(snappedLeftInset(), snappedTopInset());
        if (canvas.getWidth() != w || canvas.getHeight() != h) {
            canvas.setWidth(Math.max(0, w));
            canvas.setHeight(Math.max(0, h));
        }
        redraw();
    }

    private void redraw() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());

        Image img = image.get();
        if (img == null || img.getWidth() == 0 || img.getHeight() == 0) return;

        double scale = Math.min(canvas.getWidth() / img.getWidth(), canvas.getHeight() / img.getHeight());
        // Snap to whole screen pixels so that all image pixels get the same size when possible.
        double dw = Math.floor(img.getWidth() * scale);
        double dh = Math.floor(img.getHeight() * scale);
        double dx = Math.floor((canvas.getWidth() - dw) / 2);
        double dy = Math.floor((canvas.getHeight() - dh) / 2);

        gc.setImageSmoothing(false);
        gc.drawImage(img, dx, dy, dw, dh);
    }
}
