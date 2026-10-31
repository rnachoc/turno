package io.github.rnachoc.turno.core.audit;

import io.github.rnachoc.turno.core.api.DomainException;
import io.github.rnachoc.turno.core.api.ErrorCode;
import io.github.rnachoc.turno.core.metrics.DomainMetrics;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records what happened and what was refused.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@Service
public class AuditService {

    /** Most entries returned by one query. */
    public static final int MAX_LIMIT = 200;

    private static final Pattern ENTITY_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,100}");

    private final AuditRepository repository;
    private final Clock clock;
    private final JsonMapper json;
    private final DomainMetrics metrics;

    AuditService(AuditRepository repository, Clock clock, JsonMapper json, DomainMetrics metrics) {
        this.repository = repository;
        this.clock = clock;
        this.json = json;
        this.metrics = metrics;
    }

    /**
     * Records an accepted action. It joins the current transaction, so the action and its entry
     * either both happen or neither does.
     *
     * @param actor who did it
     * @param action what they did
     * @param entityId the entity affected, if any
     * @param patientId the patient concerned, if any
     * @param details further facts, which must not include personal data
     */
    @Transactional
    public void accepted(
            String actor,
            String action,
            @Nullable String entityId,
            @Nullable UUID patientId,
            Map<String, ?> details) {
        repository.insert(
                clock.instant(), actor, action, "ACCEPTED", entityId, patientId, json.writeValueAsString(details));
        metrics.audited(actor, action, "ACCEPTED");
    }

    /**
     * Records a refused action. It must be called once the refused transaction has rolled back.
     *
     * @param actor who tried it
     * @param action what they tried
     * @param entityId the entity concerned, if any
     * @param patientId the patient concerned, if any
     * @param reason the error code of the refusal
     */
    @Transactional
    public void rejected(
            String actor,
            String action,
            @Nullable String entityId,
            @Nullable UUID patientId,
            String reason) {
        repository.insert(
                clock.instant(),
                actor,
                action,
                "REJECTED",
                entityId,
                patientId,
                json.writeValueAsString(Map.of("reason", reason)));
        metrics.audited(actor, action, "REJECTED");
    }

    /**
     * Reads the trail, newest first.
     *
     * @param entityId restricts the result to this entity, if given
     * @param patientId restricts the result to this patient, if given
     * @param limit how many entries to return; capped at {@link #MAX_LIMIT}
     * @return the matching entries
     */
    @Transactional(readOnly = true)
    public List<AuditEvent> find(@Nullable String entityId, @Nullable UUID patientId, int limit) {
        // Entities are identifiers and dates, and PostgreSQL fails on some characters of anything else.
        if (entityId != null && !ENTITY_ID.matcher(entityId).matches()) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED);
        }
        return repository.find(entityId, patientId, Math.clamp(limit, 1, MAX_LIMIT));
    }
}
