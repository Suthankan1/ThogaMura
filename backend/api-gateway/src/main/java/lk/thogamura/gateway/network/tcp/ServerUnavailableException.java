package lk.thogamura.gateway.network.tcp;

/**
 * Thrown when the warehouse server is offline, connection is refused, or drops prematurely.
 */
public class ServerUnavailableException extends CommandTransportException {

    public ServerUnavailableException(String message) {
        super(message);
    }

    public ServerUnavailableException(String message, String requestId) {
        super(message, requestId);
    }

    public ServerUnavailableException(String message, String requestId, Throwable cause) {
        super(message, requestId, cause);
    }
}
