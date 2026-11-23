package lk.thogamura.networkserver.persistence;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring JDBC implementation of {@link StockRequestRepository}.
 */
@Repository
public class JdbcStockRequestRepository implements StockRequestRepository {

    public static final int MAX_QUERY_LIMIT = 500;

    private static final RowMapper<StockRequestRecord> ROW_MAPPER = (rs, rowNum) -> {
        Timestamp createdTs = rs.getTimestamp("created_at");
        Timestamp updatedTs = rs.getTimestamp("updated_at");
        return new StockRequestRecord(
                rs.getObject("request_id", UUID.class),
                rs.getString("request_fingerprint"),
                rs.getString("sku"),
                rs.getInt("quantity"),
                rs.getString("status"),
                rs.getString("decision_reason"),
                createdTs != null ? createdTs.toInstant() : Instant.now(),
                updatedTs != null ? updatedTs.toInstant() : Instant.now(),
                rs.getString("branch_id")
        );
    };

    private final JdbcTemplate jdbcTemplate;

    public JdbcStockRequestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    public void insert(StockRequestRecord request) {
        Objects.requireNonNull(request, "request must not be null");
        String sql = """
            INSERT INTO stock_requests (
                request_id, request_fingerprint, sku, quantity, status, decision_reason, created_at, updated_at, branch_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        Timestamp created = request.createdAt() != null ? Timestamp.from(request.createdAt()) : Timestamp.from(Instant.now());
        Timestamp updated = request.updatedAt() != null ? Timestamp.from(request.updatedAt()) : created;

        jdbcTemplate.update(
                sql,
                request.requestId(),
                request.requestFingerprint(),
                request.sku(),
                request.quantity(),
                request.status(),
                request.decisionReason(),
                created,
                updated,
                request.branchId()
        );
    }

    @Override
    public Optional<StockRequestRecord> findById(UUID requestId) {
        Objects.requireNonNull(requestId, "requestId must not be null");
        String sql = """
            SELECT request_id, request_fingerprint, sku, quantity, status, decision_reason, created_at, updated_at, branch_id
            FROM stock_requests
            WHERE request_id = ?
            """;
        try {
            StockRequestRecord record = jdbcTemplate.queryForObject(sql, ROW_MAPPER, requestId);
            return Optional.ofNullable(record);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<StockRequestRecord> findByIdForUpdate(UUID requestId) {
        Objects.requireNonNull(requestId, "requestId must not be null");
        String sql = """
            SELECT request_id, request_fingerprint, sku, quantity, status, decision_reason, created_at, updated_at, branch_id
            FROM stock_requests
            WHERE request_id = ?
            FOR UPDATE
            """;
        try {
            StockRequestRecord record = jdbcTemplate.queryForObject(sql, ROW_MAPPER, requestId);
            return Optional.ofNullable(record);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public void updateStatus(UUID requestId, String status) {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        String sql = "UPDATE stock_requests SET status = ?, updated_at = NOW() WHERE request_id = ?";
        int updated = jdbcTemplate.update(sql, status, requestId);
        if (updated == 0) {
            throw new IllegalStateException("Failed to update status for stock request: " + requestId);
        }
    }

    @Override
    public List<StockRequestRecord> findAll() {
        String sql = """
            SELECT request_id, request_fingerprint, sku, quantity, status, decision_reason, created_at, updated_at, branch_id
            FROM stock_requests
            WHERE branch_id IS NULL
            ORDER BY created_at DESC, request_id DESC
            LIMIT ?
            """;
        return jdbcTemplate.query(sql, ROW_MAPPER, MAX_QUERY_LIMIT);
    }

    @Override
    public List<StockRequestRecord> findByStatus(String status) {
        Objects.requireNonNull(status, "status must not be null");
        String sql = """
            SELECT request_id, request_fingerprint, sku, quantity, status, decision_reason, created_at, updated_at, branch_id
            FROM stock_requests
            WHERE status = ? AND branch_id IS NULL
            ORDER BY created_at DESC, request_id DESC
            LIMIT ?
            """;
        return jdbcTemplate.query(sql, ROW_MAPPER, status, MAX_QUERY_LIMIT);
    }

    @Override
    public List<StockRequestRecord> findBySku(String sku) {
        Objects.requireNonNull(sku, "sku must not be null");
        String sql = """
            SELECT request_id, request_fingerprint, sku, quantity, status, decision_reason, created_at, updated_at, branch_id
            FROM stock_requests
            WHERE sku = ? AND branch_id IS NULL
            ORDER BY created_at DESC, request_id DESC
            LIMIT ?
            """;
        return jdbcTemplate.query(sql, ROW_MAPPER, sku, MAX_QUERY_LIMIT);
    }
}
