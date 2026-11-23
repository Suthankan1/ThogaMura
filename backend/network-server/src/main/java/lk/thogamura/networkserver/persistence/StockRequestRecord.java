package lk.thogamura.networkserver.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable entity representing a stock fulfillment request in PostgreSQL {@code stock_requests}.
 */
public record StockRequestRecord(
        UUID requestId,
        String requestFingerprint,
        String sku,
        int quantity,
        String status,
        String decisionReason,
        Instant createdAt,
        Instant updatedAt,
        String branchId
) {
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_PICKED = "PICKED";
    public static final String STATUS_DISPATCHED = "DISPATCHED";

    private static final Set<String> VALID_STATUSES = Set.of(
            STATUS_ACCEPTED, STATUS_REJECTED, STATUS_PICKED, STATUS_DISPATCHED, "CANCELLED"
    );

    public StockRequestRecord(UUID requestId, String requestFingerprint, String sku, int quantity,
                              String status, String decisionReason, Instant createdAt, Instant updatedAt) {
        this(requestId, requestFingerprint, sku, quantity, status, decisionReason, createdAt, updatedAt, null);
    }

    public StockRequestRecord forBranch(String branch) {
        return new StockRequestRecord(requestId, requestFingerprint, sku, quantity, status,
                decisionReason, createdAt, updatedAt, branch);
    }

    public StockRequestRecord {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(requestFingerprint, "requestFingerprint must not be null");
        Objects.requireNonNull(sku, "sku must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive: " + quantity);
        }
        if (!VALID_STATUSES.contains(status)) {
            throw new IllegalArgumentException("invalid status: " + status + "; must be one of " + VALID_STATUSES);
        }
        if (requestFingerprint.length() != 64) {
            throw new IllegalArgumentException("requestFingerprint must be 64 characters hex: " + requestFingerprint);
        }
    }

    /**
     * Convenience factory for an accepted reservation request record.
     */
    public static StockRequestRecord accepted(UUID requestId, String fingerprint, String sku, int quantity) {
        Instant now = Instant.now();
        return new StockRequestRecord(requestId, fingerprint, sku, quantity, STATUS_ACCEPTED, null, now, now);
    }

    /**
     * Convenience factory for a rejected reservation request record.
     */
    public static StockRequestRecord rejected(UUID requestId, String fingerprint, String sku, int quantity, String reason) {
        Instant now = Instant.now();
        return new StockRequestRecord(requestId, fingerprint, sku, quantity, STATUS_REJECTED, reason, now, now);
    }
}
