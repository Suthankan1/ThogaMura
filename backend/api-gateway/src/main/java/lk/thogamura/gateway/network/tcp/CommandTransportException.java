package lk.thogamura.gateway.network.tcp;

import java.io.IOException;

/**
 * Root exception for all network transport and wire-level protocol failures
 * encountered by the gateway command client.
 */
public class CommandTransportException extends IOException {

    private final String requestId;

    public CommandTransportException(String message) {
        this(message, null, null);
    }

    public CommandTransportException(String message, String requestId) {
        this(message, requestId, null);
    }

    public CommandTransportException(String message, Throwable cause) {
        this(message, null, cause);
    }

    public CommandTransportException(String message, String requestId, Throwable cause) {
        super(message, cause);
        this.requestId = requestId;
    }

    public String getRequestId() {
        return requestId;
    }
}
