package lk.thogamura.networkserver.persistence;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for managing product inventory levels in PostgreSQL.
 */
public interface ProductStockRepository {

    /**
     * Finds product stock by SKU without acquiring a row-level lock.
     *
     * @param sku product SKU
     * @return optional containing the stock if found
     */
    Optional<ProductStock> findBySku(String sku);

    /**
     * Finds and locks the product stock row using {@code SELECT ... FOR UPDATE}
     * within the active database transaction to prevent concurrent race conditions.
     *
     * @param sku product SKU
     * @return optional containing the locked stock row if found
     */
    Optional<ProductStock> findBySkuForUpdate(String sku);

    /**
     * Updates the reserved stock quantity for a product.
     *
     * @param sku product SKU
     * @param newReserved new reserved quantity
     */
    void updateReserved(String sku, int newReserved);

    /**
     * Retrieves all product stock records ordered by SKU.
     *
     * @return list of product stocks
     */
    List<ProductStock> findAll();
}
