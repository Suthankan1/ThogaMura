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
 * Spring JDBC implementation of {@link StockMovementRepository}.
 */
@Repository
public class JdbcStockMovementRepository implements StockMovementRepository {

    private static final RowMapper<StockMovementRecord> ROW_MAPPER = (rs, rowNum) -> {
        Timestamp occurredTs = rs.getTimestamp("occurred_at");
        return new StockMovementRecord(
                rs.getLong("id"),
                rs.getObject("stock_request_id", UUID.class),
                rs.getString("sku"),
                rs.getInt("quantity_delta"),
                rs.getString("reason"),
                occurredTs != null ? occurredTs.toInstant() : Instant.now()
        );
    };

    private final JdbcTemplate jdbcTemplate;

    public JdbcStockMovementRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    public void insert(StockMovementRecord movement) {
        Objects.requireNonNull(movement, "movement must not be null");
        String sql = """
            INSERT INTO stock_movements (stock_request_id, sku, quantity_delta, reason, occurred_at)
            VALUES (?, ?, ?, ?, ?)
            """;
        Timestamp occurred = movement.occurredAt() != null
                ? Timestamp.from(movement.occurredAt())
                : Timestamp.from(Instant.now());

        jdbcTemplate.update(
                sql,
                movement.stockRequestId(),
                movement.sku(),
                movement.quantityDelta(),
                movement.reason(),
                occurred
        );
    }

    @Override
    public Optional<StockMovementRecord> findByStockRequestId(UUID stockRequestId) {
        Objects.requireNonNull(stockRequestId, "stockRequestId must not be null");
        String sql = """
            SELECT id, stock_request_id, sku, quantity_delta, reason, occurred_at
            FROM stock_movements
            WHERE stock_request_id = ?
            """;
        try {
            StockMovementRecord record = jdbcTemplate.queryForObject(sql, ROW_MAPPER, stockRequestId);
            return Optional.ofNullable(record);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<StockMovementRecord> findAll() {
        String sql = """
            SELECT id, stock_request_id, sku, quantity_delta, reason, occurred_at
            FROM stock_movements
            ORDER BY id ASC
            """;
        return jdbcTemplate.query(sql, ROW_MAPPER);
    }
}
