package io.github.rnachoc.turno.core.waitlist;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rnachoc.turno.core.support.ApiIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Checks who gets offered a freed slot, and when. These decisions belong to the core, so they are
 * tested here against the real API and database, with the clock on each boundary.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
class WaitlistIT extends ApiIntegrationTest {

    /** Well inside the free cancellation period. */
    private static final Instant SLOT = madrid(2027, 3, 9, 9, 0);

    private static final Instant MIDDAY = madrid(2027, 3, 1, 12, 0);

    @BeforeEach
    void startWithinContactHours() {
        clock.set(MIDDAY);
    }

    private static String waitlist(UUID patient) {
        return "/api/v1/patients/" + patient + "/waitlist";
    }

    private UUID waiting(String treatment) {
        UUID patient = newPatient();
        // Their place in the queue comes from this instant, and the clock only moves by hand.
        clock.advance(Duration.ofSeconds(1));
        join(patient, treatment, null);
        return patient;
    }

    private Response join(UUID patient, String treatment, String practitioner) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("treatmentId", treatment);
        if (practitioner != null) {
            body.put("practitionerId", practitioner);
        }
        return post(waitlist(patient), body);
    }

    /** Books and cancels an appointment, which frees its slot, and returns that slot's identifier. */
    private String freeSlot(String treatment, Instant startsAt) {
        UUID patient = newPatient();
        String id = bookOk(patient, "ana", treatment, startsAt);
        // The clock only moves by hand, and the latest slot is found by its time of creation.
        clock.advance(Duration.ofSeconds(1));
        assertThat(post(appointments(patient) + "/" + id + "/cancel", null).status()).isEqualTo(200);
        return latestSlot();
    }

    private String latestSlot() {
        return owner.jdbc()
                .sql("SELECT id::text FROM freed_slot ORDER BY created_at DESC, id LIMIT 1")
                .query(String.class)
                .single();
    }

    private Response workflowPost(String path) {
        return send("POST", "/api/v1/workflows" + path, WORKFLOWS, newKey(), null);
    }

    private Response workflowGet(String path) {
        return send("GET", "/api/v1/workflows" + path, WORKFLOWS, null, null);
    }

    private Response nextOffer(String slot) {
        return workflowPost("/freed-slots/" + slot + "/next-offer");
    }

    private String holdOwner(Response offer) {
        return owner.jdbc()
                .sql("SELECT patient_id::text FROM appointment WHERE id = CAST(:id AS uuid)")
                .param("id", offer.field("appointmentId"))
                .query(String.class)
                .single();
    }

    private String outcome(Response offer) {
        return workflowGet("/offers/" + offer.field("offerId")).field("outcome");
    }

    @Test
    void aPatientCanJoinListAndLeave() {
        UUID patient = newPatient();

        Response joined = join(patient, "check-up", "ana");

        assertThat(joined.status()).isEqualTo(201);
        assertThat(joined.field("status")).isEqualTo("WAITING");
        assertThat(get(waitlist(patient)).json()).hasSize(1);

        Response left = post(waitlist(patient) + "/" + joined.field("id") + "/leave", null);

        assertThat(left.status()).isEqualTo(200);
        assertThat(get(waitlist(patient)).json().get(0).path("status").asString()).isEqualTo("REMOVED");
        assertThat(join(patient, "check-up", null).status()).as("can join again").isEqualTo(201);
    }

    @Test
    void joiningFollowsTheRulesOfTheClinic() {
        UUID patient = newPatient();

        assertThat(join(UUID.randomUUID(), "check-up", null).code()).isEqualTo("PATIENT_NOT_FOUND");
        assertThat(join(patient, "laser", null).code()).isEqualTo("TREATMENT_NOT_FOUND");
        assertThat(join(patient, "check-up", "nobody").code()).isEqualTo("PRACTITIONER_NOT_FOUND");
        assertThat(join(patient, "filling", "ben").code()).isEqualTo("PRACTITIONER_CANNOT_PERFORM");
        assertThat(join(patient, "check-up", null).status()).isEqualTo(201);
        assertThat(join(patient, "check-up", null).code()).isEqualTo("ALREADY_ON_WAITLIST");
        assertThat(join(patient, "filling", null).status()).as("another treatment").isEqualTo(201);
    }

    @Test
    void anEntryCannotBeRemovedThroughAnotherPatient() {
        UUID owner = newPatient();
        Response joined = join(owner, "check-up", null);

        Response attempt = post(waitlist(newPatient()) + "/" + joined.field("id") + "/leave", null);

        assertThat(attempt.code()).isEqualTo("WAITLIST_ENTRY_NOT_FOUND");
        assertThat(get(waitlist(owner)).json().get(0).path("status").asString()).isEqualTo("WAITING");
    }

    @Test
    void aCancellationFreesItsSlotInTheSameTransaction() {
        UUID patient = newPatient();
        String id = bookOk(patient, "ana", "check-up", SLOT);
        assertThat(workflowGet("/freed-slots").json()).isEmpty();

        post(appointments(patient) + "/" + id + "/cancel", null);

        Response pending = workflowGet("/freed-slots");
        assertThat(pending.json()).hasSize(1);
        assertThat(pending.json().get(0).path("practitionerId").asString()).isEqualTo("ana");
        assertThat(pending.json().get(0).path("startsAt").asString()).isEqualTo(SLOT.toString());
        assertThat(pending.json().get(0).path("endsAt").asString())
                .isEqualTo(SLOT.plus(Duration.ofMinutes(30)).toString());
    }

    @Test
    void aRefusedCancellationFreesNothing() {
        UUID patient = newPatient();
        String id = holdOk(patient, "ana", "check-up", SLOT);

        assertThat(post(appointments(patient) + "/" + id + "/cancel", null).code()).isEqualTo("INVALID_STATE");

        assertThat(count("SELECT count(*) FROM freed_slot")).isZero();
    }

    @Test
    void movingAnAppointmentFreesItsOldSlot() {
        UUID patient = newPatient();
        String id = bookOk(patient, "ana", "check-up", SLOT);
        Instant target = SLOT.plus(Duration.ofHours(1));

        post(appointments(patient) + "/" + id + "/reschedule",
                Map.of("practitionerId", "ana", "startsAt", target.toString()));

        assertThat(workflowGet("/freed-slots").json().get(0).path("startsAt").asString()).isEqualTo(SLOT.toString());
    }

    @Test
    void aReleasedHoldFreesNothing() {
        UUID patient = newPatient();
        String id = holdOk(patient, "ana", "check-up", SLOT);

        post(appointments(patient) + "/" + id + "/release", null);

        assertThat(count("SELECT count(*) FROM freed_slot")).isZero();
    }

    @Test
    void acknowledgingRemovesASlotFromThePendingListAndCanBeRepeated() {
        String slot = freeSlot("check-up", SLOT);

        assertThat(workflowPost("/freed-slots/" + slot + "/acknowledge").status()).isEqualTo(200);
        assertThat(workflowPost("/freed-slots/" + slot + "/acknowledge").status()).isEqualTo(200);

        assertThat(workflowGet("/freed-slots").json()).isEmpty();
        assertThat(workflowPost("/freed-slots/" + UUID.randomUUID() + "/acknowledge").code())
                .isEqualTo("FREED_SLOT_NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings = {AGENTS, STAFF})
    void onlyTheWorkerCanUseTheWorkflowApi(String token) {
        String slot = freeSlot("check-up", SLOT);

        assertThat(send("GET", "/api/v1/workflows/freed-slots", token, null, null).status()).isEqualTo(403);
        assertThat(send("POST", "/api/v1/workflows/freed-slots/" + slot + "/next-offer", token, newKey(), null).status())
                .isEqualTo(403);
    }

    @Test
    void theWorkerCanReachNothingElse() {
        UUID patient = newPatient();

        assertThat(send("GET", "/api/v1/clinic", WORKFLOWS, null, null).status()).isEqualTo(403);
        assertThat(send("GET", "/api/v1/patients/" + patient, WORKFLOWS, null, null).status()).isEqualTo(403);
        assertThat(send("GET", appointments(patient), WORKFLOWS, null, null).status()).isEqualTo(403);
        assertThat(send("GET", "/api/v1/staff/audit", WORKFLOWS, null, null).status()).isEqualTo(403);
    }

    @Test
    void theOldestOnTheListIsOfferedTheSlotFirst() {
        UUID first = waiting("check-up");
        clock.advance(Duration.ofMinutes(1));
        waiting("check-up");
        String slot = freeSlot("check-up", SLOT);

        Response offer = nextOffer(slot);

        assertThat(offer.field("decision")).isEqualTo("OFFERED");
        assertThat(holdOwner(offer)).isEqualTo(first.toString());
        assertThat(offer.field("waitUntil")).isEqualTo(clock.instant().plus(Duration.ofMinutes(30)).toString());
    }

    @Test
    void anOfferIsAnOrdinaryHoldAtTheClinicsPrice() {
        UUID patient = waiting("check-up");
        String slot = freeSlot("check-up", SLOT);

        Response offer = nextOffer(slot);

        Response appointment = get(appointments(patient) + "/" + offer.field("appointmentId"));
        assertThat(appointment.field("status")).isEqualTo("HOLD");
        assertThat(appointment.field("startsAt")).isEqualTo(SLOT.toString());
        assertThat(appointment.json().path("priceCents").asInt()).isEqualTo(3500);
        assertThat(hold(newPatient(), "ana", "check-up", SLOT).code()).as("nobody else can take it").isEqualTo("SLOT_UNAVAILABLE");
    }

    @Test
    void askingAgainWhileAnOfferRunsReturnsTheSameOffer() {
        waiting("check-up");
        waiting("check-up");
        String slot = freeSlot("check-up", SLOT);

        Response first = nextOffer(slot);
        Response second = nextOffer(slot);

        assertThat(second.field("offerId")).isEqualTo(first.field("offerId"));
        assertThat(count("SELECT count(*) FROM slot_offer")).isEqualTo(1);
    }

    @Test
    void simultaneousRequestsMakeOnlyOneOffer() throws Exception {
        for (int i = 0; i < 6; i++) {
            waiting("check-up");
        }
        String slot = freeSlot("check-up", SLOT);

        List<Callable<Response>> calls = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            calls.add(() -> nextOffer(slot));
        }
        List<Response> responses = race(calls);

        assertThat(responses).allSatisfy(r -> assertThat(r.status()).as(r.body()).isEqualTo(200));
        assertThat(responses.stream().map(r -> r.field("offerId")).distinct()).hasSize(1);
        assertThat(count("SELECT count(*) FROM slot_offer")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM appointment WHERE status = 'HOLD'")).isEqualTo(1);
    }

    @Test
    void acceptingIsConfirmingTheHoldAndEndsTheWait() {
        UUID patient = waiting("check-up");
        String slot = freeSlot("check-up", SLOT);
        Response offer = nextOffer(slot);
        assertThat(outcome(offer)).isEqualTo("PENDING");

        post(appointments(patient) + "/" + offer.field("appointmentId") + "/confirm", null);

        assertThat(outcome(offer)).isEqualTo("ACCEPTED");
        assertThat(get(waitlist(patient)).json().get(0).path("status").asString()).isEqualTo("BOOKED");
        assertThat(nextOffer(slot).field("decision")).isEqualTo("SLOT_TAKEN");
    }

    @Test
    void anAcceptedPatientIsNotOfferedAnythingElse() {
        UUID patient = waiting("check-up");
        String taken = freeSlot("check-up", SLOT);
        Response offer = nextOffer(taken);
        post(appointments(patient) + "/" + offer.field("appointmentId") + "/confirm", null);

        String other = freeSlot("check-up", SLOT.plus(Duration.ofDays(1)));

        assertThat(nextOffer(other).field("decision")).isEqualTo("NO_CANDIDATE");
    }

    @Test
    void decliningPassesTheSlotToTheNextPatient() {
        UUID first = waiting("check-up");
        clock.advance(Duration.ofMinutes(1));
        UUID second = waiting("check-up");
        String slot = freeSlot("check-up", SLOT);
        Response offer = nextOffer(slot);

        post(appointments(first) + "/" + offer.field("appointmentId") + "/release", null);

        assertThat(outcome(offer)).isEqualTo("DECLINED");
        Response next = nextOffer(slot);
        assertThat(next.field("decision")).isEqualTo("OFFERED");
        assertThat(holdOwner(next)).isEqualTo(second.toString());
    }

    @Test
    void anUnansweredOfferExpiresOnTheExactInstantAndPassesOn() {
        waiting("check-up");
        clock.advance(Duration.ofMinutes(1));
        UUID second = waiting("check-up");
        String slot = freeSlot("check-up", SLOT);
        Response offer = nextOffer(slot);

        clock.advance(Duration.ofMinutes(30).minusMillis(1));
        assertThat(outcome(offer)).isEqualTo("PENDING");
        assertThat(nextOffer(slot).field("offerId")).isEqualTo(offer.field("offerId"));

        clock.advance(Duration.ofMillis(1));
        assertThat(outcome(offer)).isEqualTo("DECLINED");
        Response next = nextOffer(slot);
        assertThat(next.field("decision")).as(next.body()).isEqualTo("OFFERED");
        assertThat(holdOwner(next)).isEqualTo(second.toString());
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'appointment.expire'")).isEqualTo(1);
    }

    @Test
    void nobodyIsAskedTwiceAboutTheSameSlot() {
        UUID only = waiting("check-up");
        String slot = freeSlot("check-up", SLOT);
        Response offer = nextOffer(slot);
        post(appointments(only) + "/" + offer.field("appointmentId") + "/release", null);

        assertThat(nextOffer(slot).field("decision")).isEqualTo("NO_CANDIDATE");
    }

    @Test
    void anEmptyListMeansNoCandidate() {
        assertThat(nextOffer(freeSlot("check-up", SLOT)).field("decision")).isEqualTo("NO_CANDIDATE");
    }

    @Test
    void thoseWhoLeftAreNotOffered() {
        UUID patient = newPatient();
        Response joined = join(patient, "check-up", null);
        post(waitlist(patient) + "/" + joined.field("id") + "/leave", null);

        assertThat(nextOffer(freeSlot("check-up", SLOT)).field("decision")).isEqualTo("NO_CANDIDATE");
    }

    @Test
    void aPreferenceForAnotherPractitionerIsRespected() {
        UUID patient = newPatient();
        join(patient, "check-up", "ben");

        assertThat(nextOffer(freeSlot("check-up", SLOT)).field("decision")).isEqualTo("NO_CANDIDATE");
    }

    @Test
    void aTreatmentThatDoesNotFitIsSkipped() {
        waiting("filling");
        UUID fits = waiting("check-up");

        Response offer = nextOffer(freeSlot("check-up", SLOT));

        assertThat(holdOwner(offer)).as("a 45-minute filling does not fit in 30").isEqualTo(fits.toString());
    }

    @Test
    void aPractitionerIsNotOfferedForATreatmentTheyDoNotPerform() {
        waiting("filling");
        // Ben only does check-ups. This slot is long enough for a filling, so only his skills can rule it out.
        Instant monday = madrid(2027, 3, 8, 9, 0);
        String slot = UUID.randomUUID().toString();
        owner.jdbc()
                .sql(
                        """
                        INSERT INTO freed_slot (id, practitioner_id, starts_at, ends_at, created_at)
                        VALUES (CAST(:id AS uuid), 'ben', CAST(:start AS timestamptz), CAST(:end AS timestamptz), now())
                        """)
                .param("id", slot)
                .param("start", monday.toString())
                .param("end", monday.plus(Duration.ofHours(1)).toString())
                .update();

        assertThat(nextOffer(slot).field("decision")).isEqualTo("NO_CANDIDATE");
    }

    @Test
    void aShorterTreatmentFitsInALongerSlot() {
        UUID patient = waiting("check-up");

        Response offer = nextOffer(freeSlot("filling", SLOT));

        assertThat(holdOwner(offer)).isEqualTo(patient.toString());
    }

    @Test
    void someoneBusyAtThatTimeIsSkipped() {
        UUID busy = waiting("check-up");
        bookOk(busy, "ana", "check-up", SLOT.plus(Duration.ofHours(4)));
        UUID alsoBusy = waiting("check-up");
        // The same time as the slot, with someone else: Ben works on Mondays and Wednesdays.
        Instant wednesday = madrid(2027, 3, 10, 9, 0);
        bookOk(alsoBusy, "ben", "check-up", wednesday);
        UUID free = waiting("check-up");

        Response offer = nextOffer(freeSlot("check-up", wednesday));

        assertThat(holdOwner(offer)).isEqualTo(busy.toString());
        post(appointments(busy) + "/" + offer.field("appointmentId") + "/release", null);
        assertThat(holdOwner(nextOffer(latestSlot()))).as("the next one is busy then").isEqualTo(free.toString());
    }

    @Test
    void someoneAlreadyHoldingASlotIsSkipped() {
        UUID holding = waiting("check-up");
        holdOk(holding, "ana", "check-up", SLOT.plus(Duration.ofHours(4)));
        UUID free = waiting("check-up");

        Response offer = nextOffer(freeSlot("check-up", SLOT));

        assertThat(holdOwner(offer)).isEqualTo(free.toString());
    }

    @Test
    void nobodyIsContactedOutsideTheAllowedHours() {
        waiting("check-up");
        String slot = freeSlot("check-up", SLOT);

        clock.set(madrid(2027, 3, 1, 8, 59));
        assertThat(nextOffer(slot).field("decision")).isEqualTo("WAIT");
        assertThat(nextOffer(slot).field("waitUntil")).isEqualTo(madrid(2027, 3, 1, 9, 0).toString());

        clock.set(madrid(2027, 3, 1, 20, 0));
        assertThat(nextOffer(slot).field("waitUntil")).as("the end of the window is excluded")
                .isEqualTo(madrid(2027, 3, 2, 9, 0).toString());

        clock.set(madrid(2027, 3, 1, 9, 0));
        assertThat(nextOffer(slot).field("decision")).isEqualTo("OFFERED");
        assertThat(count("SELECT count(*) FROM slot_offer")).isEqualTo(1);
    }

    @Test
    void aPatientIsNotAskedMoreThanAllowedInADay() {
        UUID patient = waiting("check-up");
        for (int i = 0; i < 2; i++) {
            Response offer = nextOffer(freeSlot("check-up", SLOT.plus(Duration.ofHours(i))));
            assertThat(offer.field("decision")).isEqualTo("OFFERED");
            post(appointments(patient) + "/" + offer.field("appointmentId") + "/release", null);
        }
        String third = freeSlot("check-up", SLOT.plus(Duration.ofHours(2)));

        assertThat(nextOffer(third).field("decision")).isEqualTo("NO_CANDIDATE");

        clock.set(madrid(2027, 3, 2, 9, 0));
        assertThat(nextOffer(third).field("decision")).as("the next day").isEqualTo("OFFERED");
    }

    @Test
    void theNumberOfOffersPerSlotIsLimited() {
        List<UUID> patients = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            patients.add(waiting("check-up"));
            clock.advance(Duration.ofMinutes(1));
        }
        String slot = freeSlot("check-up", SLOT);

        for (int i = 0; i < 3; i++) {
            Response offer = nextOffer(slot);
            assertThat(offer.field("decision")).isEqualTo("OFFERED");
            post(appointments(patients.get(i)) + "/" + offer.field("appointmentId") + "/release", null);
        }

        assertThat(nextOffer(slot).field("decision")).isEqualTo("LIMIT_REACHED");
    }

    @Test
    void itIsTooLateWhenThereIsNoTimeToAnswerBeforeTheSlot() {
        waiting("check-up");
        String slot = freeSlot("check-up", SLOT);

        clock.set(SLOT.minus(Duration.ofMinutes(30)).plusMillis(1));
        assertThat(nextOffer(slot).field("decision")).isEqualTo("TOO_LATE");
    }

    @Test
    void theNormalNoticeRuleStillApplies() {
        waiting("check-up");
        Instant afternoon = madrid(2027, 3, 9, 16, 0);
        String slot = freeSlot("check-up", afternoon);

        // Time to answer, but under the two hours of notice that any booking needs.
        clock.set(afternoon.minus(Duration.ofHours(1)));

        assertThat(nextOffer(slot).field("decision")).isEqualTo("NO_CANDIDATE");
    }

    @Test
    void aSlotBookedInAnyOtherWayIsNotOffered() {
        waiting("check-up");
        String slot = freeSlot("check-up", SLOT);
        holdOk(newPatient(), "ana", "check-up", SLOT);

        assertThat(nextOffer(slot).field("decision")).isEqualTo("SLOT_TAKEN");
    }

    @Test
    void anExpiredHoldDoesNotCountAsTakingTheSlot() {
        UUID patient = waiting("check-up");
        String slot = freeSlot("check-up", SLOT);
        holdOk(newPatient(), "ana", "check-up", SLOT);

        clock.advance(Duration.ofMinutes(5));

        Response offer = nextOffer(slot);
        assertThat(offer.field("decision")).as(offer.body()).isEqualTo("OFFERED");
        assertThat(holdOwner(offer)).isEqualTo(patient.toString());
    }

    @Test
    void offersAreAuditedWithTheirAuthorAndNoPersonalData() {
        waiting("check-up");
        Response offer = nextOffer(freeSlot("check-up", SLOT));

        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'waitlist.offer' AND actor = 'workflows' "
                        + "AND entity_id = '" + offer.field("appointmentId") + "'"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_event WHERE audit_event::text LIKE '%Test Patient%' "
                        + "OR audit_event::text LIKE '%+3460%'"))
                .isZero();
    }

    @Test
    void unknownIdentifiersAreRefused() {
        assertThat(nextOffer(UUID.randomUUID().toString()).code()).isEqualTo("FREED_SLOT_NOT_FOUND");
        assertThat(workflowGet("/offers/" + UUID.randomUUID()).code()).isEqualTo("OFFER_NOT_FOUND");
    }

    @Test
    void theServiceRoleCannotDeleteFromTheNewTables() {
        waiting("check-up");
        nextOffer(freeSlot("check-up", SLOT));

        for (String table : List.of("waitlist_entry", "freed_slot", "slot_offer")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("DELETE FROM " + table).update())
                    .rootCause()
                    .hasMessageContaining("permission denied");
        }
    }

    private List<Response> race(List<Callable<Response>> calls) throws Exception {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Response>> futures = new ArrayList<>();
            for (Callable<Response> call : calls) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return call.call();
                }));
            }
            go.countDown();
            List<Response> responses = new ArrayList<>();
            for (Future<Response> future : futures) {
                responses.add(future.get());
            }
            return responses;
        }
    }
}
