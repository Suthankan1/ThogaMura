package lk.thogamura.networkserver.inventory;

import java.util.UUID;

/**
 * Service interface for atomic stock reservations with durable idempotency receipts.
 *
 * <p>Enforces core business rule: {@code available = on_hand - reserved >= 0}.
 * In the same database transaction as the inventory reservation, a durable receipt
 * is persisted in PostgreSQL to ensure deterministic replay and duplicate conflict detection.</p>
 */
public interface StockReservationService {

    /**
     * Executes an atomic stock reservation with durable command receipts.
     *
     * @param command reservation command containing requestId, sku, and quantity
     * @return authoritative reservation result containing wire response JSON
     */
    ReservationResult reserveStock(ReservationCommand command);

    /**
     * Convenience overload for reservation by explicit arguments.
     *
     * @param requestId request UUID
     * @param sku product SKU
     * @param quantity requested quantity (> 0)
     * @return authoritative reservation result
     */
    default ReservationResult reserveStock(UUID requestId, String sku, int quantity) {
        return reserveStock(new ReservationCommand(requestId, sku, quantity));
    }

    /**
     * Convenience overload parsing requestId from String.
     *
     * @param requestIdStr request UUID string
     * @param sku product SKU
     * @param quantity requested quantity (> 0)
     * @return authoritative reservation result
     */
    default ReservationResult reserveStock(String requestIdStr, String sku, int quantity) {
        return reserveStock(ReservationCommand.of(requestIdStr, sku, quantity));
    }

    /**
     * Parses a raw JSON command frame and executes the reservation.
     *
     * @param rawFrameJson raw JSON string of the REQUEST_STOCK command
     * @return authoritative reservation result
     */
    ReservationResult processReservationCommand(String rawFrameJson);
}
