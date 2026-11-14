package pl.marcinchwedczuk.template.domain.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Low level, strict request/response implementation of the arducam serial protocol
 * (see {@code arduino/PROTOCOL.md}): sends one command and reads until its final
 * {@code OK}/{@code ERR} line, receiving any {@code DATA} blocks on the way.
 *
 * <p>The input stream is expected to throw {@link InterruptedIOException} when a read
 * times out (jSerialComm does that in semi-blocking mode) so that deadlines can be checked.
 * Any other {@link IOException}, or end of stream, means the connection is lost.
 *
 * <p>Not thread safe, use from a single thread.
 */
public class ArducamClient {
    /** Lines longer than this are garbage, the firmware never sends them. */
    private static final int MAX_LINE_LENGTH = 1024;
    private static final int MAX_DATA_BYTES = 1024 * 1024;

    private final InputStream in;
    private final OutputStream out;
    private final Duration dataIdleTimeout;
    private volatile ProtocolListener listener = new ProtocolListener() { };

    private final byte[] buffer = new byte[4096];
    private int bufferPos = 0;
    private int bufferLimit = 0;

    public ArducamClient(InputStream in, OutputStream out, Duration dataIdleTimeout) {
        this.in = requireNonNull(in);
        this.out = requireNonNull(out);
        this.dataIdleTimeout = requireNonNull(dataIdleTimeout);
    }

    public ArducamClient(InputStream in, OutputStream out) {
        this(in, out, Duration.ofSeconds(1));
    }

    public void setListener(ProtocolListener listener) {
        this.listener = requireNonNull(listener);
    }

    /**
     * Sends a lone newline, so that the firmware discards any partial command.
     * Recommended after opening the port.
     */
    public void sendEmptyLine() throws IOException {
        write("\n");
    }

    /**
     * Sends {@code command} and reads the response until the final line.
     * An {@code ERR} response is returned normally, check {@link CommandResult#isOk()}.
     */
    public CommandResult execute(String command, Duration timeout) throws IOException {
        discardPendingInput();

        listener.onSent(command);
        write(command + "\n");

        long deadline = System.nanoTime() + timeout.toNanos();
        List<ResponseLine> lines = new ArrayList<>();
        List<DataBlock> blocks = new ArrayList<>();

        while (true) {
            ResponseLine line = ResponseLine.parse(readLine(command, deadline));
            listener.onReceived(line.raw());

            if (line.isFinal()) {
                return new CommandResult(command, line, List.copyOf(lines), List.copyOf(blocks));
            }

            lines.add(line);
            if (line.type() == ResponseLine.Type.DATA) {
                blocks.add(readDataBlock(command, line, deadline));
            }
            // Other line types (INFO, TEST, BOOT, unknown) are only collected.
        }
    }

    private DataBlock readDataBlock(String command, ResponseLine header, long deadline) throws IOException {
        int bytes, offset, width, height;
        PixelFormat format;
        try {
            bytes = header.intParam("bytes");
            offset = header.intParam("offset");
            width = header.intParam("w");
            height = header.intParam("h");
            format = PixelFormat.parse(header.param("fmt") == null ? "" : header.param("fmt"));
        } catch (IllegalArgumentException e) {
            throw new ProtocolException(ProtocolException.Kind.MALFORMED,
                    "Bad DATA header: " + e.getMessage());
        }
        if (bytes < 0 || bytes > MAX_DATA_BYTES) {
            throw new ProtocolException(ProtocolException.Kind.MALFORMED,
                    "Bad DATA size: " + header.raw());
        }

        byte[] data = readExactly(command, bytes);
        listener.onBinaryReceived(bytes);

        ResponseLine end = ResponseLine.parse(readLine(command, deadline));
        listener.onReceived(end.raw());
        if (end.type() != ResponseLine.Type.END || end.param("crc16") == null) {
            throw new ProtocolException(ProtocolException.Kind.MALFORMED,
                    "Expected 'END crc16=...' after binary data, got: " + end.raw());
        }

        int expectedCrc;
        try {
            expectedCrc = Integer.parseInt(end.param("crc16"), 16);
        } catch (NumberFormatException e) {
            throw new ProtocolException(ProtocolException.Kind.MALFORMED, "Bad CRC: " + end.raw());
        }

        int actualCrc = Crc16Xmodem.compute(data);
        if (actualCrc != expectedCrc) {
            listener.onEvent(String.format("CRC mismatch: expected %04x, got %04x.", expectedCrc, actualCrc));
        }

        return new DataBlock(offset, width, height, format, data, expectedCrc, actualCrc);
    }

