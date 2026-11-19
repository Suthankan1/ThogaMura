package lk.thogamura.networkserver.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for managing immutable stock movement audit records in PostgreSQL.
 */
public interface StockMovementRepository {

    /**
     * Persists a stock movement audit record.
     *
     * @param movement movement entity to insert
     */
    void insert(StockMovementRecord movement);

    /**
     * Finds a movement record by its unique associated stock request ID.
     *
     * @param stockRequestId stock request UUID
     * @return optional containing the movement if found
     */
    Optional<StockMovementRecord> findByStockRequestId(UUID stockRequestId);

    /**
     * Retrieves all stock movements ordered by ID ascending.
     *
     * @return list of stock movements
     */
    List<StockMovementRecord> findAll();
}
