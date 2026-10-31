package io.github.rnachoc.turno.core.activity;

import java.time.Instant;
import java.util.UUID;

/**
 * A conversation that waits for a person.
 *
 * @param id unique identifier
 * @param conversationId the conversation
 * @param patientId the patient
 * @param patientName how to address them
 * @param patientPhone how to reach them
 * @param reason why the agent stepped aside
 * @param createdAt when the core heard about it
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
public record Handover(
        UUID id,
        UUID conversationId,
        UUID patientId,
        String patientName,
        String patientPhone,
        String reason,
        Instant createdAt) {}
