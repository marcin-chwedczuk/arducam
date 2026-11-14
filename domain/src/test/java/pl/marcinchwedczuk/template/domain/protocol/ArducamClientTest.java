package pl.marcinchwedczuk.template.domain.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pl.marcinchwedczuk.template.domain.protocol.FakeArduino.*;

class ArducamClientTest {
    private static final Duration TIMEOUT = Duration.ofMillis(300);

    @Test
    void collects_test_lines_until_final_line() throws Exception {
        var arduino = new FakeArduino(cmd -> text(
                "TEST data_path PASS",
                "TEST counter_clear PASS",
                "TEST step_short PASS",
                "TEST step_long PASS steps=1023",
                "OK MEMTEST result=pass"));
        var client = new ArducamClient(arduino.input(), arduino.output());

        CommandResult result = client.execute("MEMTEST", TIMEOUT);

        assertThat(arduino.receivedCommands()).containsExactly("MEMTEST");
        assertThat(result.isOk()).isTrue();
        assertThat(result.linesOfType(ResponseLine.Type.TEST)).hasSize(4);
        assertThat(TestReport.from("Memory test", result).passed()).isTrue();
    }

    @Test
    void receives_binary_block_and_logs_it_without_payload() throws Exception {
        // Payload containing newlines must not be treated as text.
        byte[] payload = new byte[160 * 120 * 2];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i % 2 == 0 ? '\n' : i);

        var arduino = new FakeArduino(cmd -> concat(
                text("INFO configured 160x120 YUYV", "INFO settling 1000 ms"),
                dataBlock(payload, 160, 120, "YUYV", Crc16Xmodem.compute(payload)),
                text("OK PICTURE w=160 h=120 fmt=YUYV bytes=38400")));
        var client = new ArducamClient(arduino.input(), arduino.output());
        List<String> log = new ArrayList<>();
        client.setListener(new ProtocolListener() {
            @Override public void onSent(String c) { log.add("-> " + c); }
            @Override public void onReceived(String l) { log.add("<- " + l); }
            @Override public void onBinaryReceived(int bytes) { log.add("binary " + bytes); }
        });

        CommandResult result = client.execute("PICTURE fmt=yuyv res=qqvga", TIMEOUT);

        assertThat(result.isOk()).isTrue();
        assertThat(result.blocks()).hasSize(1);
        DataBlock block = result.blocks().getFirst();
        assertThat(block.crcOk()).isTrue();
        assertThat(block.data()).isEqualTo(payload);
        assertThat(block.format()).isEqualTo(PixelFormat.YUYV);

        assertThat(log).containsExactly(
                "-> PICTURE fmt=yuyv res=qqvga",
                "<- INFO configured 160x120 YUYV",
                "<- INFO settling 1000 ms",
                "<- DATA bytes=38400 offset=0 w=160 h=120 fmt=YUYV",
                "binary 38400",
                String.format("<- END crc16=%04x", Crc16Xmodem.compute(payload)),
                "<- OK PICTURE w=160 h=120 fmt=YUYV bytes=38400");
    }

    @Test
    void detects_crc_mismatch() throws Exception {
        byte[] payload = {1, 2, 3, 4};
        var arduino = new FakeArduino(cmd -> concat(
                dataBlock(payload, 1, 2, "YUYV", 0xdead), text("OK READ")));
        var client = new ArducamClient(arduino.input(), arduino.output());

        DataBlock block = client.execute("READ", TIMEOUT).blocks().getFirst();

        assertThat(block.crcOk()).isFalse();
        assertThat(block.expectedCrc()).isEqualTo(0xdead);
    }

    @Test
    void returns_err_response_normally() throws Exception {
        var arduino = new FakeArduino(cmd -> text("ERR PICTURE POWER_OFF"));
        var client = new ArducamClient(arduino.input(), arduino.output());

        CommandResult result = client.execute("PICTURE", TIMEOUT);

        assertThat(result.isOk()).isFalse();
        assertThat(result.finalLine().errorCode()).isEqualTo("POWER_OFF");
    }

    @Test
    void times_out_when_no_final_line() {
        var arduino = new FakeArduino(cmd -> text("INFO working"));
        var client = new ArducamClient(arduino.input(), arduino.output());

        assertThatThrownBy(() -> client.execute("CAMTEST", TIMEOUT))
                .isInstanceOf(ProtocolException.class)
                .extracting(e -> ((ProtocolException) e).kind())
                .isEqualTo(ProtocolException.Kind.TIMEOUT);
    }

    @Test
    void times_out_when_binary_data_stops() {
        var arduino = new FakeArduino(cmd -> text("DATA bytes=100 offset=0 w=10 h=10 fmt=BAYER"));
        var client = new ArducamClient(arduino.input(), arduino.output(), Duration.ofMillis(100));

        assertThatThrownBy(() -> client.execute("READ", Duration.ofSeconds(10)))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("received 0 of 100 bytes");
    }

    @Test
    void end_of_stream_means_disconnected() {
        var client = new ArducamClient(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());

        assertThatThrownBy(() -> client.execute("STATUS", TIMEOUT))
                .isInstanceOf(ProtocolException.class)
                .extracting(e -> ((ProtocolException) e).kind())
                .isEqualTo(ProtocolException.Kind.DISCONNECTED);
    }

    @Test
    void discards_unsolicited_input_before_command() throws Exception {
        var arduino = new FakeArduino(cmd -> text("OK STATUS fw=arducam proto=1"));
        arduino.inject(text("BOOT fw=arducam ver=0.1.0 proto=1"));
        var client = new ArducamClient(arduino.input(), arduino.output());
        List<String> events = new ArrayList<>();
        client.setListener(new ProtocolListener() {
            @Override public void onEvent(String e) { events.add(e); }
        });

        CommandResult result = client.execute("STATUS", TIMEOUT);

        assertThat(result.finalLine().verb()).isEqualTo("STATUS");
        assertThat(events).singleElement().asString().contains("Discarded");
    }
}
