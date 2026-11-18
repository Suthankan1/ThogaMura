package lk.thogamura.networkserver.persistence;

import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Shared test fixture initializing PostgreSQL DataSource, Flyway migration,
 * and TransactionManager for database integration tests.
 */
public final class PostgresTestHelper {

    private static PostgreSQLContainer<?> postgresContainer;
    private static DataSource dataSource;
    private static JdbcTemplate jdbcTemplate;
    private static PlatformTransactionManager transactionManager;

    private PostgresTestHelper() {
    }

    public static synchronized DataSource getDataSource() {
        if (dataSource == null) {
            initDataSource();
        }
        return dataSource;
    }

    public static synchronized JdbcTemplate getJdbcTemplate() {
        if (jdbcTemplate == null) {
            jdbcTemplate = new JdbcTemplate(getDataSource());
        }
        return jdbcTemplate;
    }

    public static synchronized PlatformTransactionManager getTransactionManager() {
        if (transactionManager == null) {
            transactionManager = new DataSourceTransactionManager(getDataSource());
        }
        return transactionManager;
    }

    private static void initDataSource() {
        String jdbcUrl = System.getenv("THOGAMURA_DB_URL");
        String username = System.getenv("THOGAMURA_DB_USER");
        String password = System.getenv("THOGAMURA_DB_PASSWORD");

        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            Path envPath = findDotEnvPath();
            if (envPath != null && Files.isReadable(envPath)) {
                Properties props = loadEnvProperties(envPath);
                jdbcUrl = props.getProperty("THOGAMURA_DB_URL");
                username = props.getProperty("THOGAMURA_DB_USER");
                password = props.getProperty("THOGAMURA_DB_PASSWORD");
            }
        }

        if (jdbcUrl == null || jdbcUrl.isBlank()) {
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

        // Run Flyway migrations
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.migrate();
    }

    public static void clearAllTables(JdbcTemplate template) {
        template.update("DELETE FROM stock_movements");
        template.update("DELETE FROM stock_requests");
        template.update("DELETE FROM command_receipts");
        template.update("DELETE FROM product_stock");
    }

    private static Path findDotEnvPath() {
        Path[] candidateLocations = new Path[]{
                Paths.get(".env"),
                Paths.get("../.env"),
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
