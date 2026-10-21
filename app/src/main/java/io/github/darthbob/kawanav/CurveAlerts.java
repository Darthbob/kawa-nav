package io.github.darthbob.kawanav;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Spoken curve warnings: which curve to say, and when. The next curve of the chosen kinds is said a
 * few seconds before it starts at the current speed, and only once, although the curves are found
 * again on every GPS fix: a curve is known again by where it starts. Plain Java so it can be
 * unit-tested off-device; {@link CurveSound} speaks it.
 */
final class CurveAlerts {

    /** Values of {@link Hub#KEY_CURVE_SOUND}: which curves are said. */
    static final int OFF = 0, HAIRPINS = 1, SHARP = 2, ALL = 3;

    /** A curve is said this long before it starts, at the current speed ... */
    static final double LEAD_S = 6;
    /** ... but not later than this close (slow) nor earlier than this far (fast), metres. */
    static final double MIN_LEAD_M = 50, MAX_LEAD_M = 300;
    /** Below this speed (m/s, ~10 km/h) nothing is said: waiting at a light, pushing the bike. */
    static final double MIN_SPEED = 2.8;
    /** Two starts closer than this that turn the same way are the same curve, seen on another fix. */
    static final double SAME_CURVE_M = 30;
    /** At least this long between two warnings; a curve that comes sooner waits for the gap. */
    static final long MIN_GAP_MS = 4_000;
    /** A curve is said again after this long: the same road ridden once more. */
    static final long FORGET_MS = 10 * 60_000;
    /** During navigation, a curve this close before the Maps maneuver is left to Maps' own voice. */
    static final double BEFORE_MANEUVER_M = 50;
    private static final int REMEMBERED = 16;

    /** A curve ahead: its arrow, the distance to where it starts, and that place. */
    static final class Ahead {
        final int turn;
        final double startDist;
        final double lat, lon;

        Ahead(int turn, double startDist, double lat, double lon) {
            this.turn = turn;
            this.startDist = startDist;
            this.lat = lat;
            this.lon = lon;
        }
    }

    private static final class Said {
        final double lat, lon;
        final boolean right;
        final long at;

        Said(Ahead c, long at) {
            this.lat = c.lat;
            this.lon = c.lon;
            this.right = right(c.turn);
            this.at = at;
        }
    }

    private final ArrayDeque<Said> said = new ArrayDeque<>();
    private long lastAt = -MIN_GAP_MS;

    /**
     * The curve to say now, or null. It then counts as said.
     *
     * @param curves    the curves ahead, nearest first
     * @param speed     m/s
     * @param level     {@link #OFF}, {@link #HAIRPINS}, {@link #SHARP} or {@link #ALL}
     * @param maneuverM distance to the next Google Maps maneuver: infinite when Maps is not navigating,
     *                  negative when it has none or it is imminent (Maps speaks then, nothing is said)
     */
    Ahead next(List<Ahead> curves, double speed, int level, double maneuverM, long now) {
        if (level <= OFF || !(speed >= MIN_SPEED) || maneuverM < 0) return null;
        while (!said.isEmpty() && now - said.peekFirst().at > FORGET_MS) said.removeFirst();
        if (now - lastAt < MIN_GAP_MS) return null;
        double lead = Math.max(MIN_LEAD_M, Math.min(MAX_LEAD_M, speed * LEAD_S));
        for (Ahead c : curves) {
            if (c.startDist <= CurveDetector.ENTERED_M) continue;
            if (c.startDist > lead || c.startDist > maneuverM - BEFORE_MANEUVER_M) return null;
            if (severity(c.turn) < minSeverity(level) || wasSaid(c)) continue;
            if (said.size() >= REMEMBERED) said.removeFirst();
            said.addLast(new Said(c, now));
            lastAt = now;
            return c;
        }
        return null;
    }

    /** "Sharp bend left": the kind of curve as on the dash's arrow, and its direction. */
    static String phrase(int turn) {
        String side = right(turn) ? "right" : "left";
        switch (severity(turn)) {
            case 3: return "Hairpin " + side;
            case 2: return "Sharp bend " + side;
            case 1: return "Bend " + side;
            default: return "Slight bend " + side;
        }
    }

    /** 3 hairpin, 2 sharp, 1 normal, 0 slight (the arrows of {@link CurveDetector#classify}), -1 no curve. */
    static int severity(int turn) {
        switch (turn) {
            case 7: case 8: return 3;
            case 3: case 4: return 2;
            case 1: case 2: return 1;
            case 5: case 6: return 0;
            default: return -1;
        }
    }

    private static int minSeverity(int level) {
        return level == HAIRPINS ? 3 : level == SHARP ? 2 : 0;
    }

    /** The curve arrows to the right have even ids. */
    private static boolean right(int turn) {
        return turn % 2 == 0;
    }

    private boolean wasSaid(Ahead c) {
        for (Said s : said) {
            if (s.right == right(c.turn) && metres(s.lat, s.lon, c.lat, c.lon) < SAME_CURVE_M) return true;
        }
        return false;
    }

    private static double metres(double lat1, double lon1, double lat2, double lon2) {
        double dy = (lat2 - lat1) * 111_132.0;
        double dx = (lon2 - lon1) * 111_320.0 * Math.cos(Math.toRadians(lat1));
        return Math.hypot(dx, dy);
    }
}
