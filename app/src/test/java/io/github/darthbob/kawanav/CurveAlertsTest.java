package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Spoken curve warnings: which curve is said, when, and that it is said once. */
public class CurveAlertsTest {

    private static final double LAT = 46.50, LON = 11.35;
    private static final double NONE = Double.POSITIVE_INFINITY; // Maps not navigating
    private static final double KMH_90 = 25; // m/s: a lead of 150 m
    private static final double WIDE = 1_000; // a radius no pace is too fast for at these speeds

    private static final int SLIGHT_LEFT = 5, LEFT = 1, SHARP_LEFT = 3, SHARP_RIGHT = 4, HAIRPIN_RIGHT = 8;

    /** A curve starting {@code dist} m ahead, at a place {@code northM} m north of the reference point. */
    private static CurveAlerts.Ahead curve(int turn, double dist, double northM) {
        return curve(turn, dist, northM, WIDE);
    }

    /** ... 30 m long. */
    private static CurveAlerts.Ahead curve(int turn, double dist, double northM, double radius) {
        return new CurveAlerts.Ahead(turn, dist, dist + 30, radius, LAT + northM / 111_132.0, LON);
    }

    private static List<CurveAlerts.Ahead> list(CurveAlerts.Ahead... curves) {
        return Arrays.asList(curves);
    }

    /** By kind only (no pace): the curve said, or null. */
    private static CurveAlerts.Ahead say(CurveAlerts a, List<CurveAlerts.Ahead> curves, double speed, int level,
                                         double maneuverM, long now) {
        CurveAlerts.Warning w = a.next(curves, speed, level, 0, maneuverM, now);
        return w == null ? null : w.curve;
    }

    private static CurveAlerts.Ahead say(List<CurveAlerts.Ahead> curves, double speed, int level) {
        return say(new CurveAlerts(), curves, speed, level, NONE, 0);
    }

    /** The lean of a fixed pace. */
    private static double lean(int pace) {
        return CurveAlerts.PACE_LEAN[pace];
    }

    /** Too fast only (no kinds). */
    private static CurveAlerts.Warning tooFast(CurveAlerts a, CurveAlerts.Ahead c, double speed, int pace, long now) {
        return a.next(list(c), speed, CurveAlerts.OFF, lean(pace), NONE, now);
    }

    // ------------------------------------------------------------------ by kind

    @Test
    public void saysACurveOnceItIsWithinTheLeadAndThenNotAgain() {
        CurveAlerts a = new CurveAlerts();
        assertNull(say(a, list(curve(SHARP_LEFT, 300, 1000)), KMH_90, CurveAlerts.SHARP, NONE, 0));
        assertNull(say(a, list(curve(SHARP_LEFT, 160, 1000)), KMH_90, CurveAlerts.SHARP, NONE, 5_000));
        CurveAlerts.Ahead c = curve(SHARP_LEFT, 140, 1000);
        assertSame(c, say(a, list(c), KMH_90, CurveAlerts.SHARP, NONE, 6_000));
        // the next fixes find it again, its start a few metres off: already said
        assertNull(say(a, list(curve(SHARP_LEFT, 115, 1006)), KMH_90, CurveAlerts.SHARP, NONE, 11_000));
        assertNull(say(a, list(curve(SHARP_LEFT, 90, 994)), KMH_90, CurveAlerts.SHARP, NONE, 12_000));
    }

    @Test
    public void theLeadFollowsTheSpeedWithinItsLimits() {
        // 10 m/s: 6 s are 60 m
        assertNull(say(list(curve(SHARP_LEFT, 70, 0)), 10, CurveAlerts.SHARP));
        assertNotNull(say(list(curve(SHARP_LEFT, 55, 0)), 10, CurveAlerts.SHARP));
        // slow: not later than 50 m before it
        assertNotNull(say(list(curve(SHARP_LEFT, 48, 0)), 3, CurveAlerts.SHARP));
        // fast: not earlier than 300 m before it
        assertNull(say(list(curve(SHARP_LEFT, 320, 0)), 60, CurveAlerts.SHARP));
        assertNotNull(say(list(curve(SHARP_LEFT, 290, 0)), 60, CurveAlerts.SHARP));
    }

