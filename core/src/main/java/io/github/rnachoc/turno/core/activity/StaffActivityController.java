package io.github.rnachoc.turno.core.activity;

import io.github.rnachoc.turno.core.api.ApiPrincipal;
import io.github.rnachoc.turno.core.api.StaffOnly;
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
 * What the clinic's staff can see of the agents' work.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@RestController
@StaffOnly
@RequestMapping("/api/v1/staff")
class StaffActivityController {

    private final ActivityService activity;
    private final IdempotencyService idempotency;

    StaffActivityController(ActivityService activity, IdempotencyService idempotency) {
        this.activity = activity;
        this.idempotency = idempotency;
    }

    @GetMapping("/handovers")
    List<Handover> handovers(@RequestAttribute(ApiPrincipal.ATTRIBUTE) ApiPrincipal principal) {
        return activity.openHandovers(principal);
    }

    @PostMapping("/handovers/{handoverId}/resolve")
    ResponseEntity<String> resolve(
            @RequestAttribute(ApiPrincipal.ATTRIBUTE) ApiPrincipal principal,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) @Nullable String key,
            @PathVariable UUID handoverId,
            HttpServletRequest request) {
        return idempotency.execute(
                principal,
                key,
                request,
                null,
                HttpStatus.OK,
                () -> {
                    activity.resolve(principal, handoverId);
                    return Map.of("status", "RESOLVED");
                });
    }

    @GetMapping("/conversations")
    List<ConversationSummary> conversations(@RequestAttribute(ApiPrincipal.ATTRIBUTE) ApiPrincipal principal) {
        return activity.conversations(principal);
    }

    @GetMapping("/conversations/{conversationId}")
    ConversationDetail conversation(
            @RequestAttribute(ApiPrincipal.ATTRIBUTE) ApiPrincipal principal, @PathVariable UUID conversationId) {
        return activity.conversation(principal, conversationId);
    }
}
