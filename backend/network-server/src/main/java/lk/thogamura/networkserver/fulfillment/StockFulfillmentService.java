package lk.thogamura.networkserver.fulfillment;

import lk.thogamura.networkserver.persistence.StockMovementRecord;
import lk.thogamura.networkserver.persistence.StockRequestRecord;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service interface governing the warehouse fulfillment transaction lifecycle:
 * {@code ACCEPTED -> PICKED -> DISPATCHED}.
 */
public interface StockFulfillmentService {

    /**
     * Transitions an accepted stock request to {@code PICKED}.
     *
     * @param commandRequestId unique idempotency command UUID
     * @param stockRequestId target stock request UUID
     * @return fulfillment result containing contract-compliant response JSON
     */
    FulfillmentResult pickStock(UUID commandRequestId, UUID stockRequestId);

    /**
     * Transitions a picked stock request to {@code DISPATCHED}, atomically decrementing
     * inventory balances and writing an immutable movement audit record.
     *
     * @param commandRequestId unique idempotency command UUID
     * @param stockRequestId target stock request UUID
     * @return fulfillment result containing contract-compliant response JSON
     */
    FulfillmentResult dispatchStock(UUID commandRequestId, UUID stockRequestId);

    /**
     * Retrieves stock requests optionally filtered by status.
     *
     * @param statusFilter optional status filter (e.g. "ACCEPTED", "PICKED", "DISPATCHED")
     * @return list of matching stock request records
     */
    List<StockRequestRecord> getRequests(Optional<String> statusFilter);

    /**
     * Retrieves all historical dispatch stock movements.
     *
     * @return list of stock movement records
     */
    List<StockMovementRecord> getMovements();
}
