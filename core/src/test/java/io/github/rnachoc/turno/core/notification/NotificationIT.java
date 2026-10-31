package io.github.rnachoc.turno.core.notification;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rnachoc.turno.core.support.ApiIntegrationTest;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The notices of offers, from the moment an offer is made until someone has sent them.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
class NotificationIT extends ApiIntegrationTest {

    private static final String PENDING = "/api/v1/notifications/pending";

    @BeforeEach
    void withinContactHours() {
        clock.set(madrid(2027, 3, 1, 9, 0));
    }

    /** Frees a slot with someone waiting for it, and has the worker make the offer. */
    private UUID offerToSomeone() {
        UUID leaving = newPatient();
        String appointment = bookOk(leaving, "ana", "check-up", tuesday(10, 0));
        UUID waiting = newPatient();
        assertThat(post("/api/v1/patients/" + waiting + "/waitlist", Map.of("treatmentId", "check-up")).status())
                .isEqualTo(201);
        assertThat(post(appointments(leaving) + "/" + appointment + "/cancel", null).status()).isEqualTo(200);
        String slot =
                send("GET", "/api/v1/workflows/freed-slots", WORKFLOWS, null, null).json().get(0).path("id").asString();
        Response offer =
                send("POST", "/api/v1/workflows/freed-slots/" + slot + "/next-offer", WORKFLOWS, newKey(), null);
        assertThat(offer.field("decision")).isEqualTo("OFFERED");
        return waiting;
    }

    @Test
    void anOfferLeavesANoticeWithWhatIsNeededToWriteIt() {
        UUID patient = offerToSomeone();

        JsonNode notice = get(PENDING).json().get(0);

        assertThat(notice.path("kind").asString()).isEqualTo("SLOT_OFFER");
        assertThat(notice.path("patientId").asString()).isEqualTo(patient.toString());
        assertThat(notice.path("phone").asString()).startsWith("+3460");
        assertThat(notice.path("language").asString()).isEqualTo("es");
        assertThat(notice.path("treatmentId").asString()).isEqualTo("check-up");
        assertThat(notice.path("practitionerId").asString()).isEqualTo("ana");
        assertThat(notice.path("startsAt").asString()).isEqualTo(tuesday(10, 0).toString());
        assertThat(notice.path("priceCents").asInt()).isEqualTo(3500);
        assertThat(get(appointments(patient) + "/" + notice.path("appointmentId").asString()).field("status"))
                .isEqualTo("HOLD");
    }

    @Test
    void cancellingWithNobodyWaitingLeavesNoNotice() {
        UUID patient = newPatient();
        String appointment = bookOk(patient, "ana", "check-up", tuesday(10, 0));

        post(appointments(patient) + "/" + appointment + "/cancel", null);

        assertThat(get(PENDING).json()).isEmpty();
    }

    @Test
    void aNoticeThatWentOutIsNotListedAgain() {
        offerToSomeone();
        String id = get(PENDING).json().get(0).path("id").asString();

        Response sent = post("/api/v1/notifications/" + id + "/sent", null);

        assertThat(sent.status()).isEqualTo(200);
        assertThat(get(PENDING).json()).isEmpty();
    }

    @Test
    void sendingTwiceIsRefusedAndARetryGetsTheSameAnswer() {
        offerToSomeone();
        String path = "/api/v1/notifications/" + get(PENDING).json().get(0).path("id").asString() + "/sent";
        String key = newKey();

        assertThat(send("POST", path, AGENTS, key, null).status()).isEqualTo(200);
        assertThat(send("POST", path, AGENTS, key, null).status()).isEqualTo(200);
        Response again = post(path, null);

        assertThat(again.status()).isEqualTo(404);
        assertThat(again.code()).isEqualTo("NOTIFICATION_NOT_FOUND");
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'notification.sent'")).isEqualTo(1);
    }

    @Test
    void anUnknownNoticeIsNotFound() {
        assertThat(post("/api/v1/notifications/" + UUID.randomUUID() + "/sent", null).code())
                .isEqualTo("NOTIFICATION_NOT_FOUND");
    }

    @Test
    void anOfferThatRanOutHasNothingLeftToSay() {
        offerToSomeone();
        assertThat(get(PENDING).json()).hasSize(1);

        clock.advance(Duration.ofMinutes(31));

        assertThat(get(PENDING).json()).isEmpty();
    }

    @Test
    void anOfferAlreadyAnsweredHasNothingLeftToSay() {
        UUID patient = offerToSomeone();
        String appointment = get(PENDING).json().get(0).path("appointmentId").asString();

        assertThat(post(appointments(patient) + "/" + appointment + "/confirm", null).status()).isEqualTo(200);

        assertThat(get(PENDING).json()).isEmpty();
    }

    @Test
    void anOfferDeclinedBeforeItWentOutHasNothingLeftToSay() {
        UUID patient = offerToSomeone();
        String appointment = get(PENDING).json().get(0).path("appointmentId").asString();

        assertThat(post(appointments(patient) + "/" + appointment + "/release", null).status()).isEqualTo(200);

        assertThat(get(PENDING).json()).isEmpty();
    }

    @Test
    void anOrdinaryHoldIsNotAnOffer() {
        holdOk(newPatient(), "ana", "check-up", tuesday(10, 0));

        assertThat(get(PENDING).json()).isEmpty();
    }

    @Test
    void askingAgainForTheSameOfferLeavesOneNotice() {
        offerToSomeone();
        String slot =
                jdbc.sql("SELECT id::text FROM freed_slot").query(String.class).single();

        send("POST", "/api/v1/workflows/freed-slots/" + slot + "/next-offer", WORKFLOWS, newKey(), null);

        assertThat(count("SELECT count(*) FROM notification")).isEqualTo(1);
    }

    private Response lookupEmail(String email) {
        return post("/api/v1/patients/lookup-email", Map.of("email", email));
    }

    private void register(String phone, String email) {
        Response response =
                post(
                        "/api/v1/patients",
                        Map.of("fullName", "Email Test", "phone", phone, "email", email, "language", "es"));
        assertThat(response.status()).as(response.body()).isEqualTo(201);
    }

    @Test
    void aPatientIsFoundByTheirAddressWhateverItsCase() {
        register("+34666000201", "laura@test.example");

        assertThat(lookupEmail("laura@test.example").status()).isEqualTo(200);
        assertThat(lookupEmail("LAURA@Test.Example").field("phone")).isEqualTo("+34666000201");
    }

    @Test
    void anAddressNobodyHasIsNotFound() {
        assertThat(lookupEmail("nobody@test.example").code()).isEqualTo("PATIENT_NOT_FOUND");
    }

    @Test
    void anAddressTwoPeopleShareIdentifiesNobody() {
        register("+34666000202", "family@test.example");
        register("+34666000203", "family@test.example");

        assertThat(lookupEmail("family@test.example").code()).isEqualTo("PATIENT_NOT_FOUND");
    }

    @Test
    void aMalformedAddressIsRefusedBeforeTheDatabase() {
        for (String email : new String[] {"not-an-address", "a\u0000@test.example", "a b@test.example"}) {
            assertThat(lookupEmail(email).status()).as(email).isEqualTo(400);
        }
    }

    @Test
    void readingWithNothingPendingWritesNothing() {
        int before = count("SELECT count(*) FROM audit_event");

        get(PENDING);

        assertThat(count("SELECT count(*) FROM audit_event")).isEqualTo(before);
    }
}
