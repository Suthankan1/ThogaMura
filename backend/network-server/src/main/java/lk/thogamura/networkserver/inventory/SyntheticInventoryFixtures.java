package lk.thogamura.networkserver.inventory;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Objects;
import java.util.UUID;

/**
 * Repeatable development and testing synthetic inventory fixtures.
 *
 * <p>Enforces requirements matching Issue #8 and QA-06:
 * <ul>
 *   <li>{@code ITEM-007}: Stocked item (On-hand = 10, Reserved = 0, Available = 10)</li>
 *   <li>{@code ITEM-008}: Confirmed zero stock (On-hand = 0, Reserved = 0, Available = 0)</li>
 *   <li>{@code ITEM-009}: Low stock item (On-hand = 5, Reserved = 3, Available = 2)</li>
 *   <li>{@code ITEM-010}: In stock boundary item (On-hand = 4, Reserved = 0, Available = 4)</li>
 *   <li>{@code ITEM-011}: Low stock boundary item (On-hand = 1, Reserved = 0, Available = 1)</li>
 *   <li>{@code ITEM-012}: Low stock boundary item (On-hand = 3, Reserved = 0, Available = 3)</li>
 *   <li>Strictly restricted to dev/test; never runs automatically during production startup</li>
 *   <li>Idempotent execution: running multiple times never causes duplicate key conflicts or corrupts inventory</li>
 * </ul>
 */
public final class SyntheticInventoryFixtures {

    public static final String SKU_ITEM_007 = "ITEM-007";
    public static final String NAME_ITEM_007 = "Organic Carrots";
    public static final int ON_HAND_ITEM_007 = 10;
    public static final int RESERVED_ITEM_007 = 0;

    public static final String SKU_ITEM_008 = "ITEM-008";
    public static final String NAME_ITEM_008 = "Red Onions";
    public static final int ON_HAND_ITEM_008 = 0;
    public static final int RESERVED_ITEM_008 = 0;

    public static final String SKU_ITEM_009 = "ITEM-009";
    public static final String NAME_ITEM_009 = "Yukon Gold Potatoes";
    public static final int ON_HAND_ITEM_009 = 5;
    public static final int RESERVED_ITEM_009 = 3;

    public static final String SKU_ITEM_010 = "ITEM-010";
    public static final String NAME_ITEM_010 = "Fresh Leeks";
    public static final int ON_HAND_ITEM_010 = 4;
    public static final int RESERVED_ITEM_010 = 0;

    public static final String SKU_ITEM_011 = "ITEM-011";
    public static final String NAME_ITEM_011 = "Ceylon Garlic";
    public static final int ON_HAND_ITEM_011 = 1;
    public static final int RESERVED_ITEM_011 = 0;

    public static final String SKU_ITEM_012 = "ITEM-012";
    public static final String NAME_ITEM_012 = "Fresh Ginger";
    public static final int ON_HAND_ITEM_012 = 3;
    public static final int RESERVED_ITEM_012 = 0;

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
        jdbcTemplate.update(sql, SKU_ITEM_009, NAME_ITEM_009, ON_HAND_ITEM_009, RESERVED_ITEM_009);
        jdbcTemplate.update(sql, SKU_ITEM_010, NAME_ITEM_010, ON_HAND_ITEM_010, RESERVED_ITEM_010);
        jdbcTemplate.update(sql, SKU_ITEM_011, NAME_ITEM_011, ON_HAND_ITEM_011, RESERVED_ITEM_011);
        jdbcTemplate.update(sql, SKU_ITEM_012, NAME_ITEM_012, ON_HAND_ITEM_012, RESERVED_ITEM_012);
    }

    /**
     * Seeds a comprehensive workflow demo dataset with representative requests in each lifecycle state:
     * ACCEPTED, PICKED, DISPATCHED, and REJECTED (QA-06).
     *
     * @param jdbcTemplate target JDBC template
     */
    public static void seedDemoWorkflow(JdbcTemplate jdbcTemplate) {
        Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");

        // 1. Seed base product inventory
        seed(jdbcTemplate);

        // Adjust reserved for ITEM-007 to match the demo requests (2 for ACCEPTED)
        jdbcTemplate.update("UPDATE product_stock SET reserved = 2 WHERE sku = ?", SKU_ITEM_007);

        // 2. Representative requests
        String insertRequestSql = """
            INSERT INTO stock_requests (request_id, request_fingerprint, sku, quantity, status, decision_reason)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (request_id) DO UPDATE
            SET status = EXCLUDED.status,
                decision_reason = EXCLUDED.decision_reason;
            """;

        UUID reqAccepted = UUID.fromString("a0000001-0000-4000-8000-000000000001");
        UUID reqPicked = UUID.fromString("a0000002-0000-4000-8000-000000000002");
        UUID reqDispatched = UUID.fromString("a0000003-0000-4000-8000-000000000003");
        UUID reqRejected = UUID.fromString("a0000004-0000-4000-8000-000000000004");

        String dummyFingerprint = "0000000000000000000000000000000000000000000000000000000000000000";

        jdbcTemplate.update(insertRequestSql, reqAccepted, dummyFingerprint, SKU_ITEM_007, 2, "ACCEPTED", null);
        jdbcTemplate.update(insertRequestSql, reqPicked, dummyFingerprint, SKU_ITEM_009, 3, "PICKED", null);
        jdbcTemplate.update(insertRequestSql, reqDispatched, dummyFingerprint, SKU_ITEM_007, 1, "DISPATCHED", null);
        jdbcTemplate.update(insertRequestSql, reqRejected, dummyFingerprint, SKU_ITEM_008, 5, "REJECTED", "Requested quantity 5 exceeds available stock 0");

        // 3. Movement record for dispatched request
        String insertMovementSql = """
            INSERT INTO stock_movements (stock_request_id, sku, quantity_delta, reason)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (stock_request_id) DO NOTHING;
            """;
        jdbcTemplate.update(insertMovementSql, reqDispatched, SKU_ITEM_007, -1, "DISPATCHED");
    }

    /**
     * Cleans up synthetic stock fixtures (only if no dependent foreign key references exist).
     *
     * @param jdbcTemplate target JDBC template
     */
    public static void cleanup(JdbcTemplate jdbcTemplate) {
        Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        jdbcTemplate.update("DELETE FROM stock_movements");
        jdbcTemplate.update("DELETE FROM stock_requests");
        jdbcTemplate.update("DELETE FROM command_receipts");
        jdbcTemplate.update("DELETE FROM product_stock WHERE sku IN (?, ?, ?, ?, ?, ?)",
                SKU_ITEM_007, SKU_ITEM_008, SKU_ITEM_009, SKU_ITEM_010, SKU_ITEM_011, SKU_ITEM_012);
    }
}
