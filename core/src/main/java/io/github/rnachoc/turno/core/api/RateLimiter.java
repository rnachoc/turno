package io.github.rnachoc.turno.core.api;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Counts events per key in windows of one minute, in memory. The core runs as one process, so this
 * needs nothing shared, and losing the counts on a restart costs one minute of protection at most.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@Component
class RateLimiter {

    /** What to do with an event. */
    enum Decision {
        ALLOW,
        /** Over the limit for the first time in this window, which is worth recording. */
        REFUSE_FIRST,
        /** Over the limit again, which must cost as little as possible. */
        REFUSE
    }

    private static final long SECONDS_PER_WINDOW = 60;
    /** More keys than this in a window means addresses are being made up, so old counts can go. */
    private static final int MAX_KEYS = 10_000;

    private record Window(long number, int count) {}

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    RateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * Counts an event and decides on it.
     *
     * @param key what is being limited
     * @param limit how many events a window allows
     * @return the decision
     */
    Decision count(String key, int limit) {
        int count = increment(key);
        if (count <= limit) {
            return Decision.ALLOW;
        }
        return count == limit + 1 ? Decision.REFUSE_FIRST : Decision.REFUSE;
    }

    /**
     * Counts an event without deciding on it.
     *
     * @param key what is being limited
     */
    void record(String key) {
        increment(key);
    }

    /**
     * Tells whether a key has used up its window, without counting anything.
     *
     * @param key what is being limited
     * @param limit how many events a window allows
     * @return whether it has reached the limit
     */
    boolean hasReached(String key, int limit) {
        Window window = windows.get(key);
        return window != null && window.number() == currentWindow() && window.count() >= limit;
    }

    /**
     * Tells how long until the counts start again.
     *
     * @return the seconds until the next window, at least one
     */
    long secondsUntilNextWindow() {
        return SECONDS_PER_WINDOW - clock.instant().getEpochSecond() % SECONDS_PER_WINDOW;
    }

    /** Forgets every count, for tests that share one application. */
    void reset() {
        windows.clear();
    }

    private int increment(String key) {
        long now = currentWindow();
        if (windows.size() >= MAX_KEYS) {
            windows.values().removeIf(window -> window.number() != now);
            if (windows.size() >= MAX_KEYS) {
                windows.clear();
            }
        }
        return windows.compute(
                        key,
                        (ignored, window) ->
                                window == null || window.number() != now
                                        ? new Window(now, 1)
                                        : new Window(now, window.count() + 1))
                .count();
    }

    private long currentWindow() {
        return clock.instant().getEpochSecond() / SECONDS_PER_WINDOW;
    }
}
