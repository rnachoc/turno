package io.github.rnachoc.turno.core.booking;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rnachoc.turno.core.support.ApiIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Walks the life of an appointment through the API, with each rule tested on its exact boundary.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
class BookingLifecycleIT extends ApiIntegrationTest {

    /** Far enough ahead to fall inside the free cancellation period. */
    private static final Instant NEXT_WEEK = madrid(2027, 3, 9, 9, 0);

    @Test
    void holdThenConfirmBooksTheAppointmentAtTheClinicPrice() {
        UUID patient = newPatient();

        Response held = hold(patient, "ana", "filling", NEXT_WEEK);

        assertThat(held.status()).isEqualTo(201);
        assertThat(held.field("status")).isEqualTo("HOLD");
        assertThat(held.field("startsAt")).isEqualTo("2027-03-09T08:00:00Z");
        assertThat(held.field("endsAt")).isEqualTo("2027-03-09T08:45:00Z");
        assertThat(held.field("holdExpiresAt")).isEqualTo(clock.instant().plus(Duration.ofMinutes(5)).toString());
        assertThat(held.json().path("priceCents").asInt()).isEqualTo(8000);
        assertThat(held.json().path("depositCents").asInt()).isEqualTo(1500);

        Response confirmed = post(appointments(patient) + "/" + held.field("id") + "/confirm", null);

        assertThat(confirmed.status()).isEqualTo(200);
        assertThat(confirmed.field("status")).isEqualTo("CONFIRMED");
        assertThat(confirmed.json().path("holdExpiresAt").isNull()).isTrue();
        assertThat(get(appointments(patient) + "/" + held.field("id")).field("status")).isEqualTo("CONFIRMED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"priceCents", "depositCents", "status", "patientId", "id", "holdExpiresAt", "depositForfeited"})
    void aProposalCannotDictateWhatTheCoreDecides(String injected) {
        UUID patient = newPatient();
        Map<String, Object> body = new HashMap<>(holdBody("ana", "filling", NEXT_WEEK));
        body.put(injected, "0");

        Response response = post(appointments(patient), body);

        assertThat(response.status()).isEqualTo(400);
        assertThat(count("SELECT count(*) FROM appointment")).isZero();
    }

    @Test
    void anAppointmentKeepsTheAgreedPriceEvenIfTheClinicPriceDiffers() {
        UUID patient = newPatient();
        String id = bookOk(patient, "ana", "filling", NEXT_WEEK);
        jdbc.sql("UPDATE appointment SET price_cents = 7000 WHERE id = '" + id + "'").update();

        Response moved = post(appointments(patient) + "/" + id + "/reschedule", move("ana", NEXT_WEEK.plus(Duration.ofHours(1))));

        assertThat(moved.status()).isEqualTo(200);
        assertThat(moved.json().path("priceCents").asInt()).isEqualTo(7000);
    }

    @Test
    void refusesWhatTheClinicRulesDoNotAllow() {
        UUID patient = newPatient();

        assertThat(hold(patient, "ana", "check-up", tuesday(9, 7)).code()).isEqualTo("SLOT_NOT_BOOKABLE");
        assertThat(hold(patient, "ana", "check-up", tuesday(14, 30)).code()).isEqualTo("SLOT_NOT_BOOKABLE");
        assertThat(hold(patient, "ana", "check-up", madrid(2027, 3, 3, 9, 0)).code())
                .as("clinic closure")
                .isEqualTo("SLOT_NOT_BOOKABLE");
        assertThat(hold(patient, "ana", "check-up", madrid(2027, 3, 1, 9, 45)).code())
                .as("under the minimum notice")
                .isEqualTo("SLOT_NOT_BOOKABLE");
        assertThat(hold(patient, "ana", "check-up", madrid(2027, 3, 1, 7, 0)).code())
                .as("in the past")
                .isEqualTo("SLOT_NOT_BOOKABLE");
        assertThat(hold(patient, "ben", "filling", NEXT_WEEK).code()).isEqualTo("PRACTITIONER_CANNOT_PERFORM");
        assertThat(hold(patient, "nobody", "check-up", NEXT_WEEK).code()).isEqualTo("PRACTITIONER_NOT_FOUND");
        assertThat(hold(patient, "ana", "laser", NEXT_WEEK).code()).isEqualTo("TREATMENT_NOT_FOUND");
        assertThat(hold(UUID.randomUUID(), "ana", "check-up", NEXT_WEEK).code()).isEqualTo("PATIENT_NOT_FOUND");
        assertThat(count("SELECT count(*) FROM appointment")).isZero();
    }

    @Test
    void acceptsAStartWrittenInAnyTimeZoneOffset() {
        UUID patient = newPatient();
        Map<String, Object> body =
                Map.of("practitionerId", "ana", "treatmentId", "check-up", "startsAt", "2027-03-09T09:00:00+01:00");

        assertThat(post(appointments(patient), body).status()).isEqualTo(201);
    }

    @Test
    void anAppointmentMayStartExactlyWhenTheLastOneEnds() {
        Instant middle = NEXT_WEEK.plus(Duration.ofHours(1));
        holdOk(newPatient(), "ana", "check-up", middle);

        assertThat(hold(newPatient(), "ana", "check-up", middle.plus(Duration.ofMinutes(30))).status()).isEqualTo(201);
        assertThat(hold(newPatient(), "ana", "check-up", middle.minus(Duration.ofMinutes(30))).status()).isEqualTo(201);
    }

    @Test
    void aPartialOverlapIsAConflict() {
        holdOk(newPatient(), "ana", "filling", NEXT_WEEK);

        Response response = hold(newPatient(), "ana", "check-up", NEXT_WEEK.plus(Duration.ofMinutes(30)));

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("SLOT_UNAVAILABLE");
    }

    @Test
    void differentPractitionersCanWorkAtTheSameTimeButAPatientCannotBeWithBoth() {
        Instant wednesday = madrid(2027, 3, 10, 9, 0);
        UUID patient = newPatient();
        bookOk(patient, "ana", "check-up", wednesday);

        assertThat(hold(newPatient(), "ben", "check-up", wednesday).status()).isEqualTo(201);
        assertThat(hold(patient, "ben", "check-up", wednesday.plus(Duration.ofMinutes(15))).code())
                .isEqualTo("PATIENT_TIME_CONFLICT");
    }

    @Test
    void aPatientCanOnlyHoldOneSlotAtATime() {
        UUID patient = newPatient();
        String first = holdOk(patient, "ana", "check-up", NEXT_WEEK);

        assertThat(hold(patient, "ana", "check-up", NEXT_WEEK.plus(Duration.ofHours(1))).code())
                .isEqualTo("HOLD_LIMIT_REACHED");

        post(appointments(patient) + "/" + first + "/release", null);
        assertThat(hold(patient, "ana", "check-up", NEXT_WEEK.plus(Duration.ofHours(1))).status()).isEqualTo(201);
    }

    @Test
    void aConfirmedAppointmentDoesNotCountAsAHold() {
        UUID patient = newPatient();
        bookOk(patient, "ana", "check-up", NEXT_WEEK);

        assertThat(hold(patient, "ana", "check-up", NEXT_WEEK.plus(Duration.ofHours(1))).status()).isEqualTo(201);
    }

    @Test
    void aHoldLastsUntilJustBeforeItsExpiry() {
        UUID patient = newPatient();
        String id = holdOk(patient, "ana", "check-up", NEXT_WEEK);
        String path = appointments(patient) + "/" + id;

        clock.advance(Duration.ofMinutes(5).minusMillis(1));
        assertThat(hold(newPatient(), "ana", "check-up", NEXT_WEEK).code())
                .as("one millisecond before expiring, the slot is still taken")
                .isEqualTo("SLOT_UNAVAILABLE");
        assertThat(post(path + "/confirm", null).status()).isEqualTo(200);
    }

    @Test
    void aHoldExpiresAtTheExactInstantOfItsExpiry() {
        UUID patient = newPatient();
        String id = holdOk(patient, "ana", "check-up", NEXT_WEEK);
        String path = appointments(patient) + "/" + id;

        clock.advance(Duration.ofMinutes(5));

        assertThat(get(path).field("status")).as("reads as expired before any job runs").isEqualTo("EXPIRED");
        assertThat(post(path + "/confirm", null).code()).isEqualTo("HOLD_EXPIRED");
        assertThat(post(path + "/release", null).code()).isEqualTo("HOLD_EXPIRED");
        assertThat(hold(newPatient(), "ana", "check-up", NEXT_WEEK).status()).isEqualTo(201);
        assertThat(post(path + "/confirm", null).code())
                .as("still refused once the new hold has marked it expired")
                .isEqualTo("HOLD_EXPIRED");
    }

    @Test
    void anExpiredHoldDoesNotCountAgainstThePatientsLimit() {
        UUID patient = newPatient();
        holdOk(patient, "ana", "check-up", NEXT_WEEK);

        clock.advance(Duration.ofMinutes(5));

        assertThat(hold(patient, "ana", "check-up", NEXT_WEEK.plus(Duration.ofHours(1))).status()).isEqualTo(201);
    }

    @Test
    void aPatientCanTakeTheSameSlotAgainAfterTheirHoldExpired() {
        UUID patient = newPatient();
        holdOk(patient, "ana", "check-up", NEXT_WEEK);

        clock.advance(Duration.ofMinutes(5));

        assertThat(hold(patient, "ana", "check-up", NEXT_WEEK).status()).isEqualTo(201);
    }

    @Test
    void releasingAHoldFreesTheSlotAtOnce() {
        UUID patient = newPatient();
        String id = holdOk(patient, "ana", "check-up", NEXT_WEEK);

        Response released = post(appointments(patient) + "/" + id + "/release", null);

        assertThat(released.field("status")).isEqualTo("RELEASED");
        assertThat(hold(newPatient(), "ana", "check-up", NEXT_WEEK).status()).isEqualTo(201);
    }

    @Test
    void cancellingExactlyAtTheLimitIsFreeAndOneMillisecondLaterIsNot() {
        UUID onTime = newPatient();
        UUID late = newPatient();
        Instant start = NEXT_WEEK;
        String onTimeId = bookOk(onTime, "ana", "filling", start);
        String lateId = bookOk(late, "ana", "filling", start.plus(Duration.ofHours(1)));

        clock.set(start.minus(Duration.ofHours(24)));
        Response free = post(appointments(onTime) + "/" + onTimeId + "/cancel", null);
        assertThat(free.field("status")).isEqualTo("CANCELLED");
        assertThat(free.json().path("depositForfeited").asBoolean()).isFalse();

        clock.set(start.plus(Duration.ofHours(1)).minus(Duration.ofHours(24)).plusMillis(1));
        Response paid = post(appointments(late) + "/" + lateId + "/cancel", null);
        assertThat(paid.field("status")).isEqualTo("CANCELLED");
        assertThat(paid.json().path("depositForfeited").asBoolean()).isTrue();
    }

    @Test
    void aLateCancellationOfATreatmentWithoutDepositForfeitsNothing() {
        UUID patient = newPatient();
        String id = bookOk(patient, "ana", "check-up", NEXT_WEEK);

        clock.set(NEXT_WEEK.minus(Duration.ofHours(1)));

        Response response = post(appointments(patient) + "/" + id + "/cancel", null);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().path("depositForfeited").asBoolean()).isFalse();
    }

    @Test
    void anAppointmentCanBeCancelledUntilItStartsButNotFromThenOn() {
        UUID early = newPatient();
        UUID late = newPatient();
        String earlyId = bookOk(early, "ana", "check-up", NEXT_WEEK);
        String lateId = bookOk(late, "ana", "check-up", NEXT_WEEK.plus(Duration.ofHours(1)));

        clock.set(NEXT_WEEK.minusMillis(1));
        assertThat(post(appointments(early) + "/" + earlyId + "/cancel", null).status()).isEqualTo(200);

        clock.set(NEXT_WEEK.plus(Duration.ofHours(1)));
        assertThat(post(appointments(late) + "/" + lateId + "/cancel", null).code())
                .isEqualTo("APPOINTMENT_ALREADY_STARTED");
    }

    @Test
    void cancellingFreesTheSlot() {
        UUID patient = newPatient();
        String id = bookOk(patient, "ana", "check-up", NEXT_WEEK);

        post(appointments(patient) + "/" + id + "/cancel", null);

        assertThat(hold(newPatient(), "ana", "check-up", NEXT_WEEK).status()).isEqualTo(201);
    }

    @Test
    void reschedulingMovesTheAppointmentAndKeepsItsPrice() {
        UUID patient = newPatient();
        String id = bookOk(patient, "ana", "filling", NEXT_WEEK);
        Instant target = NEXT_WEEK.plus(Duration.ofHours(1));

        Response moved = post(appointments(patient) + "/" + id + "/reschedule", move("ana", target));

        assertThat(moved.status()).isEqualTo(200);
        assertThat(moved.field("startsAt")).isEqualTo(target.toString());
        assertThat(moved.field("endsAt")).isEqualTo(target.plus(Duration.ofMinutes(45)).toString());
        assertThat(moved.field("status")).isEqualTo("CONFIRMED");
        assertThat(moved.json().path("priceCents").asInt()).isEqualTo(8000);
        assertThat(hold(newPatient(), "ana", "check-up", NEXT_WEEK).status()).as("the old slot is free").isEqualTo(201);
    }

    @Test
    void anAppointmentCanMoveOverItsOwnOldTime() {
        UUID patient = newPatient();
        String id = bookOk(patient, "ana", "filling", NEXT_WEEK);

        Response moved = post(appointments(patient) + "/" + id + "/reschedule", move("ana", NEXT_WEEK.plus(Duration.ofMinutes(15))));

        assertThat(moved.status()).isEqualTo(200);
    }

    @Test
    void reschedulingFollowsTheSameRulesAsBooking() {
        UUID patient = newPatient();
        String id = bookOk(patient, "ana", "filling", NEXT_WEEK);
        String path = appointments(patient) + "/" + id + "/reschedule";
        bookOk(newPatient(), "ana", "check-up", NEXT_WEEK.plus(Duration.ofHours(2)));
        Instant wednesday = madrid(2027, 3, 10, 9, 0);
        bookOk(patient, "ben", "check-up", wednesday);

        assertThat(post(path, move("ana", NEXT_WEEK.plus(Duration.ofHours(2)))).code()).isEqualTo("SLOT_UNAVAILABLE");
        assertThat(post(path, move("ana", wednesday)).code())
                .as("Ana is free, but the patient is with Ben")
                .isEqualTo("PATIENT_TIME_CONFLICT");
        assertThat(post(path, move("ana", NEXT_WEEK.plus(Duration.ofMinutes(7)))).code()).isEqualTo("SLOT_NOT_BOOKABLE");
        assertThat(post(path, move("ben", NEXT_WEEK)).code())
                .as("Ben does not perform fillings")
                .isEqualTo("PRACTITIONER_CANNOT_PERFORM");
        assertThat(post(path, move("nobody", NEXT_WEEK)).code()).isEqualTo("PRACTITIONER_NOT_FOUND");
        assertThat(get(appointments(patient) + "/" + id).field("startsAt")).isEqualTo(NEXT_WEEK.toString());
    }

    @Test
    void reschedulingCannotDodgeTheLateCancellationRule() {
        UUID onTime = newPatient();
        UUID late = newPatient();
        String onTimeId = bookOk(onTime, "ana", "check-up", NEXT_WEEK);
        String lateId = bookOk(late, "ana", "check-up", NEXT_WEEK.plus(Duration.ofHours(1)));
        Instant target = madrid(2027, 3, 16, 9, 0);

        clock.set(NEXT_WEEK.minus(Duration.ofHours(24)));
        assertThat(post(appointments(onTime) + "/" + onTimeId + "/reschedule", move("ana", target)).status()).isEqualTo(200);

        clock.set(NEXT_WEEK.plus(Duration.ofHours(1)).minus(Duration.ofHours(24)).plusMillis(1));
        assertThat(post(appointments(late) + "/" + lateId + "/reschedule", move("ana", target.plus(Duration.ofHours(1)))).code())
                .isEqualTo("RESCHEDULE_WINDOW_CLOSED");
    }

    @Test
    void reschedulingCanTakeAnExpiredHoldsSlot() {
        UUID patient = newPatient();
        String id = bookOk(patient, "ana", "check-up", NEXT_WEEK);
        Instant target = NEXT_WEEK.plus(Duration.ofHours(1));
        holdOk(newPatient(), "ana", "check-up", target);

        clock.advance(Duration.ofMinutes(5));

        assertThat(post(appointments(patient) + "/" + id + "/reschedule", move("ana", target)).status()).isEqualTo(200);
    }

    @Test
    void illegalTransitionsAreRefused() {
        UUID patient = newPatient();
        String held = holdOk(patient, "ana", "check-up", NEXT_WEEK);
        String path = appointments(patient) + "/" + held;

        assertThat(post(path + "/cancel", null).code()).as("cancel a hold").isEqualTo("INVALID_STATE");
        assertThat(post(path + "/reschedule", move("ana", NEXT_WEEK)).code()).as("move a hold").isEqualTo("INVALID_STATE");

        post(path + "/confirm", null);
        assertThat(post(path + "/confirm", null).code()).as("confirm twice").isEqualTo("INVALID_STATE");
        assertThat(post(path + "/release", null).code()).as("release a booking").isEqualTo("INVALID_STATE");

        post(path + "/cancel", null);
        assertThat(post(path + "/cancel", null).code()).as("cancel twice").isEqualTo("INVALID_STATE");
        assertThat(post(path + "/confirm", null).code()).as("revive a cancellation").isEqualTo("INVALID_STATE");
        assertThat(post(path + "/reschedule", move("ana", NEXT_WEEK)).code()).isEqualTo("INVALID_STATE");
    }

    @Test
    void anAppointmentCannotBeReachedThroughAnotherPatient() {
        UUID owner = newPatient();
        UUID other = newPatient();
        String id = holdOk(owner, "ana", "check-up", NEXT_WEEK);
        String foreign = appointments(other) + "/" + id;

        assertThat(get(foreign).status()).isEqualTo(404);
        assertThat(post(foreign + "/confirm", null).code()).isEqualTo("APPOINTMENT_NOT_FOUND");
        assertThat(post(foreign + "/release", null).code()).isEqualTo("APPOINTMENT_NOT_FOUND");
        assertThat(post(foreign + "/cancel", null).code()).isEqualTo("APPOINTMENT_NOT_FOUND");
        assertThat(post(foreign + "/reschedule", move("ana", NEXT_WEEK)).code()).isEqualTo("APPOINTMENT_NOT_FOUND");
        assertThat(get(appointments(other)).json()).isEmpty();
        assertThat(get(appointments(owner) + "/" + id).field("status")).as("nothing changed").isEqualTo("HOLD");
    }

    @Test
    void listsOnlyThePatientsOwnAppointmentsInOrder() {
        UUID patient = newPatient();
        bookOk(patient, "ana", "check-up", NEXT_WEEK.plus(Duration.ofHours(1)));
        bookOk(patient, "ana", "check-up", NEXT_WEEK);
        bookOk(newPatient(), "ana", "check-up", NEXT_WEEK.plus(Duration.ofHours(2)));

        assertThat(get(appointments(patient)).json().valueStream().map(a -> a.path("startsAt").asString()).toList())
                .containsExactly(NEXT_WEEK.toString(), NEXT_WEEK.plus(Duration.ofHours(1)).toString());
    }

    @Test
    void staffCloseAppointmentsOnlyOnceTheyHaveStarted() {
        UUID patient = newPatient();
        String id = bookOk(patient, "ana", "filling", NEXT_WEEK);
        String staffPath = "/api/v1/staff/appointments/" + id;

        clock.set(NEXT_WEEK.minusMillis(1));
        assertThat(staffPost(staffPath + "/no-show").code()).isEqualTo("APPOINTMENT_NOT_STARTED");
        assertThat(staffPost(staffPath + "/complete").code()).isEqualTo("APPOINTMENT_NOT_STARTED");

        clock.set(NEXT_WEEK);
        Response noShow = staffPost(staffPath + "/no-show");
        assertThat(noShow.field("status")).isEqualTo("NO_SHOW");
        assertThat(noShow.json().path("depositForfeited").asBoolean()).isTrue();
        assertThat(staffPost(staffPath + "/complete").code()).isEqualTo("INVALID_STATE");
    }

    @Test
    void completingKeepsTheDepositAndAHoldCannotBeClosed() {
        UUID patient = newPatient();
        String booked = bookOk(patient, "ana", "filling", NEXT_WEEK);
        String held = holdOk(patient, "ana", "filling", NEXT_WEEK.plus(Duration.ofHours(1)));

        clock.set(NEXT_WEEK.plus(Duration.ofHours(2)));

        Response completed = staffPost("/api/v1/staff/appointments/" + booked + "/complete");
        assertThat(completed.field("status")).isEqualTo("COMPLETED");
        assertThat(completed.json().path("depositForfeited").asBoolean()).isFalse();
        assertThat(staffPost("/api/v1/staff/appointments/" + held + "/complete").code()).isEqualTo("INVALID_STATE");
        assertThat(staffPost("/api/v1/staff/appointments/" + UUID.randomUUID() + "/no-show").code())
                .isEqualTo("APPOINTMENT_NOT_FOUND");
    }

    private static Map<String, Object> move(String practitioner, Instant startsAt) {
        return Map.of("practitionerId", practitioner, "startsAt", startsAt.toString());
    }
}
