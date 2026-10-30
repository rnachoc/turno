package io.github.rnachoc.turno.core.booking;

import io.github.rnachoc.turno.core.api.ApiPrincipal;
import io.github.rnachoc.turno.core.idempotency.IdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP interface for the appointments of a patient. Every route goes through the patient, so an
 * appointment can only be reached together with its owner.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@RestController
@RequestMapping("/api/v1/patients/{patientId}/appointments")
class BookingController {

    /** Proposal to hold a slot. It has no price: the core takes that from its own configuration. */
    record HoldRequest(@NotBlank String practitionerId, @NotBlank String treatmentId, @NotNull Instant startsAt) {}

    /** Proposal to move an appointment. */
    record RescheduleRequest(@NotBlank String practitionerId, @NotNull Instant startsAt) {}

    private final BookingService bookings;
    private final IdempotencyService idempotency;

    BookingController(BookingService bookings, IdempotencyService idempotency) {
        this.bookings = bookings;
        this.idempotency = idempotency;
    }

    @PostMapping
    ResponseEntity<String> hold(
            @RequestAttribute(ApiPrincipal.ATTRIBUTE) ApiPrincipal principal,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) @Nullable String key,
            @PathVariable UUID patientId,
            @Valid @RequestBody HoldRequest body,
            HttpServletRequest request) {
        return idempotency.execute(
                principal,
                key,
                request,
                body,
                HttpStatus.CREATED,
                () -> bookings.hold(principal, patientId, body.practitionerId(), body.treatmentId(), body.startsAt()));
    }

    @PostMapping("/{appointmentId}/confirm")
    ResponseEntity<String> confirm(
            @RequestAttribute(ApiPrincipal.ATTRIBUTE) ApiPrincipal principal,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) @Nullable String key,
            @PathVariable UUID patientId,
            @PathVariable UUID appointmentId,
            HttpServletRequest request) {
        return idempotency.execute(
                principal,
                key,
                request,
                null,
                HttpStatus.OK,
                () -> bookings.confirm(principal, patientId, appointmentId));
    }

    @PostMapping("/{appointmentId}/release")
    ResponseEntity<String> release(
            @RequestAttribute(ApiPrincipal.ATTRIBUTE) ApiPrincipal principal,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) @Nullable String key,
            @PathVariable UUID patientId,
            @PathVariable UUID appointmentId,
            HttpServletRequest request) {
        return idempotency.execute(
                principal,
                key,
                request,
                null,
                HttpStatus.OK,
                () -> bookings.release(principal, patientId, appointmentId));
    }

    @PostMapping("/{appointmentId}/cancel")
    ResponseEntity<String> cancel(
            @RequestAttribute(ApiPrincipal.ATTRIBUTE) ApiPrincipal principal,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) @Nullable String key,
            @PathVariable UUID patientId,
            @PathVariable UUID appointmentId,
            HttpServletRequest request) {
        return idempotency.execute(
                principal,
                key,
                request,
                null,
                HttpStatus.OK,
                () -> bookings.cancel(principal, patientId, appointmentId));
    }

    @PostMapping("/{appointmentId}/reschedule")
    ResponseEntity<String> reschedule(
            @RequestAttribute(ApiPrincipal.ATTRIBUTE) ApiPrincipal principal,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) @Nullable String key,
            @PathVariable UUID patientId,
            @PathVariable UUID appointmentId,
            @Valid @RequestBody RescheduleRequest body,
            HttpServletRequest request) {
        return idempotency.execute(
                principal,
                key,
                request,
                body,
                HttpStatus.OK,
                () -> bookings.reschedule(principal, patientId, appointmentId, body.practitionerId(), body.startsAt()));
    }

    @GetMapping
    List<Appointment> list(
            @RequestAttribute(ApiPrincipal.ATTRIBUTE) ApiPrincipal principal,
            @PathVariable UUID patientId) {
        return bookings.list(principal, patientId);
    }

    @GetMapping("/{appointmentId}")
    Appointment get(
            @RequestAttribute(ApiPrincipal.ATTRIBUTE) ApiPrincipal principal,
            @PathVariable UUID patientId,
            @PathVariable UUID appointmentId) {
        return bookings.get(principal, patientId, appointmentId);
    }
}
