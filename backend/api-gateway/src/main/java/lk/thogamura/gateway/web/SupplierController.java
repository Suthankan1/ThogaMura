package lk.thogamura.gateway.web;

import com.fasterxml.jackson.databind.JsonNode;
import lk.thogamura.gateway.network.tcp.CommandTimeoutException;
import lk.thogamura.gateway.network.tcp.CommandTransportException;
import lk.thogamura.gateway.network.tcp.ServerUnavailableException;
import lk.thogamura.gateway.network.tcp.TcpCommandClient;
import lk.thogamura.protocol.ProtocolInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * REST controller exposing advisory external supplier stock availability inquiries.
 *
 * <p>Conforms to {@code docs/contracts/HTTP_API_V1.md} Section 2.4.
 * Bridges browser HTTP requests to the warehouse server over Java TCP Wire Protocol v1.</p>
 */
@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "*")
public class SupplierController {

    private static final Logger logger = LoggerFactory.getLogger(SupplierController.class);
    private static final Pattern SKU_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final TcpCommandClient tcpCommandClient;

    public SupplierController(TcpCommandClient tcpCommandClient) {
        this.tcpCommandClient = Objects.requireNonNull(tcpCommandClient, "tcpCommandClient must not be null");
    }

    @GetMapping("/suppliers/availability")
    public ResponseEntity<?> getSupplierAvailability(@RequestParam(name = "sku", required = false) String sku) {
        if (sku == null || sku.isBlank()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("code", ProtocolInfo.ERROR_VALIDATION);
            err.put("message", "Query parameter 'sku' is required and must match SKU format");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err);
        }

        String trimmedSku = sku.trim();
        if (!SKU_PATTERN.matcher(trimmedSku).matches()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("code", ProtocolInfo.ERROR_VALIDATION);
            err.put("message", "Query parameter 'sku' contains invalid characters: " + trimmedSku);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err);
        }

        try {
            JsonNode resultEnvelope = tcpCommandClient.sendCommand(
                    ProtocolInfo.COMMAND_GET_SUPPLIER_STOCK,
                    Map.of("sku", trimmedSku)
            );

            String status = resultEnvelope.hasNonNull("status") ? resultEnvelope.get("status").asText() : "";
            JsonNode dataNode = resultEnvelope.get("data");

            if (ProtocolInfo.STATUS_AVAILABLE.equals(status)) {
                return ResponseEntity.ok(dataNode);
            }

            if (dataNode != null && dataNode.hasNonNull("code")) {
                String code = dataNode.get("code").asText();
                return mapErrorCodeToResponse(code, dataNode, trimmedSku);
            }

            if (ProtocolInfo.STATUS_ERROR.equals(status)) {
                JsonNode errorNode = resultEnvelope.get("error");
                String code = (errorNode != null && errorNode.hasNonNull("code"))
                        ? errorNode.get("code").asText()
                        : ProtocolInfo.ERROR_INTERNAL;
                return mapErrorCodeToResponse(code, errorNode, trimmedSku);
            }

            if (ProtocolInfo.STATUS_UNKNOWN.equals(status)) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("code", ProtocolInfo.ERROR_NOT_FOUND);
                err.put("message", "Item is not cataloged by supplier");
                err.put("sku", trimmedSku);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err);
            }

            // Fallback for unexpected status
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("code", ProtocolInfo.ERROR_SUPPLIER_UNAVAILABLE);
            err.put("message", "External supplier service is currently unreachable");
            err.put("sku", trimmedSku);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(err);

        } catch (ServerUnavailableException e) {
            logger.warn("Warehouse server unavailable when querying supplier for SKU '{}': {}", trimmedSku, e.getMessage());
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("code", ProtocolInfo.ERROR_SUPPLIER_UNAVAILABLE);
            err.put("message", "External supplier service is currently unreachable: " + e.getMessage());
            err.put("sku", trimmedSku);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(err);

        } catch (CommandTimeoutException e) {
            logger.warn("Supplier command timed out for SKU '{}': {}", trimmedSku, e.getMessage());
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("code", ProtocolInfo.ERROR_SUPPLIER_TIMEOUT);
            err.put("message", "Supplier inquiry timed out");
            err.put("sku", trimmedSku);
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(err);

        } catch (CommandTransportException e) {
            logger.error("Transport error executing GET_SUPPLIER_STOCK for SKU '{}': {}", trimmedSku, e.getMessage(), e);
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("code", ProtocolInfo.ERROR_INTERNAL);
            err.put("message", "Internal error communicating with warehouse server: " + e.getMessage());
            err.put("sku", trimmedSku);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        }
    }

    private ResponseEntity<?> mapErrorCodeToResponse(String code, JsonNode payload, String sku) {
        if (ProtocolInfo.ERROR_NOT_FOUND.equals(code)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(payload);
        } else if (ProtocolInfo.ERROR_SUPPLIER_UNAVAILABLE.equals(code)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(payload);
        } else if (ProtocolInfo.ERROR_SUPPLIER_TIMEOUT.equals(code)) {
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(payload);
        } else if (ProtocolInfo.ERROR_SUPPLIER_UNKNOWN.equals(code)) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(payload);
        } else if (ProtocolInfo.ERROR_VALIDATION.equals(code)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(payload);
        } else {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(payload);
        }
    }
}
