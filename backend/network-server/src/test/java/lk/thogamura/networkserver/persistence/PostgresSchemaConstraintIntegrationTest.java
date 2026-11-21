package lk.thogamura.networkserver.persistence;

import lk.thogamura.networkserver.inventory.SyntheticInventoryFixtures;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test verifying Flyway schema initialization, table design,
 * synthetic inventory seed fixtures, and PostgreSQL constraints on real PostgreSQL 17.
 *
 * <p>Enforces requirements for Issue #8 matching {@code docs/contracts/NETWORK_PROTOCOL_V1.md}.
 * Connects to configured local PostgreSQL (Docker Compose) via environment or root .env,
 * falling back to Testcontainers.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PostgresSchemaConstraintIntegrationTest {

    private static PostgreSQLContainer<?> postgresContainer;
    private static DataSource dataSource;
    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void setUp() {
        String jdbcUrl = System.getenv("THOGAMURA_DB_URL");
        String username = System.getenv("THOGAMURA_DB_USER");
        String password = System.getenv("THOGAMURA_DB_PASSWORD");

        // Attempt reading root .env if environment variables are not set in the process
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            Path envPath = findDotEnvPath();
            if (envPath != null && Files.isReadable(envPath)) {
                Properties props = loadEnvProperties(envPath);
                jdbcUrl = props.getProperty("THOGAMURA_DB_URL");
                username = props.getProperty("THOGAMURA_DB_USER");
                password = props.getProperty("THOGAMURA_DB_PASSWORD");
            }
        }

        boolean canConnect = false;
        if (jdbcUrl != null && !jdbcUrl.isBlank()) {
            PGSimpleDataSource probeDs = new PGSimpleDataSource();
            probeDs.setUrl(jdbcUrl);
            probeDs.setUser(username != null ? username : "thogamura");
            probeDs.setPassword(password != null ? password : "");
            try (java.sql.Connection conn = probeDs.getConnection()) {
                canConnect = true;
            } catch (Exception ignored) {
                // Configured DB is unavailable or authentication failed; fall back to Testcontainers
            }
        }

        if (!canConnect) {
            if (System.getProperty("DOCKER_API_VERSION") == null && System.getenv("DOCKER_API_VERSION") == null) {
                System.setProperty("DOCKER_API_VERSION", "1.44");
                System.setProperty("api.version", "1.44");
            }
            postgresContainer = new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("thogamura_test")
                    .withUsername("thogamura")
                    .withPassword("test_secret");
            postgresContainer.start();

            jdbcUrl = postgresContainer.getJdbcUrl();
            username = postgresContainer.getUsername();
            password = postgresContainer.getPassword();
        }

        if (username == null) {
            username = "thogamura";
        }
        if (password == null) {
            password = "";
        }

        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(jdbcUrl);
        ds.setUser(username);
        ds.setPassword(password);
        dataSource = ds;
        jdbcTemplate = new JdbcTemplate(dataSource);

        // Execute Flyway migration against the real database
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.migrate();

        // Clean tables before running tests
        clearTables();
    }

    @org.junit.jupiter.api.BeforeEach
    void setUpEach() {
        clearTables();
    }

    @AfterAll
    static void tearDown() {
        if (jdbcTemplate != null) {
            clearTables();
        }
        if (postgresContainer != null && postgresContainer.isRunning()) {
            postgresContainer.stop();
        }
    }

    private static void clearTables() {
        if (jdbcTemplate != null) {
            jdbcTemplate.update("DELETE FROM stock_movements");
            jdbcTemplate.update("DELETE FROM stock_requests");
            jdbcTemplate.update("DELETE FROM command_receipts");
            jdbcTemplate.update("DELETE FROM product_stock");
        }
    }

    @Test
    @Order(1)
    @DisplayName("Flyway migration applies to a clean database and creates all required tables")
    void shouldVerifyAllRequiredTablesExist() {
        List<String> tables = jdbcTemplate.queryForList(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                ORDER BY table_name;
                """,
                String.class
        );

        assertThat(tables).contains(
                "command_receipts",
                "flyway_schema_history",
                "product_stock",
                "stock_movements",
                "stock_requests"
        );

        // Verify Flyway schema version 1 is recorded as successful
        Integer schemaVersion = jdbcTemplate.queryForObject(
                "SELECT installed_rank FROM flyway_schema_history WHERE version = '1' AND success = true;",
                Integer.class
        );
        assertThat(schemaVersion).isNotNull();
    }

    @Test
    @Order(2)
    @DisplayName("Inserts valid product stock record and enforces primary key uniqueness")
    void shouldInsertValidStockAndEnforcePrimaryKey() {
        String sku = "TEST-VALID-01";
        jdbcTemplate.update(
                "INSERT INTO product_stock (sku, name, on_hand, reserved) VALUES (?, ?, ?, ?)",
                sku, "Valid Test Item", 50, 5
        );

        Map<String, Object> record = jdbcTemplate.queryForMap(
                "SELECT sku, name, on_hand, reserved, (on_hand - reserved) AS available FROM product_stock WHERE sku = ?",
                sku
        );
        assertThat(record.get("sku")).isEqualTo(sku);
        assertThat(((Number) record.get("on_hand")).intValue()).isEqualTo(50);
        assertThat(((Number) record.get("reserved")).intValue()).isEqualTo(5);
        assertThat(((Number) record.get("available")).intValue()).isEqualTo(45);

        // Enforce PK uniqueness: duplicate insert must fail
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO product_stock (sku, name, on_hand, reserved) VALUES (?, ?, ?, ?)",
                sku, "Duplicate Item", 10, 0
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Order(3)
    @DisplayName("PostgreSQL rejects negative on_hand quantity")
    void shouldRejectNegativeOnHand() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO product_stock (sku, name, on_hand, reserved) VALUES (?, ?, ?, ?)",
                "TEST-NEG-ONHAND", "Negative On Hand", -1, 0
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Order(4)
    @DisplayName("PostgreSQL rejects negative reserved quantity")
    void shouldRejectNegativeReserved() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO product_stock (sku, name, on_hand, reserved) VALUES (?, ?, ?, ?)",
                "TEST-NEG-RES", "Negative Reserved", 10, -1
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Order(5)
    @DisplayName("PostgreSQL rejects reserved > on_hand (available < 0 violation)")
    void shouldRejectReservedGreaterThanOnHand() {
        // Violates ck_reserved_not_over_on_hand
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO product_stock (sku, name, on_hand, reserved) VALUES (?, ?, ?, ?)",
                "TEST-OVER-RES", "Over-reserved Item", 10, 11
        )).isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("ck_reserved_not_over_on_hand");

        // Updating an existing record to violate the invariant must also fail
        String sku = "TEST-UPDATE-OVER-RES";
        jdbcTemplate.update("INSERT INTO product_stock (sku, name, on_hand, reserved) VALUES (?, ?, ?, ?)", sku, "Item", 10, 5);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE product_stock SET reserved = 15 WHERE sku = ?",
                sku
        )).isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("ck_reserved_not_over_on_hand");
    }

    @Test
    @Order(6)
    @DisplayName("Enforces foreign keys, quantity > 0, and status check constraints on stock_requests")
    void shouldEnforceStockRequestConstraints() {
        UUID validRequestId = UUID.randomUUID();
        String validSku = "TEST-REQ-SKU";
        jdbcTemplate.update("INSERT INTO product_stock (sku, name, on_hand, reserved) VALUES (?, ?, ?, ?)", validSku, "Req Item", 20, 0);

        // 1. Foreign key rejection on non-existent SKU
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO stock_requests (request_id, request_fingerprint, sku, quantity, status) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), "f".repeat(64), "NON-EXISTENT-SKU", 5, "ACCEPTED"
        )).isInstanceOf(DataIntegrityViolationException.class);

        // 2. Reject quantity <= 0
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO stock_requests (request_id, request_fingerprint, sku, quantity, status) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), "f".repeat(64), validSku, 0, "ACCEPTED"
        )).isInstanceOf(DataIntegrityViolationException.class);

        // 3. Reject illegal status (e.g. 'PENDING')
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO stock_requests (request_id, request_fingerprint, sku, quantity, status) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), "f".repeat(64), validSku, 5, "PENDING"
        )).isInstanceOf(DataIntegrityViolationException.class);

        // 4. Valid insert succeeds
        int rows = jdbcTemplate.update(
                "INSERT INTO stock_requests (request_id, request_fingerprint, sku, quantity, status) VALUES (?, ?, ?, ?, ?)",
                validRequestId, "a".repeat(64), validSku, 5, "ACCEPTED"
        );
        assertThat(rows).isEqualTo(1);

        // 5. Duplicate request_id rejected
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO stock_requests (request_id, request_fingerprint, sku, quantity, status) VALUES (?, ?, ?, ?, ?)",
                validRequestId, "b".repeat(64), validSku, 5, "ACCEPTED"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Order(7)
    @DisplayName("Enforces command_receipts primary key uniqueness and payload fingerprinting")
    void shouldEnforceCommandReceiptsUniqueness() {
        UUID cmdId = UUID.randomUUID();
        String fingerprint = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        String resultJson = "{\"v\":1,\"type\":\"RESULT\",\"status\":\"ACCEPTED\"}";

        int rows = jdbcTemplate.update(
                "INSERT INTO command_receipts (command_request_id, command_type, payload_fingerprint, result_json) VALUES (?, ?, ?, ?::jsonb)",
                cmdId, "REQUEST_STOCK", fingerprint, resultJson
        );
        assertThat(rows).isEqualTo(1);

        // Duplicate command_request_id must be rejected by PostgreSQL
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO command_receipts (command_request_id, command_type, payload_fingerprint, result_json) VALUES (?, ?, ?, ?::jsonb)",
                cmdId, "REQUEST_STOCK", fingerprint, resultJson
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Order(8)
    @DisplayName("Enforces stock_movements constraints: delta < 0, reason = DISPATCH, and 1 movement per request")
    void shouldEnforceStockMovementsConstraints() {
        UUID reqId = UUID.randomUUID();
        String sku = "TEST-MOV-SKU";
        jdbcTemplate.update("INSERT INTO product_stock (sku, name, on_hand, reserved) VALUES (?, ?, ?, ?)", sku, "Movement Item", 20, 5);
        jdbcTemplate.update(
                "INSERT INTO stock_requests (request_id, request_fingerprint, sku, quantity, status) VALUES (?, ?, ?, ?, ?)",
                reqId, "c".repeat(64), sku, 5, "PICKED"
        );

        // 1. Reject positive delta (must be < 0)
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO stock_movements (stock_request_id, sku, quantity_delta, reason) VALUES (?, ?, ?, ?)",
                reqId, sku, 5, "DISPATCH"
        )).isInstanceOf(DataIntegrityViolationException.class);

        // 2. Reject reason != 'DISPATCH'
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO stock_movements (stock_request_id, sku, quantity_delta, reason) VALUES (?, ?, ?, ?)",
                reqId, sku, -5, "RESTOCK"
        )).isInstanceOf(DataIntegrityViolationException.class);

        // 3. Valid movement succeeds
        int rows = jdbcTemplate.update(
                "INSERT INTO stock_movements (stock_request_id, sku, quantity_delta, reason) VALUES (?, ?, ?, ?)",
                reqId, sku, -5, "DISPATCH"
        );
        assertThat(rows).isEqualTo(1);

        // 4. Exactly one movement per request (UNIQUE constraint on stock_request_id)
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO stock_movements (stock_request_id, sku, quantity_delta, reason) VALUES (?, ?, ?, ?)",
                reqId, sku, -5, "DISPATCH"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Order(9)
    @DisplayName("Synthetic inventory fixtures initialize idempotently and create ITEM-007 (10) and ITEM-008 (0)")
    void shouldInitializeSyntheticFixturesIdempotently() {
        // Run seed first time
        SyntheticInventoryFixtures.seed(jdbcTemplate);

        Map<String, Object> item007 = jdbcTemplate.queryForMap(
                "SELECT sku, on_hand, reserved, (on_hand - reserved) AS available FROM product_stock WHERE sku = ?",
                SyntheticInventoryFixtures.SKU_ITEM_007
        );
        assertThat(((Number) item007.get("on_hand")).intValue()).isEqualTo(10);
        assertThat(((Number) item007.get("reserved")).intValue()).isEqualTo(0);
        assertThat(((Number) item007.get("available")).intValue()).isEqualTo(10);

        Map<String, Object> item008 = jdbcTemplate.queryForMap(
                "SELECT sku, on_hand, reserved, (on_hand - reserved) AS available FROM product_stock WHERE sku = ?",
                SyntheticInventoryFixtures.SKU_ITEM_008
        );
        assertThat(((Number) item008.get("on_hand")).intValue()).isEqualTo(0);
        assertThat(((Number) item008.get("reserved")).intValue()).isEqualTo(0);
        assertThat(((Number) item008.get("available")).intValue()).isEqualTo(0);

        // Run seed a second time to prove idempotency (no duplicate errors, balances intact)
        SyntheticInventoryFixtures.seed(jdbcTemplate);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_stock WHERE sku IN (?, ?)",
                Integer.class,
                SyntheticInventoryFixtures.SKU_ITEM_007,
                SyntheticInventoryFixtures.SKU_ITEM_008
        );
        assertThat(count).isEqualTo(2);
    }

    @Test
    @Order(10)
    @DisplayName("Committed transactions persist across connection restarts without data loss")
    void shouldVerifyCommittedDataPersists() {
        String sku = "DURABLE-001";
        jdbcTemplate.update(
                "INSERT INTO product_stock (sku, name, on_hand, reserved) VALUES (?, ?, ?, ?)",
                sku, "Durable Item", 100, 20
        );

        // Verify across independent fresh query
        Map<String, Object> record = jdbcTemplate.queryForMap(
                "SELECT sku, on_hand, reserved, (on_hand - reserved) AS available FROM product_stock WHERE sku = ?",
                sku
        );
        assertThat(record.get("sku")).isEqualTo(sku);
        assertThat(((Number) record.get("on_hand")).intValue()).isEqualTo(100);
        assertThat(((Number) record.get("reserved")).intValue()).isEqualTo(20);
        assertThat(((Number) record.get("available")).intValue()).isEqualTo(80);
    }

    private static Path findDotEnvPath() {
        Path[] candidateLocations = new Path[]{
                Paths.get(".env"),
                Paths.get("../../.env"),
                Paths.get("../../../.env")
        };
        for (Path candidate : candidateLocations) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private static Properties loadEnvProperties(Path envPath) {
        Properties properties = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(envPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq > 0) {
                    String key = line.substring(0, eq).trim();
                    String value = line.substring(eq + 1).trim();
                    properties.setProperty(key, value);
                }
            }
        } catch (IOException ignored) {
        }
        return properties;
    }
}
