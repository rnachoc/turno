package io.github.rnachoc.turno.core.waitlist;

import io.github.rnachoc.turno.core.api.ApiPrincipal;
import io.github.rnachoc.turno.core.api.DomainException;
import io.github.rnachoc.turno.core.api.ErrorCode;
import io.github.rnachoc.turno.core.audit.AuditService;
import io.github.rnachoc.turno.core.availability.Slot;
import io.github.rnachoc.turno.core.availability.SlotCalculator;
import io.github.rnachoc.turno.core.booking.Appointment;
import io.github.rnachoc.turno.core.booking.AppointmentRepository;
import io.github.rnachoc.turno.core.booking.AppointmentStatus;
import io.github.rnachoc.turno.core.booking.BookingService;
import io.github.rnachoc.turno.core.notification.NotificationService;
import io.github.rnachoc.turno.core.patient.PatientRepository;
import io.github.rnachoc.turno.core.tenant.Tenant;
import io.github.rnachoc.turno.core.tenant.Tenant.Practitioner;
import io.github.rnachoc.turno.core.tenant.Tenant.Treatment;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides who is offered a freed slot, and when. The workflow engine only takes care of the waiting:
 * every decision is made here, with the rules of the core, each time it asks.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@Service
public class WaitlistService {

    /** Most freed slots returned by one query. */
    private static final int MAX_PENDING = 50;

    private final WaitlistRepository waitlist;
    private final AppointmentRepository appointments;
    private final BookingService bookings;
    private final PatientRepository patients;
    private final NotificationService notifications;
    private final AuditService audit;
    private final Tenant tenant;
    private final Clock clock;

    WaitlistService(
            WaitlistRepository waitlist,
            AppointmentRepository appointments,
            BookingService bookings,
            PatientRepository patients,
            NotificationService notifications,
            AuditService audit,
            Tenant tenant,
            Clock clock) {
        this.waitlist = waitlist;
        this.appointments = appointments;
        this.bookings = bookings;
        this.patients = patients;
        this.notifications = notifications;
        this.audit = audit;
        this.tenant = tenant;
        this.clock = clock;
    }

    /**
     * Puts a patient on the list. Asking for it is what allows the clinic to contact them about it.
     *
     * @param principal the caller
     * @param patientId the patient
     * @param treatmentId the treatment they want
     * @param practitionerId the practitioner they want, if they mind
     * @return the new entry
     */
    @Transactional
    public WaitlistEntry join(
            ApiPrincipal principal, UUID patientId, String treatmentId, @Nullable String practitionerId) {
        if (!patients.lock(patientId)) {
            throw new DomainException(ErrorCode.PATIENT_NOT_FOUND);
        }
        Treatment treatment =
                tenant.treatment(treatmentId)
                        .orElseThrow(() -> new DomainException(ErrorCode.TREATMENT_NOT_FOUND));
        if (practitionerId != null) {
            Practitioner practitioner =
                    tenant.practitioner(practitionerId)
                            .orElseThrow(() -> new DomainException(ErrorCode.PRACTITIONER_NOT_FOUND));
            if (!practitioner.treatments().contains(treatment.id())) {
                throw new DomainException(ErrorCode.PRACTITIONER_CANNOT_PERFORM);
            }
        }
        WaitlistEntry entry =
                new WaitlistEntry(
                        UUID.randomUUID(), patientId, treatmentId, practitionerId, "WAITING", clock.instant());
        if (!waitlist.insertIfNotWaiting(entry)) {
            throw new DomainException(ErrorCode.ALREADY_ON_WAITLIST);
        }
        audit.accepted(principal.actor(), "waitlist.join", entry.id().toString(), patientId, Map.of("treatmentId", treatmentId));
        return entry;
    }

    /**
     * Takes a patient off the list.
     *
     * @param principal the caller
     * @param patientId the patient
     * @param entryId the entry
     */
    @Transactional
    public void leave(ApiPrincipal principal, UUID patientId, UUID entryId) {
        if (!waitlist.remove(entryId, patientId)) {
            throw new DomainException(ErrorCode.WAITLIST_ENTRY_NOT_FOUND);
        }
        audit.accepted(principal.actor(), "waitlist.leave", entryId.toString(), patientId, Map.of());
    }

