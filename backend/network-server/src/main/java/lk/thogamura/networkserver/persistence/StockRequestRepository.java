package lk.thogamura.networkserver.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for managing stock request lifecycle records in PostgreSQL.
 */
public interface StockRequestRepository {

    /**
     * Persists a stock request record.
     *
     * @param request request entity to persist
     */
    void insert(StockRequestRecord request);

    /**
     * Finds a stock request by its unique request ID.
     *
     * @param requestId request UUID
     * @return optional containing the request record if found
     */
    Optional<StockRequestRecord> findById(UUID requestId);

    /**
     * Finds and locks a stock request row using {@code SELECT ... FOR UPDATE}
     * within the active database transaction to safely serialize state transitions.
     *
     * @param requestId request UUID
     * @return optional containing the locked stock request if found
     */
    Optional<StockRequestRecord> findByIdForUpdate(UUID requestId);

    /**
     * Updates the status of a stock request.
     *
     * @param requestId request UUID
     * @param status new status ('PICKED', 'DISPATCHED')
     */
    void updateStatus(UUID requestId, String status);

    /**
     * Finds all stock requests ordered by creation time ascending.
     *
     * @return list of all requests
     */
    List<StockRequestRecord> findAll();

    /**
     * Finds all stock requests matching a specific status ordered by creation time ascending.
     *
     * @param status request status filter
     * @return list of matching requests
     */
    List<StockRequestRecord> findByStatus(String status);

    /**
     * Finds all stock requests for a given SKU ordered by creation time descending.
     *
     * @param sku product SKU
     * @return list of requests for the SKU
     */
    List<StockRequestRecord> findBySku(String sku);
}
