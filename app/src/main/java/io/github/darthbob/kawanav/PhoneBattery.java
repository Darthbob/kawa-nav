package io.github.darthbob.kawanav;

/**
 * The phone's battery percentage for the dash icon. Android occasionally answers with no reading
 * (0 or less, or a value past 100), and a real battery cannot lose more than a few points in the
 * five seconds between two icon frames: either would reach the dash as a low or unknown battery and
 * flash its warning icon for a few seconds (issue #7). Keeps the last plausible reading instead.
 * Times are passed in, which keeps it plain Java for the tests.
 */
final class PhoneBattery {

    /** A drop larger than this between two readings seconds apart is not a battery. */
    static final int MAX_DROP = 20;
    /** A reading only counts as a drop in the battery when the previous one was this recent. */
    static final long RECENT_MS = 15_000;

    private int percent = -1;
    private long at = Long.MIN_VALUE;

    /** Takes one reading; true when it was plausible and is now the value to show. */
    boolean accept(int reading, long now) {
        if (reading <= 0 || reading > 100) return false;
        if (percent >= 0 && now - at <= RECENT_MS && reading < percent - MAX_DROP) return false;
        percent = reading;
        at = now;
        return true;
    }

    /** The percentage to show: the last plausible reading, -1 until there has been one. */
    int percent() {
        return percent;
    }
}
