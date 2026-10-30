package io.github.rnachoc.turno.core;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Provides a real PostgreSQL instance for tests, using the same image as Docker Compose so that
 * tests exercise the exact database engine the service runs against.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** Image shared with {@code compose.yaml}; keep both in sync. */
    static final String POSTGRES_IMAGE = "postgres:18.6-alpine";

    /**
     * Declares the PostgreSQL container; {@link ServiceConnection} wires its URL and credentials
     * into the datasource automatically.
     *
     * @return the container, started and stopped by the Spring test context
     */
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(POSTGRES_IMAGE);
    }
}
