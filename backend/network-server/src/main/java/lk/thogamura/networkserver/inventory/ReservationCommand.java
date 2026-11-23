package lk.thogamura.networkserver.inventory;

import java.util.Objects;
import java.util.UUID;

/**
 * Inbound command value object for requesting stock reservation.
 */
public record ReservationCommand(
        UUID requestId,
        String sku,
        int quantity,
        String branchId
) {
    public ReservationCommand(UUID requestId, String sku, int quantity) {
        this(requestId, sku, quantity, null);
    }

    public ReservationCommand {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(sku, "sku must not be null");
    }

    public static ReservationCommand of(UUID requestId, String sku, int quantity) {
        return new ReservationCommand(requestId, sku, quantity);
    }

    public static ReservationCommand of(String requestId, String sku, int quantity) {
        Objects.requireNonNull(requestId, "requestId must not be null");
        try {
            return new ReservationCommand(UUID.fromString(requestId.trim()), sku, quantity);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid requestId UUID format: " + requestId, e);
        }
    }
}
