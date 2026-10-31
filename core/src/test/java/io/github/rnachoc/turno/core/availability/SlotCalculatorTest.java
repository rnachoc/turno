package io.github.rnachoc.turno.core.availability;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rnachoc.turno.core.tenant.Tenant;
import io.github.rnachoc.turno.core.tenant.Tenant.Practitioner;
import io.github.rnachoc.turno.core.tenant.Tenant.TimeWindow;
import io.github.rnachoc.turno.core.tenant.Tenant.Treatment;
import io.github.rnachoc.turno.core.tenant.TenantLoader;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Exercises the rules of the slot engine on their exact boundaries, including the real daylight
 * saving changes in Madrid: 28 March 2027 at 02:00 and 31 October 2027 at 03:00.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
class SlotCalculatorTest {

    private static final Tenant TENANT =
            TenantLoader.load(Path.of("src/test/resources/tenants/test-clinic.yaml"));
    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    private static final Practitioner ANA = TENANT.practitioner("ana").orElseThrow();
    private static final Practitioner BEN = TENANT.practitioner("ben").orElseThrow();
    private static final Treatment CHECK_UP = TENANT.treatment("check-up").orElseThrow();
    private static final Treatment FILLING = TENANT.treatment("filling").orElseThrow();

    /** Monday 1 March 2027 at 08:00 in Madrid. */
    private static final Instant NOW = at(2027, 3, 1, 8, 0);

    private static final LocalDate TUESDAY = LocalDate.of(2027, 3, 2);

    @Test
    void listsSlotsOnTheGridInsideEachOpeningWindowInOrder() {
        List<LocalTime> starts = startsOn(TUESDAY, ANA, CHECK_UP);

        assertThat(starts).startsWith(LocalTime.of(9, 0), LocalTime.of(9, 15));
        assertThat(starts).isSorted();
        assertThat(starts).allMatch(time -> time.getMinute() % 15 == 0);
        assertThat(starts).noneMatch(time -> time.isAfter(LocalTime.of(13, 30)) && time.isBefore(LocalTime.of(16, 0)));
    }

    @Test
    void anAppointmentMayEndExactlyAtClosingButNotAfter() {
        assertThat(startsOn(TUESDAY, ANA, CHECK_UP))
                .contains(LocalTime.of(13, 30), LocalTime.of(19, 30))
                .doesNotContain(LocalTime.of(13, 45), LocalTime.of(19, 45));
        assertThat(startsOn(TUESDAY, ANA, FILLING))
                .contains(LocalTime.of(13, 15))
                .doesNotContain(LocalTime.of(13, 30));
    }

    @Test
    void slotLengthMatchesTheTreatment() {
        assertThat(SlotCalculator.slots(TENANT, ANA, FILLING, TUESDAY, TUESDAY, NOW))
                .isNotEmpty()
                .allMatch(s -> Duration.between(s.startsAt(), s.endsAt()).toMinutes() == 45);
    }

    @Test
    void minimumNoticeIsInclusiveToTheMillisecond() {
        LocalDate today = LocalDate.of(2027, 3, 1);
        assertThat(startsOn(today, ANA, CHECK_UP)).first().isEqualTo(LocalTime.of(10, 0));

        Instant oneMilliLater = NOW.plusMillis(1);
        assertThat(SlotCalculator.slots(TENANT, ANA, CHECK_UP, today, today, oneMilliLater))
                .first()
                .extracting(Slot::startsAt)
                .isEqualTo(at(2027, 3, 1, 10, 15));
    }

    @Test
    void maximumAdvanceIsInclusive() {
        LocalDate lastAllowed = LocalDate.of(2027, 4, 30);
        assertThat(lastAllowed).isEqualTo(LocalDate.of(2027, 3, 1).plusDays(60));

        assertThat(SlotCalculator.slots(TENANT, ANA, CHECK_UP, lastAllowed, lastAllowed, NOW))
                .isNotEmpty();
        assertThat(SlotCalculator.slots(
                        TENANT, ANA, CHECK_UP, lastAllowed.plusDays(3), lastAllowed.plusDays(3), NOW))
                .as("the next weekday is beyond the limit")
                .isEmpty();
    }

    @Test
    void noSlotsOnClosuresOrDaysOffOrWeekendsOrInThePast() {
        LocalDate closure = LocalDate.of(2027, 3, 3);
        LocalDate thursday = LocalDate.of(2027, 3, 4);
        LocalDate saturday = LocalDate.of(2027, 3, 6);
        LocalDate lastWeek = LocalDate.of(2027, 2, 22);

        assertThat(startsOn(closure, ANA, CHECK_UP)).isEmpty();
        assertThat(startsOn(thursday, BEN, CHECK_UP)).as("Ben does not work on Thursdays").isEmpty();
        assertThat(startsOn(thursday, ANA, CHECK_UP)).isNotEmpty();
        assertThat(startsOn(saturday, ANA, CHECK_UP)).isEmpty();
        assertThat(startsOn(lastWeek, ANA, CHECK_UP)).isEmpty();
    }

    @Test
    void anInvertedRangeIsEmpty() {
        assertThat(SlotCalculator.slots(TENANT, ANA, CHECK_UP, TUESDAY.plusDays(1), TUESDAY, NOW))
                .isEmpty();
    }

