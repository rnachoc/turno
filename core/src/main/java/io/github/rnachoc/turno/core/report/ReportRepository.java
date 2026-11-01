package io.github.rnachoc.turno.core.report;

import io.github.rnachoc.turno.core.tenant.ClinicDay;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads the figures of the report and the agenda. It only ever reads.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@Repository
class ReportRepository {

    /** Most appointments returned by one query. */
    private static final int MAX_ROWS = 500;

    /** Far more than a small clinic can book in the longest period the agenda can be asked for. */
    private static final int MAX_PERIOD_ROWS = 5000;

    private final JdbcClient jdbc;

    ReportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The agenda, the money and the booked time of the appointments of a day. */
    record AppointmentFigures(DailyReport.Agenda agenda, DailyReport.Money money, int bookedMinutes) {}

    AppointmentFigures appointments(ClinicDay day, Instant now) {
        return jdbc.sql(
                        """
                        SELECT count(*) FILTER (WHERE status = 'HOLD' AND hold_expires_at > :now) AS held,
                               count(*) FILTER (WHERE status = 'CONFIRMED') AS confirmed,
                               count(*) FILTER (WHERE status = 'COMPLETED') AS completed,
                               count(*) FILTER (WHERE status = 'NO_SHOW') AS no_show,
                               count(*) FILTER (WHERE status = 'CANCELLED') AS cancelled,
                               coalesce(sum(price_cents) FILTER (WHERE status = 'COMPLETED'), 0) AS completed_cents,
                               coalesce(sum(price_cents) FILTER (WHERE status = 'NO_SHOW'), 0) AS no_show_cents,
                               coalesce(sum(deposit_cents) FILTER (WHERE deposit_forfeited), 0) AS forfeited_cents,
                               coalesce(sum(extract(epoch FROM ends_at - starts_at) / 60)
                                        FILTER (WHERE status IN ('CONFIRMED', 'COMPLETED', 'NO_SHOW')), 0)::integer
                                   AS booked_minutes
                        FROM appointment
                        WHERE starts_at >= :from AND starts_at < :to
                        """)
                .param("now", timestamp(now))
                .param("from", timestamp(day.start()))
                .param("to", timestamp(day.end()))
                .query(
                        (rs, row) ->
                                new AppointmentFigures(
                                        new DailyReport.Agenda(
                                                rs.getInt("held"),
                                                rs.getInt("confirmed"),
                                                rs.getInt("completed"),
                                                rs.getInt("no_show"),
                                                rs.getInt("cancelled")),
                                        new DailyReport.Money(
                                                rs.getLong("completed_cents"),
                                                rs.getLong("no_show_cents"),
                                                rs.getLong("forfeited_cents")),
                                        rs.getInt("booked_minutes")))
                .single();
    }

    DailyReport.Activity activity(ClinicDay day) {
        return jdbc.sql(
                        """
                        SELECT count(*) FILTER (WHERE action = 'appointment.confirm' AND outcome = 'ACCEPTED') AS booked,
                               count(*) FILTER (WHERE action = 'appointment.confirm' AND outcome = 'ACCEPTED'
                                                  AND actor = 'agents') AS booked_by_agents,
                               count(*) FILTER (WHERE action = 'appointment.cancel' AND outcome = 'ACCEPTED') AS cancelled,
                               count(*) FILTER (WHERE action = 'appointment.reschedule' AND outcome = 'ACCEPTED') AS rescheduled,
                               count(*) FILTER (WHERE outcome = 'REJECTED') AS refused
                        FROM audit_event
                        WHERE occurred_at >= :from AND occurred_at < :to
                        """)
                .param("from", timestamp(day.start()))
                .param("to", timestamp(day.end()))
                .query(
                        (rs, row) ->
                                new DailyReport.Activity(
                                        rs.getInt("booked"),
                                        rs.getInt("booked_by_agents"),
                                        rs.getInt("cancelled"),
                                        rs.getInt("rescheduled"),
                                        rs.getInt("refused")))
                .single();
    }

