package lk.thogamura.networkserver.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import lk.thogamura.networkserver.persistence.StockMovementRecord;
import lk.thogamura.networkserver.persistence.StockRequestRecord;
import lk.thogamura.networkserver.transport.tcp.CommandHandler;
import lk.thogamura.protocol.ProtocolInfo;
import lk.thogamura.protocol.tcp.TcpMessageCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Command handler adapting frozen wire commands to {@link StockFulfillmentService}.
 *
 * <p>Handles:
 * <ul>
 *   <li>{@code MARK_PICKED}: Transitions request state from ACCEPTED to PICKED</li>
 *   <li>{@code MARK_DISPATCHED}: Transitions request state from PICKED to DISPATCHED</li>
 *   <li>{@code GET_REQUESTS}: Returns fulfillment request list with optional status filtering</li>
 *   <li>{@code GET_MOVEMENTS}: Returns historical stock movements</li>
 * </ul>
 */
public class StockFulfillmentCommandHandler implements CommandHandler {

    private static final Logger logger = LoggerFactory.getLogger(StockFulfillmentCommandHandler.class);

    private final StockFulfillmentService fulfillmentService;

    public StockFulfillmentCommandHandler(StockFulfillmentService fulfillmentService) {
        this.fulfillmentService = Objects.requireNonNull(fulfillmentService, "fulfillmentService must not be null");
    }

    @Override
    public String handle(JsonNode requestNode) {
        Objects.requireNonNull(requestNode, "requestNode must not be null");

        String type = requestNode.hasNonNull("type") ? requestNode.get("type").asText() : "";
        String requestIdStr = requestNode.hasNonNull("requestId") ? requestNode.get("requestId").asText().trim() : null;

        if (requestIdStr == null || requestIdStr.isBlank()) {
            return TcpMessageCodec.formatErrorResponse(
                    null,
                    ProtocolInfo.ERROR_VALIDATION,
                    "Missing required field 'requestId'"
            );
        }

        UUID commandRequestId;
        try {
            commandRequestId = UUID.fromString(requestIdStr);
        } catch (IllegalArgumentException e) {
            return TcpMessageCodec.formatErrorResponse(
                    requestIdStr,
                    ProtocolInfo.ERROR_VALIDATION,
                    "Field 'requestId' is not a valid canonical UUID: '" + requestIdStr + "'"
            );
        }

        return switch (type) {
            case ProtocolInfo.COMMAND_MARK_PICKED -> handlePick(commandRequestId, requestIdStr, requestNode);
            case ProtocolInfo.COMMAND_MARK_DISPATCHED -> handleDispatch(commandRequestId, requestIdStr, requestNode);
            case ProtocolInfo.COMMAND_GET_REQUESTS -> handleGetRequests(commandRequestId, requestNode);
            case ProtocolInfo.COMMAND_GET_MOVEMENTS -> handleGetMovements(commandRequestId);
            default -> TcpMessageCodec.formatErrorResponse(
                    requestIdStr,
                    ProtocolInfo.ERROR_VALIDATION,
                    "Unsupported command type: " + type
            );
        };
    }

    private String handlePick(UUID commandRequestId, String requestIdStr, JsonNode requestNode) {
        UUID stockRequestId = extractStockRequestId(requestIdStr, requestNode);
        if (stockRequestId == null) {
            return validationError(requestIdStr, "Missing or invalid 'stockRequestId' UUID in command data");
        }

        logger.debug("Executing pick for commandRequestId='{}', stockRequestId='{}'", commandRequestId, stockRequestId);
        FulfillmentResult result = fulfillmentService.pickStock(commandRequestId, stockRequestId);
        return result.responseJson();
    }

    private String handleDispatch(UUID commandRequestId, String requestIdStr, JsonNode requestNode) {
        UUID stockRequestId = extractStockRequestId(requestIdStr, requestNode);
        if (stockRequestId == null) {
            return validationError(requestIdStr, "Missing or invalid 'stockRequestId' UUID in command data");
        }

        logger.debug("Executing dispatch for commandRequestId='{}', stockRequestId='{}'", commandRequestId, stockRequestId);
        FulfillmentResult result = fulfillmentService.dispatchStock(commandRequestId, stockRequestId);
        return result.responseJson();
    }