    /**
     * Lists a patient's entries.
     *
     * @param principal the caller
     * @param patientId the patient
     * @return the entries, oldest first
     */
    @Transactional
    public List<WaitlistEntry> list(ApiPrincipal principal, UUID patientId) {
        audit.accepted(principal.actor(), "waitlist.list", null, patientId, Map.of());
        return waitlist.listForPatient(patientId);
    }

    /**
     * Lists the freed slots that no workflow has picked up yet.
     *
     * @return the slots, oldest first
     */
    @Transactional(readOnly = true)
    public List<FreedSlot> pendingSlots() {
        return waitlist.pendingSlots(MAX_PENDING);
    }

    /**
     * Records that a workflow is taking care of a slot. Doing it twice is harmless.
     *
     * @param slotId the slot
     */
    @Transactional
    public void acknowledge(UUID slotId) {
        if (!waitlist.markPickedUp(slotId, clock.instant())) {
            throw new DomainException(ErrorCode.FREED_SLOT_NOT_FOUND);
        }
    }

    /**
     * Decides what happens next with a freed slot and, if the answer is an offer, makes it.
     *
     * <p>Locking the slot makes a retry safe: if an offer is already running, it is returned instead
     * of making another one.
     *
     * @param principal the caller
     * @param slotId the slot
     * @return the decision
     */
    @Transactional
    public NextOffer nextOffer(ApiPrincipal principal, UUID slotId) {
        Instant now = clock.instant();
        FreedSlot slot =
                waitlist.lockSlot(slotId)
                        .orElseThrow(() -> new DomainException(ErrorCode.FREED_SLOT_NOT_FOUND));
        Optional<WaitlistRepository.OfferState> running = waitlist.findRunningOffer(slotId, now);
        if (running.isPresent()) {
            WaitlistRepository.OfferState offer = running.get();
            return NextOffer.offered(offer.id(), offer.appointmentId(), offer.holdExpiresAt());
        }
        if (waitlist.countOffers(slotId) >= tenant.waitlist().maxOffersPerSlot()) {
            return NextOffer.finished(NextOffer.Decision.LIMIT_REACHED);
        }
        Instant expiresAt = now.plus(Duration.ofMinutes(tenant.waitlist().offerTtlMinutes()));
        if (slot.startsAt().isBefore(expiresAt)) {
            return NextOffer.finished(NextOffer.Decision.TOO_LATE);
        }
        if (appointments.findBusy(slot.startsAt(), slot.endsAt(), now).stream()
                .anyMatch(busy -> busy.practitionerId().equals(slot.practitionerId()))) {
            return NextOffer.finished(NextOffer.Decision.SLOT_TAKEN);
        }
        Optional<Instant> contactAt = nextContactTime(now);
        if (contactAt.isPresent()) {
            return NextOffer.waitUntil(contactAt.get());
        }
        return offerToFirstEligible(principal, slot, now, expiresAt);
    }

    /**
     * Reads the outcome of an offer.
     *
     * @param offerId the offer
     * @return {@code PENDING}, {@code ACCEPTED} or {@code DECLINED}
     */
    @Transactional(readOnly = true)
    public String offerOutcome(UUID offerId) {
        WaitlistRepository.OfferState offer =
                waitlist.findOffer(offerId)
                        .orElseThrow(() -> new DomainException(ErrorCode.OFFER_NOT_FOUND));
        AppointmentStatus status = AppointmentStatus.valueOf(offer.appointmentStatus());
        if (status == AppointmentStatus.HOLD && offer.holdExpiresAt().isAfter(clock.instant())) {
            return "PENDING";
        }
        boolean accepted =
                status == AppointmentStatus.CONFIRMED
                        || status == AppointmentStatus.COMPLETED
                        || status == AppointmentStatus.NO_SHOW
                        || status == AppointmentStatus.CANCELLED;
        return accepted ? "ACCEPTED" : "DECLINED";
    }

