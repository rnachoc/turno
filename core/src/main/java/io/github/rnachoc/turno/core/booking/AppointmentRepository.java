package io.github.rnachoc.turno.core.booking;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Stores appointments. The exclusion constraints of the {@code appointment} table are the final
 * word on double bookings; this class only translates their verdict.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@Repository
public class AppointmentRepository {

    /** Result of moving an appointment to a new time. */
    enum MoveResult {
        MOVED,
        PRACTITIONER_BUSY,
        PATIENT_BUSY
    }

    /** A hold that has just been marked as expired. */
    record ExpiredHold(UUID id, UUID patientId) {}

    /** A period in which a practitioner is occupied. */
    public record BusyPeriod(String practitionerId, Instant startsAt, Instant endsAt) {}

    private static final String EXCLUSION_VIOLATION = "23P01";
    private static final String ACTIVE =
            "(status = 'CONFIRMED' OR (status = 'HOLD' AND hold_expires_at > :now))";

    private final JdbcClient jdbc;

    AppointmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts an appointment unless it collides with an active one. {@code ON CONFLICT} makes a
     * concurrent insert wait for the winner instead of deadlocking with it.
     *
     * @param appointment the appointment
     * @return whether it was inserted
     */
    public boolean insertIfFree(Appointment appointment) {
        return jdbc.sql(
                                """
                                INSERT INTO appointment (id, patient_id, practitioner_id, treatment_id, starts_at, ends_at,
                                    status, hold_expires_at, price_cents, deposit_cents, created_at, updated_at)
                                VALUES (:id, :patientId, :practitionerId, :treatmentId, :startsAt, :endsAt,
                                    :status, :holdExpiresAt, :priceCents, :depositCents, :createdAt, :updatedAt)
                                ON CONFLICT DO NOTHING
                                """)
                        .param("id", appointment.id())
                        .param("patientId", appointment.patientId())
                        .param("practitionerId", appointment.practitionerId())
                        .param("treatmentId", appointment.treatmentId())
                        .param("startsAt", timestamp(appointment.startsAt()))
                        .param("endsAt", timestamp(appointment.endsAt()))
                        .param("status", appointment.status().name())
                        .param("holdExpiresAt", timestamp(appointment.holdExpiresAt()))
                        .param("priceCents", appointment.priceCents())
                        .param("depositCents", appointment.depositCents())
                        .param("createdAt", timestamp(appointment.createdAt()))
                        .param("updatedAt", timestamp(appointment.updatedAt()))
                        .update()
                == 1;
    }

    Optional<Appointment> findForPatient(UUID id, UUID patientId) {
        return jdbc.sql("SELECT * FROM appointment WHERE id = :id AND patient_id = :patientId")
                .param("id", id)
                .param("patientId", patientId)
                .query(AppointmentRepository::map)
                .optional();
    }

    Optional<Appointment> lockForPatient(UUID id, UUID patientId) {
        return jdbc.sql("SELECT * FROM appointment WHERE id = :id AND patient_id = :patientId FOR UPDATE")
                .param("id", id)
                .param("patientId", patientId)
                .query(AppointmentRepository::map)
                .optional();
    }

