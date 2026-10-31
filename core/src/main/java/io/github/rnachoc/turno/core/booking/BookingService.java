package io.github.rnachoc.turno.core.booking;

import io.github.rnachoc.turno.core.api.ApiPrincipal;
import io.github.rnachoc.turno.core.api.DomainException;
import io.github.rnachoc.turno.core.api.ErrorCode;
import io.github.rnachoc.turno.core.audit.AuditService;
import io.github.rnachoc.turno.core.availability.Slot;
import io.github.rnachoc.turno.core.availability.SlotCalculator;
import io.github.rnachoc.turno.core.patient.PatientRepository;
import io.github.rnachoc.turno.core.tenant.Tenant;
import io.github.rnachoc.turno.core.tenant.Tenant.Practitioner;
import io.github.rnachoc.turno.core.tenant.Tenant.Treatment;
import io.github.rnachoc.turno.core.waitlist.FreedSlot;
import io.github.rnachoc.turno.core.waitlist.WaitlistRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides on every change to an appointment. Agents only propose: each proposal is checked here,
 * with the time and prices of the core itself, before anything is written.
 *
 * <p>Operations on behalf of a patient lock that patient first, which serialises them and keeps the
 * lock order the same everywhere. Asking about an appointment through the wrong patient answers
 * "not found", so that a manipulated agent cannot learn that it exists.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@Service
public class BookingService {

    private static final String SYSTEM_ACTOR = "system";

    private final AppointmentRepository appointments;
    private final PatientRepository patients;
    private final AuditService audit;
    private final WaitlistRepository waitlist;
    private final Tenant tenant;
    private final Clock clock;

    BookingService(
            AppointmentRepository appointments,
            PatientRepository patients,
            AuditService audit,
            WaitlistRepository waitlist,
            Tenant tenant,
            Clock clock) {
        this.appointments = appointments;
        this.patients = patients;
        this.audit = audit;
        this.waitlist = waitlist;
        this.tenant = tenant;
        this.clock = clock;
    }

    /**
     * Reserves a slot for a few minutes while the patient decides.
     *
     * @param principal the caller
     * @param patientId the patient
     * @param practitionerId the practitioner
     * @param treatmentId the treatment
     * @param startsAt the proposed start
     * @return the appointment, on hold
     */
    @Transactional
    public Appointment hold(
            ApiPrincipal principal, UUID patientId, String practitionerId, String treatmentId, Instant startsAt) {
        Instant now = clock.instant();
        lockPatient(patientId);
        Treatment treatment = treatment(treatmentId);
        Slot slot = bookableSlot(practitioner(practitionerId), treatment, startsAt, now);
        expireHoldsInTheWay(slot, patientId, now);

        if (appointments.countActiveHolds(patientId, now) >= tenant.booking().maxActiveHoldsPerPatient()) {
            throw new DomainException(ErrorCode.HOLD_LIMIT_REACHED);
        }
        Instant expiresAt = now.plus(Duration.ofMinutes(tenant.booking().holdTtlMinutes()));
        Appointment appointment =
                new Appointment(
                        UUID.randomUUID(),
                        patientId,
                        slot.practitionerId(),
                        slot.treatmentId(),
                        slot.startsAt(),
                        slot.endsAt(),
                        AppointmentStatus.HOLD,
                        expiresAt,
                        treatment.priceCents(),
                        treatment.depositCents(),
                        false,
                        now,
                        now);
        if (!appointments.insertIfFree(appointment)) {
            throw new DomainException(
                    appointments.patientIsBusy(patientId, slot.startsAt(), slot.endsAt(), now)
                            ? ErrorCode.PATIENT_TIME_CONFLICT
                            : ErrorCode.SLOT_UNAVAILABLE);
        }
        record(principal, "appointment.hold", appointment);
        return appointment;
    }

    /**
     * Turns a hold into a booked appointment.
     *
     * @param principal the caller
     * @param patientId the patient
     * @param appointmentId the appointment
     * @return the confirmed appointment
     */
    @Transactional
    public Appointment confirm(ApiPrincipal principal, UUID patientId, UUID appointmentId) {
        Instant now = clock.instant();
        Appointment appointment = lockOnHold(patientId, appointmentId, now);
        appointments.updateStatus(appointment.id(), AppointmentStatus.CONFIRMED, false, now);
        // If this hold came from an offer, the patient has got what they were waiting for.
        waitlist.markBookedByAppointment(appointment.id());
        record(principal, "appointment.confirm", appointment);
        return reload(appointment);
    }

    /**
     * Gives up a hold, so that the slot is free again at once.
     *
     * @param principal the caller
     * @param patientId the patient
     * @param appointmentId the appointment
     * @return the released appointment
     */
    @Transactional
    public Appointment release(ApiPrincipal principal, UUID patientId, UUID appointmentId) {
        Instant now = clock.instant();
        Appointment appointment = lockOnHold(patientId, appointmentId, now);
        appointments.updateStatus(appointment.id(), AppointmentStatus.RELEASED, false, now);
        record(principal, "appointment.release", appointment);
        return reload(appointment);
    }

