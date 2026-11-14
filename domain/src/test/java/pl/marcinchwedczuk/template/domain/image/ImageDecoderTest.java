package pl.marcinchwedczuk.template.domain.image;

import org.junit.jupiter.api.Test;
import pl.marcinchwedczuk.template.domain.protocol.PixelFormat;
import pl.marcinchwedczuk.template.domain.protocol.RawImage;

import static org.assertj.core.api.Assertions.assertThat;

class ImageDecoderTest {
    @Test
    void y8_is_grayscale_without_gamma() {
        byte[] data = {0, 99, (byte) 255};

        RgbImage img = ImageDecoder.decode(new RawImage(3, 1, PixelFormat.Y8, data));

        assertThat(img.pixels()).containsExactly(0x000000, 0x636363, 0xffffff);
    }

    @Test
    void yuyv_neutral_chroma_gives_gray() {
        byte[] data = {(byte) 128, (byte) 128, (byte) 200, (byte) 128};

        RgbImage img = ImageDecoder.decode(new RawImage(2, 1, PixelFormat.YUYV, data));

        assertThat(img.rgb(0, 0)).isEqualTo(0x808080);
        assertThat(img.rgb(1, 0)).isEqualTo(0xc8c8c8);
    }

    @Test
    void yuyv_high_v_gives_red() {
        // Y=76 U=85 V=255 is roughly pure red in BT.601 full range
        byte[] data = {76, 85, 76, (byte) 255};

        int rgb = ImageDecoder.decode(new RawImage(2, 1, PixelFormat.YUYV, data)).rgb(0, 0);

        assertThat((rgb >> 16) & 0xff).isGreaterThan(240);
        assertThat((rgb >> 8) & 0xff).isLessThan(20);
        assertThat(rgb & 0xff).isLessThan(20);
    }

    @Test
    void bayer_uniform_gives_uniform_gray() {
        byte[] data = new byte[4 * 4];
        java.util.Arrays.fill(data, (byte) 99);

        RgbImage img = ImageDecoder.decodeBayerBggr(4, 4, data);

        for (int y = 0; y < 4; y++)
            for (int x = 0; x < 4; x++)
                assertThat(img.rgb(x, y)).isEqualTo(0x636363);
    }

    @Test
    void decoded_bayer_is_gamma_corrected() {
        byte[] data = new byte[4 * 4];
        java.util.Arrays.fill(data, (byte) 99);

        RgbImage img = ImageDecoder.decode(new RawImage(4, 4, PixelFormat.BAYER, data));

        // 255 * (99/255)^(1/2.2) = 165.8
        assertThat(img.rgb(0, 0)).isEqualTo(0xa6a6a6);
    }

    @Test
    void decoded_yuyv_is_not_gamma_corrected() {
        byte[] data = {99, (byte) 128, 99, (byte) 128};

        RgbImage img = ImageDecoder.decode(new RawImage(2, 1, PixelFormat.YUYV, data));

        assertThat(img.rgb(0, 0)).isEqualTo(0x636363);
    }

    @Test
    void bayer_bggr_channels_are_assigned_correctly() {
        // Only blue sites lit: even rows, even columns.
        int w = 4, h = 4;
        byte[] data = new byte[w * h];
        for (int y = 0; y < h; y += 2)
            for (int x = 0; x < w; x += 2)
                data[y * w + x] = (byte) 200;

        RgbImage img = ImageDecoder.decodeBayerBggr(w, h, data);

        assertThat(img.rgb(0, 0)).isEqualTo(200);   // B only
        assertThat(img.rgb(1, 1)).isEqualTo(200);   // R site, B interpolated from diagonals
    }
}