    @Test
    public void nothingIsSaidWhenSlowOrOff() {
        List<CurveAlerts.Ahead> c = list(curve(HAIRPIN_RIGHT, 40, 0, 10));
        assertNull(say(c, 2, CurveAlerts.ALL));
        assertNull(say(c, Double.NaN, CurveAlerts.ALL));
        assertNull(new CurveAlerts().next(c, KMH_90, CurveAlerts.OFF, 0, NONE, 0));
        assertNull(new CurveAlerts().next(c, 2, CurveAlerts.OFF, lean(CurveAlerts.RELAXED), NONE, 0));
    }

    @Test
    public void theLevelPicksTheKindsOfCurve() {
        List<CurveAlerts.Ahead> sharp = list(curve(SHARP_LEFT, 100, 0));
        assertNull(say(sharp, KMH_90, CurveAlerts.HAIRPINS));
        assertNotNull(say(sharp, KMH_90, CurveAlerts.SHARP));
        List<CurveAlerts.Ahead> slight = list(curve(SLIGHT_LEFT, 100, 0));
        assertNull(say(slight, KMH_90, CurveAlerts.SHARP));
        assertNotNull(say(slight, KMH_90, CurveAlerts.ALL));
    }

    @Test
    public void aHairpinBehindAMilderBendIsStillSaid() {
        CurveAlerts.Ahead hairpin = curve(HAIRPIN_RIGHT, 120, 120);
        assertSame(hairpin, say(list(curve(LEFT, 30, 30), hairpin), KMH_90, CurveAlerts.HAIRPINS));
    }

    @Test
    public void aCurveAlreadyEnteredIsNotSaid() {
        assertNull(say(list(curve(SHARP_LEFT, 3, 0)), KMH_90, CurveAlerts.ALL));
    }

    @Test
    public void aCurveRightAfterAnotherWaitsForTheGap() {
        CurveAlerts a = new CurveAlerts();
        // 90 m between the end of the first and the start of the second: too far to be named with it
        CurveAlerts.Ahead first = curve(SHARP_LEFT, 60, 60);
        CurveAlerts.Ahead second = curve(SHARP_RIGHT, 180, 180);
        assertSame(first, say(a, list(first, second), KMH_90, CurveAlerts.SHARP, NONE, 0));
        assertNull(say(a, list(curve(SHARP_RIGHT, 155, 180)), KMH_90, CurveAlerts.SHARP, NONE, 1_000));
        CurveAlerts.Ahead later = curve(SHARP_RIGHT, 80, 180);
        assertSame(later, say(a, list(later), KMH_90, CurveAlerts.SHARP, NONE, CurveAlerts.MIN_GAP_MS));
    }

    // ------------------------------------------------------------------ the bend right after

    @Test
    public void aBendRightAfterIsNamedWithItAndNotSaidAgain() {
        CurveAlerts a = new CurveAlerts();
        // the left one ends at 130 m, the right one starts 40 m later
        CurveAlerts.Warning w = a.next(list(curve(SHARP_LEFT, 100, 0), curve(SHARP_RIGHT, 170, 170)), KMH_90,
                CurveAlerts.SHARP, 0, NONE, 0);
        assertNotNull(w);
        assertEquals("Sharp bend left, then sharp bend right", w.text());
        assertNull(a.next(list(curve(SHARP_RIGHT, 60, 172)), KMH_90, CurveAlerts.SHARP, 0, NONE, 5_000));
    }

    @Test
    public void aBendFurtherOnIsNotNamed() {
        CurveAlerts.Warning w = new CurveAlerts().next(list(curve(SHARP_LEFT, 100, 0), curve(SHARP_RIGHT, 220, 220)),
                KMH_90, CurveAlerts.SHARP, 0, NONE, 0);
        assertNotNull(w);
        assertNull(w.then);
        assertEquals("Sharp bend left", w.text());
    }

