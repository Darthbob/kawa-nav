package io.github.darthbob.kawanav;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Spoken curve warnings: which curve to say, and when. Two reasons to say a bend, each its own
 * setting: its kind (said a few seconds before it starts at the current speed), and coming at it
 * too fast for the rider's pace (said with "slow down" once gentle braking would only just be
 * enough). Each bend is said only once, although the curves are found again on every GPS fix: a
 * bend is known again by where it starts. Plain Java so it can be unit-tested off-device;
 * {@link CurveSound} speaks it.
 */
final class CurveAlerts {

    /** Values of {@link Hub#KEY_CURVE_SOUND}: which kinds of bend are always said. */
    static final int OFF = 0, HAIRPINS = 1, SHARP = 2, ALL = 3;
    /**
     * Values of {@link Hub#KEY_CURVE_PACE}: the riding pace a bend can be too fast for. FROM_RIDES leans
     * as the rider usually does in the recorded rides ({@link RideStats#usualBendLean}).
     */
    static final int PACE_OFF = 0, RELAXED = 1, NORMAL = 2, SPORTY = 3, FAST = 4, FROM_RIDES = 5;
    /** How far each fixed pace leans through a bend, degrees (index: the pace). */
    static final double[] PACE_LEAN = {0, 20, 28, 35, 42};
    /** The most a pace leans, also one from the rides: beyond it the tyres set the limit, not the rider. */
    static final double MAX_LEAN = 45;

    /** A bend of a chosen kind is said this long before it starts, at the current speed ... */
    static final double LEAD_S = 6;
    /** ... but not later than this close (slow) nor earlier than this far (fast), metres. */
    static final double MIN_LEAD_M = 50, MAX_LEAD_M = 300;
    /** Faster than the pace's speed by this factor counts as too fast: a little over is not. */
    static final double TOO_FAST = 1.1;
    /** Too fast: the time to hear the warning and start braking, and the braking then (m/s², ~0.3 g). */
    static final double REACTION_S = 2.5, BRAKING = 3.0;
    /** A bend too fast for the pace is not said earlier than this far before it, metres. */
    static final double MAX_SLOW_M = 500;
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
    private static final double G = 9.81;

    /** A curve ahead: its arrow, the distance to where it starts, its tightest radius, and that place. */
    static final class Ahead {
        final int turn;
        final double startDist;
        final double minRadius;
        final double lat, lon;

        Ahead(int turn, double startDist, double minRadius, double lat, double lon) {
            this.turn = turn;
            this.startDist = startDist;
            this.minRadius = minRadius;
            this.lat = lat;
            this.lon = lon;
        }
    }

    /** What to say about a curve. */
    static final class Warning {
        final Ahead curve;
        /** Coming at it too fast for the pace. */
        final boolean slowDown;
        /** The speed the pace takes it at, m/s (infinite without a pace). */
        final double okSpeed;

        Warning(Ahead curve, boolean slowDown, double okSpeed) {
            this.curve = curve;
            this.slowDown = slowDown;
            this.okSpeed = okSpeed;
        }

        /** "Sharp bend left", "Bend right, slow down". */
        String text() {
            return phrase(curve.turn) + (slowDown ? ", slow down" : "");
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
     * What to say now, or null. The curve then counts as said.
     *
     * @param curves    the curves ahead, nearest first
     * @param speed     m/s
     * @param level     the kinds always said: {@link #OFF}, {@link #HAIRPINS}, {@link #SHARP} or {@link #ALL}
     * @param lean      the lean of the pace a bend is too fast for, degrees ({@link #PACE_LEAN}, or the
     *                  rider's own); 0 or NaN: no pace
     * @param maneuverM distance to the next Google Maps maneuver: infinite when Maps is not navigating,
     *                  negative when it has none or it is imminent (Maps speaks then, nothing is said)
     */
    Warning next(List<Ahead> curves, double speed, int level, double lean, double maneuverM, long now) {
        boolean pace = lean > 0;
        if ((level <= OFF && !pace) || !(speed >= MIN_SPEED) || maneuverM < 0) return null;
        while (!said.isEmpty() && now - said.peekFirst().at > FORGET_MS) said.removeFirst();
        if (now - lastAt < MIN_GAP_MS) return null;
        double lead = Math.max(MIN_LEAD_M, Math.min(MAX_LEAD_M, speed * LEAD_S));
        for (Ahead c : curves) {
            if (c.startDist <= CurveDetector.ENTERED_M) continue;
            if (c.startDist > Math.max(lead, MAX_SLOW_M) || c.startDist > maneuverM - BEFORE_MANEUVER_M) return null;
            if (wasSaid(c)) continue;
            double ok = pace ? okSpeed(c.minRadius, lean) : Double.POSITIVE_INFINITY;
            boolean slowDown = speed > ok * TOO_FAST;
            boolean byKind = level > OFF && severity(c.turn) >= minSeverity(level) && c.startDist <= lead;
            // a rider already braking enough gets there in time: the distance needed shrinks faster
            boolean late = slowDown && c.startDist <= slowingDistance(speed, ok);
            if (!byKind && !late) continue;
            if (said.size() >= REMEMBERED) said.removeFirst();
            said.addLast(new Said(c, now));
            lastAt = now;
            return new Warning(c, slowDown, ok);
        }
        return null;
    }

    /** The speed (m/s) at which a pace leaning this far (degrees) takes a bend of this radius: v² = g · r · tan(lean). */
    static double okSpeed(double radius, double lean) {
        return Math.sqrt(G * radius * Math.tan(Math.toRadians(Math.min(MAX_LEAN, lean))));
    }

    /** Metres needed to hear the warning, react and brake gently from {@code speed} down to {@code ok}. */
    static double slowingDistance(double speed, double ok) {
        return speed * REACTION_S + (speed * speed - ok * ok) / (2 * BRAKING);
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
