package pl.marcinchwedczuk.template.domain.protocol;

import java.util.function.Function;

import static pl.marcinchwedczuk.template.domain.protocol.FakeArduino.*;

/**
 * Stateful simulation of the protocol v2 firmware, for {@link FakeArduino}.
 * Fields can be changed by tests to inject failures.
 */
class FakeFirmware implements Function<String, byte[]> {
    boolean powered = false;
    String config = null;          // e.g. "160x120/YUYV"
    int w, h;
    String fmt;
    byte[] image = null;

    /** Number of next POWER on commands that fail with SCCB. */
    int failPowerOn = 0;
    /** Number of next image transfers (PICTURE or READ) sent with a corrupted CRC. */
    int corruptCrc = 0;
    String protocol = "2";

    /** Simulates pressing the reset button. */
    void reset() {
        powered = false;
        config = null;
        image = null;
    }

    @Override
    public byte[] apply(String cmd) {
        String[] t = cmd.split(" ");
        return switch (t[0]) {
            case "STATUS" -> text(String.format("OK STATUS fw=arducam ver=0.2.0 proto=%s power=%s cam=unknown config=%s image=none",
                    protocol, powered ? "on" : "off", config == null ? "none" : config));
            case "POWER" -> power(cmd);
            case "MEMTEST" -> text("TEST data_path PASS", "OK MEMTEST result=pass");
            case "CAMTEST" -> {
                if (!powered) yield text("ERR CAMTEST POWER_OFF");
                config = null;
                yield text("TEST sccb_id PASS pid=0x76 ver=0x73", "OK CAMTEST result=pass");
            }
            case "CONFIG" -> configure(cmd);
            case "PICTURE" -> picture(cmd);
            case "READ" -> image == null ? text("ERR READ NO_IMAGE") : concat(imageBlock(), text("OK READ"));
            default -> text("ERR ? UNKNOWN_CMD " + t[0]);
        };
    }

    private byte[] power(String cmd) {
        if (cmd.equals("POWER on")) {
            powered = true;
            config = null;
            if (failPowerOn > 0) {
                failPowerOn--;
                return text("ERR POWER SCCB no answer");
            }
            return text("OK POWER state=on pid=0x76 ver=0x73");
        }
        powered = false;
        config = null;
        return text("OK POWER state=off");
    }

    private byte[] configure(String cmd) {
        if (!powered) return text("ERR CONFIG POWER_OFF");
        boolean bayer = cmd.contains("fmt=bayer");
        if (cmd.contains("res=qqvga")) { w = 160; h = 120; }
        else if (cmd.contains("res=vga")) { w = 640; h = 480; }
        else { w = 320; h = 240; }
        fmt = bayer ? "BAYER" : "YUYV";
        config = w + "x" + h + "/" + fmt;
        int bytes = w * h * (bayer ? 1 : 2);
        return text(String.format("OK CONFIG w=%d h=%d fmt=%s bytes=%d", w, h, fmt, bytes));
    }

    private byte[] picture(String cmd) {
        if (!powered) return text("ERR PICTURE POWER_OFF");
        if (config == null) return text("ERR PICTURE NOT_CONFIGURED");

        image = new byte[w * h * (fmt.equals("BAYER") ? 1 : 2)];
        for (int i = 0; i < image.length; i++) image[i] = (byte) (i * 7);

        String settle = cmd.contains("settle=") ? cmd.substring(cmd.indexOf("settle=") + 7) : null;
        return concat(
                settle == null ? new byte[0] : text("INFO settling " + settle + " ms"),
                imageBlock(),
                text(String.format("OK PICTURE w=%d h=%d fmt=%s bytes=%d", w, h, fmt, image.length)));
    }

    private byte[] imageBlock() {
        int crc = Crc16Xmodem.compute(image);
        if (corruptCrc > 0) {
            corruptCrc--;
            crc ^= 1;
        }
        return dataBlock(image, w, h, fmt, crc);
    }
}