    DailyReport.Waitlist waitlist(ClinicDay day) {
        return jdbc.sql(
                        """
                        SELECT (SELECT count(*) FROM freed_slot
                                WHERE created_at >= :from AND created_at < :to) AS slots_freed,
                               (SELECT count(*) FROM waitlist_entry WHERE status = 'WAITING') AS waiting_now,
                               count(*) AS offers_made,
                               count(*) FILTER (WHERE a.status IN ('CONFIRMED', 'COMPLETED', 'NO_SHOW')) AS offers_accepted,
                               coalesce(sum(a.price_cents)
                                        FILTER (WHERE a.status IN ('CONFIRMED', 'COMPLETED', 'NO_SHOW')), 0) AS recovered_cents
                        FROM slot_offer o
                        JOIN appointment a ON a.id = o.appointment_id
                        WHERE o.created_at >= :from AND o.created_at < :to
                        """)
                .param("from", timestamp(day.start()))
                .param("to", timestamp(day.end()))
                .query(
                        (rs, row) ->
                                new DailyReport.Waitlist(
                                        rs.getInt("slots_freed"),
                                        rs.getInt("offers_made"),
                                        rs.getInt("offers_accepted"),
                                        rs.getLong("recovered_cents"),
                                        rs.getInt("waiting_now")))
                .single();
    }

    DailyReport.Agents agents(ClinicDay day, int bookedByAgents) {
        Map<String, Integer> reasons = new LinkedHashMap<>();
        RowCallbackHandler addReason = rs -> reasons.put(rs.getString("reason"), rs.getInt("total"));
        jdbc.sql(
                        """
                        SELECT reason, count(*) AS total
                        FROM handover
                        WHERE created_at >= :from AND created_at < :to
                        GROUP BY reason
                        ORDER BY total DESC, reason
                        """)
                .param("from", timestamp(day.start()))
                .param("to", timestamp(day.end()))
                .query(addReason);
        return jdbc.sql(
                        """
                        SELECT count(DISTINCT conversation_id) AS conversations,
                               count(*) FILTER (WHERE kind = 'model') AS model_calls,
                               coalesce(sum(input_tokens), 0) AS input_tokens,
                               coalesce(sum(output_tokens), 0) AS output_tokens,
                               coalesce(sum(estimated_cost_usd), 0) AS cost,
                               count(*) FILTER (WHERE kind = 'blocked') AS blocked
                        FROM agent_step
                        WHERE created_at >= :from AND created_at < :to
                        """)
                .param("from", timestamp(day.start()))
                .param("to", timestamp(day.end()))
                .query(
                        (rs, row) -> {
                            BigDecimal cost = rs.getBigDecimal("cost");
                            return new DailyReport.Agents(
                                    rs.getInt("conversations"),
                                    rs.getInt("model_calls"),
                                    rs.getLong("input_tokens"),
                                    rs.getLong("output_tokens"),
                                    cost,
                                    bookedByAgents == 0
                                            ? null
                                            : cost.divide(BigDecimal.valueOf(bookedByAgents), 6, RoundingMode.HALF_UP),
                                    rs.getInt("blocked"),
                                    reasons.values().stream().mapToInt(Integer::intValue).sum(),
                                    reasons);
                        })
                .single();
    }

    DailyReport.Attention attention(Instant now) {
        return jdbc.sql(
                        """
                        SELECT (SELECT count(*) FROM handover WHERE resolved_at IS NULL) AS open_handovers,
                               (SELECT count(*) FROM appointment
                                WHERE status = 'CONFIRMED' AND starts_at <= :now) AS awaiting_outcome
                        """)
                .param("now", timestamp(now))
                .query((rs, row) -> new DailyReport.Attention(rs.getInt("open_handovers"), rs.getInt("awaiting_outcome")))
                .single();
    }

