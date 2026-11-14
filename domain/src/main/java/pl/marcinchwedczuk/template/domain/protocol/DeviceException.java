package pl.marcinchwedczuk.template.domain.protocol;

/** The firmware answered a command with {@code ERR}, or with an unexpected response. */
public class DeviceException extends Exception {
    private final String errorCode;

    public DeviceException(String message) {
        this(message, null);
    }

    private DeviceException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public static DeviceException fromErr(ResponseLine err) {
        String text = err.errorText();
        return new DeviceException(String.format("%s failed: %s%s",
                err.verb(), err.errorCode(), text.isEmpty() ? "" : " (" + text + ")"),
                err.errorCode());
    }

    /** Error code from the {@code ERR} line, e.g. {@code POWER_OFF}, or null for other failures. */
    public String errorCode() {
        return errorCode;
    }
}
