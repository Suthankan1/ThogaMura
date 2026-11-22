package lk.thogamura.networkserver.persistence;

import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
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
        // The suite clears application tables. Its only target is a fresh, disposable container.
        if (System.getProperty("DOCKER_API_VERSION") == null && System.getenv("DOCKER_API_VERSION") == null) {
            System.setProperty("api.version", "1.44");
        }
        postgresContainer = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("thogamura_test")
                .withUsername("thogamura")
                .withPassword("test_secret");
        postgresContainer.start();

        PGSimpleDataSource containerDataSource = new PGSimpleDataSource();
        containerDataSource.setUrl(postgresContainer.getJdbcUrl());
        containerDataSource.setUser(postgresContainer.getUsername());
        containerDataSource.setPassword(postgresContainer.getPassword());
        Flyway.configure().dataSource(containerDataSource)
                .locations("classpath:db/migration").load().migrate();
        dataSource = containerDataSource;
    }

    public static void clearAllTables(JdbcTemplate template) {
        if (template.getDataSource() != getDataSource()) {
            throw new IllegalArgumentException("Test cleanup requires the disposable PostgreSQL container");
        }
        template.update("DELETE FROM stock_movements");
        template.update("DELETE FROM stock_requests");
        template.update("DELETE FROM command_receipts");
        template.update("DELETE FROM product_stock");
    }
}