    List<AttendanceReport.Row> attendance(Instant from, Instant to) {
        return jdbc.sql(
                        """
                        SELECT treatment_id, practitioner_id,
                               count(*) FILTER (WHERE status = 'COMPLETED') AS attended,
                               count(*) FILTER (WHERE status = 'NO_SHOW') AS no_show,
                               count(*) FILTER (WHERE status = 'CANCELLED') AS cancelled,
                               coalesce(sum(price_cents) FILTER (WHERE status = 'NO_SHOW'), 0) AS no_show_cents
                        FROM appointment
                        WHERE starts_at >= :from AND starts_at < :to
                          AND status IN ('COMPLETED', 'NO_SHOW', 'CANCELLED')
                        GROUP BY treatment_id, practitioner_id
                        ORDER BY no_show DESC, no_show_cents DESC, treatment_id, practitioner_id
                        """)
                .param("from", timestamp(from))
                .param("to", timestamp(to))
                .query(
                        (rs, row) ->
                                new AttendanceReport.Row(
                                        rs.getString("treatment_id"),
                                        rs.getString("practitioner_id"),
                                        rs.getInt("attended"),
                                        rs.getInt("no_show"),
                                        rs.getInt("cancelled"),
                                        rs.getLong("no_show_cents")))
                .list();
    }

    /** Lists the appointments of a day that are or were real: holds that ran out do not count. */
    List<AgendaEntry> agenda(ClinicDay day, Instant now) {
        return agenda(day.start(), day.end(), now, MAX_ROWS);
    }

    /** Lists the same for a period, from one instant up to another. */
    List<AgendaEntry> agenda(Instant from, Instant to, Instant now) {
        return agenda(from, to, now, MAX_PERIOD_ROWS);
    }

    private List<AgendaEntry> agenda(Instant from, Instant to, Instant now, int limit) {
        return jdbc.sql(
                        """
                        SELECT a.*, p.full_name, p.phone
                        FROM appointment a
                        JOIN patient p ON p.id = a.patient_id
                        WHERE a.starts_at >= :from AND a.starts_at < :to
                          AND (a.status IN ('CONFIRMED', 'COMPLETED', 'NO_SHOW', 'CANCELLED')
                               OR (a.status = 'HOLD' AND a.hold_expires_at > :now))
                        ORDER BY a.starts_at, a.practitioner_id, a.id
                        LIMIT :limit
                        """)
                .param("from", timestamp(from))
                .param("to", timestamp(to))
                .param("now", timestamp(now))
                .param("limit", limit)
                .query((rs, row) -> agendaEntry(rs))
                .list();
    }

    /** Lists the appointments that have started and still need an outcome, oldest first. */
    List<AgendaEntry> awaitingOutcome(Instant now) {
        return jdbc.sql(
                        """
                        SELECT a.*, p.full_name, p.phone
                        FROM appointment a
                        JOIN patient p ON p.id = a.patient_id
                        WHERE a.status = 'CONFIRMED' AND a.starts_at <= :now
                        ORDER BY a.starts_at, a.id
                        LIMIT :limit
                        """)
                .param("now", timestamp(now))
                .param("limit", MAX_ROWS)
                .query((rs, row) -> agendaEntry(rs))
                .list();
    }

    private static AgendaEntry agendaEntry(ResultSet rs) throws SQLException {
        return new AgendaEntry(
                rs.getObject("id", UUID.class),
                rs.getObject("patient_id", UUID.class),
                rs.getString("full_name"),
                rs.getString("phone"),
                rs.getString("practitioner_id"),
                rs.getString("treatment_id"),
                rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                rs.getObject("ends_at", OffsetDateTime.class).toInstant(),
                rs.getString("status"),
                rs.getInt("price_cents"),
                rs.getInt("deposit_cents"),
                rs.getBoolean("deposit_forfeited"));
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
