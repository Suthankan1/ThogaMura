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
     * Finds all stock requests for a given SKU ordered by creation time descending.
     *
     * @param sku product SKU
     * @return list of requests for the SKU
     */
    List<StockRequestRecord> findBySku(String sku);
}
