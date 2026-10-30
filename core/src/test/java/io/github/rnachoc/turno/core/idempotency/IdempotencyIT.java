package io.github.rnachoc.turno.core.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rnachoc.turno.core.support.ApiIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Checks that a retry is safe. The concurrent case, the same request at the same moment, is covered
 * in {@code ConcurrencyIT}.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
class IdempotencyIT extends ApiIntegrationTest {

    private static final Instant SLOT = madrid(2027, 3, 9, 9, 0);

    @Autowired
    private IdempotencyService idempotency;

    @Test
    void aRetryGetsTheOriginalResponseAndChangesNothing() {
        UUID patient = newPatient();
        String key = newKey();
        String body = JSON.writeValueAsString(holdBody("ana", "check-up", SLOT));

        Response first = send("POST", appointments(patient), AGENTS, key, body);
        clock.advance(Duration.ofSeconds(30));
        Response retry = send("POST", appointments(patient), AGENTS, key, body);

        assertThat(first.status()).isEqualTo(201);
        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.body()).isEqualTo(first.body());
        assertThat(first.raw().headers().firstValue("Idempotent-Replayed")).contains("false");
        assertThat(retry.raw().headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(count("SELECT count(*) FROM appointment")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'appointment.hold'")).isEqualTo(1);
    }

    @Test
    void aRetriedConfirmationDoesNotFailAsAlreadyConfirmed() {
        UUID patient = newPatient();
        String id = holdOk(patient, "ana", "check-up", SLOT);
        String key = newKey();
        String path = appointments(patient) + "/" + id + "/confirm";

        Response first = send("POST", path, AGENTS, key, null);
        Response retry = send("POST", path, AGENTS, key, null);

        assertThat(first.status()).isEqualTo(200);
        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.body()).isEqualTo(first.body());
    }

    @Test
    void aRetriedCancellationKeepsItsOriginalVerdictOnTheDeposit() {
        UUID patient = newPatient();
        String id = bookOk(patient, "ana", "filling", SLOT);
        String key = newKey();
        String path = appointments(patient) + "/" + id + "/cancel";

        Response free = send("POST", path, AGENTS, key, null);
        clock.set(SLOT.minus(Duration.ofHours(1)));
        Response retry = send("POST", path, AGENTS, key, null);

        assertThat(free.json().path("depositForfeited").asBoolean()).isFalse();
        assertThat(retry.json().path("depositForfeited").asBoolean()).isFalse();
    }

    @Test
    void aRetryStillAnswersAfterTheHoldHasExpired() {
        UUID patient = newPatient();
        String key = newKey();
        String body = JSON.writeValueAsString(holdBody("ana", "check-up", SLOT));
        Response first = send("POST", appointments(patient), AGENTS, key, body);

        clock.advance(Duration.ofHours(1));

        assertThat(send("POST", appointments(patient), AGENTS, key, body).body()).isEqualTo(first.body());
    }

    @Test
    void theSameKeyWithADifferentBodyIsRefused() {
        UUID patient = newPatient();
        String key = newKey();
        send("POST", appointments(patient), AGENTS, key, JSON.writeValueAsString(holdBody("ana", "check-up", SLOT)));

        Response response =
                send(
                        "POST",
                        appointments(patient),
                        AGENTS,
                        key,
                        JSON.writeValueAsString(holdBody("ana", "check-up", SLOT.plus(Duration.ofHours(1)))));

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(count("SELECT count(*) FROM appointment")).isEqualTo(1);
    }

    @Test
    void theSameKeyOnAnotherRouteIsRefused() {
        UUID patient = newPatient();
        String id = holdOk(patient, "ana", "check-up", SLOT);
        String key = newKey();
        String base = appointments(patient) + "/" + id;
        send("POST", base + "/confirm", AGENTS, key, null);

        Response response = send("POST", base + "/cancel", AGENTS, key, null);

        assertThat(response.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(get(base).field("status")).isEqualTo("CONFIRMED");
    }

    @Test
    void keysOfDifferentCallersDoNotCollide() {
        UUID patient = newPatient();
        String id = bookOk(patient, "ana", "filling", SLOT);
        clock.set(SLOT);
        String key = newKey();

        // An agent's key cannot replay, or block, what staff do with that same key.
        send("POST", "/api/v1/patients", AGENTS, key, "{\"fullName\":\"A B\",\"phone\":\"+34699000001\",\"language\":\"es\"}");
        Response staff = send("POST", "/api/v1/staff/appointments/" + id + "/complete", STAFF, key, null);

        assertThat(staff.status()).isEqualTo(200);
        assertThat(staff.field("status")).isEqualTo("COMPLETED");
    }

    @Test
    void aRefusalIsNotRememberedSoTheRetryIsJudgedAgain() {
        UUID blocker = newPatient();
        String held = holdOk(blocker, "ana", "check-up", SLOT);
        UUID patient = newPatient();
        String key = newKey();
        String body = JSON.writeValueAsString(holdBody("ana", "check-up", SLOT));

        Response refused = send("POST", appointments(patient), AGENTS, key, body);
        assertThat(refused.code()).isEqualTo("SLOT_UNAVAILABLE");
        assertThat(count("SELECT count(*) FROM idempotency_key WHERE key = '" + key + "'")).isZero();

        post(appointments(blocker) + "/" + held + "/release", null);
        Response retry = send("POST", appointments(patient), AGENTS, key, body);

        assertThat(retry.status()).isEqualTo(201);
    }

    @Test
    void writesWithoutAKeyAreRefused() {
        UUID patient = newPatient();
        String body = JSON.writeValueAsString(holdBody("ana", "check-up", SLOT));

        Response response = send("POST", appointments(patient), AGENTS, null, body);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(count("SELECT count(*) FROM appointment")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "has spaces in it", "semi;colon;key", "drop'table'key"})
    void malformedKeysAreRefused(String key) {
        UUID patient = newPatient();
        String body = JSON.writeValueAsString(holdBody("ana", "check-up", SLOT));

        Response response = send("POST", appointments(patient), AGENTS, key, body);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("IDEMPOTENCY_KEY_INVALID");
    }

    @Test
    void anOverlongKeyIsRefused() {
        Response response =
                send("POST", appointments(newPatient()), AGENTS, "k".repeat(129), JSON.writeValueAsString(holdBody("ana", "check-up", SLOT)));

        assertThat(response.code()).isEqualTo("IDEMPOTENCY_KEY_INVALID");
    }

    @Test
    void keysAreKeptForSevenDaysAndThenPurged() {
        UUID patient = newPatient();
        String key = newKey();
        send("POST", appointments(patient), AGENTS, key, JSON.writeValueAsString(holdBody("ana", "check-up", SLOT)));
        int before = count("SELECT count(*) FROM idempotency_key");

        clock.advance(Duration.ofDays(7));
        assertThat(idempotency.purgeExpired()).as("exactly seven days is still inside").isZero();

        clock.advance(Duration.ofMillis(1));
        assertThat(idempotency.purgeExpired()).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM idempotency_key")).isZero();
    }
}
