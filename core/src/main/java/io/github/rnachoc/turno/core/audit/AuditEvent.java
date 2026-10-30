package io.github.rnachoc.turno.core.audit;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * One entry of the audit trail. It holds identifiers and rule outcomes, but no personal data.
 *
 * @param id position in the trail
 * @param occurredAt when it happened
 * @param actor who did it
 * @param action what they tried to do
 * @param outcome {@code ACCEPTED} or {@code REJECTED}
 * @param entityId the entity affected, if any
 * @param patientId the patient concerned, if any
 * @param details further facts about the action
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
public record AuditEvent(
        long id,
        Instant occurredAt,
        String actor,
        String action,
        String outcome,
        @Nullable String entityId,
        @Nullable UUID patientId,
        JsonNode details) {}
