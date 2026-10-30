package io.github.rnachoc.turno.core.booking;

import io.github.rnachoc.turno.core.idempotency.IdempotencyService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic housekeeping. No rule depends on it: expired holds already stop counting at the instant
 * they run out, so a delay here only delays the audit entry. Running it on several instances at once
 * is harmless.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "turno.jobs.enabled", havingValue = "true")
class MaintenanceJob {

    private final BookingService bookings;
    private final IdempotencyService idempotency;

    MaintenanceJob(BookingService bookings, IdempotencyService idempotency) {
        this.bookings = bookings;
        this.idempotency = idempotency;
    }

    @Scheduled(fixedDelay = 60_000)
    void expireHolds() {
        bookings.expireDueHolds();
    }

    @Scheduled(fixedDelay = 3_600_000)
    void purgeIdempotencyKeys() {
        idempotency.purgeExpired();
    }
}
