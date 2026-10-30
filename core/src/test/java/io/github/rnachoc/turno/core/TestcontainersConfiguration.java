package io.github.rnachoc.turno.core;

import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Provides a real PostgreSQL instance for tests, using the same image as Docker Compose so that
 * tests exercise the exact database engine the service runs against.
 *
 * <p>As in production, Flyway connects as the owner and the service as the restricted role. Every
 * integration test therefore also proves that the role's privileges are enough.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** Image shared with {@code compose.yaml}; keep both in sync. */
    static final String POSTGRES_IMAGE = "postgres:18.6-alpine";

    /** The role the service connects as. */
    public static final String APP_ROLE = "turno_app";

    /** The password given to that role by the migrations. */
    public static final String APP_PASSWORD = "test-app-password";

    /**
     * A connection as the database owner, for what a test needs and the service cannot do.
     *
     * @param jdbc a client connected as the owner
     */
    public record OwnerDatabase(JdbcClient jdbc) {}

    /**
     * Declares the PostgreSQL container.
     *
     * @return the container, started and stopped by the Spring test context
     */
    @Bean
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(POSTGRES_IMAGE);
    }

    /**
     * Points the service and Flyway at the container, each with its own credentials.
     *
     * @param postgres the container
     * @return the registrar of the properties
     */
    @Bean
    DynamicPropertyRegistrar databaseProperties(PostgreSQLContainer postgres) {
        return registry -> {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", () -> APP_ROLE);
            registry.add("spring.datasource.password", () -> APP_PASSWORD);
            registry.add("spring.flyway.url", postgres::getJdbcUrl);
            registry.add("spring.flyway.user", postgres::getUsername);
            registry.add("spring.flyway.password", postgres::getPassword);
            registry.add("spring.flyway.placeholders.appPassword", () -> APP_PASSWORD);
        };
    }

    /**
     * Provides the owner's connection to tests.
     *
     * @param postgres the container
     * @return the connection
     */
    @Bean
    OwnerDatabase ownerDatabase(PostgreSQLContainer postgres) {
        DataSource dataSource =
                new SimpleDriverDataSource(
                        new org.postgresql.Driver(),
                        postgres.getJdbcUrl(),
                        postgres.getUsername(),
                        postgres.getPassword());
        return new OwnerDatabase(JdbcClient.create(dataSource));
    }
}
