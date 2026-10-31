package io.github.rnachoc.turno.core.waitlist;

import io.github.rnachoc.turno.core.api.ApiPrincipal;
import io.github.rnachoc.turno.core.idempotency.IdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
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
 * HTTP interface for a patient's place on the waitlist.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@RestController
@RequestMapping("/api/v1/patients/{patientId}/waitlist")
class WaitlistController {

    /** Request to join the list. */
    record JoinRequest(@NotBlank String treatmentId, @Nullable String practitionerId) {}

    private final WaitlistService waitlist;
    private final IdempotencyService idempotency;

    WaitlistController(WaitlistService waitlist, IdempotencyService idempotency) {
        this.waitlist = waitlist;
        this.idempotency = idempotency;
    }

    @PostMapping
    ResponseEntity<String> join(
            @RequestAttribute(ApiPrincipal.ATTRIBUTE) ApiPrincipal principal,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) @Nullable String key,
            @PathVariable UUID patientId,
            @Valid @RequestBody JoinRequest body,
            HttpServletRequest request) {
        return idempotency.execute(
                principal,
                key,
                request,
                body,
                HttpStatus.CREATED,
                () -> waitlist.join(principal, patientId, body.treatmentId(), body.practitionerId()));
    }

    @PostMapping("/{entryId}/leave")
    ResponseEntity<String> leave(
            @RequestAttribute(ApiPrincipal.ATTRIBUTE) ApiPrincipal principal,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) @Nullable String key,
            @PathVariable UUID patientId,
            @PathVariable UUID entryId,
            HttpServletRequest request) {
        return idempotency.execute(
                principal,
                key,
                request,
                null,
                HttpStatus.OK,
                () -> {
                    waitlist.leave(principal, patientId, entryId);
                    return Map.of("status", "REMOVED");
                });
    }

    @GetMapping
    List<WaitlistEntry> list(
            @RequestAttribute(ApiPrincipal.ATTRIBUTE) ApiPrincipal principal,
            @PathVariable UUID patientId) {
        return waitlist.list(principal, patientId);
    }
}
