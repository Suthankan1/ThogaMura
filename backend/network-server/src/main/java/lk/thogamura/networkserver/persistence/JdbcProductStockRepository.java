package lk.thogamura.networkserver.persistence;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Spring JDBC implementation of {@link ProductStockRepository} utilizing
 * PostgreSQL row-level locks ({@code SELECT ... FOR UPDATE}) for concurrency control.
 */
@Repository
public class JdbcProductStockRepository implements ProductStockRepository {

    public static final int MAX_QUERY_LIMIT = 500;

    private static final RowMapper<ProductStock> ROW_MAPPER = (rs, rowNum) -> new ProductStock(
            rs.getString("sku"),
            rs.getString("name"),
            rs.getInt("on_hand"),
            rs.getInt("reserved")
    );

    private final JdbcTemplate jdbcTemplate;

    public JdbcProductStockRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    public Optional<ProductStock> findBySku(String sku) {
        Objects.requireNonNull(sku, "sku must not be null");
        String sql = "SELECT sku, name, on_hand, reserved FROM product_stock WHERE sku = ?";
        try {
            ProductStock stock = jdbcTemplate.queryForObject(sql, ROW_MAPPER, sku);
            return Optional.ofNullable(stock);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<ProductStock> findBySkuForUpdate(String sku) {
        Objects.requireNonNull(sku, "sku must not be null");
        String sql = "SELECT sku, name, on_hand, reserved FROM product_stock WHERE sku = ? FOR UPDATE";
        try {
            ProductStock stock = jdbcTemplate.queryForObject(sql, ROW_MAPPER, sku);
            return Optional.ofNullable(stock);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public void updateReserved(String sku, int newReserved) {
        Objects.requireNonNull(sku, "sku must not be null");
        String sql = "UPDATE product_stock SET reserved = ? WHERE sku = ?";
        int updated = jdbcTemplate.update(sql, newReserved, sku);
        if (updated == 0) {
            throw new IllegalStateException("Failed to update stock reserved count for sku: " + sku);
        }
    }

    @Override
    public void updateBalances(String sku, int newOnHand, int newReserved) {
        Objects.requireNonNull(sku, "sku must not be null");
        String sql = "UPDATE product_stock SET on_hand = ?, reserved = ? WHERE sku = ?";
        int updated = jdbcTemplate.update(sql, newOnHand, newReserved, sku);
        if (updated == 0) {
            throw new IllegalStateException("Failed to update stock balances for sku: " + sku);
        }
    }

    @Override
    public List<ProductStock> findAll() {
        String sql = "SELECT sku, name, on_hand, reserved FROM product_stock ORDER BY sku LIMIT ?";
        return jdbcTemplate.query(sql, ROW_MAPPER, MAX_QUERY_LIMIT);
    }
}
