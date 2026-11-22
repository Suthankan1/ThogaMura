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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * REST endpoint for product inventory queries conforming to {@code docs/contracts/HTTP_API_V1.md}.
 *
 * <p>Translates HTTP GET /api/v1/products to real Java TCP {@code GET_PRODUCTS} command envelopes
 * dispatched to the central warehouse server. Never accesses PostgreSQL directly.</p>
 */
@RestController
@RequestMapping("/api/v1")
public class ProductController {

    private static final Logger logger = LoggerFactory.getLogger(ProductController.class);

    private final TcpCommandClient tcpCommandClient;

    public ProductController(TcpCommandClient tcpCommandClient) {
        this.tcpCommandClient = Objects.requireNonNull(tcpCommandClient, "tcpCommandClient must not be null");
    }

    @GetMapping("/products")
    public ResponseEntity<?> getProducts() {
        String requestId = UUID.randomUUID().toString();
        try {
            JsonNode response = tcpCommandClient.sendCommand(ProtocolInfo.COMMAND_GET_PRODUCTS, requestId, null);
            String status = response.path("status").asText();

            if (!ProtocolInfo.STATUS_ACCEPTED.equals(status)) {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("code", "INTERNAL_ERROR");
                error.put("message", "Failed to retrieve products from warehouse");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
            }

            JsonNode productsNode = response.path("data").path("products");
            List<Map<String, Object>> products = new ArrayList<>();
            if (!productsNode.isArray()) return WarehouseHttpErrors.invalidData();
            for (JsonNode item : productsNode) {
                JsonNode onHand = item.path("onHand");
                JsonNode reserved = item.path("reserved");
                JsonNode available = item.path("available");
                if (!WarehouseHttpErrors.hasText(item, "sku") || !WarehouseHttpErrors.hasText(item, "name") ||
                        !onHand.isInt() || !reserved.isInt() || !available.isInt() || reserved.intValue() < 0 ||
                        available.intValue() < 0 || (long) reserved.intValue() + available.intValue() != onHand.intValue()) {
                    return WarehouseHttpErrors.invalidData();
                }
                products.add(Map.of(
                        "sku", item.path("sku").textValue(), "name", item.path("name").textValue(),
                        "onHand", onHand.intValue(), "reserved", reserved.intValue(), "available", available.intValue()
                ));
            }
            return ResponseEntity.ok(products);
        } catch (ServerUnavailableException e) {
            logger.warn("Warehouse command server is unreachable during getProducts: {}", e.getMessage());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "WAREHOUSE_UNAVAILABLE");
            body.put("message", "Warehouse command server is unreachable. Please verify service status.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        } catch (CommandTimeoutException e) {
            logger.warn("Warehouse command timed out during getProducts: {}", e.getMessage());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "WAREHOUSE_TIMEOUT");
            body.put("message", "Warehouse command timed out. Please retry.");
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(body);
        } catch (CommandTransportException e) {
            logger.error("Transport error during getProducts: {}", e.getMessage(), e);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "INTERNAL_TRANSPORT_ERROR");
            body.put("message", "Warehouse command transport error occurred.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        }
    }
}
