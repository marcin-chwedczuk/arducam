package pl.marcinchwedczuk.template.domain.protocol;

/**
 * CRC-16/XMODEM: poly 0x1021, init 0x0000, no reflection, no final XOR.
 * Same as avr-libc {@code _crc_xmodem_update()}. Check value: "123456789" -> 0x31c3.
 */
public final class Crc16Xmodem {
    private Crc16Xmodem() { }

    public static int update(int crc, byte b) {
        crc ^= (b & 0xff) << 8;
        for (int i = 0; i < 8; i++) {
            crc = ((crc & 0x8000) != 0) ? (crc << 1) ^ 0x1021 : (crc << 1);
        }
        return crc & 0xffff;
    }

    public static int compute(byte[] data, int offset, int length) {
        int crc = 0;
        for (int i = offset; i < offset + length; i++) {
            crc = update(crc, data[i]);
        }
        return crc;
    }

    public static int compute(byte[] data) {
        return compute(data, 0, data.length);
    }
}