    private NextOffer offerToFirstEligible(
            ApiPrincipal principal, FreedSlot slot, Instant now, Instant expiresAt) {
        Practitioner practitioner = tenant.practitioner(slot.practitionerId()).orElse(null);
        if (practitioner == null) {
            return NextOffer.finished(NextOffer.Decision.NO_CANDIDATE);
        }
        Instant startOfDay = startOfLocalDay(now);
        for (WaitlistEntry entry : waitlist.queueFor(slot.id())) {
            Optional<Slot> fit = fittingSlot(entry, practitioner, slot, now);
            if (fit.isEmpty()
                    || waitlist.countOffersToPatientSince(entry.patientId(), startOfDay)
                            >= tenant.outreach().maxAttemptsPerPatientPerDay()) {
                continue;
            }
            if (!patients.lock(entry.patientId())
                    || appointments.countActiveHolds(entry.patientId(), now)
                            >= tenant.booking().maxActiveHoldsPerPatient()) {
                continue;
            }
            Treatment treatment = tenant.treatment(entry.treatmentId()).orElseThrow();
            Appointment hold =
                    new Appointment(
                            UUID.randomUUID(),
                            entry.patientId(),
                            practitioner.id(),
                            treatment.id(),
                            fit.get().startsAt(),
                            fit.get().endsAt(),
                            AppointmentStatus.HOLD,
                            expiresAt,
                            treatment.priceCents(),
                            treatment.depositCents(),
                            false,
                            now,
                            now);
            bookings.expireHoldsInTheWay(fit.get(), entry.patientId(), now);
            // A conflict here means that the patient is busy at that time, so the next one is asked.
            if (!appointments.insertIfFree(hold)) {
                continue;
            }
            UUID offerId = UUID.randomUUID();
            waitlist.insertOffer(offerId, slot.id(), entry.id(), entry.patientId(), hold.id(), now);
            notifications.offerMade(entry.patientId(), hold.id(), now);
            audit.accepted(
                    principal.actor(),
                    "waitlist.offer",
                    hold.id().toString(),
                    entry.patientId(),
                    Map.of("freedSlotId", slot.id().toString(), "treatmentId", treatment.id()));
            return NextOffer.offered(offerId, hold.id(), expiresAt);
        }
        return NextOffer.finished(NextOffer.Decision.NO_CANDIDATE);
    }

    /** Checks the treatment against the normal booking rules, and that it fits in the freed period. */
    private Optional<Slot> fittingSlot(
            WaitlistEntry entry, Practitioner practitioner, FreedSlot freed, Instant now) {
        if (entry.practitionerId() != null && !entry.practitionerId().equals(practitioner.id())) {
            return Optional.empty();
        }
        Optional<Treatment> treatment = tenant.treatment(entry.treatmentId());
        if (treatment.isEmpty() || !practitioner.treatments().contains(treatment.get().id())) {
            return Optional.empty();
        }
        return SlotCalculator.slotAt(tenant, practitioner, treatment.get(), freed.startsAt(), now)
                .filter(slot -> !slot.endsAt().isAfter(freed.endsAt()));
    }

    /** Returns when patients may next be contacted, if that is not right now. */
    private Optional<Instant> nextContactTime(Instant now) {
        ZoneId zone = tenant.timezone();
        ZonedDateTime local = now.atZone(zone);
        LocalTime from = tenant.outreach().allowedFrom();
        LocalTime until = tenant.outreach().allowedUntil();
        LocalTime time = local.toLocalTime();
        if (!time.isBefore(from) && time.isBefore(until)) {
            return Optional.empty();
        }
        LocalDate day = time.isBefore(from) ? local.toLocalDate() : local.toLocalDate().plusDays(1);
        return Optional.of(day.atTime(from).atZone(zone).toInstant());
    }

    private Instant startOfLocalDay(Instant now) {
        ZoneId zone = tenant.timezone();
        return now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
    }
}
