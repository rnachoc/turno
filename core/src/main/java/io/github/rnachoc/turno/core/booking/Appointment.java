package io.github.rnachoc.turno.core.booking;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An appointment. Price, deposit and times are copies taken at booking time, so later changes to
 * the clinic's prices do not alter it.
 *
 * @param id unique identifier
 * @param patientId the patient
 * @param practitionerId the practitioner
 * @param treatmentId the treatment
 * @param startsAt first instant of the appointment
 * @param endsAt instant at which the appointment ends, exclusive
 * @param status current status
 * @param holdExpiresAt when the hold runs out; only set while on hold
 * @param priceCents agreed price, in cents
 * @param depositCents agreed deposit, in cents
 * @param depositForfeited whether the patient lost the deposit
 * @param createdAt when it was created
 * @param updatedAt when it last changed
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
public record Appointment(
        UUID id,
        UUID patientId,
        String practitionerId,
        String treatmentId,
        Instant startsAt,
        Instant endsAt,
        AppointmentStatus status,
        @Nullable Instant holdExpiresAt,
        int priceCents,
        int depositCents,
        boolean depositForfeited,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * Tells whether this is a hold whose time has run out, even if nothing has marked it yet.
     *
     * @param now the current instant
     * @return whether the hold has expired
     */
    boolean holdHasExpired(Instant now) {
        return status == AppointmentStatus.HOLD && holdExpiresAt != null && !holdExpiresAt.isAfter(now);
    }

    /**
     * Returns the appointment as callers should see it: an expired hold reads as expired from the
     * instant it runs out, without waiting for the job that marks it.
     *
     * @param now the current instant
     * @return this appointment, with its status corrected if needed
     */
    Appointment asSeenAt(Instant now) {
        if (!holdHasExpired(now)) {
            return this;
        }
        return new Appointment(
                id,
                patientId,
                practitionerId,
                treatmentId,
                startsAt,
                endsAt,
                AppointmentStatus.EXPIRED,
                holdExpiresAt,
                priceCents,
                depositCents,
                depositForfeited,
                createdAt,
                updatedAt);
    }
}
