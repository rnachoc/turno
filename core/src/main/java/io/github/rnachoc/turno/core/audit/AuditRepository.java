package io.github.rnachoc.turno.core.audit;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores the audit trail. It offers no way to change an entry, and the database refuses it anyway.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@Repository
class AuditRepository {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    AuditRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    void insert(
            Instant occurredAt,
            String actor,
            String action,
            String outcome,
            @Nullable String entityId,
            @Nullable UUID patientId,
            String details) {
        jdbc.sql(
                        """
                        INSERT INTO audit_event (occurred_at, actor, action, outcome, entity_id, patient_id, details)
                        VALUES (:occurredAt, :actor, :action, :outcome, :entityId, :patientId, CAST(:details AS jsonb))
                        """)
                .param("occurredAt", OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC))
                .param("actor", actor)
                .param("action", action)
                .param("outcome", outcome)
                .param("entityId", entityId)
                .param("patientId", patientId)
                .param("details", details)
                .update();
    }

    List<AuditEvent> find(@Nullable String entityId, @Nullable UUID patientId, int limit) {
        return jdbc.sql(
                        """
                        SELECT id, occurred_at, actor, action, outcome, entity_id, patient_id, details::text AS details
                        FROM audit_event
                        WHERE (CAST(:entityId AS text) IS NULL OR entity_id = :entityId)
                          AND (CAST(:patientId AS uuid) IS NULL OR patient_id = :patientId)
                        ORDER BY id DESC
                        LIMIT :limit
                        """)
                .param("entityId", entityId)
                .param("patientId", patientId)
                .param("limit", limit)
                .query(
                        (rs, row) ->
                                new AuditEvent(
                                        rs.getLong("id"),
                                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                                        rs.getString("actor"),
                                        rs.getString("action"),
                                        rs.getString("outcome"),
                                        rs.getString("entity_id"),
                                        rs.getObject("patient_id", UUID.class),
                                        json.readTree(rs.getString("details"))))
                .list();
    }
}
