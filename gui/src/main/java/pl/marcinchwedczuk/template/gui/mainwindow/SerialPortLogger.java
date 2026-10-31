package pl.marcinchwedczuk.template.gui.mainwindow;

import javafx.application.Platform;

import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

public class SerialPortLogger {
    private final Consumer<String> loggerAction;

    public SerialPortLogger(Consumer<String> loggerAction) {
        this.loggerAction = requireNonNull(loggerAction);
    }

    public void logSent(String data) {
        Platform.runLater(() -> {
            loggerAction.accept("-> [" + escapeJavaString(data) + "]");
        });
    }

    public void logRecv(String data) {
        Platform.runLater(() -> {
            loggerAction.accept("<- [" + escapeJavaString(data) + "]");
        });
    }

    public void logEvent(String event) {
        Platform.runLater(() -> {
            loggerAction.accept(escapeJavaString(event));
        });
    }

    public static String escapeJavaString(String input) {
        StringBuilder escapedString = new StringBuilder();
        for (char c : input.toCharArray()) {
            switch (c) {
                case '\n':
                    escapedString.append("\\n");
                    break;
                case '\t':
                    escapedString.append("\\t");
                    break;
                case '\b':
                    escapedString.append("\\b");
                    break;
                case '\r':
                    escapedString.append("\\r");
                    break;
                case '\f':
                    escapedString.append("\\f");
                    break;
                case '\"':
                    escapedString.append("\\\"");
                    break;
                case '\'':
                    escapedString.append("\\'");
                    break;
                case '\\':
                    escapedString.append("\\\\");
                    break;
                default:
                    if (c < 32 || c > 126) {
                        escapedString.append(String.format("\\u%04x", (int) c));
                    } else {
                        escapedString.append(c);
                    }
            }
        }
        return escapedString.toString();
    }
}
