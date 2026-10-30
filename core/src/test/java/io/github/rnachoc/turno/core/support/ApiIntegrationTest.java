package io.github.rnachoc.turno.core.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rnachoc.turno.core.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base of the tests that use the real API: real HTTP, real database and a clock under control.
 *
 * <p>Every test starts on Monday 1 March 2027 at 08:00 in Madrid, with an empty database.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, ApiTestConfiguration.class})
public abstract class ApiIntegrationTest {

    protected static final String AGENTS = "test-agents-token-0123456789-abcdef";
    protected static final String STAFF = "test-staff-token-0123456789-abcdefg";
    protected static final JsonMapper JSON = JsonMapper.builder().build();

    private static final AtomicLong SEQUENCE = new AtomicLong(1_000_000);
    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @Value("${local.server.port}")
    private int port;

    /** A connection as the restricted role, the same one the service uses. */
    @Autowired
    protected JdbcClient jdbc;

    /** A connection as the owner, for cleaning up and for what the service cannot do. */
    @Autowired
    protected TestcontainersConfiguration.OwnerDatabase owner;

    @Autowired
    protected MutableClock clock;

    /** An HTTP response. */
    public record Response(int status, String body, HttpResponse<String> raw) {

        public JsonNode json() {
            return JSON.readTree(body);
        }

        public String field(String name) {
            return json().path(name).asString();
        }

        public String code() {
            return field("code");
        }
    }

    @BeforeEach
    void resetState() {
        clock.set(madrid(2027, 3, 1, 8, 0));
        owner.jdbc().sql("ALTER TABLE audit_event DISABLE TRIGGER USER").update();
        owner.jdbc().sql("TRUNCATE audit_event, appointment, patient, idempotency_key").update();
        owner.jdbc().sql("ALTER TABLE audit_event ENABLE TRIGGER USER").update();
    }

    /** Converts a local time in the clinic into an instant. */
    protected static Instant madrid(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute).atZone(MADRID).toInstant();
    }

    /** Tuesday 2 March 2027 at the given time in Madrid: a normal working day for Ana. */
    protected static Instant tuesday(int hour, int minute) {
        return madrid(2027, 3, 2, hour, minute);
    }

    protected Response send(String method, String path, String token, String key, String body) {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body(), response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    protected Response get(String path) {
        return send("GET", path, AGENTS, null, null);
    }

    protected Response post(String path, Object body) {
        return send("POST", path, AGENTS, newKey(), body == null ? null : JSON.writeValueAsString(body));
    }

    protected Response staffPost(String path) {
        return send("POST", path, STAFF, newKey(), null);
    }

    protected static String newKey() {
        return UUID.randomUUID().toString();
    }

    /** Registers a new patient with a unique phone number and returns their identifier. */
    protected UUID newPatient() {
        Response response =
                post(
                        "/api/v1/patients",
                        Map.of(
                                "fullName", "Test Patient",
                                "phone", "+3460" + SEQUENCE.incrementAndGet(),
                                "language", "es"));
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return UUID.fromString(response.field("id"));
    }

    protected static Map<String, Object> holdBody(String practitioner, String treatment, Instant startsAt) {
        return Map.of("practitionerId", practitioner, "treatmentId", treatment, "startsAt", startsAt.toString());
    }

    protected static String appointments(UUID patient) {
        return "/api/v1/patients/" + patient + "/appointments";
    }

    protected Response hold(UUID patient, String practitioner, String treatment, Instant startsAt) {
        return post(appointments(patient), holdBody(practitioner, treatment, startsAt));
    }

    /** Holds a slot, failing the test if that is not possible, and returns the appointment id. */
    protected String holdOk(UUID patient, String practitioner, String treatment, Instant startsAt) {
        Response response = hold(patient, practitioner, treatment, startsAt);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return response.field("id");
    }

    /** Holds and confirms a slot, failing the test if that is not possible. */
    protected String bookOk(UUID patient, String practitioner, String treatment, Instant startsAt) {
        String id = holdOk(patient, practitioner, treatment, startsAt);
        Response confirmed = post(appointments(patient) + "/" + id + "/confirm", null);
        assertThat(confirmed.status()).as(confirmed.body()).isEqualTo(200);
        return id;
    }

    protected int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }
}
