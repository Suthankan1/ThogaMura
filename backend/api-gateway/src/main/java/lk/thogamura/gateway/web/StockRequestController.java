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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * REST endpoint for stock reservation requests and queue queries conforming to
 * {@code docs/contracts/HTTP_API_V1.md}.
 *
 * <p>Translates HTTP requests directly to Java TCP {@code REQUEST_STOCK} and {@code GET_REQUESTS}
 * command frames over the real socket boundary. Enforces canonical UUID v4 idempotency keys.</p>
 */
@RestController
@RequestMapping("/api/v1")
public class StockRequestController {

    private static final Logger logger = LoggerFactory.getLogger(StockRequestController.class);
    private static final Set<String> ALLOWED_STATUS_FILTERS = Set.of("ACCEPTED", "REJECTED", "PICKED", "DISPATCHED");

    private final TcpCommandClient tcpCommandClient;

    public StockRequestController(TcpCommandClient tcpCommandClient) {
        this.tcpCommandClient = Objects.requireNonNull(tcpCommandClient, "tcpCommandClient must not be null");
    }

    public record CreateRequestPayload(JsonNode sku, JsonNode quantity) {}

    @PostMapping("/requests")
    public ResponseEntity<?> submitStockRequest(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) CreateRequestPayload payload) {

        // 1. Idempotency-Key validation
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "MISSING_IDEMPOTENCY_KEY");
            body.put("message", "Header 'Idempotency-Key' is required for stock reservation");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }

        String trimmedKey = idempotencyKey.trim();
        if (!TcpMessageCodec.isCanonicalUuidV4(trimmedKey)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "INVALID_IDEMPOTENCY_KEY");
            body.put("message", "Header 'Idempotency-Key' must be a valid canonical lowercase UUID v4");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }

        // 2. Payload validation
        if (payload == null || payload.sku() == null || !payload.sku().isTextual() || payload.sku().textValue().isBlank()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "VALIDATION_ERROR");
            body.put("message", "Field 'sku' is required and must not be blank");
            body.put("requestId", trimmedKey);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }

        String trimmedSku = payload.sku().textValue().trim();
        if (!ProtocolInfo.isValidSku(trimmedSku)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "VALIDATION_ERROR");
            body.put("message", "Field 'sku' must contain 1–64 letters, digits, underscores or hyphens");
            body.put("requestId", trimmedKey);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }

        if (payload.quantity() == null || !payload.quantity().isInt() || payload.quantity().intValue() <= 0) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "VALIDATION_ERROR");
            body.put("message", "Field 'quantity' must be a positive integer");
            body.put("requestId", trimmedKey);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }

        int quantity = payload.quantity().intValue();

        // 3. Dispatch to central warehouse via Java TCP socket
        try {
            Map<String, Object> commandData = new LinkedHashMap<>();
            commandData.put("sku", trimmedSku);
            commandData.put("quantity", quantity);

            JsonNode response = tcpCommandClient.sendCommand(
                    ProtocolInfo.COMMAND_REQUEST_STOCK,
                    trimmedKey,
                    commandData
            );

            String status = response.path("status").asText();
            JsonNode dataNode = response.path("data");

            if (ProtocolInfo.STATUS_ACCEPTED.equals(status)) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("requestId", trimmedKey);
                body.put("status", "ACCEPTED");
                if (!trimmedSku.equals(dataNode.path("sku").textValue()) ||
                        !dataNode.path("quantity").isInt() || dataNode.path("quantity").intValue() != quantity) {
                    return WarehouseHttpErrors.invalidData();
                }
                body.put("sku", trimmedSku);
                body.put("quantity", quantity);
                return ResponseEntity.status(HttpStatus.CREATED).body(body);
            } else if (ProtocolInfo.STATUS_REJECTED.equals(status)) {
                if (response.has("error")) {
                    return WarehouseHttpErrors.from(response, "requestId", trimmedKey);
                }
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                        "code", ProtocolInfo.ERROR_INSUFFICIENT_STOCK,
                        "message", dataNode.path("reason").asText("Requested quantity exceeds available stock"),
                        "requestId", trimmedKey
                ));
            } else if (ProtocolInfo.STATUS_ERROR.equals(status)) {
                return WarehouseHttpErrors.from(response, "requestId", trimmedKey);
            } else {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("code", "UNEXPECTED_STATUS");
                body.put("message", "Unexpected warehouse response status: " + status);
                body.put("requestId", trimmedKey);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
            }
        } catch (ServerUnavailableException e) {
            logger.warn("Warehouse command server is unreachable during stock request: {}", e.getMessage());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "WAREHOUSE_UNAVAILABLE");
            body.put("message", "Warehouse command server is unreachable. Please verify service status.");
            body.put("requestId", trimmedKey);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        } catch (CommandTimeoutException e) {
            logger.warn("Warehouse command timed out during stock request: {}", e.getMessage());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "WAREHOUSE_TIMEOUT");
            body.put("message", "Warehouse command timed out. Safe to retry with same Idempotency-Key.");
            body.put("requestId", trimmedKey);
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(body);
        } catch (CommandTransportException e) {
            logger.error("Transport error during stock request: {}", e.getMessage(), e);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "INTERNAL_TRANSPORT_ERROR");
            body.put("message", "Warehouse command transport error occurred.");
            body.put("requestId", trimmedKey);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        }
    }

    @GetMapping("/requests")
    public ResponseEntity<?> getRequests(@RequestParam(value = "status", required = false) String statusFilter) {
        String requestId = UUID.randomUUID().toString();
        try {
            Map<String, Object> data = null;
            if (statusFilter != null && !statusFilter.isBlank()) {
                String trimmed = statusFilter.trim().toUpperCase();
                if (!"ALL".equals(trimmed)) {
                    if (!ALLOWED_STATUS_FILTERS.contains(trimmed)) {
                        Map<String, Object> error = new LinkedHashMap<>();
                        error.put("code", "VALIDATION_ERROR");
                        error.put("message", "Invalid status filter '" + statusFilter + "'. Allowed values: ALL, ACCEPTED, REJECTED, PICKED, DISPATCHED");
                        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
                    }
                    data = Map.of("status", trimmed);
                }
            }

            JsonNode response = tcpCommandClient.sendCommand(ProtocolInfo.COMMAND_GET_REQUESTS, requestId, data);
            String status = response.path("status").asText();

            if (!ProtocolInfo.STATUS_ACCEPTED.equals(status)) {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("code", "INTERNAL_ERROR");
                error.put("message", "Failed to retrieve stock requests from warehouse");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
            }

            JsonNode requestsNode = response.path("data").path("requests");
            List<Map<String, Object>> requests = new ArrayList<>();
            if (!requestsNode.isArray()) return WarehouseHttpErrors.invalidData();
            for (JsonNode item : requestsNode) {
                String recordStatus = item.path("status").asText();
                if (!WarehouseHttpErrors.hasText(item, "requestId") || !WarehouseHttpErrors.hasText(item, "sku")
                        || !item.path("quantity").isInt() || item.path("quantity").intValue() <= 0
                        || !ALLOWED_STATUS_FILTERS.contains(recordStatus) || "ALL".equals(recordStatus)
                        || !WarehouseHttpErrors.hasText(item, "createdAt")) {
                    return WarehouseHttpErrors.invalidData();
                }
                requests.add(Map.of(
                        "requestId", item.path("requestId").textValue(), "sku", item.path("sku").textValue(),
                        "quantity", item.path("quantity").intValue(), "status", recordStatus,
                        "createdAt", item.path("createdAt").textValue()
                ));
            }
            return ResponseEntity.ok(requests);
        } catch (ServerUnavailableException e) {
            logger.warn("Warehouse command server is unreachable during getRequests: {}", e.getMessage());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "WAREHOUSE_UNAVAILABLE");
            body.put("message", "Warehouse command server is unreachable. Please verify service status.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        } catch (CommandTimeoutException e) {
            logger.warn("Warehouse command timed out during getRequests: {}", e.getMessage());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "WAREHOUSE_TIMEOUT");
            body.put("message", "Warehouse command timed out. Please retry.");
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(body);
        } catch (CommandTransportException e) {
            logger.error("Transport error during getRequests: {}", e.getMessage(), e);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "INTERNAL_TRANSPORT_ERROR");
            body.put("message", "Warehouse command transport error occurred.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        }
    }
}
