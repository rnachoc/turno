package io.github.rnachoc.turno.core.notification;

import io.github.rnachoc.turno.core.api.ApiPrincipal;
import io.github.rnachoc.turno.core.api.DomainException;
import io.github.rnachoc.turno.core.api.ErrorCode;
import io.github.rnachoc.turno.core.audit.AuditService;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps what the clinic has to tell patients until someone has sent it.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@Service
public class NotificationService {

    /** Most notices returned by one query. */
    private static final int MAX_PENDING = 50;

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final Clock clock;

    NotificationService(JdbcClient jdbc, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Records that an offer has to be announced. It joins the transaction of the offer.
     *
     * @param patientId the patient
     * @param appointmentId the hold that the offer consists of
     * @param now the current instant
     */
    @Transactional
    public void offerMade(UUID patientId, UUID appointmentId, Instant now) {
        jdbc.sql(
                        """
                        INSERT INTO notification (id, kind, patient_id, appointment_id, created_at)
                        VALUES (:id, 'SLOT_OFFER', :patientId, :appointmentId, :now)
                        """)
                .param("id", UUID.randomUUID())
                .param("patientId", patientId)
                .param("appointmentId", appointmentId)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    /**
     * Lists what has yet to be sent. A notice whose offer has ended has nothing left to say.
     *
     * @param principal the caller
     * @return the notices, oldest first
     */
    @Transactional
    public List<Notification> pending(ApiPrincipal principal) {
        List<Notification> pending =
                jdbc.sql(
                                """
                                SELECT n.id, n.kind, n.patient_id, p.phone, p.email, p.language, n.appointment_id,
                                       a.treatment_id, a.practitioner_id, a.starts_at, a.hold_expires_at,
                                       a.price_cents, a.deposit_cents
                                FROM notification n
                                JOIN patient p ON p.id = n.patient_id
                                JOIN appointment a ON a.id = n.appointment_id
                                WHERE n.sent_at IS NULL AND a.status = 'HOLD' AND a.hold_expires_at > :now
                                ORDER BY n.created_at, n.id
                                LIMIT :limit
                                """)
                        .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                        .param("limit", MAX_PENDING)
                        .query(
                                (rs, row) ->
                                        new Notification(
                                                rs.getObject("id", UUID.class),
                                                rs.getString("kind"),
                                                rs.getObject("patient_id", UUID.class),
                                                rs.getString("phone"),
                                                rs.getString("email"),
                                                rs.getString("language"),
                                                rs.getObject("appointment_id", UUID.class),
                                                rs.getString("treatment_id"),
                                                rs.getString("practitioner_id"),
                                                rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                                                rs.getObject("hold_expires_at", OffsetDateTime.class).toInstant(),
                                                rs.getInt("price_cents"),
                                                rs.getInt("deposit_cents")))
                        .list();
        if (!pending.isEmpty()) {
            audit.accepted(principal.actor(), "notification.list", null, null, Map.of("count", pending.size()));
        }
        return pending;
    }

    /**
     * Records that a notice went out.
     *
     * @param principal the caller
     * @param id the notice
     */
    @Transactional
    public void markSent(ApiPrincipal principal, UUID id) {
        UUID patientId =
                jdbc.sql(
                                """
                                UPDATE notification SET sent_at = :now
                                WHERE id = :id AND sent_at IS NULL
                                RETURNING patient_id
                                """)
                        .param("id", id)
                        .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                        .query(UUID.class)
                        .optional()
                        .orElseThrow(() -> new DomainException(ErrorCode.NOTIFICATION_NOT_FOUND));
        audit.accepted(principal.actor(), "notification.sent", id.toString(), patientId, Map.of());
    }
}