    private byte[] readExactly(String command, int count) throws IOException {
        byte[] data = new byte[count];
        int received = 0;
        long lastByteTime = System.nanoTime();
        listener.onBinaryProgress(0, count);

        while (received < count) {
            if (bufferPos == bufferLimit) {
                if (!fillBuffer()) {
                    if (System.nanoTime() - lastByteTime > dataIdleTimeout.toNanos()) {
                        throw new ProtocolException(ProtocolException.Kind.TIMEOUT, String.format(
                                "%s: no data for %d ms, received %d of %d bytes.",
                                command, dataIdleTimeout.toMillis(), received, count));
                    }
                    continue;
                }
                lastByteTime = System.nanoTime();
            }

            int n = Math.min(count - received, bufferLimit - bufferPos);
            System.arraycopy(buffer, bufferPos, data, received, n);
            bufferPos += n;
            received += n;
            listener.onBinaryProgress(received, count);
        }

        return data;
    }

    private String readLine(String command, long deadline) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            if (bufferPos == bufferLimit) {
                if (!fillBuffer()) {
                    if (System.nanoTime() > deadline) {
                        throw new ProtocolException(ProtocolException.Kind.TIMEOUT,
                                command + ": timeout waiting for response.");
                    }
                    continue;
                }
            }

            byte b = buffer[bufferPos++];
            if (b == '\n') {
                return line.toString(StandardCharsets.US_ASCII);
            }
            if (b != '\r') {
                line.write(b);
            }
            if (line.size() > MAX_LINE_LENGTH) {
                throw new ProtocolException(ProtocolException.Kind.MALFORMED,
                        command + ": response line too long (wrong baud rate?).");
            }
        }
    }

    /** @return false on read timeout. */
    private boolean fillBuffer() throws IOException {
        int n;
        try {
            n = in.read(buffer, 0, buffer.length);
        } catch (InterruptedIOException timeout) {
            return false;
        } catch (IOException e) {
            throw new ProtocolException(ProtocolException.Kind.DISCONNECTED,
                    "Connection lost: " + e.getMessage(), e);
        }

        if (n < 0) {
            throw new ProtocolException(ProtocolException.Kind.DISCONNECTED, "Connection lost.");
        }
        bufferPos = 0;
        bufferLimit = n;
        return n > 0;
    }

    /** Drops anything received outside a command, e.g. a BOOT line after a reset. */
    private void discardPendingInput() throws IOException {
        int discarded = bufferLimit - bufferPos;
        bufferPos = bufferLimit = 0;

        try {
            while (in.available() > 0) {
                int n = in.read(buffer, 0, Math.min(buffer.length, in.available()));
                if (n <= 0) break;
                discarded += n;
            }
        } catch (InterruptedIOException ignored) {
            // nothing more to read
        } catch (IOException e) {
            throw new ProtocolException(ProtocolException.Kind.DISCONNECTED,
                    "Connection lost: " + e.getMessage(), e);
        }

        if (discarded > 0) {
            listener.onEvent("Discarded " + discarded + " unexpected byte(s).");
        }
    }

    private void write(String text) throws IOException {
        try {
            out.write(text.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        } catch (InterruptedIOException e) {
            throw new ProtocolException(ProtocolException.Kind.TIMEOUT, "Write timeout.", e);
        } catch (IOException e) {
            throw new ProtocolException(ProtocolException.Kind.DISCONNECTED,
                    "Connection lost: " + e.getMessage(), e);
        }
    }
}
