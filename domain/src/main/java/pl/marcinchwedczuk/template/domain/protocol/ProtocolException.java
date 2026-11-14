package pl.marcinchwedczuk.template.domain.protocol;

import java.io.IOException;

/** Communication failure: timeout, lost connection or malformed response. */
public class ProtocolException extends IOException {
    public enum Kind { TIMEOUT, DISCONNECTED, MALFORMED }

    private final Kind kind;

    public ProtocolException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public ProtocolException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
