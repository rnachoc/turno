package io.github.rnachoc.turno.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rnachoc.turno.core.support.ApiIntegrationTest;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

/**
 * Checks the size limit on its exact boundary, and that it cannot be dodged by not stating a length.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
class RequestSizeIT extends ApiIntegrationTest {

    @Value("${local.server.port}")
    private int port;

    private static String bodyOf(long bytes) {
        return "x".repeat((int) bytes);
    }

    @Test
    void aBodyExactlyAtTheLimitReachesTheApi() {
        Response response =
                send("POST", "/api/v1/patients", AGENTS, newKey(), bodyOf(RequestSizeFilter.MAX_BODY_BYTES));

        assertThat(response.status()).as("refused as bad JSON, so it got through").isEqualTo(400);
    }

    @Test
    void oneByteMoreIsRefusedBeforeReadingIt() {
        Response response =
                send("POST", "/api/v1/patients", AGENTS, newKey(), bodyOf(RequestSizeFilter.MAX_BODY_BYTES + 1));

        assertThat(response.status()).isEqualTo(413);
        assertThat(response.code()).isEqualTo("REQUEST_TOO_LARGE");
    }

    @Test
    void theLimitAppliesBeforeAuthentication() {
        Response response =
                send("POST", "/api/v1/patients", null, null, bodyOf(RequestSizeFilter.MAX_BODY_BYTES + 1));

        assertThat(response.status()).isEqualTo(413);
    }

    @Test
    void aBodyOfUnknownLengthIsRefused() throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/patients"))
                        .version(HttpClient.Version.HTTP_1_1)
                        .header("Authorization", "Bearer " + AGENTS)
                        .header("Content-Type", "application/json")
                        // A stream has no known length, so the client sends it in chunks.
                        .POST(HttpRequest.BodyPublishers.ofInputStream(
                                () -> new ByteArrayInputStream(new byte[] {'{', '}'})))
                        .build();

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(411);
            assertThat(response.body()).contains("LENGTH_REQUIRED");
        }
    }

    @Test
    void aWriteWithNoBodyStillWorks() {
        var patient = newPatient();
        String id = holdOk(patient, "ana", "check-up", tuesday(9, 0));

        Response response = post(appointments(patient) + "/" + id + "/confirm", null);

        assertThat(response.status()).isEqualTo(200);
    }

    @Test
    void readsAreUnaffected() {
        assertThat(get("/api/v1/clinic").status()).isEqualTo(200);
        assertThat(send("GET", "/actuator/health", null, null, null).status()).isEqualTo(200);
    }
}
