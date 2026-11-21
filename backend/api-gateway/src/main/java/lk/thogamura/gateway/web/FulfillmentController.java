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
    public ResponseEntity<?> markPicked(@PathVariable("id") String requestIdStr) {
        if (requestIdStr == null || !TcpMessageCodec.isCanonicalUuidV4(requestIdStr.trim())) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "VALIDATION_ERROR");
            body.put("message", "Path parameter 'id' must be a canonical lowercase UUID v4");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }

        String commandRequestId = UUID.randomUUID().toString();
        try {
            Map<String, Object> data = Map.of("stockRequestId", requestIdStr.trim());
            JsonNode response = tcpCommandClient.sendCommand(
                    ProtocolInfo.COMMAND_MARK_PICKED,
                    commandRequestId,
                    data
            );

            String status = response.path("status").asText();
            if (ProtocolInfo.STATUS_PICKED.equals(status)) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("stockRequestId", requestIdStr.trim());
                body.put("status", "PICKED");
                return ResponseEntity.ok(body);
            } else if (ProtocolInfo.STATUS_REJECTED.equals(status)) {
                String reason = response.path("data").path("reason").asText("Cannot transition request to PICKED");
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("code", "INVALID_STATE_TRANSITION");
                body.put("message", reason);
                body.put("stockRequestId", requestIdStr.trim());
                return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
            } else {
                JsonNode errorNode = response.path("error");
                String code = errorNode.path("code").asText("INTERNAL_ERROR");
                String message = errorNode.path("message").asText("Warehouse rejected pick operation");
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("code", code);
                body.put("message", message);
                body.put("stockRequestId", requestIdStr.trim());
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
            }
        } catch (ServerUnavailableException e) {
            logger.warn("Warehouse command server is unreachable during markPicked: {}", e.getMessage());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "WAREHOUSE_UNAVAILABLE");
            body.put("message", "Warehouse command server is unreachable. Please verify service status.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        } catch (CommandTimeoutException e) {
            logger.warn("Warehouse command timed out during markPicked: {}", e.getMessage());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "WAREHOUSE_TIMEOUT");
            body.put("message", "Warehouse command timed out. Please retry.");
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(body);
        } catch (CommandTransportException e) {
            logger.error("Transport error during markPicked: {}", e.getMessage(), e);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "INTERNAL_TRANSPORT_ERROR");
            body.put("message", "Warehouse command transport error occurred.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        }
    }

    @PostMapping("/requests/{id}/dispatch")
    public ResponseEntity<?> markDispatched(@PathVariable("id") String requestIdStr) {
        if (requestIdStr == null || !TcpMessageCodec.isCanonicalUuidV4(requestIdStr.trim())) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "VALIDATION_ERROR");
            body.put("message", "Path parameter 'id' must be a canonical lowercase UUID v4");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }

        String commandRequestId = UUID.randomUUID().toString();
        try {
            Map<String, Object> data = Map.of("stockRequestId", requestIdStr.trim());
            JsonNode response = tcpCommandClient.sendCommand(
                    ProtocolInfo.COMMAND_MARK_DISPATCHED,
                    commandRequestId,
                    data
            );

            String status = response.path("status").asText();
            if (ProtocolInfo.STATUS_DISPATCHED.equals(status)) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("stockRequestId", requestIdStr.trim());
                body.put("status", "DISPATCHED");
                return ResponseEntity.ok(body);
            } else if (ProtocolInfo.STATUS_REJECTED.equals(status)) {
                String reason = response.path("data").path("reason").asText("Cannot transition request to DISPATCHED");
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("code", "INVALID_STATE_TRANSITION");
                body.put("message", reason);
                body.put("stockRequestId", requestIdStr.trim());
                return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
            } else {
                JsonNode errorNode = response.path("error");
                String code = errorNode.path("code").asText("INTERNAL_ERROR");
                String message = errorNode.path("message").asText("Warehouse rejected dispatch operation");
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("code", code);
                body.put("message", message);
                body.put("stockRequestId", requestIdStr.trim());
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
            }
        } catch (ServerUnavailableException e) {
            logger.warn("Warehouse command server is unreachable during markDispatched: {}", e.getMessage());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "WAREHOUSE_UNAVAILABLE");
            body.put("message", "Warehouse command server is unreachable. Please verify service status.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        } catch (CommandTimeoutException e) {
            logger.warn("Warehouse command timed out during markDispatched: {}", e.getMessage());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "WAREHOUSE_TIMEOUT");
            body.put("message", "Warehouse command timed out. Please retry.");
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(body);
        } catch (CommandTransportException e) {
            logger.error("Transport error during markDispatched: {}", e.getMessage(), e);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "INTERNAL_TRANSPORT_ERROR");
            body.put("message", "Warehouse command transport error occurred.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        }
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
            if (movementsNode.isArray()) {
                for (JsonNode item : movementsNode) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", item.path("id").asLong());
                    m.put("stockRequestId", item.path("stockRequestId").asText());
                    m.put("sku", item.path("sku").asText());
                    m.put("quantityDelta", item.path("quantityDelta").asInt());
                    m.put("reason", item.path("reason").asText());
                    m.put("occurredAt", item.path("occurredAt").asText());
                    movements.add(m);
                }
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
