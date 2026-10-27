package io.github.darthbob.kawanav;

/**
 * The cold engine warning: said in the helmet when the rider revs the engine above the chosen limit
 * while the coolant is still below {@link #WARM_C}. Once each time the engine goes over the limit, and
 * not again within {@link #REPEAT_MS}; once the coolant has reached {@link #WARM_C} it is quiet for the
 * rest of the connection, since an engine does not get cold again on a ride (a long descent took a
 * Z900 down to 56 °C after it had been warm). Plain Java, so the rule is tested without a phone.
 */
final class ColdEngine {
    /** The coolant from which the engine counts as warm. */
    static final double WARM_C = 60;
    /** The setting: an index into these, 0 is off. */
    static final int[] LIMITS = {0, 5000, 6000, 7000};
    static final String[] LABELS = {"Off", "Above 5,000 rpm", "Above 6,000 rpm", "Above 7,000 rpm"};
    static final long REPEAT_MS = 60_000;
    /** An engine speed older than this is not used: the revs change from one second to the next. */
    static final long FRESH_MS = 5_000;
    static final String PHRASE = "Engine still cold";

    private boolean warm, over;
    private long saidAt = -REPEAT_MS;

    /** The limit of a setting, 0 for off or a setting this version does not know. */
    static int limit(int setting) {
        return setting > 0 && setting < LIMITS.length ? LIMITS[setting] : 0;
    }

    /**
     * Takes the latest coolant (°C, NaN when the bike has not said) and engine speed (rpm, negative when
     * not known); true when the warning is to be said now.
     */
    boolean check(double coolantC, int rpm, int limitRpm, long now) {
        if (coolantC == coolantC && coolantC >= WARM_C) warm = true;
        if (warm || limitRpm <= 0 || coolantC != coolantC || rpm < 0) {
            over = false;
            return false;
        }
        boolean wasOver = over;
        over = rpm > limitRpm;
        if (!over || wasOver || now - saidAt < REPEAT_MS) return false;
        saidAt = now;
        return true;
    }

    /** True once the coolant has been warm on this connection. */
    boolean warm() {
        return warm;
    }

    /** A new connection: the engine may have cooled down since. */
    void reset() {
        warm = over = false;
        saidAt = -REPEAT_MS;
    }
}
