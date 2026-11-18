package lk.thogamura.gateway.network.tcp;

/**
 * Thrown when the warehouse server explicitly returns a Protocol v1 {@code ERROR} response.
 */
public class CommandExecutionException extends CommandTransportException {

    private final String errorCode;

    public CommandExecutionException(String message, String requestId, String errorCode) {
        super(message, requestId);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
