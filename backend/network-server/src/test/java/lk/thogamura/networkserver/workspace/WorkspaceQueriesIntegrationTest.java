package lk.thogamura.networkserver.workspace;

import lk.thogamura.networkserver.persistence.JdbcStockRequestRepository;
import lk.thogamura.networkserver.persistence.PostgresTestHelper;
import lk.thogamura.protocol.access.WorkspaceAccess.Actor;
import lk.thogamura.protocol.access.WorkspaceAccess.Role;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
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
    private static final WorkspaceQueries.DateRange ANY_DATE = new WorkspaceQueries.DateRange(null, null);
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

        var page1 = queries.requests(NORTH, 2, "", "", "", ANY_DATE);
        assertThat(items(page1)).hasSize(2);
        assertThat(items(page1).get(0)).containsEntry("requestId", THIRD);
        assertThat(items(page1).get(1)).containsEntry("requestId", SECOND);

        UUID newer = UUID.randomUUID();
        insertRequest(newer, "north", "ACCEPTED");
        jdbc.update("UPDATE stock_requests SET created_at = NOW() WHERE request_id = ?", newer);
        String cursor = (String) page1.get("nextCursor");
        var page2 = queries.requests(NORTH, 2, cursor, "", "", ANY_DATE);
        assertThat(items(page2)).hasSize(1);
        assertThat(items(page2).getFirst()).containsEntry("requestId", FIRST);
        assertThat(page2.get("nextCursor")).isNull();
        assertThat(queries.request(NORTH, newer)).containsEntry("branchId", "north");
        assertThat(queries.request(NORTH, OTHER)).isNull();
        assertThatThrownBy(() -> queries.requests(NORTH, 2, cursor, "PICKED", "", ANY_DATE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overviewSelectsOldestVisibleStageAcrossEveryPage() {
        jdbc.update("INSERT INTO product_stock(sku,name,on_hand) VALUES ('A','Available',8),('B','Shortage',0)");
        insertRequest(FIRST, "north", "ACCEPTED");
        insertRequest(OTHER, "south", "PICKED");
        jdbc.update("UPDATE stock_requests SET updated_at = now() - interval '2 days' WHERE request_id = ?", FIRST);
        jdbc.update("UPDATE stock_requests SET updated_at = now() - interval '3 days' WHERE request_id = ?", OTHER);
        for (int i = 0; i < 25; i++) insertRequest(UUID.randomUUID(), "north", "ACCEPTED");
        var overview = queries.overview(NORTH);
        assertThat(((Map<?, ?>) overview.get("waiting")).get("requestId")).isEqualTo(FIRST);
        assertThat(((Map<?, ?>) overview.get("stockout")).get("sku")).isEqualTo("B");
        jdbc.update("UPDATE stock_requests SET status='PICKED', updated_at=now() WHERE request_id=?", FIRST);
        jdbc.update("UPDATE stock_requests SET status='PICKED' WHERE request_id=?", FIRST);
        var events = (List<?>) queries.request(NORTH, FIRST).get("events");
        assertThat(events.stream().map(e -> ((Map<?, ?>) e).get("stage").toString()).toList())
                .containsExactly("REQUESTED", "ACCEPTED", "PICKED");
    }

    @Test
    void boundsDisplayFieldsOnEachInventoryPage() {
        jdbc.update("INSERT INTO product_stock (sku, name, on_hand) VALUES ('A', ?, 7)", "🍵".repeat(500));
        jdbc.update("INSERT INTO product_stock (sku, name, on_hand) VALUES ('B', 'Other product', 6)");
        var first = queries.products(1, "", "", "", "");
        String displayed = (String) items(first).getFirst().get("name");
        assertThat(displayed.codePointCount(0, displayed.length())).isEqualTo(200);
        var second = queries.products(1, (String) first.get("nextCursor"), "", "", "");
        assertThat(items(second).getFirst()).containsEntry("sku", "B");
        assertThat(second.get("nextCursor")).isNull();
    }

    @Test
    void filtersBeforePagingAndPreservesBranchScope() {
        jdbc.update("INSERT INTO product_stock (sku, name, on_hand) VALUES ('A', 'Organic Carrots', 2), ('B', 'Carrot juice', 8)");
        insertRequest(FIRST, "north", "ACCEPTED");
        insertRequest(SECOND, "north", "ACCEPTED");
        insertRequest(OTHER, "south", "ACCEPTED");
        assertThat(items(queries.products(24, "", "cArRoT", "low_stock", "")))
                .singleElement().satisfies(row -> assertThat(row).containsEntry("sku", "A"));
        assertThat(items(queries.products(24, "", "", "", "B")))
                .singleElement().satisfies(row -> assertThat(row).containsEntry("sku", "B"));

        var dates = new WorkspaceQueries.DateRange(OffsetDateTime.parse("2026-11-01T00:00:00Z"),
                OffsetDateTime.parse("2026-11-02T00:00:00Z"));
        var page = queries.requests(NORTH, 1, "", "", "carrots", dates);
        assertThat(items(page)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("requestId", SECOND).containsEntry("productName", "Organic Carrots"));
        String cursor = (String) page.get("nextCursor");
        assertThat(items(queries.requests(NORTH, 1, cursor, "", "carrots", dates)))
                .singleElement().satisfies(row -> assertThat(row).containsEntry("requestId", FIRST));
        assertThat(items(queries.requests(NORTH, 24, "", "", "south", dates))).isEmpty();
        assertThatThrownBy(() -> queries.requests(NORTH, 1, cursor, "", "north", dates))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void filtersMovementsOnLocalDateBoundariesSuppliedByTheCaller() {
        jdbc.update("INSERT INTO product_stock (sku, name, on_hand) VALUES ('A', 'Organic Carrots', 2)");
        UUID command = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO command_receipts (command_request_id, command_type, payload_fingerprint, result_json)
                VALUES (?, 'RECEIVE_STOCK', repeat('0', 64), '{}'::jsonb)
                """, command);
        jdbc.update("""
                INSERT INTO stock_movements (receiving_command_id, sku, quantity_delta, reason, recorded_by, note, occurred_at)
                VALUES (?, 'A', 2, 'RECEIVE', 'supervisor', 'Morning delivery', '2026-11-01T20:30:00Z')
                """, command);
        var dates = new WorkspaceQueries.DateRange(OffsetDateTime.parse("2026-11-02T00:00:00+05:30"),
                OffsetDateTime.parse("2026-11-03T00:00:00+05:30"));
        assertThat(items(queries.movements(24, "", "DELIVERY", "RECEIVE", dates)))
                .singleElement().satisfies(row -> assertThat(row)
                        .containsEntry("receivingCommandId", command)
                        .containsEntry("productName", "Organic Carrots"));
        assertThat(items(queries.movements(24, "", "carrots", "RECEIVE", dates)))
                .singleElement().satisfies(row -> assertThat(row).containsEntry("receivingCommandId", command));
        assertThat(items(queries.movements(24, "", "delivery", "DISPATCH", dates))).isEmpty();

        UUID firstDispatch = UUID.randomUUID();
        UUID secondDispatch = UUID.randomUUID();
        insertRequest(firstDispatch, null, "DISPATCHED");
        insertRequest(secondDispatch, null, "DISPATCHED");
        jdbc.update("""
                INSERT INTO stock_movements (stock_request_id, sku, quantity_delta, reason, occurred_at)
                VALUES (?, 'A', -1, 'DISPATCH', '2026-11-01T10:00:00Z')
                """, firstDispatch);
        jdbc.update("""
                INSERT INTO stock_movements (stock_request_id, sku, quantity_delta, reason, occurred_at)
                VALUES (?, 'A', -2, 'DISPATCH', '2026-11-02T10:00:00Z')
                """, secondDispatch);
        var legacyMovements = new lk.thogamura.networkserver.persistence.JdbcStockMovementRepository(jdbc).findAll();
        assertThat(legacyMovements).hasSize(2)
                .first().satisfies(row -> assertThat(row.stockRequestId()).isEqualTo(secondDispatch));
    }

    @Test
    void backfillsOnlyRecordedWorkspaceStageTimes() {
        String schema = "event_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var migration = Flyway.configure().dataSource(PostgresTestHelper.getDataSource()).schemas(schema);
        migration.target("3").load().migrate();
        try {
            jdbc.update("INSERT INTO " + schema + ".product_stock(sku,name,on_hand) VALUES ('A','Historical',4)");
            jdbc.update("INSERT INTO " + schema + ".stock_requests(request_id,request_fingerprint,sku,quantity,status,branch_id,created_at,updated_at) "
                    + "VALUES (?,repeat('0',64),'A',1,'DISPATCHED','north','2026-10-01','2026-10-03')", FIRST);
            migration.target("latest").load().migrate();
            assertThat(jdbc.queryForList("SELECT stage FROM " + schema + ".request_events ORDER BY sequence", String.class))
                    .containsExactly("REQUESTED", "DISPATCHED");
        } finally {
            jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
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

    @Test
    void legacyHistoryKeepsTheMostRecentRequestInsideItsExistingBound() {
        jdbc.update("INSERT INTO product_stock (sku, name, on_hand) VALUES ('A', 'Legacy product', 7)");
        jdbc.update("""
                INSERT INTO stock_requests (request_id, request_fingerprint, sku, quantity, status, created_at)
                SELECT gen_random_uuid(), repeat('0', 64), 'A', 1, 'ACCEPTED',
                    TIMESTAMPTZ '2026-10-01T00:00:00Z' + step * INTERVAL '1 second'
                FROM generate_series(1, ?) AS steps(step)
                """, JdbcStockRequestRepository.MAX_QUERY_LIMIT);
        insertRequest(FIRST, null, "ACCEPTED");

        var legacy = new JdbcStockRequestRepository(jdbc);
        assertThat(legacy.findAll()).hasSize(JdbcStockRequestRepository.MAX_QUERY_LIMIT)
                .first().satisfies(row -> assertThat(row.requestId()).isEqualTo(FIRST));
        assertThat(legacy.findByStatus("ACCEPTED")).hasSize(JdbcStockRequestRepository.MAX_QUERY_LIMIT)
                .first().satisfies(row -> assertThat(row.requestId()).isEqualTo(FIRST));
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
