package io.github.rnachoc.turno.core.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rnachoc.turno.core.support.ApiIntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.JsonNode;

/**
 * What the agents report, and the conversations they pass to a person.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
class ActivityIT extends ApiIntegrationTest {

    private static final String HANDOVERS = "/api/v1/staff/handovers";

    private static String stepsPath(UUID patient, UUID conversation) {
        return "/api/v1/patients/" + patient + "/conversations/" + conversation + "/steps";
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

    private static Map<String, Object> modelCall() {
        return Map.of(
                "kind", "model",
                "detail", "claude-opus-5-5[1m]",
                "inputTokens", 1000,
                "outputTokens", 200,
                "estimatedCostUsd", 0.0125,
                "seconds", 3.25);
    }

    private Response report(UUID patient, UUID conversation, int first, List<Map<String, Object>> steps) {
        return post(stepsPath(patient, conversation), Map.of("firstPosition", first, "steps", steps));
    }

    private void reportOk(UUID patient, UUID conversation, int first, List<Map<String, Object>> steps) {
        Response response = report(patient, conversation, first, steps);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
    }

    private Response staffGet(String path) {
        return send("GET", path, STAFF, null, null);
    }

    private List<JsonNode> openHandovers() {
        List<JsonNode> open = new ArrayList<>();
        staffGet(HANDOVERS).json().forEach(open::add);
        return open;
    }

    @Test
    void theStaffCanReadWhatHappenedInAConversation() {
        UUID patient = newPatient();
        UUID conversation = UUID.randomUUID();

        reportOk(patient, conversation, 0, List.of(modelCall(), step("consent", "asked:book")));
        reportOk(patient, conversation, 2, List.of(step("action", "book:ok")));

        JsonNode detail = staffGet("/api/v1/staff/conversations/" + conversation).json();
        assertThat(detail.path("patientId").asString()).isEqualTo(patient.toString());
        assertThat(detail.path("patientName").asString()).isEqualTo("Test Patient");
        JsonNode steps = detail.path("steps");
        assertThat(steps).hasSize(3);
        JsonNode first = steps.get(0);
        assertThat(first.path("position").asInt()).isZero();
        assertThat(first.path("kind").asString()).isEqualTo("model");
        assertThat(first.path("detail").asString()).isEqualTo("claude-opus-5-5[1m]");
        assertThat(first.path("inputTokens").asInt()).isEqualTo(1000);
        assertThat(first.path("outputTokens").asInt()).isEqualTo(200);
        assertThat(first.path("estimatedCostUsd").decimalValue()).isEqualByComparingTo("0.0125");
        assertThat(first.path("seconds").decimalValue()).isEqualByComparingTo("3.25");
        assertThat(steps.get(2).path("detail").asString()).isEqualTo("book:ok");

        JsonNode summary = staffGet("/api/v1/staff/conversations").json().get(0);
        assertThat(summary.path("id").asString()).isEqualTo(conversation.toString());
        assertThat(summary.path("patientName").asString()).isEqualTo("Test Patient");
        assertThat(summary.path("steps").asInt()).isEqualTo(3);
        assertThat(summary.path("estimatedCostUsd").decimalValue()).isEqualByComparingTo("0.0125");
        assertThat(summary.path("waitingForPerson").asBoolean()).isFalse();
    }

    @Test
    void aConversationWithNoStepsCostsNothingAndNewestComesFirst() {
        UUID patient = newPatient();
        UUID older = UUID.randomUUID();
        reportOk(patient, older, 0, List.of(step("action", "book:ok")));
        clock.advance(Duration.ofMinutes(1));
        UUID newer = UUID.randomUUID();
        reportOk(patient, newer, 0, List.of(step("handover", "core_down")));

        JsonNode list = staffGet("/api/v1/staff/conversations").json();

        assertThat(list.get(0).path("id").asString()).isEqualTo(newer.toString());
        assertThat(list.get(0).path("waitingForPerson").asBoolean()).isTrue();
        assertThat(list.get(1).path("id").asString()).isEqualTo(older.toString());
        assertThat(list.get(1).path("estimatedCostUsd").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void sendingAStepAgainChangesNothing() {
        UUID patient = newPatient();
        UUID conversation = UUID.randomUUID();
        reportOk(patient, conversation, 0, List.of(step("action", "book:ok")));

        // The answer got lost, so the agents service sends it again along with what came next.
        Response again = report(patient, conversation, 0, List.of(step("action", "changed"), step("action", "cancel:ok")));

        assertThat(again.json().path("added").asInt()).isEqualTo(1);
        JsonNode steps = staffGet("/api/v1/staff/conversations/" + conversation).json().path("steps");
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).path("detail").asString()).isEqualTo("book:ok");
        assertThat(steps.get(1).path("detail").asString()).isEqualTo("cancel:ok");
    }

    @Test
    void aConversationCannotMoveToAnotherPatient() {
        UUID owner = newPatient();
        UUID other = newPatient();
        UUID conversation = UUID.randomUUID();
        reportOk(owner, conversation, 0, List.of(step("action", "book:ok")));

        Response moved = report(other, conversation, 1, List.of(step("handover", "core_down")));

        assertThat(moved.status()).isEqualTo(404);
        assertThat(moved.code()).isEqualTo("CONVERSATION_NOT_FOUND");
        assertThat(staffGet("/api/v1/staff/conversations/" + conversation).json().path("steps")).hasSize(1);
        assertThat(openHandovers()).isEmpty();
    }

    @Test
    void anUnknownPatientOrConversationIsNotFound() {
        Response unknownPatient = report(UUID.randomUUID(), UUID.randomUUID(), 0, List.of(step("action", "book:ok")));
        assertThat(unknownPatient.code()).isEqualTo("PATIENT_NOT_FOUND");
        assertThat(count("SELECT count(*) FROM conversation")).isZero();

        Response unknown = staffGet("/api/v1/staff/conversations/" + UUID.randomUUID());
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.code()).isEqualTo("CONVERSATION_NOT_FOUND");
    }

    @ParameterizedTest
    @CsvSource({
        "model,me duele mucho la muela",
        "model,+34600111222",
        "model,laura@example.com",
        "free text,code",
        "MODEL,code",
        "'',code",
    })
    void whatAPatientWroteCannotGetIn(String kind, String detail) {
        Response response = report(newPatient(), UUID.randomUUID(), 0, List.of(step(kind, detail)));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(count("SELECT count(*) FROM agent_step")).isZero();
    }

    @Test
    void absurdReportsAreRefused() {
        UUID patient = newPatient();
        UUID conversation = UUID.randomUUID();
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            many.add(step("action", "book:ok"));
        }
        Map<String, Object> negative = new java.util.HashMap<>(modelCall());
        negative.put("inputTokens", -1);
        Map<String, Object> expensive = new java.util.HashMap<>(modelCall());
        expensive.put("estimatedCostUsd", 100001);
        Map<String, Object> slow = new java.util.HashMap<>(modelCall());
        slow.put("seconds", -0.5);

        assertThat(report(patient, conversation, 0, List.of()).status()).isEqualTo(400);
        assertThat(report(patient, conversation, 0, many).status()).isEqualTo(400);
        assertThat(report(patient, conversation, -1, List.of(modelCall())).status()).isEqualTo(400);
        assertThat(report(patient, conversation, 1_000_001, List.of(modelCall())).status()).isEqualTo(400);
        assertThat(report(patient, conversation, 0, List.of(negative)).status()).isEqualTo(400);
        assertThat(report(patient, conversation, 0, List.of(expensive)).status()).isEqualTo(400);
        assertThat(report(patient, conversation, 0, List.of(slow)).status()).isEqualTo(400);
        assertThat(count("SELECT count(*) FROM agent_step")).isZero();
    }

    @Test
    void theLargestValuesAllowedFitInTheDatabase() {
        UUID patient = newPatient();
        Map<String, Object> largest =
                Map.of(
                        "kind", "model",
                        "detail", "x".repeat(100),
                        "inputTokens", 100_000_000,
                        "outputTokens", 100_000_000,
                        "estimatedCostUsd", 100000,
                        "seconds", 100000);

        reportOk(patient, UUID.randomUUID(), 1_000_000, List.of(largest));

        assertThat(count("SELECT count(*) FROM agent_step")).isEqualTo(1);
    }

    @Test
    void aHandoverWaitsForAPersonUntilItIsResolved() {
        UUID patient = newPatient();
        UUID conversation = UUID.randomUUID();

        reportOk(patient, conversation, 0, List.of(step("handover", "triage_medical")));

        List<JsonNode> open = openHandovers();
        assertThat(open).hasSize(1);
        JsonNode handover = open.get(0);
        assertThat(handover.path("reason").asString()).isEqualTo("triage_medical");
        assertThat(handover.path("conversationId").asString()).isEqualTo(conversation.toString());
        assertThat(handover.path("patientId").asString()).isEqualTo(patient.toString());
        assertThat(handover.path("patientName").asString()).isEqualTo("Test Patient");
        assertThat(handover.path("patientPhone").asString()).startsWith("+3460");

        Response resolved = staffPost(HANDOVERS + "/" + handover.path("id").asString() + "/resolve");

        assertThat(resolved.status()).as(resolved.body()).isEqualTo(200);
        assertThat(openHandovers()).isEmpty();
        assertThat(staffGet("/api/v1/staff/conversations").json().get(0).path("waitingForPerson").asBoolean())
                .isFalse();
        assertThat(
                        jdbc.sql("SELECT resolved_by FROM handover").query(String.class).single())
                .isEqualTo("staff");
    }

    @Test
    void resolvingTwiceOrSomethingUnknownIsNotFound() {
        UUID patient = newPatient();
        reportOk(patient, UUID.randomUUID(), 0, List.of(step("handover", "core_down")));
        String id = openHandovers().get(0).path("id").asString();
        assertThat(staffPost(HANDOVERS + "/" + id + "/resolve").status()).isEqualTo(200);

        Response twice = staffPost(HANDOVERS + "/" + id + "/resolve");
        Response unknown = staffPost(HANDOVERS + "/" + UUID.randomUUID() + "/resolve");

        assertThat(twice.status()).isEqualTo(404);
        assertThat(twice.code()).isEqualTo("HANDOVER_NOT_FOUND");
        assertThat(unknown.code()).isEqualTo("HANDOVER_NOT_FOUND");
    }

    @Test
    void aRetriedResolutionGetsTheSameAnswer() {
        UUID patient = newPatient();
        reportOk(patient, UUID.randomUUID(), 0, List.of(step("handover", "core_down")));
        String path = HANDOVERS + "/" + openHandovers().get(0).path("id").asString() + "/resolve";
        String key = newKey();

        Response first = send("POST", path, STAFF, key, null);
        Response retry = send("POST", path, STAFF, key, null);

        assertThat(first.status()).isEqualTo(200);
        assertThat(retry.status()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'handover.resolve'")).isEqualTo(1);
    }

    @Test
    void aRetryCannotReopenAResolvedHandover() {
        UUID patient = newPatient();
        UUID conversation = UUID.randomUUID();
        List<Map<String, Object>> steps = List.of(step("handover", "core_down"));
        reportOk(patient, conversation, 0, steps);
        staffPost(HANDOVERS + "/" + openHandovers().get(0).path("id").asString() + "/resolve");

        reportOk(patient, conversation, 0, steps);

        assertThat(openHandovers()).isEmpty();
    }

    @Test
    void askingAgainAfterItWasResolvedOpensItAgain() {
        UUID patient = newPatient();
        UUID conversation = UUID.randomUUID();
        reportOk(patient, conversation, 0, List.of(step("handover", "core_down")));
        staffPost(HANDOVERS + "/" + openHandovers().get(0).path("id").asString() + "/resolve");

        reportOk(patient, conversation, 1, List.of(step("handover", "core_down")));

        assertThat(openHandovers()).hasSize(1);
    }

    @Test
    void theSameRequestForAPersonOnlyCountsOnceWhileOpen() {
        UUID patient = newPatient();
        UUID conversation = UUID.randomUUID();

        reportOk(
                patient,
                conversation,
                0,
                List.of(step("handover", "triage_medical"), step("handover", "triage_medical")));

        assertThat(openHandovers()).hasSize(1);
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'handover.open'")).isEqualTo(1);
    }

    @Test
    void emergenciesComeFirstThenWhoeverHasWaitedLongest() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID emergency = UUID.randomUUID();
        reportOk(newPatient(), first, 0, List.of(step("handover", "model_failure")));
        clock.advance(Duration.ofMinutes(1));
        reportOk(newPatient(), second, 0, List.of(step("handover", "core_down")));
        clock.advance(Duration.ofMinutes(1));
        reportOk(newPatient(), emergency, 0, List.of(step("handover", "triage_emergency")));

        assertThat(openHandovers())
                .extracting(handover -> handover.path("conversationId").asString())
                .containsExactly(emergency.toString(), first.toString(), second.toString());
    }

    @Test
    void anEmergencyDoesNotHideBehindAnEarlierHandover() {
        UUID patient = newPatient();
        UUID conversation = UUID.randomUUID();
        reportOk(patient, conversation, 0, List.of(step("handover", "triage_medical")));

        reportOk(patient, conversation, 1, List.of(step("handover", "triage_emergency")));

        assertThat(openHandovers())
                .extracting(handover -> handover.path("reason").asString())
                .containsExactly("triage_emergency", "triage_medical");
    }

    @Test
    void onlyTheStaffCanSeeOrResolveThis() {
        UUID patient = newPatient();
        UUID conversation = UUID.randomUUID();
        reportOk(patient, conversation, 0, List.of(step("handover", "core_down")));
        String resolve = HANDOVERS + "/" + openHandovers().get(0).path("id").asString() + "/resolve";
        String steps = "/api/v1/staff/conversations/" + conversation;

        for (String token : new String[] {AGENTS, WORKFLOWS}) {
            assertThat(send("GET", HANDOVERS, token, null, null).status()).isEqualTo(403);
            assertThat(send("GET", "/api/v1/staff/conversations", token, null, null).status()).isEqualTo(403);
            assertThat(send("GET", steps, token, null, null).status()).isEqualTo(403);
            assertThat(send("POST", resolve, token, newKey(), null).status()).isEqualTo(403);
        }
        assertThat(send("GET", HANDOVERS, null, null, null).status()).isEqualTo(401);
        assertThat(send("GET", steps, "wrong", null, null).status()).isEqualTo(401);
        assertThat(openHandovers()).hasSize(1);
    }

    @Test
    void theWorkerCannotReportSteps() {
        Response response =
                send(
                        "POST",
                        stepsPath(newPatient(), UUID.randomUUID()),
                        WORKFLOWS,
                        newKey(),
                        JSON.writeValueAsString(Map.of("firstPosition", 0, "steps", List.of(step("action", "x")))));

        assertThat(response.status()).isEqualTo(403);
        assertThat(count("SELECT count(*) FROM agent_step")).isZero();
    }

    @Test
    void reportingNeedsAnIdempotencyKey() {
        Response response =
                send(
                        "POST",
                        stepsPath(newPatient(), UUID.randomUUID()),
                        AGENTS,
                        null,
                        JSON.writeValueAsString(Map.of("firstPosition", 0, "steps", List.of(step("action", "x")))));

        assertThat(response.code()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    }

    @Test
    void readingAndChangingLeaveATrailWithoutPersonalData() {
        UUID patient = newPatient();
        UUID conversation = UUID.randomUUID();
        reportOk(patient, conversation, 0, List.of(step("handover", "core_down")));
        staffGet("/api/v1/staff/conversations");
        staffGet("/api/v1/staff/conversations/" + conversation);
        staffPost(HANDOVERS + "/" + openHandovers().get(0).path("id").asString() + "/resolve");

        for (String action :
                List.of(
                        "conversation.steps",
                        "handover.open",
                        "handover.list",
                        "conversation.list",
                        "conversation.read",
                        "handover.resolve")) {
            assertThat(count("SELECT count(*) FROM audit_event WHERE action = '" + action + "'"))
                    .as(action)
                    .isGreaterThanOrEqualTo(1);
        }
        assertThat(count("SELECT count(*) FROM audit_event WHERE details::text LIKE '%Test Patient%'")).isZero();
    }

    @Test
    void theServiceCannotRewriteWhatTheAgentsDid() {
        UUID patient = newPatient();
        UUID conversation = UUID.randomUUID();
        reportOk(patient, conversation, 0, List.of(step("handover", "core_down")));

        for (String statement :
                List.of(
                        "DELETE FROM agent_step",
                        "UPDATE agent_step SET detail = 'other'",
                        "DELETE FROM conversation",
                        "UPDATE conversation SET patient_id = patient_id",
                        "DELETE FROM handover")) {
            assertThatThrownBy(() -> jdbc.sql(statement).update())
                    .as(statement)
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining("permission denied");
        }
    }
}
