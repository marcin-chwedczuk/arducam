package pl.marcinchwedczuk.template.gui.mainwindow;

import pl.marcinchwedczuk.template.domain.image.RgbImage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

/** Saves pictures as PNG or BMP files. */
public final class ImageFiles {
    public enum Format {
        PNG("png"), BMP("bmp");

        private final String extension;

        Format(String extension) {
            this.extension = extension;
        }

        public String extension() {
            return extension;
        }
    }

    private ImageFiles() { }

    /** Format implied by the file name extension, or null when the extension is not supported. */
    public static Format formatOf(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        for (Format f : Format.values()) {
            if (name.endsWith("." + f.extension())) return f;
        }
        return null;
    }

    /** Adds the extension of {@code format} unless the file already has a supported extension. */
    public static File withExtension(File file, Format format) {
        return formatOf(file) != null ? file : new File(file.getPath() + "." + format.extension());
    }

    public static void save(RgbImage image, File file, Format format) throws IOException {
        // TYPE_INT_RGB, BMP writer does not support alpha.
        BufferedImage buffered = new BufferedImage(image.width(), image.height(), BufferedImage.TYPE_INT_RGB);
        buffered.setRGB(0, 0, image.width(), image.height(), image.pixels(), 0, image.width());

        if (!ImageIO.write(buffered, format.extension(), file)) {
            throw new IOException("No image writer for " + format);
        }
    }
}
