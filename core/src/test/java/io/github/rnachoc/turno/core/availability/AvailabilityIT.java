package io.github.rnachoc.turno.core.availability;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rnachoc.turno.core.support.ApiIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Checks that availability subtracts what is taken, and that what it offers can really be booked.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
class AvailabilityIT extends ApiIntegrationTest {

    private static final String TUESDAY = "from=2027-03-02&to=2027-03-02";
    private static final Instant NINE = tuesday(9, 0);

    private List<JsonNode> slots(String query) {
        Response response = get("/api/v1/availability?" + query);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json().valueStream().toList();
    }

    private List<String> anaCheckUpStarts() {
        return slots("treatmentId=check-up&practitionerId=ana&" + TUESDAY).stream()
                .map(slot -> slot.path("startsAt").asString())
                .toList();
    }

    @Test
    void offersTheFreeSlotsOfEveryPractitionerOrderedByTime() {
        Instant monday = madrid(2027, 3, 8, 9, 0);

        List<JsonNode> slots = slots("treatmentId=check-up&from=2027-03-08&to=2027-03-08");

        assertThat(slots.stream().limit(2).map(s -> s.path("practitionerId").asString()))
                .containsExactly("ana", "ben");
        assertThat(slots.stream().limit(2).map(s -> s.path("startsAt").asString()))
                .containsOnly(monday.toString());
    }

    @Test
    void takenAndOverlappingSlotsDisappearAndAdjacentOnesStay() {
        holdOk(newPatient(), "ana", "filling", tuesday(10, 0));

        assertThat(anaCheckUpStarts())
                .contains(tuesday(9, 30).toString(), tuesday(10, 45).toString())
                .doesNotContain(
                        tuesday(9, 45).toString(),
                        tuesday(10, 0).toString(),
                        tuesday(10, 30).toString());
    }

    @Test
    void anAppointmentOfOnePractitionerDoesNotHideAnother() {
        Instant monday = madrid(2027, 3, 8, 9, 0);
        holdOk(newPatient(), "ana", "check-up", monday);

        assertThat(slots("treatmentId=check-up&from=2027-03-08&to=2027-03-08"))
                .anyMatch(s -> s.path("practitionerId").asString().equals("ben")
                        && s.path("startsAt").asString().equals(monday.toString()))
                .noneMatch(s -> s.path("practitionerId").asString().equals("ana")
                        && s.path("startsAt").asString().equals(monday.toString()));
    }

    @Test
    void aSlotComesBackWhenItsHoldExpiresOrIsReleased() {
        UUID patient = newPatient();
        String id = holdOk(patient, "ana", "check-up", NINE);
        assertThat(anaCheckUpStarts()).doesNotContain(NINE.toString());

        clock.advance(Duration.ofMinutes(5).minusMillis(1));
        assertThat(anaCheckUpStarts()).doesNotContain(NINE.toString());

        clock.advance(Duration.ofMillis(1));
        assertThat(anaCheckUpStarts()).contains(NINE.toString());

        String again = holdOk(patient, "ana", "check-up", NINE);
        assertThat(anaCheckUpStarts()).doesNotContain(NINE.toString());
        post(appointments(patient) + "/" + again + "/release", null);
        assertThat(anaCheckUpStarts()).contains(NINE.toString());
        assertThat(id).isNotEqualTo(again);
    }

    @Test
    void cancellingBringsASlotBack() {
        UUID patient = newPatient();
        String id = bookOk(patient, "ana", "check-up", NINE);

        post(appointments(patient) + "/" + id + "/cancel", null);

        assertThat(anaCheckUpStarts()).contains(NINE.toString());
    }

    @Test
    void anAppointmentCrossingTheLimitsOfTheRangeStillCounts() {
        Instant lastOfMonday = madrid(2027, 3, 8, 19, 30);
        holdOk(newPatient(), "ana", "check-up", lastOfMonday);

        assertThat(slots("treatmentId=check-up&practitionerId=ana&from=2027-03-08&to=2027-03-08"))
                .noneMatch(s -> s.path("startsAt").asString().equals(lastOfMonday.toString()));
    }

    @Test
    void everySlotOfferedCanActuallyBeHeld() {
        List<JsonNode> offered = slots("treatmentId=filling&" + TUESDAY);
        assertThat(offered).isNotEmpty();

        for (JsonNode slot : offered) {
            Response held =
                    hold(
                            newPatient(),
                            slot.path("practitionerId").asString(),
                            slot.path("treatmentId").asString(),
                            Instant.parse(slot.path("startsAt").asString()));

            // Each hold can take neighbouring slots away, but it must never be refused by the rules.
            assertThat(held.status()).as(held.body()).isIn(201, 409);
        }
    }

    @Test
    void refusesDatesAtTheLimitsOfTheCalendar() {
        for (String range :
                new String[] {
                    "from=%2B999999999-12-30&to=%2B999999999-12-31", "from=-999999999-01-01&to=-999999999-01-02"
                }) {
            Response response = get("/api/v1/availability?treatmentId=check-up&" + range);

            assertThat(response.status()).as(range).isEqualTo(400);
            assertThat(response.code()).as(range).isEqualTo("DATE_RANGE_INVALID");
        }
    }

    @Test
    void refusesInvalidQueries() {
        assertThat(get("/api/v1/availability?treatmentId=check-up&from=2027-03-03&to=2027-03-02").code())
                .as("inverted range")
                .isEqualTo("DATE_RANGE_INVALID");
        assertThat(get("/api/v1/availability?treatmentId=check-up&from=2027-03-01&to=2027-04-01").code())
                .as("32 days")
                .isEqualTo("DATE_RANGE_INVALID");
        assertThat(get("/api/v1/availability?treatmentId=check-up&from=2027-03-01&to=2027-03-31").status())
                .as("31 days")
                .isEqualTo(200);
        assertThat(get("/api/v1/availability?treatmentId=laser&" + TUESDAY).code()).isEqualTo("TREATMENT_NOT_FOUND");
        assertThat(get("/api/v1/availability?treatmentId=check-up&practitionerId=nobody&" + TUESDAY).code())
                .isEqualTo("PRACTITIONER_NOT_FOUND");
        assertThat(get("/api/v1/availability?treatmentId=filling&practitionerId=ben&" + TUESDAY).code())
                .isEqualTo("PRACTITIONER_CANNOT_PERFORM");
        assertThat(get("/api/v1/availability?treatmentId=check-up&from=2027-03-02").status())
                .as("missing parameter")
                .isEqualTo(400);
    }

    @Test
    void theClinicEndpointDescribesTheOffer() {
        Response clinic = get("/api/v1/clinic");

        assertThat(clinic.field("id")).isEqualTo("test-clinic");
        assertThat(clinic.field("timezone")).isEqualTo("Europe/Madrid");
        assertThat(clinic.field("emergencyNumber")).isEqualTo("112");
        assertThat(clinic.json().path("treatments")).hasSize(2);
    }
}