    private String handleGetRequests(UUID commandRequestId, JsonNode requestNode) {
        Optional<String> statusFilter = Optional.empty();
        JsonNode data = requestNode.get("data");
        if (data != null && data.isObject() && data.hasNonNull("status")) {
            String s = data.get("status").asText().trim();
            if (!s.isBlank()) {
                statusFilter = Optional.of(s);
            }
        }

        List<StockRequestRecord> records = fulfillmentService.getRequests(statusFilter);
        List<Map<String, Object>> requestsList = new ArrayList<>(records.size());
        for (StockRequestRecord r : records) {
            Map<String, Object> reqMap = new LinkedHashMap<>();
            reqMap.put("requestId", r.requestId().toString());
            reqMap.put("sku", r.sku());
            reqMap.put("quantity", r.quantity());
            reqMap.put("status", r.status());
            if (r.decisionReason() != null) {
                reqMap.put("decisionReason", r.decisionReason());
            }
            if (r.createdAt() != null) {
                reqMap.put("createdAt", r.createdAt().toString());
            }
            requestsList.add(reqMap);
        }

        Map<String, Object> resultData = Map.of("requests", requestsList);
        String responseJson = TcpMessageCodec.formatResultResponse(commandRequestId.toString(), ProtocolInfo.STATUS_ACCEPTED, resultData);
        if (responseJson.getBytes(StandardCharsets.UTF_8).length + 1 > ProtocolInfo.MAX_FRAME_BYTES) {
            logger.warn("GET_REQUESTS response exceeded maximum frame limit of {} bytes for requestId '{}'",
                    ProtocolInfo.MAX_FRAME_BYTES, commandRequestId);
            return TcpMessageCodec.formatErrorResponse(
                    commandRequestId.toString(),
                    ProtocolInfo.ERROR_INTERNAL,
                    "Response size exceeds maximum TCP frame limit of " + ProtocolInfo.MAX_FRAME_BYTES + " bytes"
            );
        }
        return responseJson;
    }

    private String handleGetMovements(UUID commandRequestId) {
        List<StockMovementRecord> movements = fulfillmentService.getMovements();
        List<Map<String, Object>> movementList = new ArrayList<>(movements.size());
        for (StockMovementRecord m : movements) {
            Map<String, Object> mMap = new LinkedHashMap<>();
            mMap.put("id", m.id());
            mMap.put("stockRequestId", m.stockRequestId().toString());
            mMap.put("sku", m.sku());
            mMap.put("quantityDelta", m.quantityDelta());
            mMap.put("reason", m.reason());
            if (m.occurredAt() != null) {
                mMap.put("occurredAt", m.occurredAt().toString());
            }
            movementList.add(mMap);
        }

        Map<String, Object> resultData = Map.of("movements", movementList);
        String responseJson = TcpMessageCodec.formatResultResponse(commandRequestId.toString(), ProtocolInfo.STATUS_ACCEPTED, resultData);
        if (responseJson.getBytes(StandardCharsets.UTF_8).length + 1 > ProtocolInfo.MAX_FRAME_BYTES) {
            logger.warn("GET_MOVEMENTS response exceeded maximum frame limit of {} bytes for requestId '{}'",
                    ProtocolInfo.MAX_FRAME_BYTES, commandRequestId);
            return TcpMessageCodec.formatErrorResponse(
                    commandRequestId.toString(),
                    ProtocolInfo.ERROR_INTERNAL,
                    "Response size exceeds maximum TCP frame limit of " + ProtocolInfo.MAX_FRAME_BYTES + " bytes"
            );
        }
        return responseJson;
    }

    private UUID extractStockRequestId(String requestIdStr, JsonNode requestNode) {
        JsonNode data = requestNode.get("data");
        if (data == null || !data.isObject() || !data.hasNonNull("stockRequestId") || !data.get("stockRequestId").isTextual()) {
            return null;
        }

        String rawId = data.get("stockRequestId").asText().trim();
        try {
            return UUID.fromString(rawId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String validationError(String requestId, String message) {
        return TcpMessageCodec.formatErrorResponse(requestId, ProtocolInfo.ERROR_VALIDATION, message);
    }
}
