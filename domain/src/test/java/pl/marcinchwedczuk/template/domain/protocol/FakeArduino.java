package pl.marcinchwedczuk.template.domain.protocol;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * In-memory serial port: every line written by the host is passed to the responder,
 * whose answer becomes readable from {@link #input()}. Reads with no data throw
 * {@link InterruptedIOException}, like jSerialComm on timeout.
 */
class FakeArduino {
    private final ArrayDeque<Byte> pending = new ArrayDeque<>();
    private final List<String> received = new ArrayList<>();
    private final Function<String, byte[]> responder;

    FakeArduino(Function<String, byte[]> responder) {
        this.responder = responder;
    }

    List<String> receivedCommands() {
        return received;
    }

    InputStream input() {
        return new InputStream() {
            @Override
            public int read() throws InterruptedIOException {
                if (pending.isEmpty()) throw new InterruptedIOException("timeout");
                return pending.poll() & 0xff;
            }

            @Override
            public int read(byte[] b, int off, int len) throws InterruptedIOException {
                if (pending.isEmpty()) throw new InterruptedIOException("timeout");
                int n = 0;
                while (n < len && !pending.isEmpty()) {
                    b[off + n++] = pending.poll();
                }
                return n;
            }

            @Override
            public int available() {
                return pending.size();
            }
        };
    }

    OutputStream output() {
        return new OutputStream() {
            private final ByteArrayOutputStream line = new ByteArrayOutputStream();

            @Override
            public void write(int b) {
                if (b == '\n') {
                    String cmd = line.toString(StandardCharsets.US_ASCII);
                    line.reset();
                    if (cmd.isEmpty()) return;
                    received.add(cmd);
                    for (byte r : responder.apply(cmd)) pending.add(r);
                } else {
                    line.write(b);
                }
            }
        };
    }

    void inject(byte[] bytes) {
        for (byte b : bytes) pending.add(b);
    }

    static byte[] text(String... lines) {
        return (String.join("\n", lines) + "\n").getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] dataBlock(byte[] payload, int w, int h, String fmt, int crc) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(text(String.format("DATA bytes=%d offset=0 w=%d h=%d fmt=%s", payload.length, w, h, fmt)));
        out.writeBytes(payload);
        out.writeBytes(text(String.format("END crc16=%04x", crc)));
        return out.toByteArray();
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }
}
