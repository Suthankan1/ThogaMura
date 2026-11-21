package lk.thogamura.networkserver.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe monotonic version provider for Java NIO cache invalidation signals.
 *
 * <p>Backed by a durable PostgreSQL sequence ({@code warehouse_change_version_seq}) when
 * running in production with database access, ensuring that version numbers are strictly
 * monotonic across server restarts even if system clocks jump backwards or prior instances
 * published higher versions (Issue #67).</p>
 *
 * <p>Falls back to an in-memory {@link AtomicLong} counter when running without a database
 * or in standalone unit test environments.</p>
 */
@Component
public class DefaultMonotonicVersionProvider implements MonotonicVersionProvider {

    private static final Logger log = LoggerFactory.getLogger(DefaultMonotonicVersionProvider.class);

    private final JdbcTemplate jdbcTemplate;
    private final AtomicLong inMemoryFallback;

    @Autowired
    public DefaultMonotonicVersionProvider(@Nullable @Autowired(required = false) JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.inMemoryFallback = new AtomicLong(1L);
    }

    public DefaultMonotonicVersionProvider() {
        this(null);
    }

    public DefaultMonotonicVersionProvider(long initialVersion) {
        this.jdbcTemplate = null;
        if (initialVersion < 0) {
            throw new IllegalArgumentException("initialVersion must be non-negative: " + initialVersion);
        }
        this.inMemoryFallback = new AtomicLong(initialVersion);
    }

    public DefaultMonotonicVersionProvider(JdbcTemplate jdbcTemplate, long initialVersion) {
        this.jdbcTemplate = jdbcTemplate;
        if (initialVersion < 0) {
            throw new IllegalArgumentException("initialVersion must be non-negative: " + initialVersion);
        }
        this.inMemoryFallback = new AtomicLong(initialVersion);
        if (jdbcTemplate != null) {
            try {
                jdbcTemplate.update("SELECT setval('warehouse_change_version_seq', ?, false)", initialVersion);
            } catch (Exception e) {
                log.debug("Unable to set initial sequence value on warehouse_change_version_seq: {}", e.getMessage());
            }
        }
    }

    @Override
    public long nextVersion() {
        if (jdbcTemplate != null) {
            try {
                Long val = jdbcTemplate.queryForObject("SELECT nextval('warehouse_change_version_seq')", Long.class);
                if (val != null) {
                    return val;
                }
            } catch (Exception e) {
                log.warn("Failed to fetch next change version from database sequence; using in-memory counter: {}", e.getMessage());
            }
        }
        return inMemoryFallback.incrementAndGet();
    }

    @Override
    public long currentVersion() {
        if (jdbcTemplate != null) {
            try {
                Long val = jdbcTemplate.queryForObject("SELECT last_value FROM warehouse_change_version_seq", Long.class);
                if (val != null) {
                    return val;
                }
            } catch (Exception e) {
                log.debug("Failed to fetch current change version from database sequence: {}", e.getMessage());
            }
        }
        return inMemoryFallback.get();
    }
}
