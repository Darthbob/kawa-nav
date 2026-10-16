package io.github.darthbob.kawanav;

/**
 * The recorded time of a ride: runs while recording, stands still while paused. Reading it changes
 * nothing, so the summary can be written as often as needed. Times are passed in (milliseconds), which
 * keeps it plain Java for the tests.
 */
final class ActiveClock {

    private long bankedMs;
    private long runningSince = -1;

    /** A clock that already holds {@code bankedMs} (a ride taken over after a restart), stopped. */
    ActiveClock(long bankedMs) {
        this.bankedMs = Math.max(0, bankedMs);
    }

    void start(long now) {
        if (runningSince < 0) runningSince = now;
    }

    void stop(long now) {
        if (runningSince < 0) return;
        bankedMs += Math.max(0, now - runningSince);
        runningSince = -1;
    }

    boolean running() {
        return runningSince >= 0;
    }

    /** Recorded milliseconds at {@code now}. */
    long ms(long now) {
        return bankedMs + (runningSince >= 0 ? Math.max(0, now - runningSince) : 0);
    }
}
