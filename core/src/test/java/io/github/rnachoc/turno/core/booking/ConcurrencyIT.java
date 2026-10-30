package io.github.rnachoc.turno.core.booking;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rnachoc.turno.core.support.ApiIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * Attacks the booking rules with simultaneous HTTP requests. The final check always looks at the
 * database itself: what matters is not what the API answered, but what actually got stored.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
class ConcurrencyIT extends ApiIntegrationTest {

    private static final int THREADS = 24;

    /** Counts pairs of active appointments that overlap for one practitioner or one patient. */
    private static final String OVERLAPS =
            """
            SELECT count(*) FROM appointment a JOIN appointment b ON a.id < b.id
             AND (a.practitioner_id = b.practitioner_id OR a.patient_id = b.patient_id)
             AND tstzrange(a.starts_at, a.ends_at, '[)') && tstzrange(b.starts_at, b.ends_at, '[)')
            WHERE a.status IN ('HOLD', 'CONFIRMED') AND b.status IN ('HOLD', 'CONFIRMED')
            """;

    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void closePool() {
        pool.close();
    }

    @RepeatedTest(3)
    void manyPatientsRaceForOneSlotAndExactlyOneWins() {
        List<UUID> patients = IntStream.range(0, THREADS).mapToObj(i -> newPatient()).toList();
        Instant start = tuesday(9, 0);

        List<Response> responses =
                race(patients.stream().map(p -> (Callable<Response>) () -> hold(p, "ana", "check-up", start)).toList());

        assertThat(responses).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(responses)
                .filteredOn(r -> r.status() != 201)
                .allSatisfy(r -> {
                    assertThat(r.status()).isEqualTo(409);
                    assertThat(r.code()).isEqualTo("SLOT_UNAVAILABLE");
                });
        assertThat(count("SELECT count(*) FROM appointment")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'appointment.hold'")).isEqualTo(1);
    }

    @RepeatedTest(3)
    void partiallyOverlappingRequestsNeverCoexist() {
        List<UUID> patients = IntStream.range(0, THREADS).mapToObj(i -> newPatient()).toList();

        List<Callable<Response>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            UUID patient = patients.get(i);
            Instant start = tuesday(9, 0).plus(Duration.ofMinutes(15L * (i % 8)));
            String treatment = i % 2 == 0 ? "check-up" : "filling";
            calls.add(() -> hold(patient, "ana", treatment, start));
        }
        List<Response> responses = race(calls);

        assertThat(responses).allSatisfy(r -> assertThat(r.status()).as(r.body()).isIn(201, 409));
        assertThat(responses).filteredOn(r -> r.status() == 201).isNotEmpty();
        assertThat(count(OVERLAPS)).isZero();
    }

    @RepeatedTest(3)
    void onePatientCannotBeInTwoPlacesAtOnce() {
        UUID patient = newPatient();
        Instant start = tuesday(9, 0);

        List<Response> responses =
                race(
                        List.of(
                                () -> hold(patient, "ana", "check-up", start),
                                () -> hold(patient, "ben", "check-up", start)));

        // Monday and Wednesday are Ben's days, so this request is refused by the rules on a Tuesday.
        assertThat(responses).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(count(OVERLAPS)).isZero();

        Instant wednesday = madrid(2027, 3, 3, 9, 0).plus(Duration.ofDays(7));
        UUID other = newPatient();
        List<Response> sameDay =
                race(
                        List.of(
                                () -> hold(other, "ana", "check-up", wednesday),
                                () -> hold(other, "ben", "check-up", wednesday)));

        assertThat(sameDay).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(sameDay)
                .filteredOn(r -> r.status() != 201)
                .allSatisfy(r -> assertThat(r.code()).isIn("PATIENT_TIME_CONFLICT", "HOLD_LIMIT_REACHED"));
        assertThat(count(OVERLAPS)).isZero();
    }

    @RepeatedTest(3)
    void theHoldLimitHoldsUnderConcurrency() {
        UUID patient = newPatient();

        List<Callable<Response>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            Instant start = tuesday(9, 0).plus(Duration.ofMinutes(30L * (i % 8)));
            calls.add(() -> hold(patient, "ana", "check-up", start));
        }
        List<Response> responses = race(calls);

