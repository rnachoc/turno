package io.github.rnachoc.turno.core.activity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A line in the list of conversations.
 *
 * @param id unique identifier
 * @param patientId the patient
 * @param patientName how to address them
 * @param createdAt when the core first heard about it
 * @param steps how many steps it has
 * @param estimatedCostUsd what its model calls would cost on the API
 * @param waitingForPerson whether it has a handover nobody has resolved
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
public record ConversationSummary(
        UUID id,
        UUID patientId,
        String patientName,
        Instant createdAt,
        int steps,
        BigDecimal estimatedCostUsd,
        boolean waitingForPerson) {}
