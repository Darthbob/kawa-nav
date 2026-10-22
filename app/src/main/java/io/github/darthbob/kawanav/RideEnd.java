package io.github.darthbob.kawanav;

/**
 * When a ride ends after the link to the bike is lost. Switching the ignition off cuts the link, and so
 * does a bike that drops out for a moment while riding: the speed it had when the link went tells them
 * apart. Standing, it was switched off, and the ride ends as the rider chose in Settings ("End a ride"):
 * as soon as the bike counts as off, or 5 or 15 minutes later, so that a fuel stop stays in the same
 * ride. Moving, the link only dropped, and the ride waits at least {@link #MOVING_MS} for it. The bike
 * coming back in time continues the ride; a ride the rider paused by hand is theirs to end. Plain Java,
 * so the rule is tested without a phone.
 */
final class RideEnd {
    private RideEnd() {}

    /** The choices, in the settings order. */
    static final String[] LABELS = {"At once", "After 5 min", "After 15 min"};
    static final int DEFAULT = 0;
    /** How long after the link was lost each choice ends the ride; the first is the bike counting as off. */
    private static final long[] AFTER_MS = {Hub.BIKE_OFF_MS, 5 * 60_000L, 15 * 60_000L};
    /** A link lost while moving is a dropout, not the ignition: the ride waits at least this long. */
    static final long MOVING_MS = 5 * 60_000L;
    /** From this speed the bike was moving when the link went (the auto-start's threshold). */
    static final double MOVING_KMH = 5;

    /** True when this speed at the link's loss says the bike was moving; no speed known is standing. */
    static boolean moving(double kmhAtLoss) {
        return kmhAtLoss >= MOVING_KMH;
    }

    /** How long after the link was lost the ride ends, for this choice and the speed the bike had then. */
    static long afterMs(int choice, double kmhAtLoss) {
        long chosen = AFTER_MS[choice >= 0 && choice < AFTER_MS.length ? choice : DEFAULT];
        return moving(kmhAtLoss) ? Math.max(chosen, MOVING_MS) : chosen;
    }
}