    Optional<Appointment> lock(UUID id) {
        return jdbc.sql("SELECT * FROM appointment WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(AppointmentRepository::map)
                .optional();
    }

    List<Appointment> listForPatient(UUID patientId) {
        return jdbc.sql("SELECT * FROM appointment WHERE patient_id = :patientId ORDER BY starts_at, id")
                .param("patientId", patientId)
                .query(AppointmentRepository::map)
                .list();
    }

    public int countActiveHolds(UUID patientId, Instant now) {
        return jdbc.sql(
                        """
                        SELECT count(*) FROM appointment
                        WHERE patient_id = :patientId AND status = 'HOLD' AND hold_expires_at > :now
                        """)
                .param("patientId", patientId)
                .param("now", timestamp(now))
                .query(Integer.class)
                .single();
    }

    boolean patientIsBusy(UUID patientId, Instant startsAt, Instant endsAt, Instant now) {
        return jdbc.sql(
                                "SELECT count(*) FROM appointment WHERE patient_id = :patientId AND "
                                        + ACTIVE
                                        + " AND tstzrange(starts_at, ends_at, '[)') && tstzrange(:startsAt, :endsAt, '[)')")
                        .param("patientId", patientId)
                        .param("now", timestamp(now))
                        .param("startsAt", timestamp(startsAt))
                        .param("endsAt", timestamp(endsAt))
                        .query(Integer.class)
                        .single()
                > 0;
    }

    /**
     * Lists what occupies each practitioner in a period, ignoring holds that have run out.
     *
     * @param from start of the period
     * @param to end of the period
     * @param now the current instant
     * @return the occupied periods
     */
    public List<BusyPeriod> findBusy(Instant from, Instant to, Instant now) {
        return jdbc.sql(
                        "SELECT practitioner_id, starts_at, ends_at FROM appointment WHERE "
                                + ACTIVE
                                + " AND tstzrange(starts_at, ends_at, '[)') && tstzrange(:from, :to, '[)')")
                .param("now", timestamp(now))
                .param("from", timestamp(from))
                .param("to", timestamp(to))
                .query(
                        (rs, row) ->
                                new BusyPeriod(
                                        rs.getString("practitioner_id"),
                                        instant(rs, "starts_at"),
                                        instant(rs, "ends_at")))
                .list();
    }

    /**
     * Marks as expired the holds that have run out and stand in the way of a new period. The
     * constraints cannot do this themselves, because they do not know what time it is.
     *
     * @return the holds it expired
     */
    List<ExpiredHold> expireHoldsInTheWay(
            String practitionerId, UUID patientId, Instant startsAt, Instant endsAt, Instant now) {
        return jdbc.sql(
                        """
                        UPDATE appointment SET status = 'EXPIRED', updated_at = :now
                        WHERE status = 'HOLD' AND hold_expires_at <= :now
                          AND (practitioner_id = :practitionerId OR patient_id = :patientId)
                          AND tstzrange(starts_at, ends_at, '[)') && tstzrange(:startsAt, :endsAt, '[)')
                        RETURNING id, patient_id
                        """)
                .param("now", timestamp(now))
                .param("practitionerId", practitionerId)
                .param("patientId", patientId)
                .param("startsAt", timestamp(startsAt))
                .param("endsAt", timestamp(endsAt))
                .query(AppointmentRepository::mapExpired)
                .list();
    }

    List<ExpiredHold> expireAllDueHolds(Instant now) {
        return jdbc.sql(
                        """
                        UPDATE appointment SET status = 'EXPIRED', updated_at = :now
                        WHERE status = 'HOLD' AND hold_expires_at <= :now
                        RETURNING id, patient_id
                        """)
                .param("now", timestamp(now))
                .query(AppointmentRepository::mapExpired)
                .list();
    }

    /** Changes the status and clears the hold expiry, which only applies while on hold. */
    void updateStatus(UUID id, AppointmentStatus status, boolean depositForfeited, Instant now) {
        jdbc.sql(
                        """
                        UPDATE appointment
                        SET status = :status, hold_expires_at = NULL, deposit_forfeited = :depositForfeited, updated_at = :now
                        WHERE id = :id
                        """)
                .param("status", status.name())
                .param("depositForfeited", depositForfeited)
                .param("now", timestamp(now))
                .param("id", id)
                .update();
    }

    /**
     * Moves an appointment to a new practitioner and time. On a collision the database aborts the
     * transaction, so the caller must refuse the request and let it roll back.
     *
     * @return the outcome
     */
    MoveResult move(UUID id, String practitionerId, Instant startsAt, Instant endsAt, Instant now) {
        try {
            jdbc.sql(
                            """
                            UPDATE appointment
                            SET practitioner_id = :practitionerId, starts_at = :startsAt, ends_at = :endsAt, updated_at = :now
                            WHERE id = :id
                            """)
                    .param("practitionerId", practitionerId)
                    .param("startsAt", timestamp(startsAt))
                    .param("endsAt", timestamp(endsAt))
                    .param("now", timestamp(now))
                    .param("id", id)
                    .update();
            return MoveResult.MOVED;
        } catch (DataIntegrityViolationException e) {
            Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
            if (cause instanceof SQLException sql && EXCLUSION_VIOLATION.equals(sql.getSQLState())) {
                return String.valueOf(sql.getMessage()).contains("appointment_no_patient_overlap")
                        ? MoveResult.PATIENT_BUSY
                        : MoveResult.PRACTITIONER_BUSY;
            }
            throw e;
        }
    }

    private static @Nullable OffsetDateTime timestamp(@Nullable Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static ExpiredHold mapExpired(ResultSet rs, int row) throws SQLException {
        return new ExpiredHold(rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class));
    }

    private static Appointment map(ResultSet rs, int row) throws SQLException {
        OffsetDateTime holdExpiresAt = rs.getObject("hold_expires_at", OffsetDateTime.class);
        return new Appointment(
                rs.getObject("id", UUID.class),
                rs.getObject("patient_id", UUID.class),
                rs.getString("practitioner_id"),
                rs.getString("treatment_id"),
                instant(rs, "starts_at"),
                instant(rs, "ends_at"),
                AppointmentStatus.valueOf(rs.getString("status")),
                holdExpiresAt == null ? null : holdExpiresAt.toInstant(),
                rs.getInt("price_cents"),
                rs.getInt("deposit_cents"),
                rs.getBoolean("deposit_forfeited"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }
}