    @Test
    public void onlyTheChosenKindsAreNamed() {
        List<CurveAlerts.Ahead> c = list(curve(SHARP_LEFT, 100, 0), curve(6, 160, 160)); // then a slight right
        assertNull(new CurveAlerts().next(c, KMH_90, CurveAlerts.SHARP, 0, NONE, 0).then);
        assertEquals("Sharp bend left, then slight bend right",
                new CurveAlerts().next(c, KMH_90, CurveAlerts.ALL, 0, NONE, 0).text());
        // without kinds (too fast only), any bend right after is named
        List<CurveAlerts.Ahead> fast = list(curve(SHARP_LEFT, 100, 0, 25), curve(6, 160, 160));
        assertEquals("Sharp bend left, slow down, then slight bend right",
                new CurveAlerts().next(fast, KMH_90, CurveAlerts.OFF, lean(CurveAlerts.NORMAL), NONE, 0).text());
    }

    @Test
    public void aNamedBendIsStillToldToSlowDown() {
        CurveAlerts a = new CurveAlerts();
        // named after a wide left bend; it is tight (25 m: 41 km/h at Normal), and the rider keeps 90 km/h
        CurveAlerts.Warning first = a.next(list(curve(SHARP_LEFT, 140, 0, 200), curve(SHARP_RIGHT, 180, 180, 25)),
                KMH_90, CurveAlerts.SHARP, lean(CurveAlerts.NORMAL), NONE, 0);
        assertNotNull(first.then);
        assertFalse(first.slowDown);
        CurveAlerts.Warning w = a.next(list(curve(SHARP_RIGHT, 100, 181, 25)), KMH_90, CurveAlerts.SHARP,
                lean(CurveAlerts.NORMAL), NONE, 5_000);
        assertNotNull(w);
        assertEquals("Sharp bend right, slow down", w.text());
    }

    @Test
    public void aBendAtTheManeuverIsNotNamed() {
        CurveAlerts.Warning w = new CurveAlerts().next(list(curve(SHARP_LEFT, 100, 0), curve(SHARP_RIGHT, 170, 170)),
                KMH_90, CurveAlerts.SHARP, 0, 200, 0);
        assertNotNull(w);
        assertNull(w.then);
    }

    @Test
    public void duringNavigationACurveAtTheManeuverIsLeftToMaps() {
        List<CurveAlerts.Ahead> c = list(curve(SHARP_LEFT, 100, 0));
        assertNull(say(new CurveAlerts(), c, KMH_90, CurveAlerts.SHARP, 130, 0));
        assertNotNull(say(new CurveAlerts(), c, KMH_90, CurveAlerts.SHARP, 160, 0));
        // the maneuver is imminent, or Maps gave no distance
        assertNull(say(new CurveAlerts(), c, KMH_90, CurveAlerts.SHARP, -1, 0));
    }

    @Test
    public void theSamePlaceTheOtherWayIsAnotherCurve() {
        // riding the road back: the left bend is a right bend, starting about where the other one ended
        CurveAlerts a = new CurveAlerts();
        assertNotNull(say(a, list(curve(SHARP_LEFT, 100, 0)), KMH_90, CurveAlerts.SHARP, NONE, 0));
        assertNotNull(say(a, list(curve(SHARP_RIGHT, 100, 5)), KMH_90, CurveAlerts.SHARP, NONE, 60_000));
    }

    @Test
    public void aCurveIsSaidAgainOnTheSameRoadLater() {
        CurveAlerts a = new CurveAlerts();
        List<CurveAlerts.Ahead> c = list(curve(SHARP_LEFT, 100, 0));
        assertNotNull(say(a, c, KMH_90, CurveAlerts.SHARP, NONE, 0));
        assertNull(say(a, c, KMH_90, CurveAlerts.SHARP, NONE, CurveAlerts.FORGET_MS));
        assertNotNull(say(a, c, KMH_90, CurveAlerts.SHARP, NONE, CurveAlerts.FORGET_MS + 1));
    }