    @Test
    void aWindowOpeningOffTheGridStartsAtTheNextGridPoint() {
        Tenant tenant = withSunday(new TimeWindow(LocalTime.of(9, 10), LocalTime.of(10, 0)), 60);
        Instant now = at(2027, 3, 1, 8, 0);
        LocalDate sunday = LocalDate.of(2027, 3, 7);

        assertThat(SlotCalculator.slots(tenant, onSundays(ANA), CHECK_UP, sunday, sunday, now))
                .extracting(s -> s.startsAt().atZone(MADRID).toLocalTime())
                .containsExactly(LocalTime.of(9, 15), LocalTime.of(9, 30));
    }

    @Test
    void springForwardOffersNothingThatTouchesTheMissingHour() {
        Tenant tenant = withSunday(new TimeWindow(LocalTime.of(0, 0), LocalTime.of(6, 0)), 60);
        LocalDate change = LocalDate.of(2027, 3, 28);

        List<Slot> slots = SlotCalculator.slots(tenant, onSundays(ANA), CHECK_UP, change, change, NOW);

        assertThat(slots).extracting(s -> s.startsAt().atZone(MADRID).toLocalTime())
                .contains(LocalTime.of(1, 15), LocalTime.of(3, 0))
                .doesNotContain(
                        LocalTime.of(1, 30),
                        LocalTime.of(1, 45),
                        LocalTime.of(2, 0),
                        LocalTime.of(2, 30));
        assertThat(slots).allMatch(s -> Duration.between(s.startsAt(), s.endsAt()).toMinutes() == 30);
    }

    @Test
    void fallBackOffersNothingThatTouchesTheRepeatedHour() {
        Tenant tenant = withSunday(new TimeWindow(LocalTime.of(0, 0), LocalTime.of(6, 0)), 300);
        LocalDate change = LocalDate.of(2027, 10, 31);

        List<Slot> slots = SlotCalculator.slots(tenant, onSundays(ANA), CHECK_UP, change, change, NOW);

        assertThat(slots).extracting(s -> s.startsAt().atZone(MADRID).toLocalTime())
                .contains(LocalTime.of(1, 15), LocalTime.of(3, 0))
                .doesNotContain(
                        LocalTime.of(1, 45),
                        LocalTime.of(2, 0),
                        LocalTime.of(2, 30));
        assertThat(slots).allMatch(s -> Duration.between(s.startsAt(), s.endsAt()).toMinutes() == 30);
    }

    @Test
    void slotAtAcceptsOnlyWhatTheListOffers() {
        assertThat(SlotCalculator.slotAt(TENANT, ANA, CHECK_UP, at(2027, 3, 2, 9, 0), NOW)).isPresent();
        assertThat(SlotCalculator.slotAt(TENANT, ANA, CHECK_UP, at(2027, 3, 2, 9, 7), NOW))
                .as("off the grid")
                .isEmpty();
        assertThat(SlotCalculator.slotAt(TENANT, ANA, CHECK_UP, at(2027, 3, 2, 14, 30), NOW))
                .as("during the lunch break")
                .isEmpty();
        assertThat(SlotCalculator.slotAt(TENANT, ANA, CHECK_UP, at(2027, 3, 1, 9, 0), NOW))
                .as("too soon")
                .isEmpty();
        assertThat(SlotCalculator.slotAt(TENANT, BEN, CHECK_UP, at(2027, 3, 2, 9, 0), NOW))
                .as("Ben does not work on Tuesdays")
                .isEmpty();
    }

    @Test
    void slotAtUsesTheClinicDateNotTheUtcDate() {
        Tenant tenant = withSunday(new TimeWindow(LocalTime.of(0, 0), LocalTime.of(6, 0)), 60);
        // 00:30 on Sunday in Madrid is still Saturday in UTC.
        Instant sundayInMadrid = at(2027, 3, 7, 0, 30);

        assertThat(sundayInMadrid.toString()).startsWith("2027-03-06T23:30");
        assertThat(SlotCalculator.slotAt(tenant, onSundays(ANA), CHECK_UP, sundayInMadrid, NOW))
                .isPresent();
    }

    private static List<LocalTime> startsOn(LocalDate date, Practitioner practitioner, Treatment treatment) {
        return SlotCalculator.slots(TENANT, practitioner, treatment, date, date, NOW).stream()
                .map(slot -> slot.startsAt().atZone(MADRID).toLocalTime())
                .toList();
    }

    private static Instant at(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute).atZone(MADRID).toInstant();
    }

    private static Practitioner onSundays(Practitioner practitioner) {
        return new Practitioner(
                practitioner.id(), practitioner.name(), practitioner.treatments(), Set.of(DayOfWeek.SUNDAY));
    }

    /** Copies the fixture, opening on Sundays with the given window and a given advance limit. */
    private static Tenant withSunday(TimeWindow window, int maxAdvanceDays) {
        Map<DayOfWeek, List<TimeWindow>> hours = new EnumMap<>(TENANT.openingHours());
        hours.put(DayOfWeek.SUNDAY, List.of(window));
        Tenant.BookingPolicy booking = TENANT.booking();
        return new Tenant(
                TENANT.id(),
                TENANT.name(),
                TENANT.fictional(),
                TENANT.locale(),
                TENANT.languages(),
                TENANT.timezone(),
                TENANT.currency(),
                TENANT.contactEmail(),
                TENANT.emergencyNumber(),
                hours,
                TENANT.closures(),
                TENANT.treatments(),
                TENANT.practitioners(),
                new Tenant.BookingPolicy(
                        booking.slotGranularityMinutes(),
                        booking.minNoticeMinutes(),
                        maxAdvanceDays,
                        booking.holdTtlMinutes(),
                        booking.maxActiveHoldsPerPatient()),
                TENANT.cancellation(),
                TENANT.outreach(),
                TENANT.waitlist());
    }
}
