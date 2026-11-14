package pl.marcinchwedczuk.template.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.marcinchwedczuk.template.domain.image.RgbImage;
import pl.marcinchwedczuk.template.gui.mainwindow.ImageFiles;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

public class ImageFilesTest {
    private final RgbImage image = new RgbImage(3, 2, new int[] {
            0xff0000, 0x00ff00, 0x0000ff,
            0x123456, 0xffffff, 0x000000});

    @Test
    public void saves_png_and_bmp_losslessly(@TempDir Path dir) throws Exception {
        for (ImageFiles.Format format : ImageFiles.Format.values()) {
            File file = dir.resolve("picture." + format.extension()).toFile();

            ImageFiles.save(image, file, format);

            BufferedImage read = ImageIO.read(file);
            assertThat(read.getWidth()).isEqualTo(3);
            assertThat(read.getHeight()).isEqualTo(2);
            for (int y = 0; y < 2; y++)
                for (int x = 0; x < 3; x++)
                    assertThat(read.getRGB(x, y) & 0xffffff).as("%s pixel %d,%d", format, x, y)
                            .isEqualTo(image.rgb(x, y));
        }
    }

    @Test
    public void format_and_extension_handling() {
        assertThat(ImageFiles.formatOf(new File("a.PNG"))).isEqualTo(ImageFiles.Format.PNG);
        assertThat(ImageFiles.formatOf(new File("a.bmp"))).isEqualTo(ImageFiles.Format.BMP);
        assertThat(ImageFiles.formatOf(new File("a.jpg"))).isNull();

        assertThat(ImageFiles.withExtension(new File("pic"), ImageFiles.Format.BMP).getName()).isEqualTo("pic.bmp");
        assertThat(ImageFiles.withExtension(new File("pic.png"), ImageFiles.Format.BMP).getName()).isEqualTo("pic.png");
    }
}