    /**
     * Cancels a booked appointment. The core works out whether the deposit is lost: the caller has
     * no say in it.
     *
     * @param principal the caller
     * @param patientId the patient
     * @param appointmentId the appointment
     * @return the cancelled appointment
     */
    @Transactional
    public Appointment cancel(ApiPrincipal principal, UUID patientId, UUID appointmentId) {
        Instant now = clock.instant();
        Appointment appointment = lockConfirmed(patientId, appointmentId);
        if (!now.isBefore(appointment.startsAt())) {
            throw new DomainException(ErrorCode.APPOINTMENT_ALREADY_STARTED);
        }
        boolean forfeited =
                !isWithinFreePeriod(appointment, now)
                        && tenant.cancellation().lateCancellationKeepsDeposit()
                        && appointment.depositCents() > 0;
        appointments.updateStatus(appointment.id(), AppointmentStatus.CANCELLED, forfeited, now);
        recordFreed(appointment, now);
        audit.accepted(
                principal.actor(),
                "appointment.cancel",
                appointment.id().toString(),
                patientId,
                Map.of("depositForfeited", forfeited));
        return reload(appointment);
    }

    /**
     * Moves a booked appointment. It is only possible during the free cancellation period, because
     * otherwise moving would be a way around the late cancellation rule.
     *
     * @param principal the caller
     * @param patientId the patient
     * @param appointmentId the appointment
     * @param practitionerId the practitioner for the new time
     * @param startsAt the new start
     * @return the moved appointment
     */
    @Transactional
    public Appointment reschedule(
            ApiPrincipal principal, UUID patientId, UUID appointmentId, String practitionerId, Instant startsAt) {
        Instant now = clock.instant();
        Appointment appointment = lockConfirmed(patientId, appointmentId);
        if (!isWithinFreePeriod(appointment, now)) {
            throw new DomainException(ErrorCode.RESCHEDULE_WINDOW_CLOSED);
        }
        Slot slot = bookableSlot(practitioner(practitionerId), treatment(appointment.treatmentId()), startsAt, now);
        expireHoldsInTheWay(slot, patientId, now);

        AppointmentRepository.MoveResult result =
                appointments.move(appointment.id(), slot.practitionerId(), slot.startsAt(), slot.endsAt(), now);
        switch (result) {
            case PRACTITIONER_BUSY -> throw new DomainException(ErrorCode.SLOT_UNAVAILABLE);
            case PATIENT_BUSY -> throw new DomainException(ErrorCode.PATIENT_TIME_CONFLICT);
            case MOVED -> {}
        }
        recordFreed(appointment, now);
        Appointment moved = reload(appointment);
        record(principal, "appointment.reschedule", moved);
        return moved;
    }

    /**
     * Records that a patient attended. Staff only.
     *
     * @param principal the caller
     * @param appointmentId the appointment
     * @return the completed appointment
     */
    @Transactional
    public Appointment complete(ApiPrincipal principal, UUID appointmentId) {
        Appointment appointment = lockStarted(appointmentId);
        appointments.updateStatus(appointment.id(), AppointmentStatus.COMPLETED, false, clock.instant());
        record(principal, "appointment.complete", appointment);
        return reload(appointment);
    }

    /**
     * Records that a patient did not attend, and applies the rule on the deposit. Staff only.
     *
     * @param principal the caller
     * @param appointmentId the appointment
     * @return the updated appointment
     */
    @Transactional
    public Appointment markNoShow(ApiPrincipal principal, UUID appointmentId) {
        Appointment appointment = lockStarted(appointmentId);
        boolean forfeited = tenant.cancellation().noShowKeepsDeposit() && appointment.depositCents() > 0;
        appointments.updateStatus(appointment.id(), AppointmentStatus.NO_SHOW, forfeited, clock.instant());
        audit.accepted(
                principal.actor(),
                "appointment.no-show",
                appointment.id().toString(),
                appointment.patientId(),
                Map.of("depositForfeited", forfeited));
        return reload(appointment);
    }

    /**
     * Reads one appointment of a patient. A treatment is health data, so the access is recorded.
     *
     * @param principal the caller
     * @param patientId the patient
     * @param appointmentId the appointment
     * @return the appointment
     */
    @Transactional
    public Appointment get(ApiPrincipal principal, UUID patientId, UUID appointmentId) {
        Appointment appointment =
                appointments
                        .findForPatient(appointmentId, patientId)
                        .orElseThrow(() -> new DomainException(ErrorCode.APPOINTMENT_NOT_FOUND))
                        .asSeenAt(clock.instant());
        audit.accepted(principal.actor(), "appointment.read", appointmentId.toString(), patientId, Map.of());
        return appointment;
    }

    /**
     * Lists the appointments of a patient. A treatment is health data, so the access is recorded.
     *
     * @param principal the caller
     * @param patientId the patient
     * @return the appointments, in chronological order
     */
    @Transactional
    public List<Appointment> list(ApiPrincipal principal, UUID patientId) {
        Instant now = clock.instant();
        audit.accepted(principal.actor(), "appointment.list", null, patientId, Map.of());
        return appointments.listForPatient(patientId).stream().map(a -> a.asSeenAt(now)).toList();
    }

