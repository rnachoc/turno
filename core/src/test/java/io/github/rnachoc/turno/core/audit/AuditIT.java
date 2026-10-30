package io.github.rnachoc.turno.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rnachoc.turno.core.booking.BookingService;
import io.github.rnachoc.turno.core.support.ApiIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Checks that the trail tells the truth: every accepted change is there, nothing that was rolled
 * back is, and refusals are recorded with their reason.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
class AuditIT extends ApiIntegrationTest {

    private static final Instant SLOT = madrid(2027, 3, 9, 9, 0);

    @Autowired
    private BookingService bookings;

    private List<String> actionsOn(String entityId) {
        Response response = send("GET", "/api/v1/staff/audit?entityId=" + entityId, STAFF, null, null);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json().valueStream()
                .map(event -> event.path("action").asString())
                .toList()
                .reversed();
    }

    @Test
    void recordsTheWholeLifeOfAnAppointmentWithItsAuthors() {
        UUID patient = newPatient();
        String id = bookOk(patient, "ana", "filling", SLOT);
        post(appointments(patient) + "/" + id + "/reschedule",
                java.util.Map.of("practitionerId", "ana", "startsAt", SLOT.plus(Duration.ofHours(1)).toString()));
        clock.set(SLOT.plus(Duration.ofHours(1)));
        staffPost("/api/v1/staff/appointments/" + id + "/no-show");

        assertThat(actionsOn(id))
                .containsExactly(
                        "appointment.hold",
                        "appointment.confirm",
                        "appointment.reschedule",
                        "appointment.no-show");
        Response events = send("GET", "/api/v1/staff/audit?entityId=" + id, STAFF, null, null);
        JsonNode newest = events.json().get(0);
        assertThat(newest.path("actor").asString()).isEqualTo("staff");
        assertThat(newest.path("patientId").asString()).isEqualTo(patient.toString());
        assertThat(newest.path("details").path("depositForfeited").asBoolean()).isTrue();
        assertThat(events.json().get(3).path("actor").asString()).isEqualTo("agents");
    }

    @Test
    void readingAppointmentsLeavesATrace() {
        UUID patient = newPatient();
        String id = holdOk(patient, "ana", "check-up", SLOT);

        get(appointments(patient));
        get(appointments(patient) + "/" + id);

        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'appointment.list' "
                        + "AND actor = 'agents' AND patient_id = '" + patient + "'"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'appointment.read' "
                        + "AND entity_id = '" + id + "' AND patient_id = '" + patient + "'"))
                .isEqualTo(1);
    }

    @Test
    void aRefusedOperationLeavesARefusalAndNoAcceptedEntry() {
        UUID first = newPatient();
        UUID second = newPatient();
        holdOk(first, "ana", "check-up", SLOT);

        Response refused = hold(second, "ana", "check-up", SLOT);

        assertThat(refused.code()).isEqualTo("SLOT_UNAVAILABLE");
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'appointment.hold'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_event WHERE outcome = 'REJECTED' "
                        + "AND action = 'POST /api/v1/patients/{patientId}/appointments' "
                        + "AND actor = 'agents' AND details->>'reason' = 'SLOT_UNAVAILABLE' "
                        + "AND patient_id = '" + second + "'"))
                .isEqualTo(1);
    }

    @Test
    void anAttemptThroughTheWrongPatientIsRecordedAgainstThatRoute() {
        UUID owner = newPatient();
        UUID other = newPatient();
        String id = holdOk(owner, "ana", "check-up", SLOT);

        post(appointments(other) + "/" + id + "/cancel", null);

        assertThat(count("SELECT count(*) FROM audit_event WHERE outcome = 'REJECTED' "
                        + "AND entity_id = '" + id + "' AND patient_id = '" + other + "' "
                        + "AND details->>'reason' = 'APPOINTMENT_NOT_FOUND'"))
                .isEqualTo(1);
    }

    @Test
    void theAcceptedEntryOfAFailedOperationIsRolledBackWithIt() {
        UUID first = newPatient();
        holdOk(first, "ana", "check-up", SLOT);
        clock.advance(Duration.ofMinutes(5));
        UUID second = newPatient();
        // This operation marks the old hold as expired before it is refused for another reason.
        holdOk(second, "ana", "check-up", SLOT.plus(Duration.ofHours(4)));

        Response refused = hold(second, "ana", "check-up", SLOT);

        assertThat(refused.code()).isEqualTo("HOLD_LIMIT_REACHED");
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'appointment.expire'"))
                .as("the expiry rolled back along with the refused operation")
                .isZero();
        assertThat(count("SELECT count(*) FROM appointment WHERE status = 'EXPIRED'")).isZero();
    }

    @Test
    void theJobMarksExpiredHoldsOnceAndRecordsThem() {
        UUID patient = newPatient();
        String expired = holdOk(patient, "ana", "check-up", SLOT);
        clock.advance(Duration.ofMinutes(5).minusMillis(1));
        assertThat(bookings.expireDueHolds()).as("one millisecond early").isZero();

        clock.advance(Duration.ofMillis(1));
        assertThat(bookings.expireDueHolds()).isEqualTo(1);
        assertThat(bookings.expireDueHolds()).as("nothing left to do").isZero();

        assertThat(actionsOn(expired)).containsExactly("appointment.hold", "appointment.expire");
        Response events = send("GET", "/api/v1/staff/audit?entityId=" + expired, STAFF, null, null);
        assertThat(events.json().get(0).path("actor").asString()).isEqualTo("system");
        assertThat(get(appointments(patient) + "/" + expired).field("status")).isEqualTo("EXPIRED");
    }

    @Test
    void theJobLeavesConfirmedAppointmentsAlone() {
        UUID patient = newPatient();
        String id = bookOk(patient, "ana", "check-up", SLOT);

        clock.advance(Duration.ofHours(1));

        assertThat(bookings.expireDueHolds()).isZero();
        assertThat(get(appointments(patient) + "/" + id).field("status")).isEqualTo("CONFIRMED");
    }

    @Test
    void theQueryFiltersByPatientAndCapsItsSize() {
        UUID patient = newPatient();
        UUID other = newPatient();
        holdOk(patient, "ana", "check-up", SLOT);
        holdOk(other, "ana", "check-up", SLOT.plus(Duration.ofHours(1)));

        Response filtered = send("GET", "/api/v1/staff/audit?patientId=" + patient, STAFF, null, null);
        assertThat(filtered.json().valueStream().map(e -> e.path("patientId").asString()))
                .containsOnly(patient.toString());

        Response limited = send("GET", "/api/v1/staff/audit?limit=1", STAFF, null, null);
        assertThat(limited.json()).hasSize(1);

        Response negative = send("GET", "/api/v1/staff/audit?limit=-5", STAFF, null, null);
        assertThat(negative.status()).isEqualTo(200);
        assertThat(negative.json()).hasSize(1);
    }
}
