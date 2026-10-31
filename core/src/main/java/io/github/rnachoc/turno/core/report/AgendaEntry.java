package io.github.rnachoc.turno.core.report;

import java.time.Instant;
import java.util.UUID;

/**
 * An appointment as the clinic's staff need to see it.
 *
 * @param appointmentId the appointment
 * @param patientId the patient
 * @param patientName how to address them
 * @param patientPhone how to reach them
 * @param practitionerId the practitioner
 * @param treatmentId the treatment
 * @param startsAt when it starts
 * @param endsAt when it ends
 * @param status where it stands
 * @param priceCents its price
 * @param depositCents its deposit
 * @param depositForfeited whether the deposit is recorded as lost
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
public record AgendaEntry(
        UUID appointmentId,
        UUID patientId,
        String patientName,
        String patientPhone,
        String practitionerId,
        String treatmentId,
        Instant startsAt,
        Instant endsAt,
        String status,
        int priceCents,
        int depositCents,
        boolean depositForfeited) {}
