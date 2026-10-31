package io.github.rnachoc.turno.core.report;

import io.github.rnachoc.turno.core.api.ApiPrincipal;
import io.github.rnachoc.turno.core.audit.AuditService;
import io.github.rnachoc.turno.core.tenant.ClinicDay;
import io.github.rnachoc.turno.core.tenant.Tenant;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Works out what the clinic's owner and staff read about a day.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@Service
public class ReportService {

    private final ReportRepository reports;
    private final AuditService audit;
    private final Tenant tenant;
    private final Clock clock;

    ReportService(ReportRepository reports, AuditService audit, Tenant tenant, Clock clock) {
        this.reports = reports;
        this.audit = audit;
        this.tenant = tenant;
        this.clock = clock;
    }

    /**
     * Works out the report of a day. All its queries see one moment, so its figures agree.
     *
     * @param principal the caller
     * @param requested the day at the clinic, or today if not given
     * @return the report
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public DailyReport daily(ApiPrincipal principal, @Nullable LocalDate requested) {
        Instant now = clock.instant();
        ClinicDay day = ClinicDay.of(tenant, requested, now);
        audit.accepted(principal.actor(), "report.read", day.date().toString(), null, Map.of());
        ReportRepository.AppointmentFigures figures = reports.appointments(day, now);
        DailyReport.Activity activity = reports.activity(day);
        int openMinutes = openMinutes(tenant, day.date());
        return new DailyReport(
                day.date(),
                now,
                tenant.timezone().getId(),
                tenant.currency(),
                figures.agenda(),
                figures.money(),
                new DailyReport.Occupancy(
                        openMinutes,
                        figures.bookedMinutes(),
                        openMinutes == 0 ? null : Math.round(100f * figures.bookedMinutes() / openMinutes)),
                activity,
                reports.waitlist(day),
                reports.agents(day, activity.bookedByAgents()),
                reports.attention(now));
    }

    /**
     * Lists the appointments of a day.
     *
     * @param principal the caller
     * @param requested the day at the clinic, or today if not given
     * @return the appointments, in order
     */
    @Transactional
    public List<AgendaEntry> agenda(ApiPrincipal principal, @Nullable LocalDate requested) {
        Instant now = clock.instant();
        ClinicDay day = ClinicDay.of(tenant, requested, now);
        audit.accepted(principal.actor(), "agenda.read", day.date().toString(), null, Map.of());
        return reports.agenda(day, now);
    }

    /**
     * Lists the appointments that need someone to say whether the patient came.
     *
     * @param principal the caller
     * @return the appointments, oldest first
     */
    @Transactional
    public List<AgendaEntry> awaitingOutcome(ApiPrincipal principal) {
        audit.accepted(principal.actor(), "agenda.awaiting-outcome", null, null, Map.of());
        return reports.awaitingOutcome(clock.instant());
    }

    /**
     * Adds up the working time of a day: the opening hours, once per practitioner who works that day.
     *
     * @param tenant the clinic
     * @param date the local date
     * @return the minutes, zero if the clinic is closed
     */
    static int openMinutes(Tenant tenant, LocalDate date) {
        if (tenant.closures().contains(date)) {
            return 0;
        }
        long perPractitioner =
                tenant.openingHours().getOrDefault(date.getDayOfWeek(), List.of()).stream()
                        .mapToLong(window -> Duration.between(window.open(), window.close()).toMinutes())
                        .sum();
        long practitioners =
                tenant.practitioners().stream()
                        .filter(practitioner -> practitioner.workingDays().contains(date.getDayOfWeek()))
                        .count();
        return Math.toIntExact(perPractitioner * practitioners);
    }
}
