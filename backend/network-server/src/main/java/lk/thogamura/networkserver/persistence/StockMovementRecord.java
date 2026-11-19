package lk.thogamura.networkserver.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable entity representing an audit log entry in PostgreSQL {@code stock_movements}.
 *
 * <p>Enforces table constraints:
 * <ul>
 *   <li>{@code stock_request_id} must be non-null and unique</li>
 *   <li>{@code quantity_delta} must be strictly negative (&lt; 0)</li>
 *   <li>{@code reason} must be exactly {@code 'DISPATCH'}</li>
 * </ul>
 */
public record StockMovementRecord(
        Long id,
        UUID stockRequestId,
        String sku,
        int quantityDelta,
        String reason,
        Instant occurredAt
) {
    public static final String REASON_DISPATCH = "DISPATCH";

    public StockMovementRecord {
        Objects.requireNonNull(stockRequestId, "stockRequestId must not be null");
        Objects.requireNonNull(sku, "sku must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        if (quantityDelta >= 0) {
            throw new IllegalArgumentException("quantityDelta must be strictly negative (< 0): " + quantityDelta);
        }
        if (!REASON_DISPATCH.equals(reason)) {
            throw new IllegalArgumentException("reason must be 'DISPATCH': " + reason);
        }
    }

    /**
     * Factory method creating a dispatch movement record with a negative delta.
     *
     * @param stockRequestId associated stock request UUID
     * @param sku product SKU
     * @param quantity positive quantity dispatched
     * @return movement record with {@code quantityDelta = -quantity}
     */
    public static StockMovementRecord dispatch(UUID stockRequestId, String sku, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive: " + quantity);
        }
        return new StockMovementRecord(null, stockRequestId, sku, -quantity, REASON_DISPATCH, Instant.now());
    }
}
