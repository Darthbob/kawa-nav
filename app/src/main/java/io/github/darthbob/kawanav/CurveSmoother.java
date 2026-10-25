package io.github.darthbob.kawanav;

/**
 * Keeps the curve on the dash from flipping back and forth. On two days of logs it did so 61 times in
 * three ways: a bend at the edge of two kinds (Sharp right 380, Right 370, Sharp right 360), a bend
 * found on one fix and not the next (Right 480, Straight 1520, Right 450), and just after a roundabout,
 * before the heading settles (Sharp right 840, Sharp left 680, Sharp right 890).
 *
 * <p>What the detector finds on a fix replaces what is shown when it is the same thing a little closer,
 * when the shown one has been reached, when it is a bend and the shown one is a straight or a bend
 * clearly further away (a bend is never held back), or when it has been found on {@link #CONFIRM} fixes
 * in a row. Otherwise the shown one stays, nearer by the distance ridden since. The unit tests replay the
 * sequences of the logs.
 */
final class CurveSmoother {
    /** Fixes in a row a change needs before it is shown (one fix a second). */
    static final int CONFIRM = 2;
    /** Distances this close are the same place: the detector's own jitter and the 10 m rounding. */
    static final double SAME_PLACE_M = 60;

    private Hub.Output shown;
    private long shownAt;
    private Hub.Output candidate;
    private long candidateAt;
    private int candidateFixes;

    /**
     * What to show for this fix.
     *
     * @param raw     what the detector found (null: nothing to show, which is shown at once)
     * @param speedMs the rider's speed, to tell how far the shown one has come closer
     */
    Hub.Output next(Hub.Output raw, long now, double speedMs) {
        if (raw == null) {
            clear();
            return null;
        }
        if (shown == null) return accept(raw, now);
        double expected = ahead(shown, shownAt, now, speedMs);
        boolean rawIsBend = raw.turn != 0;
        if (expected <= CurveDetector.ENTERED_M                                     // reached: on to the next
                || sameThing(raw, shown, expected)                                  // the same, closer
                || rawIsBend && (shown.turn == 0 || raw.distanceM < expected - SAME_PLACE_M)) {
            return accept(raw, now);
        }
        if (candidate != null && sameThing(raw, candidate, ahead(candidate, candidateAt, now, speedMs))) {
            candidateFixes++;
        } else {
            candidateFixes = 1;
        }
        candidate = raw;
        candidateAt = now;
        if (candidateFixes >= CONFIRM) return accept(raw, now);
        return new Hub.Output(shown.turn, CurveEngine.roundDistance(expected), shown.source);
    }

    /** Forgets what was shown (detection stopped, no road, no heading). */
    void clear() {
        shown = null;
        candidate = null;
        candidateFixes = 0;
    }

    private Hub.Output accept(Hub.Output raw, long now) {
        shown = raw;
        shownAt = now;
        candidate = null;
        candidateFixes = 0;
        return raw;
    }

    /** Where {@code o} is now, from where it was at {@code at} and the distance ridden since. */
    private static double ahead(Hub.Output o, long at, long now, double speedMs) {
        return o.distanceM - Math.max(0, speedMs) * Math.max(0, now - at) / 1000.0;
    }

    private static boolean sameThing(Hub.Output raw, Hub.Output known, double knownNow) {
        return raw.turn == known.turn && Math.abs(raw.distanceM - knownNow) <= SAME_PLACE_M;
    }
}
