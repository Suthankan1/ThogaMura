package lk.thogamura.networkserver.integration.supplier;

/**
 * Interface for querying external supplier product stock availability.
 *
 * <p>Strictly read-only and advisory. Never modifies central warehouse inventory.</p>
 */
public interface SupplierHttpClient {

    /**
     * Inquires product availability from the configured supplier service.
     *
     * @param sku product identifier to look up
     * @return availability response conforming to {@code docs/contracts/SUPPLIER_HTTP_V1.md}
     */
    SupplierStockResponse checkAvailability(String sku);
}
