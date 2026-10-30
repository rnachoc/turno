package io.github.rnachoc.turno.core.patient;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A patient of the clinic.
 *
 * @param id unique identifier
 * @param fullName full name
 * @param phone phone number in E.164 format, unique per patient
 * @param email email address, if known
 * @param language language in which the patient is attended
 * @param createdAt when the patient was registered
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
public record Patient(
        UUID id,
        String fullName,
        String phone,
        @Nullable String email,
        String language,
        Instant createdAt) {}
