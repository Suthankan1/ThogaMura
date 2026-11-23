package lk.thogamura.gateway.web;

import com.fasterxml.jackson.databind.JsonNode;
import lk.thogamura.gateway.network.tcp.CommandTimeoutException;
import lk.thogamura.gateway.network.tcp.CommandTransportException;
import lk.thogamura.gateway.network.tcp.ServerUnavailableException;
import lk.thogamura.gateway.network.tcp.TcpCommandClient;
import lk.thogamura.protocol.ProtocolInfo;
import lk.thogamura.protocol.tcp.TcpMessageCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * REST endpoint for warehouse fulfillment operations (picking, dispatching, and movement history)
 * conforming to {@code docs/contracts/HTTP_API_V1.md}.
 *
 * <p>Translates operations to Java TCP commands {@code MARK_PICKED}, {@code MARK_DISPATCHED},
 * and {@code GET_MOVEMENTS} over the socket transport to the central warehouse server.</p>
 */
@RestController
@RequestMapping("/api/v1")
public class FulfillmentController {

    private static final Logger logger = LoggerFactory.getLogger(FulfillmentController.class);

    private final TcpCommandClient tcpCommandClient;

    public FulfillmentController(TcpCommandClient tcpCommandClient) {
        this.tcpCommandClient = Objects.requireNonNull(tcpCommandClient, "tcpCommandClient must not be null");
    }

    @PostMapping("/requests/{id}/pick")
    public ResponseEntity<?> markPicked(@PathVariable("id") String requestId) {
        return transition(requestId, ProtocolInfo.COMMAND_MARK_PICKED, ProtocolInfo.STATUS_PICKED);
    }

    @PostMapping("/requests/{id}/dispatch")
    public ResponseEntity<?> markDispatched(@PathVariable("id") String requestId) {
        return transition(requestId, ProtocolInfo.COMMAND_MARK_DISPATCHED, ProtocolInfo.STATUS_DISPATCHED);
    }

    private ResponseEntity<?> transition(String rawRequestId, String command, String expectedStatus) {
        String requestId = rawRequestId.trim();
        if (!TcpMessageCodec.isCanonicalUuidV4(requestId)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", ProtocolInfo.ERROR_VALIDATION,
                    "message", "Path parameter 'id' must be a canonical lowercase UUID v4"
            ));
        }

        try {
            String commandRequestId = fulfillmentCommandId(command, requestId);
            JsonNode response = tcpCommandClient.sendCommand(command, commandRequestId, Map.of("stockRequestId", requestId));
            if (expectedStatus.equals(response.path("status").asText())) {
                return ResponseEntity.ok(Map.of("stockRequestId", requestId, "status", expectedStatus));
            }
            return WarehouseHttpErrors.from(response, "stockRequestId", requestId);
        } catch (ServerUnavailableException e) {
            return transitionFailure(HttpStatus.SERVICE_UNAVAILABLE, "WAREHOUSE_UNAVAILABLE", requestId);
        } catch (CommandTimeoutException e) {
            return transitionFailure(HttpStatus.GATEWAY_TIMEOUT, "WAREHOUSE_TIMEOUT", requestId);
        } catch (CommandTransportException e) {
            logger.warn("Warehouse {} transport failed for stock request {}", command, requestId, e);
            return transitionFailure(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_TRANSPORT_ERROR", requestId);
        }
    }

    private static ResponseEntity<Map<String, String>> transitionFailure(HttpStatus status, String code, String requestId) {
        return ResponseEntity.status(status).body(Map.of(
                "code", code,
                "message", "Warehouse did not confirm the transition. Retrying the same action is safe.",
                "stockRequestId", requestId
        ));
    }

    /**
     * Each fulfillment action is a single intent per stock request. Derive its stable command
     * identifier so an HTTP retry can find the same receipt through any gateway.
     */
    private static String fulfillmentCommandId(String command, String requestId) {
        byte[] hash;
        try {
            hash = MessageDigest.getInstance("SHA-256")
                    .digest(("thogamura.fulfillment.v1/" + command + "/" + requestId).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", e);
        }
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x40);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
        ByteBuffer words = ByteBuffer.wrap(hash);
        return new UUID(words.getLong(), words.getLong()).toString();
    }

    @GetMapping("/movements")
    public ResponseEntity<?> getMovements() {
        String requestId = UUID.randomUUID().toString();
        try {
            JsonNode response = tcpCommandClient.sendCommand(ProtocolInfo.COMMAND_GET_MOVEMENTS, requestId, null);
            String status = response.path("status").asText();

            if (!ProtocolInfo.STATUS_ACCEPTED.equals(status)) {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("code", "INTERNAL_ERROR");
                error.put("message", "Failed to retrieve stock movements from warehouse");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
            }

            JsonNode movementsNode = response.path("data").path("movements");
            List<Map<String, Object>> movements = new ArrayList<>();
            if (!movementsNode.isArray()) return WarehouseHttpErrors.invalidData();
            for (JsonNode item : movementsNode) {
                if (!item.path("id").isIntegralNumber() || !item.path("id").canConvertToLong() ||
                        !WarehouseHttpErrors.hasText(item, "stockRequestId") || !WarehouseHttpErrors.hasText(item, "sku") ||
                        !item.path("quantityDelta").isInt() || !WarehouseHttpErrors.hasText(item, "reason") ||
                        !WarehouseHttpErrors.hasText(item, "occurredAt")) {
                    return WarehouseHttpErrors.invalidData();
                }
                movements.add(Map.of(
                        "id", item.path("id").longValue(), "stockRequestId", item.path("stockRequestId").textValue(),
                        "sku", item.path("sku").textValue(), "quantityDelta", item.path("quantityDelta").intValue(),
                        "reason", item.path("reason").textValue(), "occurredAt", item.path("occurredAt").textValue()
                ));
            }
            return ResponseEntity.ok(movements);
        } catch (ServerUnavailableException e) {
            logger.warn("Warehouse command server is unreachable during getMovements: {}", e.getMessage());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "WAREHOUSE_UNAVAILABLE");
            body.put("message", "Warehouse command server is unreachable. Please verify service status.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        } catch (CommandTimeoutException e) {
            logger.warn("Warehouse command timed out during getMovements: {}", e.getMessage());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "WAREHOUSE_TIMEOUT");
            body.put("message", "Warehouse command timed out. Please retry.");
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(body);
        } catch (CommandTransportException e) {
            logger.error("Transport error during getMovements: {}", e.getMessage(), e);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "INTERNAL_TRANSPORT_ERROR");
            body.put("message", "Warehouse command transport error occurred.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        }
    }
}