    /**
     * Marks every hold that has run out. Availability already ignores them; this makes their end
     * visible in the audit trail.
     *
     * @return how many holds were marked
     */
    @Transactional
    public int expireDueHolds() {
        List<AppointmentRepository.ExpiredHold> expired = appointments.expireAllDueHolds(clock.instant());
        expired.forEach(this::recordExpiry);
        return expired.size();
    }

    /** Written in this same transaction, so the workflow can neither miss it nor act on a rollback. */
    private void recordFreed(Appointment appointment, Instant now) {
        waitlist.recordFreedSlot(
                new FreedSlot(
                        UUID.randomUUID(),
                        appointment.practitionerId(),
                        appointment.startsAt(),
                        appointment.endsAt()),
                now);
    }

    private void lockPatient(UUID patientId) {
        if (!patients.lock(patientId)) {
            throw new DomainException(ErrorCode.PATIENT_NOT_FOUND);
        }
    }

    private Appointment lockForPatient(UUID patientId, UUID appointmentId) {
        lockPatient(patientId);
        return appointments
                .lockForPatient(appointmentId, patientId)
                .orElseThrow(() -> new DomainException(ErrorCode.APPOINTMENT_NOT_FOUND));
    }

    private Appointment lockOnHold(UUID patientId, UUID appointmentId, Instant now) {
        Appointment appointment = lockForPatient(patientId, appointmentId);
        if (appointment.holdHasExpired(now) || appointment.status() == AppointmentStatus.EXPIRED) {
            throw new DomainException(ErrorCode.HOLD_EXPIRED);
        }
        if (appointment.status() != AppointmentStatus.HOLD) {
            throw new DomainException(ErrorCode.INVALID_STATE);
        }
        return appointment;
    }

    private Appointment lockConfirmed(UUID patientId, UUID appointmentId) {
        Appointment appointment = lockForPatient(patientId, appointmentId);
        if (appointment.status() != AppointmentStatus.CONFIRMED) {
            throw new DomainException(ErrorCode.INVALID_STATE);
        }
        return appointment;
    }

    private Appointment lockStarted(UUID appointmentId) {
        Appointment appointment =
                appointments
                        .lock(appointmentId)
                        .orElseThrow(() -> new DomainException(ErrorCode.APPOINTMENT_NOT_FOUND));
        if (appointment.status() != AppointmentStatus.CONFIRMED) {
            throw new DomainException(ErrorCode.INVALID_STATE);
        }
        if (clock.instant().isBefore(appointment.startsAt())) {
            throw new DomainException(ErrorCode.APPOINTMENT_NOT_STARTED);
        }
        return appointment;
    }

    private boolean isWithinFreePeriod(Appointment appointment, Instant now) {
        Duration freePeriod = Duration.ofHours(tenant.cancellation().freeUntilHoursBefore());
        return !now.isAfter(appointment.startsAt().minus(freePeriod));
    }

    private Treatment treatment(String treatmentId) {
        return tenant.treatment(treatmentId)
                .orElseThrow(() -> new DomainException(ErrorCode.TREATMENT_NOT_FOUND));
    }

    private Practitioner practitioner(String practitionerId) {
        return tenant.practitioner(practitionerId)
                .orElseThrow(() -> new DomainException(ErrorCode.PRACTITIONER_NOT_FOUND));
    }

    private Slot bookableSlot(Practitioner practitioner, Treatment treatment, Instant startsAt, Instant now) {
        if (!practitioner.treatments().contains(treatment.id())) {
            throw new DomainException(ErrorCode.PRACTITIONER_CANNOT_PERFORM);
        }
        return SlotCalculator.slotAt(tenant, practitioner, treatment, startsAt, now)
                .orElseThrow(() -> new DomainException(ErrorCode.SLOT_NOT_BOOKABLE));
    }

    /**
     * Marks the holds that have run out and stand in the way of a slot, which the database's
     * constraints would otherwise treat as occupying it.
     *
     * @param slot the slot
     * @param patientId the patient who wants it
     * @param now the current instant
     */
    @Transactional
    public void expireHoldsInTheWay(Slot slot, UUID patientId, Instant now) {
        appointments
                .expireHoldsInTheWay(slot.practitionerId(), patientId, slot.startsAt(), slot.endsAt(), now)
                .forEach(this::recordExpiry);
    }

    private Appointment reload(Appointment appointment) {
        return appointments.findForPatient(appointment.id(), appointment.patientId()).orElseThrow();
    }

    private void recordExpiry(AppointmentRepository.ExpiredHold hold) {
        audit.accepted(SYSTEM_ACTOR, "appointment.expire", hold.id().toString(), hold.patientId(), Map.of());
    }

    private void record(ApiPrincipal principal, String action, Appointment appointment) {
        audit.accepted(
                principal.actor(),
                action,
                appointment.id().toString(),
                appointment.patientId(),
                Map.of(
                        "practitionerId", appointment.practitionerId(),
                        "treatmentId", appointment.treatmentId(),
                        "startsAt", appointment.startsAt().toString()));
    }
}
