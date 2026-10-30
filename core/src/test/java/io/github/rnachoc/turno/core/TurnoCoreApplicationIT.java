package io.github.rnachoc.turno.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Boots the whole service against a real PostgreSQL and checks the operational surface: health
 * probes, database migrations, and that nothing sensitive is exposed over HTTP.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class TurnoCoreApplicationIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void healthIsUpAndHidesComponentDetails() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.path("status").asString()).isEqualTo("UP");
        assertThat(body.has("components")).as("component details must stay private").isFalse();
        assertThat(body.has("details")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health/liveness", "/actuator/health/readiness"})
    void kubernetesStyleProbesAreUp(String path) throws Exception {
        HttpResponse<String> response = get(path);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(response.body()).path("status").asString()).isEqualTo("UP");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/actuator/env",
                "/actuator/beans",
                "/actuator/configprops",
                "/actuator/heapdump",
                "/actuator/threaddump",
                "/actuator/loggers",
                "/actuator/mappings",
                "/actuator/metrics",
                "/actuator/flyway"
            })
    void sensitiveActuatorEndpointsAreNotExposed(String path) throws Exception {
        assertThat(get(path).statusCode()).isEqualTo(404);
    }

    @Test
    void migrationsRunAndEnableBtreeGist() {
        Integer applied =
                jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success")
                        .query(Integer.class)
                        .single();
        Integer extension =
                jdbc.sql("SELECT count(*) FROM pg_extension WHERE extname = 'btree_gist'")
                        .query(Integer.class)
                        .single();

        assertThat(applied).isEqualTo(1);
        assertThat(extension).isEqualTo(1);
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