    @Test
    public void theBikesSpeedGoesBeforeTheGps() {
        assertEquals(25, CurveAlerts.speed(90, 500, 20), 1e-9);
        // the bike standing still is standing still, whatever the GPS drifts to
        assertEquals(0, CurveAlerts.speed(0, 500, 4), 1e-9);
        // too old, or not sent: the GPS
        assertEquals(20, CurveAlerts.speed(90, CurveAlerts.BIKE_FRESH_MS + 1, 20), 1e-9);
        assertEquals(20, CurveAlerts.speed(Double.NaN, 0, 20), 1e-9);
    }

    @Test
    public void nothingAheadSaysNothing() {
        assertNull(new CurveAlerts().next(Collections.emptyList(), KMH_90, CurveAlerts.ALL, lean(CurveAlerts.FAST), NONE, 0));
    }

    // ------------------------------------------------------------------ too fast for the pace

    @Test
    public void thePacesSpeedsFollowTheLean() {
        assertEquals(37, CurveAlerts.okSpeed(30, lean(CurveAlerts.RELAXED)) * 3.6, 1);
        assertEquals(82, CurveAlerts.okSpeed(100, lean(CurveAlerts.NORMAL)) * 3.6, 1);
        assertEquals(73, CurveAlerts.okSpeed(60, lean(CurveAlerts.SPORTY)) * 3.6, 1);
        assertEquals(151, CurveAlerts.okSpeed(200, lean(CurveAlerts.FAST)) * 3.6, 1);
    }

    @Test
    public void aLeanFromTheRidesIsUsedAsItIsUpToTheLimit() {
        // the rider usually leans 31°: between Normal and Sporty
        assertEquals(68, CurveAlerts.okSpeed(60, 31) * 3.6, 1);
        // a reading beyond 45° leans 45°
        assertEquals(CurveAlerts.okSpeed(60, CurveAlerts.MAX_LEAN), CurveAlerts.okSpeed(60, 55), 1e-9);
        CurveAlerts.Warning w = new CurveAlerts().next(list(curve(LEFT, 100, 0, 60)), KMH_90, CurveAlerts.OFF, 31, NONE, 0);
        assertNotNull(w);
        assertTrue(w.slowDown);
    }

    @Test
    public void aBendOfAnyKindIsSaidWithSlowDownWhenTooFast() {
        // radius 60 m, Normal: 64 km/h; at 90 km/h gentle braking needs ~115 m
        CurveAlerts a = new CurveAlerts();
        assertNull(tooFast(a, curve(SLIGHT_LEFT, 130, 0, 60), KMH_90, CurveAlerts.NORMAL, 0));
        CurveAlerts.Warning w = tooFast(a, curve(SLIGHT_LEFT, 110, 0, 60), KMH_90, CurveAlerts.NORMAL, 1_000);
        assertNotNull(w);
        assertTrue(w.slowDown);
        assertEquals("Slight bend left, slow down", w.text());
        assertEquals(64, w.okSpeed * 3.6, 1);
        // and only once
        assertNull(tooFast(a, curve(SLIGHT_LEFT, 85, 0, 60), KMH_90, CurveAlerts.NORMAL, 6_000));
    }

    @Test
    public void aLittleOverThePacesSpeedIsNotTooFast() {
        // 64 km/h for the bend, 68 km/h is under the 10% over
        assertNull(tooFast(new CurveAlerts(), curve(LEFT, 20, 0, 60), 19, CurveAlerts.NORMAL, 0));
    }

    @Test
    public void theFasterThePaceTheLaterABendIsTooFast() {
        // radius 60 m at 90 km/h: Relaxed needs ~131 m to slow down, Normal ~115 m, Sporty ~98 m
        assertNotNull(tooFast(new CurveAlerts(), curve(LEFT, 120, 0, 60), KMH_90, CurveAlerts.RELAXED, 0));
        assertNull(tooFast(new CurveAlerts(), curve(LEFT, 120, 0, 60), KMH_90, CurveAlerts.NORMAL, 0));
        assertNotNull(tooFast(new CurveAlerts(), curve(LEFT, 110, 0, 60), KMH_90, CurveAlerts.NORMAL, 0));
        assertNull(tooFast(new CurveAlerts(), curve(LEFT, 110, 0, 60), KMH_90, CurveAlerts.SPORTY, 0));
        assertNotNull(tooFast(new CurveAlerts(), curve(LEFT, 95, 0, 60), KMH_90, CurveAlerts.SPORTY, 0));
        // Fast takes it at 83 km/h: 90 km/h is less than 10% over
        assertNull(tooFast(new CurveAlerts(), curve(LEFT, 20, 0, 60), KMH_90, CurveAlerts.FAST, 0));
    }

