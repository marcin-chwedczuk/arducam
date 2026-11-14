package pl.marcinchwedczuk.template.domain.image;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RgbImageTest {
    // 3x2:
    // 1 2 3
    // 4 5 6
    private final RgbImage img = new RgbImage(3, 2, new int[] {1, 2, 3, 4, 5, 6});

    @Test
    void rotate_clockwise() {
        RgbImage r = img.rotateClockwise();
        // 4 1
        // 5 2
        // 6 3
        assertThat(r.width()).isEqualTo(2);
        assertThat(r.height()).isEqualTo(3);
        assertThat(r.pixels()).containsExactly(4, 1, 5, 2, 6, 3);
    }

    @Test
    void rotate_counter_clockwise() {
        RgbImage r = img.rotateCounterClockwise();
        // 3 6
        // 2 5
        // 1 4
        assertThat(r.pixels()).containsExactly(3, 6, 2, 5, 1, 4);
    }

    @Test
    void rotate_by_quarter_turns() {
        assertThat(img.rotate(4).pixels()).containsExactly(img.pixels());
        assertThat(img.rotate(-1).pixels()).containsExactly(img.rotateCounterClockwise().pixels());
        assertThat(img.rotate(2).pixels()).containsExactly(6, 5, 4, 3, 2, 1);
    }

    @Test
    void argb_pixels_are_opaque() {
        assertThat(img.argbPixels()[0]).isEqualTo(0xff000001);
    }
}
