package pl.marcinchwedczuk.template.domain.image;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ColorCorrectionTest {
    @Test
    void gamma_lut_end_points_and_mid_value() {
        int[] lut = ColorCorrection.gammaLut(2.2);

        assertThat(lut[0]).isEqualTo(0);
        assertThat(lut[255]).isEqualTo(255);
        // 255 * (128/255)^(1/2.2) = 186.1
        assertThat(lut[128]).isEqualTo(186);
        // Monotonic
        for (int i = 1; i < 256; i++) assertThat(lut[i]).isGreaterThanOrEqualTo(lut[i - 1]);
    }

    @Test
    void gamma_brightens_dark_values() {
        RgbImage img = new RgbImage(1, 1, new int[] {0x404040});

        assertThat(ColorCorrection.applyGamma(img, 2.2).rgb(0, 0) & 0xff).isGreaterThan(0x40);
    }

    @Test
    void white_balance_neutralizes_tinted_image() {
        // Gray scene seen through a green/blue tint: R low, B high.
        int[] pixels = new int[100];
        for (int i = 0; i < pixels.length; i++) {
            int level = 40 + i;              // varying brightness
            int r = level / 2, g = level, b = Math.min(255, level * 3 / 2);
            pixels[i] = (r << 16) | (g << 8) | b;
        }

        RgbImage balanced = ColorCorrection.grayWorldWhiteBalance(new RgbImage(10, 10, pixels));

        double[] means = channelMeans(balanced);
        assertThat(means[0]).isCloseTo(means[1], org.assertj.core.data.Offset.offset(1.0));
        assertThat(means[2]).isCloseTo(means[1], org.assertj.core.data.Offset.offset(1.0));
    }

    @Test
    void white_balance_gain_is_limited() {
        // Pure green scene: R and B gains would be infinite.
        int[] pixels = new int[16];
        Arrays.fill(pixels, 0x01c801); // R=1 G=200 B=1

        RgbImage balanced = ColorCorrection.grayWorldWhiteBalance(new RgbImage(4, 4, pixels));

        int p = balanced.rgb(0, 0);
        assertThat((p >> 16) & 0xff).isEqualTo(4);   // 1 * MAX_WB_GAIN
        assertThat((p >> 8) & 0xff).isEqualTo(200);  // G untouched
        assertThat(p & 0xff).isEqualTo(4);
    }

    @Test
    void black_image_is_left_alone() {
        RgbImage black = new RgbImage(2, 2, new int[4]);

        assertThat(ColorCorrection.correctRawImage(black).pixels()).containsOnly(0);
    }

    private static double[] channelMeans(RgbImage img) {
        double r = 0, g = 0, b = 0;
        for (int p : img.pixels()) {
            r += (p >> 16) & 0xff;
            g += (p >> 8) & 0xff;
            b += p & 0xff;
        }
        int n = img.width() * img.height();
        return new double[] {r / n, g / n, b / n};
    }
}
