package lk.thogamura.networkserver.integration.supplier;

import com.fasterxml.jackson.databind.JsonNode;
import lk.thogamura.networkserver.transport.tcp.CommandHandler;
import lk.thogamura.protocol.ProtocolInfo;
import lk.thogamura.protocol.tcp.TcpMessageCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.Objects;

/**
 * Command handler servicing {@link ProtocolInfo#COMMAND_GET_SUPPLIER_STOCK} requests.
 *
 * <p>Translates wire protocol envelopes into outbound Java HTTP inquiries to external suppliers
 * via {@link SupplierHttpClient}, mapping responses to Protocol v1 envelopes.</p>
 */
public class SupplierCommandHandler implements CommandHandler {

    private static final Logger logger = LoggerFactory.getLogger(SupplierCommandHandler.class);

    private final SupplierHttpClient supplierClient;
    private final Semaphore supplierSlots = new Semaphore(4);

    public SupplierCommandHandler(SupplierHttpClient supplierClient) {
        this.supplierClient = Objects.requireNonNull(supplierClient, "supplierClient must not be null");
    }

    public SupplierCommandHandler() {
        this(new DefaultSupplierHttpClient());
    }

    @Override
    public String handle(JsonNode requestNode) {
        String requestId = requestNode.hasNonNull("requestId") ? requestNode.get("requestId").asText() : null;
        JsonNode dataNode = requestNode.get("data");

        if (dataNode == null || !dataNode.path("sku").isTextual() || !ProtocolInfo.isValidSku(dataNode.path("sku").textValue().trim())) {
            logger.warn("Rejecting GET_SUPPLIER_STOCK command without required 'sku' (requestId: '{}')", requestId);
            return TcpMessageCodec.formatErrorResponse(
                    requestId,
                    ProtocolInfo.ERROR_VALIDATION,
                    "Missing required field 'sku' in command data payload"
            );
        }

        String sku = dataNode.get("sku").asText().trim();
        logger.debug("Dispatching GET_SUPPLIER_STOCK inquiry for SKU '{}' (requestId: '{}')", sku, requestId);

        SupplierStockResponse response;
        boolean acquired = false;
        try {
            acquired = supplierSlots.tryAcquire(250, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (acquired) {
            try {
                response = supplierClient.checkAvailability(sku);
            } finally {
                supplierSlots.release();
            }
        } else {
            response = SupplierStockResponse.unavailable(sku, ProtocolInfo.ERROR_SUPPLIER_UNAVAILABLE,
                    "Supplier inquiry capacity is busy. Please check again shortly.");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sku", response.sku());
        data.put("status", response.status());
        if (response.supplier() != null) {
            data.put("supplier", response.supplier());
        }
        data.put("reportedQuantity", response.reportedQuantity());
        if (response.estimatedRestockDays() != null) {
            data.put("estimatedRestockDays", response.estimatedRestockDays());
        }
        data.put("source", response.source());
        if (response.checkedAt() != null) {
            data.put("checkedAt", response.checkedAt());
        }
        if (response.code() != null) {
            data.put("code", response.code());
        }
        if (response.message() != null) {
            data.put("message", response.message());
        }

        return TcpMessageCodec.formatResultResponse(requestId, response.status(), data);
    }
}
