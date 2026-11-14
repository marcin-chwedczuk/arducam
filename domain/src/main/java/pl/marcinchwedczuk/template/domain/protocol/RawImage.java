package pl.marcinchwedczuk.template.domain.protocol;

/** Image exactly as sent by the firmware: sensor orientation, no colour processing. */
public record RawImage(int width, int height, PixelFormat format, byte[] data) {
    public RawImage {
        int expected = expectedBytes(width, height, format);
        if (data.length != expected) {
            throw new IllegalArgumentException(String.format(
                    "%dx%d %s image needs %d bytes, got %d.", width, height, format, expected, data.length));
        }
    }

    public static int expectedBytes(int width, int height, PixelFormat format) {
        return width * height * format.bytesPerPixel();
    }

    @Override
    public String toString() {
        return String.format("%dx%d/%s", width, height, format);
    }
}
