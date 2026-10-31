package io.github.rnachoc.turno.core.notification;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Something the clinic has to tell a patient, with what is needed to write it. The wording belongs to
 * whoever sends it, and no model takes part in it.
 *
 * @param id unique identifier
 * @param kind what it announces
 * @param patientId the patient
 * @param phone where to reach them
 * @param email where else to reach them, if they gave one
 * @param language the language to write in
 * @param appointmentId the appointment it is about
 * @param treatmentId the treatment
 * @param practitionerId the practitioner
 * @param startsAt when the appointment would start
 * @param expiresAt until when the patient can answer
 * @param priceCents its price
 * @param depositCents its deposit
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
public record Notification(
        UUID id,
        String kind,
        UUID patientId,
        String phone,
        @Nullable String email,
        String language,
        UUID appointmentId,
        String treatmentId,
        String practitionerId,
        Instant startsAt,
        Instant expiresAt,
        int priceCents,
        int depositCents) {}
