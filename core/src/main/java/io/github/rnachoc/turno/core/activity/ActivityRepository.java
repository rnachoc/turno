package io.github.rnachoc.turno.core.activity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Stores what the agents did and the conversations that wait for a person.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@Repository
class ActivityRepository {

    /** Most lines returned by one query. */
    private static final int MAX_ROWS = 200;

    private final JdbcClient jdbc;

    ActivityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insertConversationIfNew(UUID id, UUID patientId, Instant now) {
        jdbc.sql(
                        """
                        INSERT INTO conversation (id, patient_id, created_at) VALUES (:id, :patientId, :now)
                        ON CONFLICT (id) DO NOTHING
                        """)
                .param("id", id)
                .param("patientId", patientId)
                .param("now", timestamp(now))
                .update();
    }

    Optional<UUID> patientOf(UUID conversationId) {
        return jdbc.sql("SELECT patient_id FROM conversation WHERE id = :id")
                .param("id", conversationId)
                .query(UUID.class)
                .optional();
    }

    /**
     * Stores a step unless the core already has it.
     *
     * @return whether it was new
     */
    boolean insertStepIfNew(UUID conversationId, AgentStep step) {
        return jdbc.sql(
                                """
                                INSERT INTO agent_step (conversation_id, position, kind, detail, input_tokens,
                                                        output_tokens, estimated_cost_usd, seconds, created_at)
                                VALUES (:conversationId, :position, :kind, :detail, :inputTokens,
                                        :outputTokens, :estimatedCostUsd, :seconds, :createdAt)
                                ON CONFLICT (conversation_id, position) DO NOTHING
                                """)
                        .param("conversationId", conversationId)
                        .param("position", step.position())
                        .param("kind", step.kind())
                        .param("detail", step.detail())
                        .param("inputTokens", step.inputTokens())
                        .param("outputTokens", step.outputTokens())
                        .param("estimatedCostUsd", step.estimatedCostUsd())
                        .param("seconds", step.seconds())
                        .param("createdAt", timestamp(step.createdAt()))
                        .update()
                == 1;
    }

    /**
     * Opens a handover unless the same request for a person is still open.
     *
     * @return whether it was opened
     */
    boolean openHandoverIfNotOpen(UUID id, UUID conversationId, UUID patientId, String reason, Instant now) {
        return jdbc.sql(
                                """
                                INSERT INTO handover (id, conversation_id, patient_id, reason, created_at)
                                VALUES (:id, :conversationId, :patientId, :reason, :now)
                                ON CONFLICT (conversation_id, reason) WHERE resolved_at IS NULL DO NOTHING
                                """)
                        .param("id", id)
                        .param("conversationId", conversationId)
                        .param("patientId", patientId)
                        .param("reason", reason)
                        .param("now", timestamp(now))
                        .update()
                == 1;
    }

    /** Lists what waits for a person: emergencies first, then whoever has waited longest. */
    List<Handover> openHandovers() {
        return jdbc.sql(
                        """
                        SELECT h.id, h.conversation_id, h.patient_id, p.full_name, p.phone, h.reason, h.created_at
                        FROM handover h
                        JOIN patient p ON p.id = h.patient_id
                        WHERE h.resolved_at IS NULL
                        ORDER BY h.reason = 'triage_emergency' DESC, h.created_at, h.id
                        LIMIT :limit
                        """)
                .param("limit", MAX_ROWS)
                .query(
                        (rs, row) ->
                                new Handover(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("conversation_id", UUID.class),
                                        rs.getObject("patient_id", UUID.class),
                                        rs.getString("full_name"),
                                        rs.getString("phone"),
                                        rs.getString("reason"),
                                        instant(rs, "created_at")))
                .list();
    }

    /**
     * Closes a handover that is still open.
     *
     * @return its patient, if it was open
     */
    Optional<UUID> resolve(UUID id, String actor, Instant now) {
        return jdbc.sql(
                        """
                        UPDATE handover SET resolved_at = :now, resolved_by = :actor
                        WHERE id = :id AND resolved_at IS NULL
                        RETURNING patient_id
                        """)
                .param("id", id)
                .param("actor", actor)
                .param("now", timestamp(now))
                .query(UUID.class)
                .optional();
    }

    List<ConversationSummary> recentConversations() {
        return jdbc.sql(
                        """
                        SELECT c.id, c.patient_id, p.full_name, c.created_at,
                               count(s.position) AS steps,
                               coalesce(sum(s.estimated_cost_usd), 0) AS cost,
                               EXISTS (SELECT 1 FROM handover h
                                       WHERE h.conversation_id = c.id AND h.resolved_at IS NULL) AS waiting
                        FROM conversation c
                        JOIN patient p ON p.id = c.patient_id
                        LEFT JOIN agent_step s ON s.conversation_id = c.id
                        GROUP BY c.id, p.full_name
                        ORDER BY c.created_at DESC, c.id
                        LIMIT :limit
                        """)
                .param("limit", MAX_ROWS)
                .query(
                        (rs, row) ->
                                new ConversationSummary(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("patient_id", UUID.class),
                                        rs.getString("full_name"),
                                        instant(rs, "created_at"),
                                        rs.getInt("steps"),
                                        rs.getBigDecimal("cost"),
                                        rs.getBoolean("waiting")))
                .list();
    }

    Optional<ConversationDetail> conversation(UUID id) {
        return jdbc.sql(
                        """
                        SELECT c.patient_id, p.full_name
                        FROM conversation c
                        JOIN patient p ON p.id = c.patient_id
                        WHERE c.id = :id
                        """)
                .param("id", id)
                .query(
                        (rs, row) ->
                                new ConversationDetail(
                                        id,
                                        rs.getObject("patient_id", UUID.class),
                                        rs.getString("full_name"),
                                        steps(id)))
                .optional();
    }

    private List<AgentStep> steps(UUID conversationId) {
        return jdbc.sql("SELECT * FROM agent_step WHERE conversation_id = :id ORDER BY position")
                .param("id", conversationId)
                .query(
                        (rs, row) ->
                                new AgentStep(
                                        rs.getInt("position"),
                                        rs.getString("kind"),
                                        rs.getString("detail"),
                                        rs.getInt("input_tokens"),
                                        rs.getInt("output_tokens"),
                                        rs.getBigDecimal("estimated_cost_usd"),
                                        rs.getBigDecimal("seconds"),
                                        instant(rs, "created_at")))
                .list();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}
