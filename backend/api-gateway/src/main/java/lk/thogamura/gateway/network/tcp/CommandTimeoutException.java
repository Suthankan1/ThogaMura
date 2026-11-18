package lk.thogamura.gateway.network.tcp;

/**
 * Thrown when either connection establishment or socket read exceeds configured bounds.
 */
public class CommandTimeoutException extends CommandTransportException {

    public CommandTimeoutException(String message) {
        super(message);
    }

    public CommandTimeoutException(String message, String requestId) {
        super(message, requestId);
    }

    public CommandTimeoutException(String message, String requestId, Throwable cause) {
        super(message, requestId, cause);
    }
}
