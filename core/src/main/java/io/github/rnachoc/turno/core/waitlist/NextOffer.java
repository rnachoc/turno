package io.github.rnachoc.turno.core.waitlist;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What the core decided to do next with a freed slot. The workflow only follows it.
 *
 * @param decision the decision
 * @param offerId the offer, if there is one to wait for
 * @param appointmentId the hold created for it, if there is one
 * @param waitUntil when to contact a patient, or when an offer runs out; absent if there is no wait
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
public record NextOffer(
        Decision decision,
        @Nullable UUID offerId,
        @Nullable UUID appointmentId,
        @Nullable Instant waitUntil) {

    /** What to do next. */
    public enum Decision {
        /** A patient has been offered the slot: wait for their answer. */
        OFFERED,
        /** It is outside the hours for contacting patients: ask again later. */
        WAIT,
        /** Nobody else on the list can take it. */
        NO_CANDIDATE,
        /** Enough patients have been asked about this slot. */
        LIMIT_REACHED,
        /** It is too soon before the slot for anyone to take it. */
        TOO_LATE,
        /** The slot was booked in some other way. */
        SLOT_TAKEN
    }

    static NextOffer finished(Decision decision) {
        return new NextOffer(decision, null, null, null);
    }

    static NextOffer waitUntil(Instant instant) {
        return new NextOffer(Decision.WAIT, null, null, instant);
    }

    static NextOffer offered(UUID offerId, UUID appointmentId, Instant expiresAt) {
        return new NextOffer(Decision.OFFERED, offerId, appointmentId, expiresAt);
    }
}
