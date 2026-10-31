package io.github.rnachoc.turno.core.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rnachoc.turno.core.support.ApiIntegrationTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The figures behind the dashboards. The registry lives as long as the tests' application, so each
 * test looks at how much a counter moved.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
class MetricsIT extends ApiIntegrationTest {

    private static final Pattern UUID_LIKE = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}");

    @Autowired
    private MeterRegistry registry;

    private double metric(String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    private double confirmed() {
        return metric("turno.audit.events", "action", "appointment.confirm", "outcome", "ACCEPTED", "actor", "agents");
    }

    private Response report(UUID patient, List<Map<String, Object>> steps) {
        Response response =
                post(
                        "/api/v1/patients/" + patient + "/conversations/" + UUID.randomUUID() + "/steps",
                        Map.of("firstPosition", 0, "steps", steps));
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response;
    }

    private static Map<String, Object> step(String kind, String detail) {
        return Map.of(
                "kind", kind,
                "detail", detail,
                "inputTokens", 0,
                "outputTokens", 0,
                "estimatedCostUsd", 0,
                "seconds", 0);
    }

    @Test
    void aBookingIsCountedOnce() {
        double before = confirmed();

        bookOk(newPatient(), "ana", "check-up", tuesday(10, 0));

        assertThat(confirmed()).isEqualTo(before + 1);
    }

    @Test
    void aRetriedOperationIsNotCountedAgain() {
        UUID patient = newPatient();
        String appointment = holdOk(patient, "ana", "check-up", tuesday(10, 0));
        String path = appointments(patient) + "/" + appointment + "/confirm";
        String key = newKey();
        double before = confirmed();

        send("POST", path, AGENTS, key, null);
        send("POST", path, AGENTS, key, null);

        assertThat(confirmed()).isEqualTo(before + 1);
    }

    @Test
    void aRefusalIsCountedAsARefusalAndNotAsADoneOperation() {
        UUID patient = newPatient();
        holdOk(patient, "ana", "check-up", tuesday(10, 0));
        String refusal = "POST /api/v1/patients/{patientId}/appointments";
        double accepted = metric("turno.audit.events", "action", "appointment.hold", "outcome", "ACCEPTED", "actor", "agents");
        double rejected = metric("turno.audit.events", "action", refusal, "outcome", "REJECTED", "actor", "agents");

        // The same slot, for someone else.
        assertThat(hold(newPatient(), "ana", "check-up", tuesday(10, 0)).status()).isEqualTo(409);

        assertThat(metric("turno.audit.events", "action", "appointment.hold", "outcome", "ACCEPTED", "actor", "agents"))
                .isEqualTo(accepted);
        assertThat(metric("turno.audit.events", "action", refusal, "outcome", "REJECTED", "actor", "agents"))
                .isEqualTo(rejected + 1);
    }

    @Test
    void refusalsAreAddedUpByWhoAskedWhateverTheRoute() {
        double before = metric("turno.refusals", "actor", "agents");

        assertThat(get("/api/v1/patients/" + UUID.randomUUID()).status()).isEqualTo(404);
        assertThat(hold(UUID.randomUUID(), "ana", "check-up", tuesday(10, 0)).status()).isEqualTo(404);

        assertThat(metric("turno.refusals", "actor", "agents")).isEqualTo(before + 2);
    }

    @Test
    void theMainSeriesExistBeforeAnythingHappens() {
        // Nothing in these tests reports this reason, this kind of caller refused or this operation.
        assertThat(registry.find("turno.handovers").tag("reason", "too_many_steps").counter()).isNotNull();
        assertThat(registry.find("turno.refusals").tag("actor", "staff").counter()).isNotNull();
        assertThat(
                        registry.find("turno.audit.events")
                                .tags("actor", "workflows", "action", "waitlist.offer", "outcome", "ACCEPTED")
                                .counter())
                .isNotNull();
        for (String kind : List.of("model", "consent", "action", "refused", "blocked", "rejected", "handover")) {
            assertThat(registry.find("turno.agent.steps").tag("kind", kind).counter()).as(kind).isNotNull();
        }
    }

    @Test
    void whatRollsBackIsNotCounted() {
        UUID patient = newPatient();
        UUID conversation = UUID.randomUUID();
        Response first =
                post(
                        "/api/v1/patients/" + patient + "/conversations/" + conversation + "/steps",
                        Map.of("firstPosition", 0, "steps", List.of(step("action", "book:ok"))));
        assertThat(first.status()).isEqualTo(200);
        double before = metric("turno.agent.steps", "kind", "handover");
        double handovers = metric("turno.handovers", "reason", "core_down");

        // Refused after the conversation is checked, for belonging to someone else.
        Response refused =
                post(
                        "/api/v1/patients/" + newPatient() + "/conversations/" + conversation + "/steps",
                        Map.of("firstPosition", 1, "steps", List.of(step("handover", "core_down"))));

        assertThat(refused.status()).isEqualTo(404);
        assertThat(metric("turno.agent.steps", "kind", "handover")).isEqualTo(before);
        assertThat(metric("turno.handovers", "reason", "core_down")).isEqualTo(handovers);
    }

    @Test
    void theWorkOfTheAgentsIsAddedUp() {
        double input = metric("turno.agent.tokens", "direction", "input");
        double output = metric("turno.agent.tokens", "direction", "output");
        double cost = metric("turno.agent.estimated.cost.usd");
        double models = metric("turno.agent.steps", "kind", "model");
        Timer timer = registry.find("turno.agent.model.duration").timer();
        long calls = timer == null ? 0 : timer.count();
        double seconds = timer == null ? 0 : timer.totalTime(TimeUnit.SECONDS);

        report(
                newPatient(),
                List.of(
                        Map.of(
                                "kind", "model",
                                "detail", "claude-opus-5-5",
                                "inputTokens", 1000,
                                "outputTokens", 200,
                                "estimatedCostUsd", 0.25,
                                "seconds", 2.5),
                        step("action", "book:ok")));

        assertThat(metric("turno.agent.tokens", "direction", "input")).isEqualTo(input + 1000);
        assertThat(metric("turno.agent.tokens", "direction", "output")).isEqualTo(output + 200);
        assertThat(metric("turno.agent.estimated.cost.usd")).isEqualTo(cost + 0.25);
        assertThat(metric("turno.agent.steps", "kind", "model")).isEqualTo(models + 1);
        Timer after = registry.find("turno.agent.model.duration").timer();
        assertThat(after).isNotNull();
        assertThat(after.count()).isEqualTo(calls + 1);
        assertThat(after.totalTime(TimeUnit.SECONDS)).isEqualTo(seconds + 2.5);
    }

    @Test
    void aStepSentTwiceIsCountedOnce() {
        UUID patient = newPatient();
        UUID conversation = UUID.randomUUID();
        String path = "/api/v1/patients/" + patient + "/conversations/" + conversation + "/steps";
        Map<String, Object> body = Map.of("firstPosition", 0, "steps", List.of(step("blocked", "x")));
        double before = metric("turno.agent.steps", "kind", "blocked");

        post(path, body);
        post(path, body);

        assertThat(metric("turno.agent.steps", "kind", "blocked")).isEqualTo(before + 1);
    }

    @Test
    void handoversAreCountedByReason() {
        double before = metric("turno.handovers", "reason", "triage_emergency");

        report(newPatient(), List.of(step("handover", "triage_emergency")));

        assertThat(metric("turno.handovers", "reason", "triage_emergency")).isEqualTo(before + 1);
    }

    @Test
    void valuesFromOutsideCannotCreateNewSeries() {
        double kinds = metric("turno.agent.steps", "kind", "other");
        double reasons = metric("turno.handovers", "reason", "other");

        report(newPatient(), List.of(step("made_up_kind", "x"), step("handover", "made-up-reason")));

        assertThat(registry.find("turno.agent.steps").tag("kind", "made_up_kind").counter()).isNull();
        assertThat(registry.find("turno.handovers").tag("reason", "made-up-reason").counter()).isNull();
        assertThat(metric("turno.agent.steps", "kind", "other")).isEqualTo(kinds + 1);
        assertThat(metric("turno.handovers", "reason", "other")).isEqualTo(reasons + 1);
    }

    @Test
    void noLabelHoldsAnIdentifierOrAPhone() {
        UUID patient = newPatient();
        String appointment = bookOk(patient, "ana", "check-up", tuesday(10, 0));
        report(patient, List.of(step("handover", "core_down")));
        send("GET", appointments(patient) + "/" + appointment, AGENTS, null, null);
        send("GET", "/api/v1/patients/" + UUID.randomUUID(), AGENTS, null, null);

        for (Meter meter : registry.getMeters()) {
            for (Tag tag : meter.getId().getTags()) {
                assertThat(UUID_LIKE.matcher(tag.getValue()).find())
                        .as("%s %s=%s", meter.getId().getName(), tag.getKey(), tag.getValue())
                        .isFalse();
                assertThat(tag.getValue()).as(meter.getId().getName()).doesNotContain("+34");
            }
        }
    }

    @Test
    void requestsAreTimedByRouteAndNotByAddress() {
        UUID patient = newPatient();

        get(appointments(patient));

        assertThat(
                        registry.find("http.server.requests")
                                .tag("uri", "/api/v1/patients/{patientId}/appointments")
                                .timer())
                .isNotNull();
    }
}
