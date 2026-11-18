package lk.thogamura.networkserver.persistence;

import java.util.Objects;

/**
 * Immutable entity representing product inventory state in PostgreSQL {@code product_stock}.
 *
 * <p>Enforces core inventory invariant:
 * {@code available = onHand - reserved >= 0}
 */
public record ProductStock(
        String sku,
        String name,
        int onHand,
        int reserved
) {
    public ProductStock {
        Objects.requireNonNull(sku, "sku must not be null");
        Objects.requireNonNull(name, "name must not be null");
        if (onHand < 0) {
            throw new IllegalArgumentException("onHand must not be negative: " + onHand);
        }
        if (reserved < 0) {
            throw new IllegalArgumentException("reserved must not be negative: " + reserved);
        }
        if (reserved > onHand) {
            throw new IllegalArgumentException(
                    "reserved (" + reserved + ") cannot exceed onHand (" + onHand + ")"
            );
        }
    }

    /**
     * Calculates current available stock: {@code on_hand - reserved}.
     *
     * @return non-negative available quantity
     */
    public int available() {
        return onHand - reserved;
    }
}
