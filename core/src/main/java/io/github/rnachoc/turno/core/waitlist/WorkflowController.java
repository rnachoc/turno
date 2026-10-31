package io.github.rnachoc.turno.core.waitlist;

import io.github.rnachoc.turno.core.api.ApiPrincipal;
import io.github.rnachoc.turno.core.api.WorkflowsOnly;
import io.github.rnachoc.turno.core.idempotency.IdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the workflow worker can ask of the core. It has its own token, which can neither talk as an
 * agent nor as staff, and agents cannot reach this.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@RestController
@WorkflowsOnly
@RequestMapping("/api/v1/workflows")
class WorkflowController {

    private final WaitlistService waitlist;
    private final IdempotencyService idempotency;

    WorkflowController(WaitlistService waitlist, IdempotencyService idempotency) {
        this.waitlist = waitlist;
        this.idempotency = idempotency;
    }

    @GetMapping("/freed-slots")
    List<FreedSlot> pending() {
        return waitlist.pendingSlots();
    }

    @PostMapping("/freed-slots/{slotId}/acknowledge")
    Map<String, String> acknowledge(@PathVariable UUID slotId) {
        waitlist.acknowledge(slotId);
        return Map.of("status", "PICKED_UP");
    }

    @PostMapping("/freed-slots/{slotId}/next-offer")
    ResponseEntity<String> nextOffer(
            @RequestAttribute(ApiPrincipal.ATTRIBUTE) ApiPrincipal principal,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) @Nullable String key,
            @PathVariable UUID slotId,
            HttpServletRequest request) {
        return idempotency.execute(
                principal, key, request, null, HttpStatus.OK, () -> waitlist.nextOffer(principal, slotId));
    }

    @GetMapping("/offers/{offerId}")
    Map<String, String> outcome(@PathVariable UUID offerId) {
        return Map.of("outcome", waitlist.offerOutcome(offerId));
    }
}
