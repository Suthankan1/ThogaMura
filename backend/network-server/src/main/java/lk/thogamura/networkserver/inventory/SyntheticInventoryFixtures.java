package lk.thogamura.networkserver.inventory;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Objects;

/**
 * Repeatable development and testing synthetic inventory fixtures.
 *
 * <p>Enforces requirements matching Issue #8:
 * <ul>
 *   <li>{@code ITEM-007}: On-hand = 10, Reserved = 0, Available = 10</li>
 *   <li>{@code ITEM-008}: On-hand = 0, Reserved = 0, Available = 0</li>
 *   <li>Strictly restricted to dev/test; never runs automatically during production startup</li>
 *   <li>Idempotent execution: running multiple times never causes duplicate key conflicts or corrupts inventory</li>
 * </ul>
 */
public final class SyntheticInventoryFixtures {

    public static final String SKU_ITEM_007 = "ITEM-007";
    public static final String NAME_ITEM_007 = "Whole Ceylon Black Pepper";
    public static final int ON_HAND_ITEM_007 = 10;
    public static final int RESERVED_ITEM_007 = 0;

    public static final String SKU_ITEM_008 = "ITEM-008";
    public static final String NAME_ITEM_008 = "Ceylon Cinnamon Quills";
    public static final int ON_HAND_ITEM_008 = 0;
    public static final int RESERVED_ITEM_008 = 0;

    private SyntheticInventoryFixtures() {
    }

    /**
     * Initializes or resets synthetic stock fixtures idempotently using an explicit {@link DataSource}.
     *
     * @param dataSource target database datasource
     */
    public static void seed(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource must not be null");
        seed(new JdbcTemplate(dataSource));
    }

    /**
     * Initializes or resets synthetic stock fixtures idempotently using {@link JdbcTemplate}.
     *
     * @param jdbcTemplate target JDBC template
     */
    public static void seed(JdbcTemplate jdbcTemplate) {
        Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");

        String sql = """
            INSERT INTO product_stock (sku, name, on_hand, reserved)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (sku) DO UPDATE
            SET name = EXCLUDED.name,
                on_hand = EXCLUDED.on_hand,
                reserved = EXCLUDED.reserved;
            """;

        jdbcTemplate.update(sql, SKU_ITEM_007, NAME_ITEM_007, ON_HAND_ITEM_007, RESERVED_ITEM_007);
        jdbcTemplate.update(sql, SKU_ITEM_008, NAME_ITEM_008, ON_HAND_ITEM_008, RESERVED_ITEM_008);
    }

    /**
     * Cleans up synthetic stock fixtures (only if no dependent foreign key references exist).
     *
     * @param jdbcTemplate target JDBC template
     */
    public static void cleanup(JdbcTemplate jdbcTemplate) {
        Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        jdbcTemplate.update("DELETE FROM product_stock WHERE sku IN (?, ?)", SKU_ITEM_007, SKU_ITEM_008);
    }
}
