package lk.thogamura.networkserver.integration.supplier;

import java.util.Objects;

/**
 * Synthetic stock item representation in the mock supplier catalog.
 *
 * <p>Enforces response schema contracts defined in {@code docs/contracts/SUPPLIER_HTTP_V1.md}.
 * Distinguishes verified positive availability from confirmed zero quantity without mutating central database state.</p>
 *
 * @param sku external product identifier (e.g. {@code ITEM-008})
 * @param supplier supplier organization name (e.g. {@code Demo Supplier})
 * @param availableUnits currently available units at external supplier
 * @param estimatedRestockDays estimated restock turnaround time in days
 */
public record SupplierStockItem(
        String sku,
        String supplier,
        int availableUnits,
        int estimatedRestockDays
) {
    public SupplierStockItem {
        Objects.requireNonNull(sku, "sku must not be null");
        Objects.requireNonNull(supplier, "supplier must not be null");
        if (availableUnits < 0) {
            throw new IllegalArgumentException("availableUnits must not be negative: " + availableUnits);
        }
        if (estimatedRestockDays < 0) {
            throw new IllegalArgumentException("estimatedRestockDays must not be negative: " + estimatedRestockDays);
        }
    }

    public static SupplierStockItem of(String sku, int availableUnits, int estimatedRestockDays) {
        return new SupplierStockItem(sku, "Demo Supplier", availableUnits, estimatedRestockDays);
    }

    public static SupplierStockItem of(String sku, String supplier, int availableUnits, int estimatedRestockDays) {
        return new SupplierStockItem(sku, supplier, availableUnits, estimatedRestockDays);
    }
}
