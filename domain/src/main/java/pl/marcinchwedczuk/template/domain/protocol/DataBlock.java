package pl.marcinchwedczuk.template.domain.protocol;

/**
 * Binary block received between {@code DATA} and {@code END} lines.
 * {@code width}, {@code height} and {@code format} describe the whole image,
 * {@code offset} is the position of the first byte within the image.
 */
public record DataBlock(int offset, int width, int height, PixelFormat format,
                        byte[] data, int expectedCrc, int actualCrc) {
    public boolean crcOk() {
        return expectedCrc == actualCrc;
    }
}
