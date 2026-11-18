package lk.thogamura.networkserver.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import lk.thogamura.networkserver.transport.tcp.CommandHandler;
import lk.thogamura.protocol.ProtocolInfo;
import lk.thogamura.protocol.tcp.TcpMessageCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.UUID;

/**
 * Command handler adapting frozen {@code REQUEST_STOCK} wire envelopes to the
 * transactional {@link StockReservationService}.
 *
 * <p>Validates envelope data fields (SKU and positive quantity), invokes the atomic
 * reservation service, and returns contract-compliant JSON responses.</p>
 */
public class StockReservationCommandHandler implements CommandHandler {

    private static final Logger logger = LoggerFactory.getLogger(StockReservationCommandHandler.class);

    private final StockReservationService reservationService;

    public StockReservationCommandHandler(StockReservationService reservationService) {
        this.reservationService = Objects.requireNonNull(reservationService, "reservationService must not be null");
    }

    @Override
    public String handle(JsonNode requestNode) {
        Objects.requireNonNull(requestNode, "requestNode must not be null");

        // 1. Extract and validate requestId (must be canonical UUID)
        String requestIdStr = requestNode.hasNonNull("requestId") ? requestNode.get("requestId").asText().trim() : null;
        if (requestIdStr == null || requestIdStr.isBlank()) {
            return TcpMessageCodec.formatErrorResponse(
                    null,
                    ProtocolInfo.ERROR_VALIDATION,
                    "Missing required field 'requestId'"
            );
        }

        UUID requestId;
        try {
            requestId = UUID.fromString(requestIdStr);
        } catch (IllegalArgumentException e) {
            return TcpMessageCodec.formatErrorResponse(
                    requestIdStr,
                    ProtocolInfo.ERROR_VALIDATION,
                    "Field 'requestId' is not a valid canonical UUID: '" + requestIdStr + "'"
            );
        }

        // 2. Validate 'data' object
        JsonNode dataNode = requestNode.get("data");
        if (dataNode == null || !dataNode.isObject()) {
            return TcpMessageCodec.formatErrorResponse(
                    requestIdStr,
                    ProtocolInfo.ERROR_VALIDATION,
                    "Missing required 'data' object in REQUEST_STOCK frame"
            );
        }

        // 3. Validate 'sku'
        if (!dataNode.hasNonNull("sku") || !dataNode.get("sku").isTextual() || dataNode.get("sku").asText().isBlank()) {
            return TcpMessageCodec.formatErrorResponse(
                    requestIdStr,
                    ProtocolInfo.ERROR_VALIDATION,
                    "Missing required field 'sku' in command data"
            );
        }
        String sku = dataNode.get("sku").asText().trim();

        // 4. Validate 'quantity'
        if (!dataNode.has("quantity") || !dataNode.get("quantity").isInt()) {
            return TcpMessageCodec.formatErrorResponse(
                    requestIdStr,
                    ProtocolInfo.ERROR_VALIDATION,
                    "Missing or non-integer field 'quantity' in command data"
            );
        }
        int quantity = dataNode.get("quantity").asInt();

        if (quantity <= 0) {
            return TcpMessageCodec.formatErrorResponse(
                    requestIdStr,
                    ProtocolInfo.ERROR_VALIDATION,
                    "Requested quantity must be positive, but was " + quantity
            );
        }

        // 5. Invoke atomic reservation service
        logger.debug("Executing atomic stock reservation for requestId='{}', sku='{}', quantity={}",
                requestId, sku, quantity);
        ReservationResult result = reservationService.reserveStock(new ReservationCommand(requestId, sku, quantity));
        return result.responseJson();
    }
}
