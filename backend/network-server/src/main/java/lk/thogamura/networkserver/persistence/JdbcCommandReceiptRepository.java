package lk.thogamura.networkserver.persistence;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring JDBC implementation of {@link CommandReceiptRepository} storing JSONB payloads.
 */
@Repository
public class JdbcCommandReceiptRepository implements CommandReceiptRepository {

    private static final RowMapper<CommandReceipt> ROW_MAPPER = (rs, rowNum) -> {
        Timestamp createdTs = rs.getTimestamp("created_at");
        return new CommandReceipt(
                rs.getObject("command_request_id", UUID.class),
                rs.getString("command_type"),
                rs.getString("payload_fingerprint"),
                rs.getString("result_json"),
                createdTs != null ? createdTs.toInstant() : Instant.now()
        );
    };

    private final JdbcTemplate jdbcTemplate;

    public JdbcCommandReceiptRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    public void insert(CommandReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt must not be null");
        String sql = """
            INSERT INTO command_receipts (
                command_request_id, command_type, payload_fingerprint, result_json, created_at
            ) VALUES (?, ?, ?, ?::jsonb, ?)
            """;
        Timestamp created = receipt.createdAt() != null ? Timestamp.from(receipt.createdAt()) : Timestamp.from(Instant.now());

        jdbcTemplate.update(
                sql,
                receipt.commandRequestId(),
                receipt.commandType(),
                receipt.payloadFingerprint(),
                receipt.resultJson(),
                created
        );
    }

    @Override
    public Optional<CommandReceipt> findByCommandRequestId(UUID commandRequestId) {
        Objects.requireNonNull(commandRequestId, "commandRequestId must not be null");
        String sql = """
            SELECT command_request_id, command_type, payload_fingerprint, result_json::text AS result_json, created_at
            FROM command_receipts
            WHERE command_request_id = ?
            """;
        try {
            CommandReceipt receipt = jdbcTemplate.queryForObject(sql, ROW_MAPPER, commandRequestId);
            return Optional.ofNullable(receipt);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
