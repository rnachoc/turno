package io.github.rnachoc.turno.core.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock that tests move by hand, so that a rule can be observed exactly on its boundary.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
public class MutableClock extends Clock {

    private volatile Instant now = Instant.EPOCH;

    /**
     * Sets the current instant.
     *
     * @param instant the new time
     */
    public void set(Instant instant) {
        now = instant;
    }

    /**
     * Moves the time forward.
     *
     * @param amount how much to move it
     */
    public void advance(Duration amount) {
        now = now.plus(amount);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
