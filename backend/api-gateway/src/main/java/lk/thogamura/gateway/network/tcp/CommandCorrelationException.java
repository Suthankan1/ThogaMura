package lk.thogamura.gateway.network.tcp;

/**
 * Thrown when the requestId returned in a server result envelope does not match
 * the requestId of the dispatched command.
 */
public class CommandCorrelationException extends CommandTransportException {

    private final String expectedRequestId;
    private final String receivedRequestId;

    public CommandCorrelationException(String message, String expectedRequestId, String receivedRequestId) {
        super(message, expectedRequestId);
        this.expectedRequestId = expectedRequestId;
        this.receivedRequestId = receivedRequestId;
    }

    public String getExpectedRequestId() {
        return expectedRequestId;
    }

    public String getReceivedRequestId() {
        return receivedRequestId;
    }
}
