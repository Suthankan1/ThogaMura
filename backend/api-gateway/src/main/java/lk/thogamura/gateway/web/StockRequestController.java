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

    private final TcpCommandClient tcpCommandClient;

    public StockRequestController(TcpCommandClient tcpCommandClient) {
        this.tcpCommandClient = Objects.requireNonNull(tcpCommandClient, "tcpCommandClient must not be null");
    }

    public record CreateRequestPayload(String sku, Integer quantity) {}

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
        if (payload == null || payload.sku() == null || payload.sku().isBlank()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "VALIDATION_ERROR");
            body.put("message", "Field 'sku' is required");
            body.put("requestId", trimmedKey);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }

        if (payload.quantity() == null || payload.quantity() <= 0) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "VALIDATION_ERROR");
            body.put("message", "Field 'quantity' must be a positive integer");
            body.put("requestId", trimmedKey);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }

        // 3. Dispatch to central warehouse via Java TCP socket
        try {
            Map<String, Object> commandData = new LinkedHashMap<>();
            commandData.put("sku", payload.sku().trim());
            commandData.put("quantity", payload.quantity());

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
                body.put("sku", dataNode.path("sku").asText(payload.sku().trim()));
                body.put("quantity", dataNode.path("quantity").asInt(payload.quantity()));
                return ResponseEntity.status(HttpStatus.CREATED).body(body);
            } else if (ProtocolInfo.STATUS_REJECTED.equals(status)) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("code", "INSUFFICIENT_STOCK");
                String reason = dataNode.path("reason").asText("Requested quantity exceeds available stock");
                body.put("message", reason);
                body.put("requestId", trimmedKey);
                return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
            } else if (ProtocolInfo.STATUS_ERROR.equals(status)) {
                JsonNode errorNode = response.path("error");
                String errorCode = errorNode.path("code").asText("INTERNAL_ERROR");
                String errorMessage = errorNode.path("message").asText("Warehouse rejected reservation request");

                Map<String, Object> body = new LinkedHashMap<>();
                body.put("code", errorCode);
                body.put("message", errorMessage);
                body.put("requestId", trimmedKey);

                if (ProtocolInfo.ERROR_DUPLICATE_ID_CONFLICT.equals(errorCode)) {
                    return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
                } else if (ProtocolInfo.ERROR_VALIDATION.equals(errorCode)) {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
                } else {
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
                }
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
                data = Map.of("status", statusFilter.trim());
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
            if (requestsNode.isArray()) {
                for (JsonNode item : requestsNode) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("requestId", item.path("requestId").asText());
                    r.put("sku", item.path("sku").asText());
                    r.put("quantity", item.path("quantity").asInt());
                    r.put("status", item.path("status").asText());
                    r.put("createdAt", item.path("createdAt").asText());
                    requests.add(r);
                }
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
