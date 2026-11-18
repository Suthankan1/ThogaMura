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
                updatedTs != null ? updatedTs.toInstant() : Instant.now()
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
                request_id, request_fingerprint, sku, quantity, status, decision_reason, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
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
                updated
        );
    }

    @Override
    public Optional<StockRequestRecord> findById(UUID requestId) {
        Objects.requireNonNull(requestId, "requestId must not be null");
        String sql = """
            SELECT request_id, request_fingerprint, sku, quantity, status, decision_reason, created_at, updated_at
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
    public List<StockRequestRecord> findBySku(String sku) {
        Objects.requireNonNull(sku, "sku must not be null");
        String sql = """
            SELECT request_id, request_fingerprint, sku, quantity, status, decision_reason, created_at, updated_at
            FROM stock_requests
            WHERE sku = ?
            ORDER BY created_at DESC
            """;
        return jdbcTemplate.query(sql, ROW_MAPPER, sku);
    }
}
