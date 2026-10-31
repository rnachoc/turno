package io.github.rnachoc.turno.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rnachoc.turno.core.support.ApiIntegrationTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The limits per caller, with small values. Every test starts at the beginning of a minute.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@TestPropertySource(
        properties = {
            "turno.rate-limit.agents-per-minute=5",
            "turno.rate-limit.staff-per-minute=3",
            "turno.rate-limit.workflows-per-minute=2",
            "turno.rate-limit.failed-authentications-per-minute=3",
        })
class RateLimitIT extends ApiIntegrationTest {

    private static final String CLINIC = "/api/v1/clinic";
    private static final String HANDOVERS = "/api/v1/staff/handovers";
    private static final String SLOTS = "/api/v1/workflows/freed-slots";

    @Autowired
    private RateLimiter limiter;

    @Autowired
    private MeterRegistry registry;

    @BeforeEach
    void startOfAMinute() {
        limiter.reset();
    }

    private Response as(String token, String path) {
        return send("GET", path, token, null, null);
    }

    private int auditedRefusals() {
        return count("SELECT count(*) FROM audit_event WHERE action = 'rate-limit'");
    }

    @Test
    void aCallerOverItsLimitIsToldToWait() {
        for (int i = 0; i < 5; i++) {
            assertThat(as(AGENTS, CLINIC).status()).isEqualTo(200);
        }

        Response refused = as(AGENTS, CLINIC);

        assertThat(refused.status()).isEqualTo(429);
        assertThat(refused.code()).isEqualTo("RATE_LIMITED");
        assertThat(refused.raw().headers().firstValue("Retry-After")).hasValue("60");
    }

    @Test
    void retryAfterSaysHowLongIsLeft() {
        for (int i = 0; i < 5; i++) {
            as(AGENTS, CLINIC);
        }
        clock.advance(Duration.ofSeconds(45));

        assertThat(as(AGENTS, CLINIC).raw().headers().firstValue("Retry-After")).hasValue("15");
    }

    @Test
    void theNextMinuteStartsAgain() {
        for (int i = 0; i < 6; i++) {
            as(AGENTS, CLINIC);
        }
        assertThat(as(AGENTS, CLINIC).status()).isEqualTo(429);

        clock.advance(Duration.ofMinutes(1));

        assertThat(as(AGENTS, CLINIC).status()).isEqualTo(200);
    }

    @Test
    void eachCallerHasItsOwnLimit() {
        for (int i = 0; i < 6; i++) {
            as(AGENTS, CLINIC);
        }
        assertThat(as(AGENTS, CLINIC).status()).isEqualTo(429);

        assertThat(as(STAFF, HANDOVERS).status()).isEqualTo(200);
        assertThat(as(STAFF, HANDOVERS).status()).isEqualTo(200);
        assertThat(as(STAFF, HANDOVERS).status()).isEqualTo(200);
        assertThat(as(STAFF, HANDOVERS).status()).isEqualTo(429);
        assertThat(as(WORKFLOWS, SLOTS).status()).isEqualTo(200);
        assertThat(as(WORKFLOWS, SLOTS).status()).isEqualTo(200);
        assertThat(as(WORKFLOWS, SLOTS).status()).isEqualTo(429);
    }

    @Test
    void aFloodLeavesOneRecordPerMinuteRatherThanOnePerRequest() {
        for (int i = 0; i < 20; i++) {
            as(AGENTS, CLINIC);
        }

        assertThat(auditedRefusals()).isEqualTo(1);
        String actor =
                jdbc.sql("SELECT actor FROM audit_event WHERE action = 'rate-limit'").query(String.class).single();
        assertThat(actor).isEqualTo("agents");

        clock.advance(Duration.ofMinutes(1));
        for (int i = 0; i < 20; i++) {
            as(AGENTS, CLINIC);
        }

        assertThat(auditedRefusals()).isEqualTo(2);
    }

    @Test
    void aRefusedWriteDoesNothing() {
        // Uses up the agents' five requests in the minute.
        for (int i = 0; i < 5; i++) {
            as(AGENTS, CLINIC);
        }

        Response refused =
                post(
                        "/api/v1/patients",
                        Map.of("fullName", "Too Many", "phone", "+34600999888", "language", "es"));

        assertThat(refused.status()).isEqualTo(429);
        assertThat(count("SELECT count(*) FROM patient")).isZero();
        assertThat(count("SELECT count(*) FROM idempotency_key")).isZero();
    }

    @Test
    void aForbiddenRequestCountsToo() {
        for (int i = 0; i < 5; i++) {
            assertThat(as(AGENTS, HANDOVERS).status()).isEqualTo(403);
        }

        assertThat(as(AGENTS, HANDOVERS).status()).isEqualTo(429);
    }

    @Test
    void wrongTokensFromOneAddressHaveToWait() {
        for (int i = 0; i < 3; i++) {
            assertThat(as("wrong-token", CLINIC).status()).isEqualTo(401);
        }

        Response refused = as("wrong-token", CLINIC);

        assertThat(refused.status()).isEqualTo(429);
        assertThat(refused.raw().headers().firstValue("Retry-After")).isPresent();
    }

    @Test
    void whileWaitingEvenTheRightTokenIsRefusedSoGuessesTellNothing() {
        for (int i = 0; i < 3; i++) {
            as("wrong-token", CLINIC);
        }

        assertThat(as(STAFF, HANDOVERS).status()).isEqualTo(429);

        clock.advance(Duration.ofMinutes(1));
        assertThat(as(STAFF, HANDOVERS).status()).isEqualTo(200);
    }

    @Test
    void noTokenAtAllCountsAsAWrongOne() {
        for (int i = 0; i < 3; i++) {
            assertThat(as(null, CLINIC).status()).isEqualTo(401);
        }

        assertThat(as(null, CLINIC).status()).isEqualTo(429);
    }

    @Test
    void rightTokensDoNotCountAgainstTheAddress() {
        for (int i = 0; i < 5; i++) {
            assertThat(as(AGENTS, CLINIC).status()).isEqualTo(200);
        }

        assertThat(as("wrong-token", CLINIC).status()).isEqualTo(401);
    }

    @Test
    void healthStaysOutOfThis() {
        for (int i = 0; i < 4; i++) {
            as("wrong-token", CLINIC);
        }

        assertThat(as(null, "/actuator/health").status()).isEqualTo(200);
    }

    @Test
    void refusalsReachTheMetrics() {
        double agents = limited("agents");
        double unknown = limited("unknown");
        Counter failures = registry.find("turno.authentication.failures").counter();
        assertThat(failures).isNotNull();
        double failed = failures.count();

        for (int i = 0; i < 7; i++) {
            as(AGENTS, CLINIC);
        }
        for (int i = 0; i < 4; i++) {
            as("wrong-token", CLINIC);
        }

        assertThat(limited("agents")).isEqualTo(agents + 2);
        assertThat(limited("unknown")).isEqualTo(unknown + 1);
        assertThat(failures.count()).isEqualTo(failed + 3);
    }

    private double limited(String actor) {
        Counter counter = registry.find("turno.rate.limited").tag("actor", actor).counter();
        assertThat(counter).isNotNull();
        return counter.count();
    }

    @Test
    void theRecordOfALimitNeverHoldsTheToken() {
        for (int i = 0; i < 7; i++) {
            as(AGENTS, CLINIC);
        }

        String details =
                jdbc.sql("SELECT details::text FROM audit_event WHERE action = 'rate-limit'")
                        .query(String.class)
                        .single();
        assertThat(details).contains("RATE_LIMITED").doesNotContain(AGENTS);
    }
}
