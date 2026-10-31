package io.github.rnachoc.turno.core.report;

import io.github.rnachoc.turno.core.api.ApiPrincipal;
import io.github.rnachoc.turno.core.api.StaffOnly;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the clinic's owner and staff read. Patients' names are in here, so the agents stay out.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@RestController
@StaffOnly
@RequestMapping("/api/v1/staff")
class ReportController {

    private final ReportService reports;

    ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/reports/daily")
    DailyReport daily(
            @RequestAttribute(ApiPrincipal.ATTRIBUTE) ApiPrincipal principal,
            @RequestParam(required = false) @Nullable LocalDate date) {
        return reports.daily(principal, date);
    }

    @GetMapping("/reports/attendance")
    AttendanceReport attendance(
            @RequestAttribute(ApiPrincipal.ATTRIBUTE) ApiPrincipal principal,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to) {
        return reports.attendance(principal, from, to);
    }

    @GetMapping("/agenda")
    List<AgendaEntry> agenda(
            @RequestAttribute(ApiPrincipal.ATTRIBUTE) ApiPrincipal principal,
            @RequestParam(required = false) @Nullable LocalDate date) {
        return reports.agenda(principal, date);
    }

    @GetMapping("/appointments/awaiting-outcome")
    List<AgendaEntry> awaitingOutcome(@RequestAttribute(ApiPrincipal.ATTRIBUTE) ApiPrincipal principal) {
        return reports.awaitingOutcome(principal);
    }
}