        assertThat(responses).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(count("SELECT count(*) FROM appointment WHERE status = 'HOLD'")).isEqualTo(1);
    }

    @RepeatedTest(3)
    void theSameRequestSentManyTimesAtOnceRunsOnce() {
        UUID patient = newPatient();
        String key = newKey();
        String body = JSON.writeValueAsString(holdBody("ana", "check-up", tuesday(9, 0)));

        List<Callable<Response>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            calls.add(() -> send("POST", appointments(patient), AGENTS, key, body));
        }
        List<Response> responses = race(calls);

        assertThat(responses).allSatisfy(r -> assertThat(r.status()).as(r.body()).isEqualTo(201));
        assertThat(responses.stream().map(Response::body).distinct()).hasSize(1);
        assertThat(responses)
                .filteredOn(r -> r.raw().headers().firstValue("Idempotent-Replayed").orElseThrow().equals("false"))
                .hasSize(1);
        assertThat(count("SELECT count(*) FROM appointment")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'appointment.hold'")).isEqualTo(1);
    }

    @RepeatedTest(3)
    void anExpiredHoldIsReclaimedByExactlyOnePatient() {
        UUID first = newPatient();
        Instant start = tuesday(9, 0);
        holdOk(first, "ana", "check-up", start);
        clock.advance(Duration.ofMinutes(5));
        List<UUID> patients = IntStream.range(0, THREADS).mapToObj(i -> newPatient()).toList();

        List<Response> responses =
                race(patients.stream().map(p -> (Callable<Response>) () -> hold(p, "ana", "check-up", start)).toList());

        assertThat(responses).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(count("SELECT count(*) FROM appointment WHERE status = 'HOLD'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM appointment WHERE status = 'EXPIRED'")).isEqualTo(1);
        assertThat(count(OVERLAPS)).isZero();
    }

    @RepeatedTest(5)
    void confirmAndReleaseOfOneHoldCannotBothWin() {
        UUID patient = newPatient();
        String id = holdOk(patient, "ana", "check-up", tuesday(9, 0));
        String path = appointments(patient) + "/" + id;

        List<Response> responses =
                race(List.of(() -> post(path + "/confirm", null), () -> post(path + "/release", null)));

        assertThat(responses).filteredOn(r -> r.status() == 200).hasSize(1);
        assertThat(responses).filteredOn(r -> r.status() == 409).hasSize(1);
        assertThat(responses).filteredOn(r -> r.status() == 409).first()
                .satisfies(r -> assertThat(r.code()).isEqualTo("INVALID_STATE"));
        assertThat(count("SELECT count(*) FROM appointment WHERE status IN ('CONFIRMED', 'RELEASED')")).isEqualTo(1);
    }

    @RepeatedTest(5)
    void cancelAndRescheduleOfOneAppointmentAreSerialised() {
        UUID patient = newPatient();
        String id = bookOk(patient, "ana", "check-up", madrid(2027, 3, 9, 9, 0));
        String path = appointments(patient) + "/" + id;
        Map<String, Object> move = Map.of("practitionerId", "ana", "startsAt", madrid(2027, 3, 9, 10, 0).toString());

        List<Response> responses = race(List.of(() -> post(path + "/cancel", null), () -> post(path + "/reschedule", move)));

        assertThat(responses).allSatisfy(r -> assertThat(r.status()).as(r.body()).isIn(200, 409));
        assertThat(get(path).field("status")).isEqualTo("CANCELLED");
    }

    @RepeatedTest(3)
    void movingManyAppointmentsIntoOneSlotLetsExactlyOneIn() {
        Instant target = madrid(2027, 3, 9, 16, 0);
        List<Callable<Response>> calls = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            UUID patient = newPatient();
            String id = bookOk(patient, "ana", "check-up", madrid(2027, 3, 9, 9, 0).plus(Duration.ofMinutes(30L * i)));
            Map<String, Object> move = Map.of("practitionerId", "ana", "startsAt", target.toString());
            calls.add(() -> post(appointments(patient) + "/" + id + "/reschedule", move));
        }

        List<Response> responses = race(calls);

        assertThat(responses).allSatisfy(r -> assertThat(r.status()).as(r.body()).isIn(200, 409));
        assertThat(responses).filteredOn(r -> r.status() == 200).hasSize(1);
        assertThat(count(OVERLAPS)).isZero();
    }

    @Test
    void twoAppointmentsSwappingSlotsNeverOverlapOrHang() {
        UUID first = newPatient();
        UUID second = newPatient();
        Instant firstStart = madrid(2027, 3, 9, 9, 0);
        Instant secondStart = madrid(2027, 3, 9, 10, 0);
        String firstId = bookOk(first, "ana", "check-up", firstStart);
        String secondId = bookOk(second, "ana", "check-up", secondStart);

        List<Response> responses =
                race(
                        List.of(
                                () -> post(appointments(first) + "/" + firstId + "/reschedule",
                                        Map.of("practitionerId", "ana", "startsAt", secondStart.toString())),
                                () -> post(appointments(second) + "/" + secondId + "/reschedule",
                                        Map.of("practitionerId", "ana", "startsAt", firstStart.toString()))));

        assertThat(responses).allSatisfy(r -> {
            assertThat(r.status()).as(r.body()).isEqualTo(409);
            assertThat(r.code()).isIn("SLOT_UNAVAILABLE", "CONCURRENT_CONFLICT");
        });
        assertThat(count(OVERLAPS)).isZero();
    }

    /**
     * A storm of mixed operations with a fixed seed. Whatever the interleaving, the invariants must
     * hold at the end: no overlaps, no server errors, and an audit entry per accepted state change.
     */
    @Test
    void aStormOfRandomOperationsLeavesTheInvariantsIntact() {
        List<UUID> patients = IntStream.range(0, 12).mapToObj(i -> newPatient()).toList();
        Random random = new Random(20270301L);
        // Bounded because the contention under test is in the database: 300 sockets connecting at
        // once only overflow the TCP accept queue, before the service sees any of them.
        Semaphore inFlight = new Semaphore(32);
        List<Callable<Response>> calls = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            UUID patient = patients.get(random.nextInt(patients.size()));
            String practitioner = random.nextBoolean() ? "ana" : "ben";
            String treatment = practitioner.equals("ana") && random.nextBoolean() ? "filling" : "check-up";
            Instant start = madrid(2027, 3, 8, 9, 0).plus(Duration.ofMinutes(15L * random.nextInt(16)));
            int action = random.nextInt(4);
            calls.add(() -> {
                inFlight.acquire();
                try {
                    Response held = hold(patient, practitioner, treatment, start);
                    if (held.status() != 201) {
                        return held;
                    }
                    String path = appointments(patient) + "/" + held.field("id");
                    return switch (action) {
                        case 0 -> post(path + "/release", null);
                        case 1 -> {
                            post(path + "/confirm", null);
                            yield post(path + "/cancel", null);
                        }
                        case 2 -> post(path + "/confirm", null);
                        default -> held;
                    };
                } finally {
                    inFlight.release();
                }
            });
        }

        List<Response> responses = race(calls);

        assertThat(responses).allSatisfy(r -> assertThat(r.status()).as(r.body()).isLessThan(500));
        assertThat(count(OVERLAPS)).isZero();
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'appointment.hold' AND outcome = 'ACCEPTED'"))
                .isEqualTo(count("SELECT count(*) FROM appointment"));
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'appointment.confirm'"))
                .isEqualTo(count("SELECT count(*) FROM appointment WHERE status IN ('CONFIRMED', 'CANCELLED')"));
    }

    /** Runs the calls at the same moment and waits for all of them. */
    private List<Response> race(List<Callable<Response>> calls) {
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Response>> futures =
                calls.stream()
                        .map(call -> pool.submit(() -> {
                            ready.countDown();
                            go.await();
                            return call.call();
                        }))
                        .toList();
        try {
            ready.await();
            go.countDown();
            List<Response> responses = new ArrayList<>();
            for (Future<Response> future : futures) {
                responses.add(future.get());
            }
            return responses;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
