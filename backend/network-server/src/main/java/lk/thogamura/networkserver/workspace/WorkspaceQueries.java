package lk.thogamura.networkserver.workspace;

import lk.thogamura.protocol.access.WorkspaceAccess.Actor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class WorkspaceQueries {
    private static final String REQUEST_FIELDS = """
            request_id AS "requestId", sku, quantity, status, branch_id AS "branchId",
            left(decision_reason, 160) AS "decisionReason", created_at AS "createdAt", updated_at AS "updatedAt"
            """;
    private final JdbcTemplate jdbc;

    WorkspaceQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Map<String, Object> request(Actor actor, UUID id) {
        List<Map<String, Object>> rows = actor.staff()
                ? jdbc.query("SELECT " + REQUEST_FIELDS + " FROM stock_requests WHERE request_id = ? AND branch_id IS NOT NULL", WorkspaceQueries::requestRow, id)
                : jdbc.query("SELECT " + REQUEST_FIELDS + " FROM stock_requests WHERE request_id = ? AND branch_id = ?", WorkspaceQueries::requestRow, id, actor.branch());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    Map<String, Object> products(int size, String cursor, String search) {
        String scope = "products:" + search;
        String[] seek = decode(cursor, scope, 1);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT sku, left(name, 200) AS name, on_hand AS "onHand", reserved, on_hand - reserved AS available
                FROM product_stock WHERE (? = '' OR starts_with(sku, ?)) AND (? = '' OR sku > ?)
                ORDER BY sku LIMIT ?
                """, search, search, seek[0], seek[0], size + 1);
        return page(rows, size, scope, "sku");
    }

    Map<String, Object> requests(Actor actor, int size, String cursor, String filter) {
        String scope = "requests:" + (actor.staff() ? "*" : actor.branch()) + ":" + filter;
        String[] seek = decode(cursor, scope, 2);
        StringBuilder sql = new StringBuilder("SELECT ").append(REQUEST_FIELDS)
                .append(" FROM stock_requests WHERE branch_id IS NOT NULL");
        List<Object> parameters = new ArrayList<>();
        if (!actor.staff()) {
            sql.append(" AND branch_id = ?");
            parameters.add(actor.branch());
        }
        if (!filter.isEmpty()) {
            sql.append(" AND status = ?");
            parameters.add(filter);
        }
        if (!seek[0].isEmpty()) {
            sql.append(" AND (created_at, request_id) < (?, ?)");
            parameters.add(OffsetDateTime.parse(seek[0]));
            parameters.add(UUID.fromString(seek[1]));
        }
        sql.append(" ORDER BY created_at DESC, request_id DESC LIMIT ?");
        parameters.add(size + 1);
        List<Map<String, Object>> rows = jdbc.query(sql.toString(), WorkspaceQueries::requestRow, parameters.toArray());
        return page(rows, size, scope, "createdAt", "requestId");
    }

    private static Map<String, Object> requestRow(ResultSet rs, int index) throws SQLException {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("requestId", rs.getObject("requestId"));
        request.put("sku", rs.getString("sku"));
        request.put("quantity", rs.getInt("quantity"));
        request.put("status", rs.getString("status"));
        request.put("branchId", rs.getString("branchId"));
        request.put("decisionReason", rs.getString("decisionReason"));
        request.put("createdAt", rs.getObject("createdAt", OffsetDateTime.class).toString());
        request.put("updatedAt", rs.getObject("updatedAt", OffsetDateTime.class).toString());
        return request;
    }

    Map<String, Object> movements(int size, String cursor) {
        String scope = "movements";
        String[] seek = decode(cursor, scope, 1);
        String sql = """
                SELECT m.id::text AS id, m.stock_request_id AS "stockRequestId",
                    m.receiving_command_id AS "receivingCommandId", m.sku, m.quantity_delta AS "quantityDelta",
                    m.reason, to_char(m.occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS "occurredAt",
                    m.recorded_by AS "recordedBy", m.note,
                    r.branch_id AS "branchId"
                FROM stock_movements m LEFT JOIN stock_requests r ON r.request_id = m.stock_request_id
                WHERE (m.reason = 'RECEIVE' OR r.branch_id IS NOT NULL)
                """;
        List<Map<String, Object>> rows = seek[0].isEmpty()
                ? jdbc.queryForList(sql + " ORDER BY m.id DESC LIMIT ?", size + 1)
                : jdbc.queryForList(sql + " AND m.id < ? ORDER BY m.id DESC LIMIT ?", Long.parseLong(seek[0]), size + 1);
        return page(rows, size, scope, "id");
    }

    private static String[] decode(String cursor, String scope, int fields) {
        if (cursor.isEmpty()) {
            String[] empty = new String[fields];
            java.util.Arrays.fill(empty, "");
            return empty;
        }
        String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        String[] parts = decoded.split("\\n", -1);
        if (parts.length != fields + 1 || !scope.equals(parts[0])) {
            throw new IllegalArgumentException("Cursor does not match this query");
        }
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].isEmpty()) throw new IllegalArgumentException("Cursor is malformed");
        }
        return java.util.Arrays.copyOfRange(parts, 1, parts.length);
    }

    private static Map<String, Object> page(List<Map<String, Object>> rows, int size, String scope, String... keys) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", rows.subList(0, Math.min(rows.size(), size)));
        String next = null;
        if (rows.size() > size) {
            Map<String, Object> tail = rows.get(size - 1);
            StringBuilder token = new StringBuilder(scope);
            for (String key : keys) token.append('\n').append(Objects.toString(tail.get(key)));
            next = Base64.getUrlEncoder().withoutPadding().encodeToString(token.toString().getBytes(StandardCharsets.UTF_8));
        }
        data.put("nextCursor", next);
        return data;
    }
}
