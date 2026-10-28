package io.github.darthbob.kawanav;

/**
 * The cold engine warning: said in the helmet when the rider revs the engine above the chosen engine speed
 * while the coolant is still below the chosen temperature. Once each time the engine goes over the limit,
 * and not again within {@link #REPEAT_MS}; once the coolant has reached that temperature it is quiet until
 * the bike has been gone {@link #KEEP_WARM_MS} or more, since an engine does not get cold again on a ride
 * (a long descent took a Z900 down to 56 °C after it had been warm), and a Bluetooth link that drops for
 * a moment is no new start. Plain Java, so the rule is tested without a phone.
 */
final class ColdEngine {
    /** The engine speeds and coolant temperatures the settings offer, and what they start at. */
    static final int[] RPM_CHOICES = {3000, 3500, 4000, 4500, 5000, 5500, 6000, 7000};
    static final int[] WARM_C_CHOICES = {50, 60, 70, 80};
    static final int DEFAULT_RPM = 4500, DEFAULT_WARM_C = 70;
    static final long REPEAT_MS = 60_000;
    /** A link back within this is the same ride: the engine is as warm as it was. */
    static final long KEEP_WARM_MS = 10 * 60_000;
    /** An engine speed older than this is not used: the revs change from one second to the next. */
    static final long FRESH_MS = 5_000;
    static final String PHRASE = "Engine still cold";

    private boolean warm, over;
    private long saidAt = -REPEAT_MS;
    /** When the link went, -1 while it is up. */
    private long lostAt = -1;

    /** The labels of {@link #RPM_CHOICES}: "4,500 rpm". */
    static String[] rpmLabels() {
        String[] out = new String[RPM_CHOICES.length];
        for (int i = 0; i < out.length; i++) out[i] = String.format(java.util.Locale.ROOT, "%,d rpm", RPM_CHOICES[i]);
        return out;
    }

    /** The labels of {@link #WARM_C_CHOICES}: "70 °C". */
    static String[] warmLabels() {
        String[] out = new String[WARM_C_CHOICES.length];
        for (int i = 0; i < out.length; i++) out[i] = WARM_C_CHOICES[i] + " °C";
        return out;
    }

    /** Where {@code value} is in {@code choices}, or where {@code fallback} is when it is not one of them. */
    static int indexOf(int[] choices, int value, int fallback) {
        for (int i = 0; i < choices.length; i++) if (choices[i] == value) return i;
        for (int i = 0; i < choices.length; i++) if (choices[i] == fallback) return i;
        return 0;
    }

    /**
     * Takes the latest coolant (°C, NaN when the bike has not said) and engine speed (rpm, negative when
     * not known) against the limits ({@code limitRpm} 0 or less: off); true when the warning is to be
     * said now.
     */
    boolean check(double coolantC, int rpm, int limitRpm, double warmC, long now) {
        if (coolantC == coolantC && coolantC >= warmC) warm = true;
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

    /** The link to the bike went (once for a run of reconnect attempts: the first loss counts). */
    void linkLost(long now) {
        if (lostAt < 0) lostAt = now;
        over = false;
    }

    /** The link is back: after {@link #KEEP_WARM_MS} or more away the engine may have cooled down. */
    void linkBack(long now) {
        if (lostAt >= 0 && now - lostAt >= KEEP_WARM_MS) {
            warm = false;
            saidAt = -REPEAT_MS;
        }
        lostAt = -1;
    }
}
