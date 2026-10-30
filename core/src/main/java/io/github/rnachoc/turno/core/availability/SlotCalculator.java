package io.github.rnachoc.turno.core.availability;

import io.github.rnachoc.turno.core.tenant.Tenant;
import io.github.rnachoc.turno.core.tenant.Tenant.BookingPolicy;
import io.github.rnachoc.turno.core.tenant.Tenant.Practitioner;
import io.github.rnachoc.turno.core.tenant.Tenant.TimeWindow;
import io.github.rnachoc.turno.core.tenant.Tenant.Treatment;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Works out which slots the clinic's rules allow, without looking at existing appointments.
 *
 * <p>This is the only definition of a valid slot: both listing availability and accepting a booking
 * use it, so the API can never offer a slot that it would then refuse, or accept one it never
 * offered. It is a pure function of its arguments.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
public final class SlotCalculator {

    private SlotCalculator() {}

    /**
     * Lists the slots allowed for a practitioner and a treatment between two local dates.
     *
     * @param tenant the clinic
     * @param practitioner the practitioner
     * @param treatment the treatment
     * @param from first local date, inclusive
     * @param to last local date, inclusive
     * @param now the current instant
     * @return the allowed slots, in chronological order
     */
    public static List<Slot> slots(
            Tenant tenant,
            Practitioner practitioner,
            Treatment treatment,
            LocalDate from,
            LocalDate to,
            Instant now) {
        BookingPolicy policy = tenant.booking();
        ZoneId zone = tenant.timezone();
        LocalDate today = now.atZone(zone).toLocalDate();
        LocalDate first = from.isBefore(today) ? today : from;
        LocalDate lastAllowed = today.plusDays(policy.maxAdvanceDays());
        LocalDate last = to.isAfter(lastAllowed) ? lastAllowed : to;
        Instant earliest = now.plus(Duration.ofMinutes(policy.minNoticeMinutes()));

        List<Slot> slots = new ArrayList<>();
        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
            if (tenant.closures().contains(date)
                    || !practitioner.workingDays().contains(date.getDayOfWeek())) {
                continue;
            }
            for (TimeWindow window : tenant.openingHours().get(date.getDayOfWeek())) {
                LocalDateTime start = date.atTime(alignUp(window.open(), policy));
                LocalDateTime close = date.atTime(window.close());
                while (!start.plusMinutes(treatment.durationMinutes()).isAfter(close)) {
                    toSlot(zone, practitioner, treatment, start)
                            .filter(slot -> !slot.startsAt().isBefore(earliest))
                            .ifPresent(slots::add);
                    start = start.plusMinutes(policy.slotGranularityMinutes());
                }
            }
        }
        return slots;
    }

    /**
     * Finds the allowed slot that starts at a given instant.
     *
     * @param tenant the clinic
     * @param practitioner the practitioner
     * @param treatment the treatment
     * @param startsAt the proposed start
     * @param now the current instant
     * @return the slot, if the rules allow it
     */
    public static Optional<Slot> slotAt(
            Tenant tenant,
            Practitioner practitioner,
            Treatment treatment,
            Instant startsAt,
            Instant now) {
        LocalDate date = startsAt.atZone(tenant.timezone()).toLocalDate();
        return slots(tenant, practitioner, treatment, date, date, now).stream()
                .filter(slot -> slot.startsAt().equals(startsAt))
                .findFirst();
    }

    /** Moves a time forward to the next multiple of the granularity, counted from midnight. */
    private static LocalTime alignUp(LocalTime time, BookingPolicy policy) {
        int granularity = policy.slotGranularityMinutes();
        int minutes = time.toSecondOfDay() / 60;
        int excess = minutes % granularity;
        return excess == 0
                ? time
                : LocalTime.MIDNIGHT.plusMinutes((long) minutes - excess + granularity);
    }

    /**
     * Converts a local start into a slot, unless daylight saving time makes it ambiguous: a local
     * time that does not exist, one that happens twice, or an appointment across the change, whose
     * real length would differ from its length on the clock. A patient could not be told such a
     * time unambiguously, so it is simply not offered.
     */
    private static Optional<Slot> toSlot(
            ZoneId zone, Practitioner practitioner, Treatment treatment, LocalDateTime start) {
        LocalDateTime end = start.plusMinutes(treatment.durationMinutes());
        if (zone.getRules().getValidOffsets(start).size() != 1
                || zone.getRules().getValidOffsets(end).size() != 1) {
            return Optional.empty();
        }
        Instant startsAt = start.atZone(zone).toInstant();
        Instant endsAt = end.atZone(zone).toInstant();
        if (!Duration.between(startsAt, endsAt)
                .equals(Duration.ofMinutes(treatment.durationMinutes()))) {
            return Optional.empty();
        }
        return Optional.of(new Slot(practitioner.id(), treatment.id(), startsAt, endsAt));
    }
}
