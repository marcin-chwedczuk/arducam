package pl.marcinchwedczuk.template.domain.protocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class Crc16XmodemTest {
    @Test
    void check_value_from_protocol_spec() {
        assertThat(Crc16Xmodem.compute("123456789".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo(0x31c3);
    }

    @Test
    void empty_input_gives_init_value() {
        assertThat(Crc16Xmodem.compute(new byte[0])).isEqualTo(0);
    }
}
