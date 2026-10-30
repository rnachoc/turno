package io.github.rnachoc.turno.core.booking;

import io.github.rnachoc.turno.core.api.ApiPrincipal;
import io.github.rnachoc.turno.core.api.StaffOnly;
import io.github.rnachoc.turno.core.audit.AuditEvent;
import io.github.rnachoc.turno.core.audit.AuditService;
import io.github.rnachoc.turno.core.idempotency.IdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operations reserved for the clinic's staff. Marking a no-show can cost a patient their deposit,
 * so an agent can never do it, however it is asked to.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@RestController
@StaffOnly
@RequestMapping("/api/v1/staff")
class StaffController {

    private final BookingService bookings;
    private final AuditService audit;
    private final IdempotencyService idempotency;

    StaffController(BookingService bookings, AuditService audit, IdempotencyService idempotency) {
        this.bookings = bookings;
        this.audit = audit;
        this.idempotency = idempotency;
    }

    @PostMapping("/appointments/{appointmentId}/complete")
    ResponseEntity<String> complete(
            @RequestAttribute(ApiPrincipal.ATTRIBUTE) ApiPrincipal principal,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) @Nullable String key,
            @PathVariable UUID appointmentId,
            HttpServletRequest request) {
        return idempotency.execute(
                principal, key, request, null, HttpStatus.OK, () -> bookings.complete(principal, appointmentId));
    }

    @PostMapping("/appointments/{appointmentId}/no-show")
    ResponseEntity<String> noShow(
            @RequestAttribute(ApiPrincipal.ATTRIBUTE) ApiPrincipal principal,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) @Nullable String key,
            @PathVariable UUID appointmentId,
            HttpServletRequest request) {
        return idempotency.execute(
                principal, key, request, null, HttpStatus.OK, () -> bookings.markNoShow(principal, appointmentId));
    }

    @GetMapping("/audit")
    List<AuditEvent> audit(
            @RequestParam(required = false) @Nullable String entityId,
            @RequestParam(required = false) @Nullable UUID patientId,
            @RequestParam(defaultValue = "50") int limit) {
        return audit.find(entityId, patientId, limit);
    }
}
