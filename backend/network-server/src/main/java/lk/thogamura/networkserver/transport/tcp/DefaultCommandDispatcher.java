package lk.thogamura.networkserver.transport.tcp;

import com.fasterxml.jackson.databind.JsonNode;
import lk.thogamura.networkserver.integration.supplier.SupplierCommandHandler;
import lk.thogamura.protocol.ProtocolInfo;
import lk.thogamura.protocol.tcp.TcpMessageCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default command dispatcher implementation handling the frozen diagnostic {@code PING}
 * and advisory external {@code GET_SUPPLIER_STOCK} commands.
 *
 * <p>Supports registration of additional business command handlers (e.g. Member 4 stock operations)
 * without modifying TCP transport or socket management.</p>
 */
public class DefaultCommandDispatcher implements CommandDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(DefaultCommandDispatcher.class);

    private final Map<String, CommandHandler> handlers = new ConcurrentHashMap<>();

    public DefaultCommandDispatcher() {
        this(new SupplierCommandHandler());
    }

    public DefaultCommandDispatcher(SupplierCommandHandler supplierCommandHandler) {
        registerHandler(ProtocolInfo.COMMAND_PING, this::handlePing);
        if (supplierCommandHandler != null) {
            registerHandler(ProtocolInfo.COMMAND_GET_SUPPLIER_STOCK, supplierCommandHandler);
        }
    }

    /**
     * Registers a custom handler for a given command type.
     *
     * @param commandType command type string from {@link ProtocolInfo}
     * @param handler handler implementation
     */
    public void registerHandler(String commandType, CommandHandler handler) {
        Objects.requireNonNull(commandType, "commandType must not be null");
        Objects.requireNonNull(handler, "handler must not be null");
        handlers.put(commandType, handler);
    }

    @Override
    public String dispatch(JsonNode requestNode) {
        String type = requestNode.hasNonNull("type") ? requestNode.get("type").asText() : "";
        String requestId = requestNode.hasNonNull("requestId") ? requestNode.get("requestId").asText() : null;

        CommandHandler handler = handlers.get(type);
        if (handler == null) {
            logger.warn("Received unhandled or unsupported command type: '{}' (requestId: '{}')", type, requestId);
            return TcpMessageCodec.formatErrorResponse(
                    requestId,
                    ProtocolInfo.ERROR_VALIDATION,
                    "Unsupported or unhandled command type: " + type
            );
        }

        try {
            return handler.handle(requestNode);
        } catch (Exception e) {
            logger.error("Error executing handler for command type: '{}' (requestId: '{}')", type, requestId, e);
            return TcpMessageCodec.formatErrorResponse(
                    requestId,
                    ProtocolInfo.ERROR_INTERNAL,
                    "An unexpected error occurred processing command '" + type + "'"
            );
        }
    }

    private String handlePing(JsonNode requestNode) {
        String requestId = requestNode.get("requestId").asText();
        String serverTime = Instant.now().toString();
        logger.debug("Handled PING command for requestId: '{}', serverTime: '{}'", requestId, serverTime);
        return TcpMessageCodec.formatResultResponse(
                requestId,
                ProtocolInfo.STATUS_PONG,
                Map.of("serverTime", serverTime)
        );
    }
}
