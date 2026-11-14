package pl.marcinchwedczuk.template.domain.protocol;

/** Observes the traffic. Called on the thread that executes commands. */
public interface ProtocolListener {
    /** A command was sent (without the trailing newline). */
    default void onSent(String command) { }

    /** A text line was received (without the line ending). */
    default void onReceived(String line) { }

    /** Progress of a binary block. Called often, implementations should be cheap. */
    default void onBinaryProgress(int received, int total) { }

    /** Binary data was received (the data itself is not passed, it can be huge). CRC is checked later. */
    default void onBinaryReceived(int bytes) { }

    /** Anything else worth logging, e.g. discarded input. */
    default void onEvent(String event) { }
}
