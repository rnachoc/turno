package io.github.rnachoc.turno.core.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rnachoc.turno.core.support.ApiIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * The owner's report and the agenda, checked against figures worked out by hand.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
class ReportIT extends ApiIntegrationTest {

    private static final String REPORT = "/api/v1/staff/reports/daily";
    private static final String TUESDAY = "2027-03-02";

    private JsonNode report(String date) {
        Response response = send("GET", REPORT + "?date=" + date, STAFF, null, null);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json();
    }

    private JsonNode agenda(String date) {
        Response response = send("GET", "/api/v1/staff/agenda?date=" + date, STAFF, null, null);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json();
    }

    private void reportSteps(UUID patient, UUID conversation, List<Map<String, Object>> steps) {
        Response response =
                post(
                        "/api/v1/patients/" + patient + "/conversations/" + conversation + "/steps",
                        Map.of("firstPosition", 0, "steps", steps));
        assertThat(response.status()).as(response.body()).isEqualTo(200);
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

    private static Map<String, Object> modelCall(int input, int output, double cost) {
        return Map.of(
                "kind", "model",
                "detail", "claude-opus-5-5",
                "inputTokens", input,
                "outputTokens", output,
                "estimatedCostUsd", cost,
                "seconds", 2);
    }

    private void staffOutcome(String appointment, String outcome) {
        Response response = staffPost("/api/v1/staff/appointments/" + appointment + "/" + outcome);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
    }

    private void cancel(UUID patient, String appointment) {
        Response response = post(appointments(patient) + "/" + appointment + "/cancel", null);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
    }

    @Test
    void aDayWithNothingIsAllZeros() {
        JsonNode report = report("2027-03-01");

        assertThat(report.path("date").asString()).isEqualTo("2027-03-01");
        assertThat(report.path("timezone").asString()).isEqualTo("Europe/Madrid");
        assertThat(report.path("currency").asString()).isEqualTo("EUR");
        assertThat(report.path("generatedAt").asString()).isEqualTo(clock.instant().toString());
        for (String field : List.of("held", "confirmed", "completed", "noShow", "cancelled")) {
            assertThat(report.path("agenda").path(field).asInt()).as(field).isZero();
        }
        assertThat(report.path("money").path("completedCents").asInt()).isZero();
        assertThat(report.path("waitlist").path("offersMade").asInt()).isZero();
        assertThat(report.path("agents").path("conversations").asInt()).isZero();
        assertThat(report.path("agents").path("estimatedCostUsd").decimalValue()).isEqualByComparingTo("0");
        assertThat(report.path("agents").path("estimatedCostPerBookingUsd").isNull()).isTrue();
        assertThat(report.path("agents").path("handoversByReason").isEmpty()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        // Nine hours for Ana and Ben.
        "2027-03-01,1080",
        // Nine hours for Ana alone.
        "2027-03-02,540",
    })
    void workingTimeCountsEachPractitionerWhoWorksThatDay(String date, int minutes) {
        assertThat(report(date).path("occupancy").path("openMinutes").asInt()).isEqualTo(minutes);
        assertThat(report(date).path("occupancy").path("percent").asInt()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2027-03-03", "2027-03-06", "2027-03-07"})
    void aClosedDayHasNoOccupancyRatherThanZero(String date) {
        JsonNode occupancy = report(date).path("occupancy");

        assertThat(occupancy.path("openMinutes").asInt()).isZero();
        assertThat(occupancy.path("percent").isNull()).isTrue();
    }

    @Test
    void theFiguresOfTheDayAddUp() {
        // Attended: a check-up of 30 minutes at 35 euros.
        UUID attended = newPatient();
        String checkUp = bookOk(attended, "ana", "check-up", tuesday(10, 0));
        // Not attended: a filling of 45 minutes at 80 euros, keeping a deposit of 15.
        UUID absent = newPatient();
        String filling = bookOk(absent, "ana", "filling", tuesday(11, 0));
        // Still confirmed: a check-up of 30 minutes.
        bookOk(newPatient(), "ana", "check-up", tuesday(12, 0));
        // Cancelled in time: it adds no time, no money and no deposit.
        UUID cancels = newPatient();
        cancel(cancels, bookOk(cancels, "ana", "filling", tuesday(16, 0)));
        // On another day, so it must not count.
        bookOk(newPatient(), "ana", "filling", madrid(2027, 3, 4, 10, 0));

        clock.set(tuesday(13, 0));
        staffOutcome(checkUp, "complete");
        staffOutcome(filling, "no-show");
        // Held right now, and it must still count as held.
        holdOk(newPatient(), "ana", "check-up", tuesday(18, 0));

        JsonNode report = report(TUESDAY);

        JsonNode agenda = report.path("agenda");
        assertThat(agenda.path("completed").asInt()).isEqualTo(1);
        assertThat(agenda.path("noShow").asInt()).isEqualTo(1);
        assertThat(agenda.path("confirmed").asInt()).isEqualTo(1);
        assertThat(agenda.path("cancelled").asInt()).isEqualTo(1);
        assertThat(agenda.path("held").asInt()).isEqualTo(1);
        JsonNode money = report.path("money");
        assertThat(money.path("completedCents").asInt()).isEqualTo(3500);
        assertThat(money.path("noShowCents").asInt()).isEqualTo(8000);
        assertThat(money.path("forfeitedDepositCents").asInt()).isEqualTo(1500);
        JsonNode occupancy = report.path("occupancy");
        assertThat(occupancy.path("bookedMinutes").asInt()).isEqualTo(30 + 45 + 30);
        // 105 of 540 minutes is 19.4%.
        assertThat(occupancy.path("percent").asInt()).isEqualTo(19);
    }

    @Test
    void aHoldThatRanOutCountsForNothing() {
        holdOk(newPatient(), "ana", "check-up", tuesday(10, 0));

        clock.advance(Duration.ofMinutes(6));

        JsonNode report = report(TUESDAY);
        assertThat(report.path("agenda").path("held").asInt()).isZero();
        assertThat(report.path("occupancy").path("bookedMinutes").asInt()).isZero();
        assertThat(agenda(TUESDAY)).isEmpty();
    }

    @Test
    void aLateCancellationCountsItsForfeitedDeposit() {
        UUID patient = newPatient();
        String appointment = bookOk(patient, "ana", "filling", tuesday(10, 0));

        // Less than 24 hours before.
        clock.set(madrid(2027, 3, 1, 12, 0));
        cancel(patient, appointment);

        JsonNode report = report(TUESDAY);
        assertThat(report.path("agenda").path("cancelled").asInt()).isEqualTo(1);
        assertThat(report.path("money").path("forfeitedDepositCents").asInt()).isEqualTo(1500);
        assertThat(report.path("money").path("noShowCents").asInt()).isZero();
    }

    @Test
    void operationsCountOnTheDayTheyHappenedNotTheDayOfTheAppointment() {
        UUID patient = newPatient();
        // Done on Monday, for an appointment on Thursday.
        String appointment = bookOk(patient, "ana", "check-up", madrid(2027, 3, 4, 10, 0));
        Response refused = post(appointments(newPatient()), holdBody("ana", "laser", madrid(2027, 3, 4, 11, 0)));
        assertThat(refused.status()).isEqualTo(422);
        clock.set(madrid(2027, 3, 1, 9, 0));
        Response moved =
                post(
                        appointments(patient) + "/" + appointment + "/reschedule",
                        Map.of("practitionerId", "ana", "startsAt", madrid(2027, 3, 4, 12, 0).toString()));
        assertThat(moved.status()).as(moved.body()).isEqualTo(200);

        // Done on Tuesday.
        clock.set(tuesday(9, 0));
        cancel(patient, moved.field("id"));

        JsonNode monday = report("2027-03-01").path("activity");
        assertThat(monday.path("booked").asInt()).isEqualTo(1);
        assertThat(monday.path("bookedByAgents").asInt()).isEqualTo(1);
        assertThat(monday.path("rescheduled").asInt()).isEqualTo(1);
        assertThat(monday.path("cancelled").asInt()).isZero();
        assertThat(monday.path("refused").asInt()).isEqualTo(1);
        JsonNode tuesday = report(TUESDAY).path("activity");
        assertThat(tuesday.path("cancelled").asInt()).isEqualTo(1);
        assertThat(tuesday.path("booked").asInt()).isZero();
    }

    @Test
    void bookingsByStaffAreNotCreditedToTheAgents() {
        UUID patient = newPatient();
        Response held =
                send(
                        "POST",
                        appointments(patient),
                        STAFF,
                        newKey(),
                        JSON.writeValueAsString(holdBody("ana", "check-up", tuesday(10, 0))));
        assertThat(held.status()).as(held.body()).isEqualTo(201);
        Response confirmed =
                send("POST", appointments(patient) + "/" + held.field("id") + "/confirm", STAFF, newKey(), null);
        assertThat(confirmed.status()).as(confirmed.body()).isEqualTo(200);

        JsonNode activity = report("2027-03-01").path("activity");

        assertThat(activity.path("booked").asInt()).isEqualTo(1);
        assertThat(activity.path("bookedByAgents").asInt()).isZero();
    }

    @Test
    void aDayStartsAtMidnightAtTheClinicNotInUtc() {
        UUID patient = newPatient();
        // 22:30 UTC on 2 March is 23:30 in Madrid, still Tuesday.
        clock.set(Instant.parse("2027-03-02T22:30:00Z"));
        bookOk(patient, "ana", "check-up", madrid(2027, 3, 4, 10, 0));
        // 23:30 UTC on 2 March is 00:30 in Madrid, already Wednesday.
        clock.set(Instant.parse("2027-03-02T23:30:00Z"));
        bookOk(patient, "ana", "check-up", madrid(2027, 3, 4, 11, 0));

        assertThat(report(TUESDAY).path("activity").path("booked").asInt()).isEqualTo(1);
        assertThat(report("2027-03-03").path("activity").path("booked").asInt()).isEqualTo(1);
    }

    @Test
    void theDayTheClocksChangeHasItsLastHours() {
        // On 28 March 2027 Madrid skips an hour, so the day lasts 23 hours and ends at 22:00 UTC.
        UUID patient = newPatient();
        clock.set(Instant.parse("2027-03-28T21:30:00Z"));
        bookOk(patient, "ana", "check-up", madrid(2027, 3, 30, 10, 0));
        clock.set(Instant.parse("2027-03-28T22:30:00Z"));
        bookOk(patient, "ana", "check-up", madrid(2027, 3, 30, 11, 0));

        assertThat(report("2027-03-28").path("activity").path("booked").asInt()).isEqualTo(1);
        assertThat(report("2027-03-29").path("activity").path("booked").asInt()).isEqualTo(1);
    }

    @Test
    void theRefillOfAFreedSlotIsReported() {
        // Bookings by a patient who then cancels, and two patients waiting.
        UUID leaving = newPatient();
        String appointment = bookOk(leaving, "ana", "check-up", tuesday(10, 0));
        UUID accepts = newPatient();
        post("/api/v1/patients/" + accepts + "/waitlist", Map.of("treatmentId", "check-up"));
        clock.advance(Duration.ofSeconds(1));
        UUID stillWaiting = newPatient();
        post("/api/v1/patients/" + stillWaiting + "/waitlist", Map.of("treatmentId", "check-up"));

        clock.set(madrid(2027, 3, 1, 9, 0));
        cancel(leaving, appointment);
        String slot =
                send("GET", "/api/v1/workflows/freed-slots", WORKFLOWS, null, null).json().get(0).path("id").asString();
        Response offer =
                send("POST", "/api/v1/workflows/freed-slots/" + slot + "/next-offer", WORKFLOWS, newKey(), null);
        assertThat(offer.field("decision")).isEqualTo("OFFERED");

        JsonNode pending = report("2027-03-01").path("waitlist");
        assertThat(pending.path("slotsFreed").asInt()).isEqualTo(1);
        assertThat(pending.path("offersMade").asInt()).isEqualTo(1);
        assertThat(pending.path("offersAccepted").asInt()).isZero();
        assertThat(pending.path("recoveredCents").asInt()).isZero();
        assertThat(pending.path("waitingNow").asInt()).isEqualTo(2);

        Response accepted = post(appointments(accepts) + "/" + offer.field("appointmentId") + "/confirm", null);
        assertThat(accepted.status()).as(accepted.body()).isEqualTo(200);

        JsonNode done = report("2027-03-01").path("waitlist");
        assertThat(done.path("offersAccepted").asInt()).isEqualTo(1);
        assertThat(done.path("recoveredCents").asInt()).isEqualTo(3500);
        assertThat(done.path("waitingNow").asInt()).isEqualTo(1);
    }

    @Test
    void anOfferNobodyAcceptedRecoversNothing() {
        UUID leaving = newPatient();
        String appointment = bookOk(leaving, "ana", "check-up", tuesday(10, 0));
        post("/api/v1/patients/" + newPatient() + "/waitlist", Map.of("treatmentId", "check-up"));
        clock.set(madrid(2027, 3, 1, 9, 0));
        cancel(leaving, appointment);
        String slot =
                send("GET", "/api/v1/workflows/freed-slots", WORKFLOWS, null, null).json().get(0).path("id").asString();
        send("POST", "/api/v1/workflows/freed-slots/" + slot + "/next-offer", WORKFLOWS, newKey(), null);

        // It runs out, and the core then marks the hold as expired.
        clock.advance(Duration.ofHours(1));

        JsonNode waitlist = report("2027-03-01").path("waitlist");
        assertThat(waitlist.path("offersMade").asInt()).isEqualTo(1);
        assertThat(waitlist.path("offersAccepted").asInt()).isZero();
        assertThat(waitlist.path("recoveredCents").asInt()).isZero();
    }

    @Test
    void theWorkOfTheAgentsIsAddedUp() {
        UUID patient = newPatient();
        reportSteps(
                patient,
                UUID.randomUUID(),
                List.of(
                        modelCall(1000, 100, 0.01),
                        step("blocked", "unbacked_state_claim"),
                        modelCall(2000, 200, 0.02),
                        step("handover", "triage_medical")));
        reportSteps(
                newPatient(),
                UUID.randomUUID(),
                List.of(modelCall(500, 50, 0.03), step("handover", "triage_medical"), step("handover", "core_down")));
        bookOk(patient, "ana", "check-up", tuesday(10, 0));
        bookOk(newPatient(), "ana", "check-up", tuesday(11, 0));

        JsonNode agents = report("2027-03-01").path("agents");

        assertThat(agents.path("conversations").asInt()).isEqualTo(2);
        assertThat(agents.path("modelCalls").asInt()).isEqualTo(3);
        assertThat(agents.path("inputTokens").asInt()).isEqualTo(3500);
        assertThat(agents.path("outputTokens").asInt()).isEqualTo(350);
        assertThat(agents.path("estimatedCostUsd").decimalValue()).isEqualByComparingTo("0.06");
        assertThat(agents.path("estimatedCostPerBookingUsd").decimalValue()).isEqualByComparingTo("0.03");
        assertThat(agents.path("blockedReplies").asInt()).isEqualTo(1);
        assertThat(agents.path("handovers").asInt()).isEqualTo(3);
        assertThat(agents.path("handoversByReason").path("triage_medical").asInt()).isEqualTo(2);
        assertThat(agents.path("handoversByReason").path("core_down").asInt()).isEqualTo(1);
    }

    @Test
    void whatWaitsForSomeoneIsTheSameWhicheverDayIsAskedFor() {
        UUID patient = newPatient();
        reportSteps(patient, UUID.randomUUID(), List.of(step("handover", "core_down")));
        bookOk(patient, "ana", "check-up", tuesday(10, 0));
        bookOk(newPatient(), "ana", "check-up", tuesday(11, 0));

        clock.set(tuesday(10, 30));

        for (String date : List.of("2027-03-01", TUESDAY, "2027-04-01")) {
            JsonNode attention = report(date).path("attention");
            assertThat(attention.path("openHandovers").asInt()).as(date).isEqualTo(1);
            // Only the first one has started.
            assertThat(attention.path("awaitingOutcome").asInt()).as(date).isEqualTo(1);
        }
    }

    @Test
    void withoutADateItIsTodayAtTheClinic() {
        // 23:30 UTC on Monday is already Tuesday in Madrid.
        clock.set(Instant.parse("2027-03-01T23:30:00Z"));

        Response report = send("GET", REPORT, STAFF, null, null);
        Response agenda = send("GET", "/api/v1/staff/agenda", STAFF, null, null);

        assertThat(report.field("date")).isEqualTo(TUESDAY);
        assertThat(agenda.status()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"nonsense", "2027-13-01", "2027-02-30", "'", "2027-03-02T10:00:00Z"})
    void aDateThatIsNotOneIsRefused(String date) {
        for (String path : List.of(REPORT, "/api/v1/staff/agenda")) {
            Response response =
                    send("GET", path + "?date=" + java.net.URLEncoder.encode(date, java.nio.charset.StandardCharsets.UTF_8), STAFF, null, null);

            assertThat(response.status()).as(path).isEqualTo(400);
            assertThat(response.code()).as(path).isEqualTo("VALIDATION_FAILED");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"2037-03-02", "2017-02-28", "%2B999999999-12-31", "-999999999-01-01", "0001-01-01"})
    void aDateTooFarAwayIsRefusedBeforeReachingTheDatabase(String date) {
        for (String path : List.of(REPORT, "/api/v1/staff/agenda")) {
            Response response = send("GET", path + "?date=" + date, STAFF, null, null);

            assertThat(response.status()).as(path).isEqualTo(400);
            assertThat(response.code()).as(path).isEqualTo("DATE_RANGE_INVALID");
        }
    }

    @Test
    void theAgendaListsTheDayInOrderWithWhoIsComing() {
        UUID later = newPatient();
        bookOk(later, "ana", "filling", tuesday(12, 0));
        UUID first = newPatient();
        String appointment = bookOk(first, "ana", "check-up", tuesday(10, 0));
        UUID cancels = newPatient();
        cancel(cancels, bookOk(cancels, "ana", "check-up", tuesday(11, 0)));
        bookOk(newPatient(), "ana", "check-up", madrid(2027, 3, 4, 10, 0));

        JsonNode agenda = agenda(TUESDAY);

        assertThat(agenda).hasSize(3);
        JsonNode entry = agenda.get(0);
        assertThat(entry.path("appointmentId").asString()).isEqualTo(appointment);
        assertThat(entry.path("patientId").asString()).isEqualTo(first.toString());
        assertThat(entry.path("patientName").asString()).isEqualTo("Test Patient");
        assertThat(entry.path("patientPhone").asString()).startsWith("+3460");
        assertThat(entry.path("practitionerId").asString()).isEqualTo("ana");
        assertThat(entry.path("treatmentId").asString()).isEqualTo("check-up");
        assertThat(entry.path("startsAt").asString()).isEqualTo(tuesday(10, 0).toString());
        assertThat(entry.path("endsAt").asString()).isEqualTo(tuesday(10, 30).toString());
        assertThat(entry.path("status").asString()).isEqualTo("CONFIRMED");
        assertThat(entry.path("priceCents").asInt()).isEqualTo(3500);
        assertThat(agenda.get(1).path("status").asString()).isEqualTo("CANCELLED");
        assertThat(agenda.get(2).path("patientId").asString()).isEqualTo(later.toString());
    }

    @Test
    void onlyAppointmentsThatStartedAwaitAnOutcome() {
        UUID patient = newPatient();
        String started = bookOk(patient, "ana", "check-up", tuesday(10, 0));
        bookOk(newPatient(), "ana", "check-up", tuesday(12, 0));
        String older = bookOk(newPatient(), "ben", "check-up", madrid(2027, 3, 1, 11, 0));
        clock.set(tuesday(10, 0));

        Response response = send("GET", "/api/v1/staff/appointments/awaiting-outcome", STAFF, null, null);

        assertThat(response.json())
                .extracting(entry -> entry.path("appointmentId").asString())
                .containsExactly(older, started);

        staffOutcome(started, "complete");

        assertThat(send("GET", "/api/v1/staff/appointments/awaiting-outcome", STAFF, null, null).json())
                .extracting(entry -> entry.path("appointmentId").asString())
                .containsExactly(older);
    }

    @Test
    void onlyTheStaffCanReadThis() {
        List<String> paths =
                List.of(REPORT, "/api/v1/staff/agenda", "/api/v1/staff/appointments/awaiting-outcome");

        for (String path : paths) {
            assertThat(send("GET", path, AGENTS, null, null).status()).as(path).isEqualTo(403);
            assertThat(send("GET", path, WORKFLOWS, null, null).status()).as(path).isEqualTo(403);
            assertThat(send("GET", path, null, null, null).status()).as(path).isEqualTo(401);
            assertThat(send("GET", path, "wrong", null, null).status()).as(path).isEqualTo(401);
        }
    }

    @Test
    void readingLeavesATrail() {
        report(TUESDAY);
        agenda(TUESDAY);
        send("GET", "/api/v1/staff/appointments/awaiting-outcome", STAFF, null, null);

        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'report.read' AND entity_id = '" + TUESDAY + "'"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'agenda.read' AND actor = 'staff'"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'agenda.awaiting-outcome'")).isEqualTo(1);
    }

    @Test
    void readingChangesNoFigure() {
        bookOk(newPatient(), "ana", "check-up", tuesday(10, 0));
        JsonNode before = report(TUESDAY);

        JsonNode after = report(TUESDAY);

        assertThat(after.path("agenda")).isEqualTo(before.path("agenda"));
        assertThat(after.path("activity").path("refused")).isEqualTo(before.path("activity").path("refused"));
    }
}
