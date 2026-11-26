package lk.thogamura.networkserver.integration.supplier;

import lk.thogamura.protocol.ProtocolInfo;

import java.time.Instant;
import java.util.Objects;

/**
 * Result record for external supplier stock availability inquiries.
 *
 * <p>Conforms to schemas defined in {@code docs/contracts/SUPPLIER_HTTP_V1.md}.
 * Distinguishes confirmed positive stock, confirmed zero units, catalog omission (404),
 * and service outage (503 / timeout).</p>
 *
 * @param sku product identifier
 * @param status availability status: {@code AVAILABLE}, {@code UNAVAILABLE}, or {@code UNKNOWN}
 * @param supplier supplier name (e.g. {@code Demo Supplier})
 * @param reportedQuantity verified available quantity (null if unavailable or unknown)
 * @param estimatedRestockDays estimated restock turnaround in days (or null)
 * @param source identifying external source (always {@code MOCK_SUPPLIER})
 * @param checkedAt ISO-8601 UTC timestamp of inquiry
 * @param code error code from {@link ProtocolInfo} (null on success)
 * @param message descriptive error message (null on success)
 */
public record SupplierStockResponse(
        String sku,
        String status,
        String supplier,
        Integer reportedQuantity,
        Integer estimatedRestockDays,
        String source,
        String checkedAt,
        String code,
        String message
) {
    public static final String SOURCE_IDENTIFIER = "MOCK_SUPPLIER";

    public SupplierStockResponse {
        Objects.requireNonNull(sku, "sku must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }

    public static SupplierStockResponse available(
            String sku,
            String supplier,
            int units,
            int restockDays,
            String checkedAt) {
        return available(sku, supplier, units, Integer.valueOf(restockDays), checkedAt);
    }

    public static SupplierStockResponse available(
            String sku,
            String supplier,
            int units,
            Integer restockDays,
            String checkedAt) {
        return new SupplierStockResponse(
                sku,
                ProtocolInfo.STATUS_AVAILABLE,
                (supplier != null && !supplier.isBlank()) ? supplier : "Demo Supplier",
                units,
                restockDays,
                SOURCE_IDENTIFIER,
                (checkedAt != null && !checkedAt.isBlank()) ? checkedAt : Instant.now().toString(),
                null,
                null
        );
    }

    public static SupplierStockResponse unknown(String sku, String code, String message) {
        return new SupplierStockResponse(
                sku,
                "UNKNOWN",
                null,
                null,
                null,
                SOURCE_IDENTIFIER,
                Instant.now().toString(),
                code != null ? code : ProtocolInfo.ERROR_NOT_FOUND,
                message != null ? message : "Item is not cataloged by supplier"
        );
    }

    public static SupplierStockResponse unavailable(String sku, String code, String message) {
        return new SupplierStockResponse(
                sku,
                "UNAVAILABLE",
                null,
                null,
                null,
                SOURCE_IDENTIFIER,
                Instant.now().toString(),
                code != null ? code : ProtocolInfo.ERROR_SUPPLIER_UNAVAILABLE,
                message != null ? message : "External supplier service is currently unreachable"
        );
    }

    public static SupplierStockResponse validationError(String sku, String message) {
        return new SupplierStockResponse(
                sku != null ? sku : "",
                "UNKNOWN",
                null,
                null,
                null,
                SOURCE_IDENTIFIER,
                Instant.now().toString(),
                ProtocolInfo.ERROR_VALIDATION,
                message != null ? message : "Invalid query parameter 'sku'"
        );
    }
}
