package pl.marcinchwedczuk.template.domain.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResponseLineTest {
    @Test
    void parses_ok_status_line_from_real_session() {
        var line = ResponseLine.parse("OK STATUS fw=arducam ver=0.1.0 proto=1 power=on cam=ok image=160x120/YUYV");

        assertThat(line.type()).isEqualTo(ResponseLine.Type.OK);
        assertThat(line.isFinal()).isTrue();
        assertThat(line.verb()).isEqualTo("STATUS");
        assertThat(line.param("image")).isEqualTo("160x120/YUYV");
        assertThat(DeviceStatus.from(line).camera()).isEqualTo("ok");
    }

    @Test
    void parses_err_line() {
        var line = ResponseLine.parse("ERR ? UNKNOWN_CMD FOO");

        assertThat(line.type()).isEqualTo(ResponseLine.Type.ERR);
        assertThat(line.isFinal()).isTrue();
        assertThat(line.verb()).isEqualTo("?");
        assertThat(line.errorCode()).isEqualTo("UNKNOWN_CMD");
        assertThat(line.errorText()).isEqualTo("FOO");
    }

    @Test
    void parses_test_line_with_hex_values() {
        var line = ResponseLine.parse("TEST sccb_id PASS pid=0x76 ver=0x73 midh=0x7f midl=0xa2");

        assertThat(line.type()).isEqualTo(ResponseLine.Type.TEST);
        assertThat(line.isFinal()).isFalse();
        assertThat(line.token(2)).isEqualTo("PASS");
        assertThat(line.intParam("pid")).isEqualTo(0x76);
    }

    @Test
    void unknown_line_types_are_tolerated() {
        var line = ResponseLine.parse("WHATEVER a=1");
        assertThat(line.type()).isEqualTo(ResponseLine.Type.UNKNOWN);
        assertThat(ResponseLine.parse("").type()).isEqualTo(ResponseLine.Type.UNKNOWN);
        // Type tokens are case-sensitive, firmware sends upper case.
        assertThat(ResponseLine.parse("ok STATUS").type()).isEqualTo(ResponseLine.Type.UNKNOWN);
    }

    @Test
    void missing_int_param_is_reported() {
        assertThatThrownBy(() -> ResponseLine.parse("DATA w=1").intParam("bytes"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bytes");
    }
}
