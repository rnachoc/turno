package io.github.rnachoc.turno.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Kills the real database under a running service and checks how the probes react: the service
 * must stop reporting itself ready, yet stay alive so that it is not restarted for nothing.
 *
 * <p>The extra property forces a dedicated Spring context, because this test destroys its database
 * and must not share it with other test classes.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.hikari.connection-timeout=1000")
@Import(TestcontainersConfiguration.class)
@DirtiesContext
class DatabaseOutageIT {

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private PostgreSQLContainer postgres;

    @Test
    void readinessFollowsTheDatabaseWhileLivenessDoesNot() throws Exception {
        assertThat(statusOf("/actuator/health/readiness")).isEqualTo(200);

        postgres.stop();

        assertThat(statusOf("/actuator/health/readiness")).isEqualTo(503);
        assertThat(statusOf("/actuator/health/liveness")).isEqualTo(200);
    }

    private int statusOf(String path) throws IOException, InterruptedException {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
