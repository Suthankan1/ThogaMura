package lk.thogamura.networkserver.transport.tcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Functional interface for handling a specific command payload.
 *
 * <p>Enables clean decoupling between low-level TCP framing/threading and
 * high-level business command handling (e.g. inventory reservations by Member 4).</p>
 */
@FunctionalInterface
public interface CommandHandler {

    /**
     * Executes the command and returns the JSON response envelope string.
     *
     * @param requestNode parsed and envelope-validated JSON request node
     * @return serialized JSON response envelope (conforming to Protocol v1)
     * @throws Exception if processing encounters an unrecoverable failure
     */
    String handle(JsonNode requestNode) throws Exception;
}
