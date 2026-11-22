package lk.thogamura.networkserver.workspace;

import lk.thogamura.networkserver.persistence.PostgresTestHelper;
import lk.thogamura.protocol.access.WorkspaceAccess.Actor;
import lk.thogamura.protocol.access.WorkspaceAccess.Role;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceQueriesIntegrationTest {
    private static final Actor NORTH = new Actor("north_operator", Role.OPERATOR, "north");
    private static final UUID FIRST = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID SECOND = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID THIRD = UUID.fromString("20000000-0000-4000-8000-000000000003");
    private static final UUID OTHER = UUID.fromString("20000000-0000-4000-8000-000000000004");
    private JdbcTemplate jdbc;
    private WorkspaceQueries queries;

    @BeforeEach
    void prepare() {
        jdbc = PostgresTestHelper.getJdbcTemplate();
        PostgresTestHelper.clearAllTables(jdbc);
        queries = new WorkspaceQueries(jdbc);
    }

    @Test
    void tiedRequestTimesAndANewerInsertDoNotRepeatOrSkipRows() {
        jdbc.update("INSERT INTO product_stock (sku, name, on_hand) VALUES ('A', 'A product', 7)");
        insertRequest(FIRST, "north", "ACCEPTED");
        insertRequest(SECOND, "north", "ACCEPTED");
        insertRequest(THIRD, "north", "ACCEPTED");
        insertRequest(OTHER, "south", "ACCEPTED");

        var page1 = queries.requests(NORTH, 2, "", "");
        assertThat(items(page1)).hasSize(2);
        assertThat(items(page1).get(0)).containsEntry("requestId", THIRD);
        assertThat(items(page1).get(1)).containsEntry("requestId", SECOND);

        UUID newer = UUID.randomUUID();
        insertRequest(newer, "north", "ACCEPTED");
        jdbc.update("UPDATE stock_requests SET created_at = NOW() WHERE request_id = ?", newer);
        String cursor = (String) page1.get("nextCursor");
        var page2 = queries.requests(NORTH, 2, cursor, "");
        assertThat(items(page2)).hasSize(1);
        assertThat(items(page2).getFirst()).containsEntry("requestId", FIRST);
        assertThat(page2.get("nextCursor")).isNull();
        assertThat(queries.request(NORTH, newer)).containsEntry("branchId", "north");
        assertThat(queries.request(NORTH, OTHER)).isNull();
        assertThatThrownBy(() -> queries.requests(NORTH, 2, cursor, "PICKED"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void boundsDisplayFieldsOnEachInventoryPage() {
        jdbc.update("INSERT INTO product_stock (sku, name, on_hand) VALUES ('A', ?, 7)", "🍵".repeat(500));
        jdbc.update("INSERT INTO product_stock (sku, name, on_hand) VALUES ('B', 'Other product', 6)");
        var first = queries.products(1, "", "");
        String displayed = (String) items(first).getFirst().get("name");
        assertThat(displayed.codePointCount(0, displayed.length())).isEqualTo(200);
        var second = queries.products(1, (String) first.get("nextCursor"), "");
        assertThat(items(second).getFirst()).containsEntry("sku", "B");
        assertThat(second.get("nextCursor")).isNull();
    }

    @Test
    void upgradesExistingLegacyRequestsAndKeepsLedgerConstraints() {
        String schema = "workspace_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure().dataSource(PostgresTestHelper.getDataSource()).schemas(schema)
                .locations("classpath:db/migration").target("2").load().migrate();
        try {
            jdbc.update("INSERT INTO " + schema + ".product_stock (sku, name, on_hand) VALUES ('A', 'Legacy product', 7)");
            jdbc.update("INSERT INTO " + schema + ".stock_requests (request_id, request_fingerprint, sku, quantity, status) "
                    + "VALUES (?, repeat('0', 64), 'A', 1, 'DISPATCHED')", FIRST);
            jdbc.update("INSERT INTO " + schema + ".stock_movements (stock_request_id, sku, quantity_delta, reason) "
                    + "VALUES (?, 'A', -1, 'DISPATCH')", FIRST);
            Flyway.configure().dataSource(PostgresTestHelper.getDataSource()).schemas(schema)
                    .locations("classpath:db/migration").load().migrate();

            assertThat(jdbc.queryForMap("SELECT branch_id, status FROM " + schema + ".stock_requests"))
                    .containsEntry("branch_id", null).containsEntry("status", "DISPATCHED");
            assertThat(jdbc.queryForObject("SELECT quantity_delta FROM " + schema + ".stock_movements", Integer.class)).isEqualTo(-1);
            assertThatThrownBy(() -> jdbc.update("UPDATE " + schema + ".stock_requests SET status = 'CANCELLED'"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO " + schema
                    + ".stock_movements (sku, quantity_delta, reason) VALUES ('A', 1, 'RECEIVE')"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private void insertRequest(UUID id, String branch, String status) {
        jdbc.update("""
                INSERT INTO stock_requests (request_id, request_fingerprint, sku, quantity, status, branch_id, created_at)
                VALUES (?, repeat('0', 64), 'A', 1, ?, ?, '2026-11-01T00:00:00Z')
                """, id, status, branch);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }
}
