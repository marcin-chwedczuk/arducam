package pl.marcinchwedczuk.template.domain.protocol;

/** The firmware answered a command with {@code ERR}, or with an unexpected response. */
public class DeviceException extends Exception {
    public DeviceException(String message) {
        super(message);
    }

    public static DeviceException fromErr(ResponseLine err) {
        String text = err.errorText();
        return new DeviceException(String.format("%s failed: %s%s",
                err.verb(), err.errorCode(), text.isEmpty() ? "" : " (" + text + ")"));
    }
}