    @Test
    public void aRiderWhoBrakesIsNotToldToSlowDown() {
        // from 90 km/h, braking at 3.5 m/s² from 200 m before a 60 m bend (Normal: 64 km/h)
        CurveAlerts a = new CurveAlerts();
        double v = KMH_90, dist = 200;
        for (int s = 0; s < 8 && dist > 10; s++) {
            assertNull("second " + s, tooFast(a, curve(LEFT, dist, 0, 60), v, CurveAlerts.NORMAL, s * 1000L));
            double slower = Math.max(0, v - 3.5);
            dist -= (v + slower) / 2;
            v = slower;
        }
    }

    @Test
    public void aRiderWhoKeepsTheSpeedIsTold() {
        CurveAlerts a = new CurveAlerts();
        CurveAlerts.Warning w = null;
        double dist = 200;
        for (int s = 0; w == null && dist > 10; s++, dist -= KMH_90) {
            w = tooFast(a, curve(LEFT, dist, 0, 60), KMH_90, CurveAlerts.NORMAL, s * 1000L);
            if (w != null) assertEquals(100, dist, 0.1); // 200, 175, 150, 125 are still far enough
        }
        assertNotNull(w);
    }

    @Test
    public void tooFastIsNotSaidFurtherThanTheLimit() {
        // 180 km/h at a hairpin, Relaxed: braking would need ~540 m
        CurveAlerts.Ahead far = curve(HAIRPIN_RIGHT, 520, 0, 10);
        assertNull(tooFast(new CurveAlerts(), far, 50, CurveAlerts.RELAXED, 0));
        assertNotNull(tooFast(new CurveAlerts(), curve(HAIRPIN_RIGHT, 490, 0, 10), 50, CurveAlerts.RELAXED, 0));
    }

    @Test
    public void aBendOfTheChosenKindSaysSlowDownWhenTooFastAlready() {
        // a sharp bend of 25 m radius: Normal takes it at 41 km/h
        CurveAlerts.Warning fast = new CurveAlerts().next(list(curve(SHARP_LEFT, 140, 0, 25)), KMH_90,
                CurveAlerts.SHARP, lean(CurveAlerts.NORMAL), NONE, 0);
        assertNotNull(fast);
        assertEquals("Sharp bend left, slow down", fast.text());
        CurveAlerts.Warning slow = new CurveAlerts().next(list(curve(SHARP_LEFT, 55, 0, 25)), 10,
                CurveAlerts.SHARP, lean(CurveAlerts.NORMAL), NONE, 0);
        assertNotNull(slow);
        assertFalse(slow.slowDown);
        assertEquals("Sharp bend left", slow.text());
    }

    // ------------------------------------------------------------------ words

    @Test
    public void phrasesNameTheKindAndTheSide() {
        assertEquals("Hairpin right", CurveAlerts.phrase(8));
        assertEquals("Hairpin left", CurveAlerts.phrase(7));
        assertEquals("Sharp bend left", CurveAlerts.phrase(3));
        assertEquals("Sharp bend right", CurveAlerts.phrase(4));
        assertEquals("Bend left", CurveAlerts.phrase(1));
        assertEquals("Slight bend right", CurveAlerts.phrase(6));
    }

    @Test
    public void severityFollowsTheDetectorsArrows() {
        assertEquals(3, CurveAlerts.severity(CurveDetector.classify(170, 20)));
        assertEquals(2, CurveAlerts.severity(CurveDetector.classify(-110, 50)));
        assertEquals(1, CurveAlerts.severity(CurveDetector.classify(70, 80)));
        assertEquals(0, CurveAlerts.severity(CurveDetector.classify(-35, 120)));
        assertEquals(-1, CurveAlerts.severity(0));
    }
}
