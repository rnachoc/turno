package io.github.rnachoc.turno.core.availability;

import io.github.rnachoc.turno.core.api.DomainException;
import io.github.rnachoc.turno.core.api.ErrorCode;
import io.github.rnachoc.turno.core.booking.AppointmentRepository;
import io.github.rnachoc.turno.core.booking.AppointmentRepository.BusyPeriod;
import io.github.rnachoc.turno.core.tenant.Tenant;
import io.github.rnachoc.turno.core.tenant.Tenant.Practitioner;
import io.github.rnachoc.turno.core.tenant.Tenant.Treatment;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers which slots are free: those the rules allow, minus those already taken. The answer is only
 * a snapshot, since only holding a slot guarantees it.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@Service
public class AvailabilityService {

    /** Longest period that one request can ask about, which bounds its cost. */
    static final int MAX_RANGE_DAYS = 31;

    private final AppointmentRepository appointments;
    private final Tenant tenant;
    private final Clock clock;

    AvailabilityService(AppointmentRepository appointments, Tenant tenant, Clock clock) {
        this.appointments = appointments;
        this.tenant = tenant;
        this.clock = clock;
    }

    /**
     * Lists the free slots for a treatment.
     *
     * @param treatmentId the treatment
     * @param practitionerId restricts the result to this practitioner, if given
     * @param from first local date, inclusive
     * @param to last local date, inclusive
     * @return the free slots, ordered by time and then by practitioner
     */
    @Transactional(readOnly = true)
    public List<Slot> freeSlots(
            String treatmentId, @Nullable String practitionerId, LocalDate from, LocalDate to) {
        if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw new DomainException(ErrorCode.DATE_RANGE_INVALID);
        }
        Treatment treatment =
                tenant.treatment(treatmentId)
                        .orElseThrow(() -> new DomainException(ErrorCode.TREATMENT_NOT_FOUND));
        Instant now = clock.instant();
        List<BusyPeriod> busy =
                appointments.findBusy(
                        from.atStartOfDay(tenant.timezone()).toInstant(),
                        to.plusDays(1).atStartOfDay(tenant.timezone()).toInstant(),
                        now);
        return practitioners(treatment, practitionerId).stream()
                .flatMap(p -> SlotCalculator.slots(tenant, p, treatment, from, to, now).stream())
                .filter(slot -> busy.stream().noneMatch(period -> overlaps(period, slot)))
                .sorted(Comparator.comparing(Slot::startsAt).thenComparing(Slot::practitionerId))
                .toList();
    }

    private List<Practitioner> practitioners(Treatment treatment, @Nullable String practitionerId) {
        if (practitionerId == null) {
            return tenant.practitioners().stream()
                    .filter(p -> p.treatments().contains(treatment.id()))
                    .toList();
        }
        Practitioner practitioner =
                tenant.practitioner(practitionerId)
                        .orElseThrow(() -> new DomainException(ErrorCode.PRACTITIONER_NOT_FOUND));
        if (!practitioner.treatments().contains(treatment.id())) {
            throw new DomainException(ErrorCode.PRACTITIONER_CANNOT_PERFORM);
        }
        return List.of(practitioner);
    }

    private static boolean overlaps(BusyPeriod period, Slot slot) {
        return period.practitionerId().equals(slot.practitionerId())
                && period.startsAt().isBefore(slot.endsAt())
                && slot.startsAt().isBefore(period.endsAt());
    }
}
