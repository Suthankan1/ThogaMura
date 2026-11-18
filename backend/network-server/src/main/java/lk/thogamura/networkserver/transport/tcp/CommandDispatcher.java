package lk.thogamura.networkserver.transport.tcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Dispatches validated command request envelopes to the appropriate registered handler.
 */
public interface CommandDispatcher {

    /**
     * Dispatches an incoming request JSON node to the appropriate command handler
     * and returns the serialized JSON response string.
     *
     * @param requestNode validated request envelope JSON node
     * @return serialized JSON response envelope string
     */
    String dispatch(JsonNode requestNode);
}
