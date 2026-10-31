package io.github.rnachoc.turno.core.waitlist;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A patient waiting for a slot to free up.
 *
 * @param id unique identifier
 * @param patientId the patient
 * @param treatmentId the treatment they want
 * @param practitionerId the practitioner they want, if they mind
 * @param status {@code WAITING}, {@code BOOKED} or {@code REMOVED}
 * @param createdAt when they joined, which decides their place in the queue
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
public record WaitlistEntry(
        UUID id,
        UUID patientId,
        String treatmentId,
        @Nullable String practitionerId,
        String status,
        Instant createdAt) {}
