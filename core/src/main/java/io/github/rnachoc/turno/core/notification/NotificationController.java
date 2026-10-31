package io.github.rnachoc.turno.core.notification;

import io.github.rnachoc.turno.core.api.ApiPrincipal;
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
 * What the channels read to know whom to tell what.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController {

    private final NotificationService notifications;
    private final IdempotencyService idempotency;

    NotificationController(NotificationService notifications, IdempotencyService idempotency) {
        this.notifications = notifications;
        this.idempotency = idempotency;
    }

    @GetMapping("/pending")
    List<Notification> pending(@RequestAttribute(ApiPrincipal.ATTRIBUTE) ApiPrincipal principal) {
        return notifications.pending(principal);
    }

    @PostMapping("/{notificationId}/sent")
    ResponseEntity<String> sent(
            @RequestAttribute(ApiPrincipal.ATTRIBUTE) ApiPrincipal principal,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) @Nullable String key,
            @PathVariable UUID notificationId,
            HttpServletRequest request) {
        return idempotency.execute(
                principal,
                key,
                request,
                null,
                HttpStatus.OK,
                () -> {
                    notifications.markSent(principal, notificationId);
                    return Map.of("status", "SENT");
                });
    }
}
