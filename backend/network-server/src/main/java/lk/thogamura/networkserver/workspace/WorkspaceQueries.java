package lk.thogamura.networkserver.workspace;

import lk.thogamura.protocol.access.WorkspaceAccess.Actor;
import lk.thogamura.networkserver.inventory.PayloadFingerprintCalculator;
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
            r.request_id AS "requestId", r.sku, left(p.name, 200) AS "productName", r.quantity, r.status, r.branch_id AS "branchId",
            left(r.decision_reason, 160) AS "decisionReason", r.created_at AS "createdAt", r.updated_at AS "updatedAt"
            """;
    private static final String REQUEST_SOURCE = " FROM stock_requests r JOIN product_stock p ON p.sku = r.sku";
    record DateRange(OffsetDateTime from, OffsetDateTime before) {
        DateRange {
            if (from != null && before != null && !from.isBefore(before)) {
                throw new IllegalArgumentException("Invalid date range");
            }
        }

        void apply(String column, StringBuilder sql, List<Object> parameters) {
            if (from != null) {
                sql.append(" AND ").append(column).append(" >= ?");
                parameters.add(from);
            }
            if (before != null) {
                sql.append(" AND ").append(column).append(" < ?");
                parameters.add(before);
            }
        }
    }
    private final JdbcTemplate jdbc;

    WorkspaceQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Map<String, Object> request(Actor actor, UUID id) {
        List<Map<String, Object>> rows = actor.staff()
                ? jdbc.query("SELECT " + REQUEST_FIELDS + REQUEST_SOURCE + " WHERE r.request_id = ? AND r.branch_id IS NOT NULL", WorkspaceQueries::requestRow, id)
                : jdbc.query("SELECT " + REQUEST_FIELDS + REQUEST_SOURCE + " WHERE r.request_id = ? AND r.branch_id = ?", WorkspaceQueries::requestRow, id, actor.branch());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    Map<String, Object> products(int size, String cursor, String search, String availability, String sku) {
        String scope = scope("products", search, availability, sku);
        String[] seek = decode(cursor, scope, 1);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT sku, left(name, 200) AS name, on_hand AS "onHand", reserved, on_hand - reserved AS available
                FROM product_stock
                WHERE (? = '' OR strpos(lower(sku), lower(?)) > 0 OR strpos(lower(name), lower(?)) > 0)
                  AND (? = '' OR sku = ?)
                  AND CASE ? WHEN 'in_stock' THEN on_hand - reserved >= 4
                    WHEN 'low_stock' THEN on_hand - reserved BETWEEN 1 AND 3
                    WHEN 'out_of_stock' THEN on_hand = reserved ELSE true END
                  AND (? = '' OR sku > ?)
                ORDER BY sku LIMIT ?
                """, search, search, search, sku, sku, availability, seek[0], seek[0], size + 1);
        return page(rows, size, scope, "sku");
    }

    Map<String, Object> requests(Actor actor, int size, String cursor, String filter, String search, DateRange dates) {
        String scope = scope("requests", actor.staff() ? "*" : actor.branch(), filter, search,
                Objects.toString(dates.from(), ""), Objects.toString(dates.before(), ""));
        String[] seek = decode(cursor, scope, 2);
        StringBuilder sql = new StringBuilder("SELECT ").append(REQUEST_FIELDS)
                .append(REQUEST_SOURCE).append(" WHERE r.branch_id IS NOT NULL");
        List<Object> parameters = new ArrayList<>();
        if (!actor.staff()) {
            sql.append(" AND r.branch_id = ?");
            parameters.add(actor.branch());
        }
        if (!filter.isEmpty()) {
            sql.append(" AND r.status = ?");
            parameters.add(filter);
        }
        contains(sql, parameters, search, "r.sku", "p.name", "r.request_id::text", "r.branch_id");
        dates.apply("r.created_at", sql, parameters);
        if (!seek[0].isEmpty()) {
            sql.append(" AND (r.created_at, r.request_id) < (?, ?)");
            parameters.add(OffsetDateTime.parse(seek[0]));
            parameters.add(UUID.fromString(seek[1]));
        }
        sql.append(" ORDER BY r.created_at DESC, r.request_id DESC LIMIT ?");
        parameters.add(size + 1);
        List<Map<String, Object>> rows = jdbc.query(sql.toString(), WorkspaceQueries::requestRow, parameters.toArray());
        return page(rows, size, scope, "createdAt", "requestId");
    }

    private static Map<String, Object> requestRow(ResultSet rs, int index) throws SQLException {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("requestId", rs.getObject("requestId"));
        request.put("sku", rs.getString("sku"));
        request.put("productName", rs.getString("productName"));
        request.put("quantity", rs.getInt("quantity"));
        request.put("status", rs.getString("status"));
        request.put("branchId", rs.getString("branchId"));
        request.put("decisionReason", rs.getString("decisionReason"));
        request.put("createdAt", rs.getObject("createdAt", OffsetDateTime.class).toString());
        request.put("updatedAt", rs.getObject("updatedAt", OffsetDateTime.class).toString());
        return request;
    }

    Map<String, Object> movements(int size, String cursor, String search, String reason, DateRange dates) {
        String scope = scope("movements", search, reason, Objects.toString(dates.from(), ""), Objects.toString(dates.before(), ""));
        String[] seek = decode(cursor, scope, 1);
        StringBuilder sql = new StringBuilder("""
                SELECT m.id::text AS id, m.stock_request_id AS "stockRequestId",
                    m.receiving_command_id AS "receivingCommandId", m.sku, m.quantity_delta AS "quantityDelta",
                    m.reason, to_char(m.occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS "occurredAt",
                    m.recorded_by AS "recordedBy", m.note,
                    r.branch_id AS "branchId"
                FROM stock_movements m LEFT JOIN stock_requests r ON r.request_id = m.stock_request_id
                WHERE (m.reason = 'RECEIVE' OR r.branch_id IS NOT NULL)
                """);
        List<Object> parameters = new ArrayList<>();
        contains(sql, parameters, search, "m.sku", "m.id::text", "m.stock_request_id::text",
                "m.receiving_command_id::text", "m.recorded_by", "m.note", "r.branch_id");
        if (!reason.isEmpty()) {
            sql.append(" AND m.reason = ?");
            parameters.add(reason);
        }
        dates.apply("m.occurred_at", sql, parameters);
        if (!seek[0].isEmpty()) {
            sql.append(" AND m.id < ?");
            parameters.add(Long.parseLong(seek[0]));
        }
        sql.append(" ORDER BY m.id DESC LIMIT ?");
        parameters.add(size + 1);
        return page(jdbc.queryForList(sql.toString(), parameters.toArray()), size, scope, "id");
    }

    private static void contains(StringBuilder sql, List<Object> parameters, String search, String... columns) {
        if (search.isEmpty()) return;
        var terms = new java.util.StringJoiner(" OR ", " AND (", ")");
        for (String column : columns) {
            terms.add("strpos(lower(" + column + "), lower(?)) > 0");
            parameters.add(search);
        }
        sql.append(terms);
    }

    private static String scope(String... fields) {
        var value = new StringBuilder();
        for (String field : fields) value.append(field.length()).append(':').append(field);
        return PayloadFingerprintCalculator.computeSha256Hex(value.toString());
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
