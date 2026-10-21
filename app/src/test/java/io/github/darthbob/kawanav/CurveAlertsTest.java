package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Spoken curve warnings: which curve is said, when, and that it is said once. */
public class CurveAlertsTest {

    private static final double LAT = 46.50, LON = 11.35;
    private static final double NONE = Double.POSITIVE_INFINITY; // Maps not navigating
    private static final double KMH_90 = 25; // m/s: a lead of 150 m

    private static final int SLIGHT_LEFT = 5, LEFT = 1, SHARP_LEFT = 3, SHARP_RIGHT = 4, HAIRPIN_RIGHT = 8;

    /** A curve starting {@code dist} m ahead, at a place {@code northM} m north of the reference point. */
    private static CurveAlerts.Ahead curve(int turn, double dist, double northM) {
        return new CurveAlerts.Ahead(turn, dist, LAT + northM / 111_132.0, LON);
    }

    private static List<CurveAlerts.Ahead> list(CurveAlerts.Ahead... curves) {
        return Arrays.asList(curves);
    }

    @Test
    public void saysACurveOnceItIsWithinTheLeadAndThenNotAgain() {
        CurveAlerts a = new CurveAlerts();
        assertNull(a.next(list(curve(SHARP_LEFT, 300, 1000)), KMH_90, CurveAlerts.SHARP, NONE, 0));
        assertNull(a.next(list(curve(SHARP_LEFT, 160, 1000)), KMH_90, CurveAlerts.SHARP, NONE, 5_000));
        CurveAlerts.Ahead c = curve(SHARP_LEFT, 140, 1000);
        assertSame(c, a.next(list(c), KMH_90, CurveAlerts.SHARP, NONE, 6_000));
        // the next fixes find it again, its start a few metres off: already said
        assertNull(a.next(list(curve(SHARP_LEFT, 115, 1006)), KMH_90, CurveAlerts.SHARP, NONE, 11_000));
        assertNull(a.next(list(curve(SHARP_LEFT, 90, 994)), KMH_90, CurveAlerts.SHARP, NONE, 12_000));
    }

    @Test
    public void theLeadFollowsTheSpeedWithinItsLimits() {
        // 10 m/s: 6 s are 60 m
        assertNull(new CurveAlerts().next(list(curve(SHARP_LEFT, 70, 0)), 10, CurveAlerts.SHARP, NONE, 0));
        assertNotNull(new CurveAlerts().next(list(curve(SHARP_LEFT, 55, 0)), 10, CurveAlerts.SHARP, NONE, 0));
        // slow: not later than 50 m before it
        assertNotNull(new CurveAlerts().next(list(curve(SHARP_LEFT, 48, 0)), 3, CurveAlerts.SHARP, NONE, 0));
        // fast: not earlier than 300 m before it
        assertNull(new CurveAlerts().next(list(curve(SHARP_LEFT, 320, 0)), 60, CurveAlerts.SHARP, NONE, 0));
        assertNotNull(new CurveAlerts().next(list(curve(SHARP_LEFT, 290, 0)), 60, CurveAlerts.SHARP, NONE, 0));
    }

    @Test
    public void nothingIsSaidWhenSlowOrOff() {
        List<CurveAlerts.Ahead> c = list(curve(HAIRPIN_RIGHT, 40, 0));
        assertNull(new CurveAlerts().next(c, 2, CurveAlerts.ALL, NONE, 0));
        assertNull(new CurveAlerts().next(c, Double.NaN, CurveAlerts.ALL, NONE, 0));
        assertNull(new CurveAlerts().next(c, KMH_90, CurveAlerts.OFF, NONE, 0));
    }

    @Test
    public void theLevelPicksTheKindsOfCurve() {
        List<CurveAlerts.Ahead> sharp = list(curve(SHARP_LEFT, 100, 0));
        assertNull(new CurveAlerts().next(sharp, KMH_90, CurveAlerts.HAIRPINS, NONE, 0));
        assertNotNull(new CurveAlerts().next(sharp, KMH_90, CurveAlerts.SHARP, NONE, 0));
        List<CurveAlerts.Ahead> slight = list(curve(SLIGHT_LEFT, 100, 0));
        assertNull(new CurveAlerts().next(slight, KMH_90, CurveAlerts.SHARP, NONE, 0));
        assertNotNull(new CurveAlerts().next(slight, KMH_90, CurveAlerts.ALL, NONE, 0));
    }

    @Test
    public void aHairpinBehindAMilderBendIsStillSaid() {
        CurveAlerts.Ahead hairpin = curve(HAIRPIN_RIGHT, 120, 120);
        assertSame(hairpin, new CurveAlerts().next(list(curve(LEFT, 30, 30), hairpin), KMH_90,
                CurveAlerts.HAIRPINS, NONE, 0));
    }

    @Test
    public void aCurveAlreadyEnteredIsNotSaid() {
        assertNull(new CurveAlerts().next(list(curve(SHARP_LEFT, 3, 0)), KMH_90, CurveAlerts.ALL, NONE, 0));
    }

    @Test
    public void aCurveRightAfterAnotherWaitsForTheGap() {
        CurveAlerts a = new CurveAlerts();
        CurveAlerts.Ahead first = curve(SHARP_LEFT, 60, 60);
        CurveAlerts.Ahead second = curve(SHARP_RIGHT, 140, 140);
        assertSame(first, a.next(list(first, second), KMH_90, CurveAlerts.SHARP, NONE, 0));
        assertNull(a.next(list(curve(SHARP_RIGHT, 115, 140)), KMH_90, CurveAlerts.SHARP, NONE, 1_000));
        CurveAlerts.Ahead later = curve(SHARP_RIGHT, 40, 140);
        assertSame(later, a.next(list(later), KMH_90, CurveAlerts.SHARP, NONE, CurveAlerts.MIN_GAP_MS));
    }

    @Test
    public void duringNavigationACurveAtTheManeuverIsLeftToMaps() {
        List<CurveAlerts.Ahead> c = list(curve(SHARP_LEFT, 100, 0));
        assertNull(new CurveAlerts().next(c, KMH_90, CurveAlerts.SHARP, 130, 0));
        assertNotNull(new CurveAlerts().next(c, KMH_90, CurveAlerts.SHARP, 160, 0));
        // the maneuver is imminent, or Maps gave no distance
        assertNull(new CurveAlerts().next(c, KMH_90, CurveAlerts.SHARP, -1, 0));
    }

    @Test
    public void theSamePlaceTheOtherWayIsAnotherCurve() {
        // riding the road back: the left bend is a right bend, starting about where the other one ended
        CurveAlerts a = new CurveAlerts();
        assertNotNull(a.next(list(curve(SHARP_LEFT, 100, 0)), KMH_90, CurveAlerts.SHARP, NONE, 0));
        assertNotNull(a.next(list(curve(SHARP_RIGHT, 100, 5)), KMH_90, CurveAlerts.SHARP, NONE, 60_000));
    }

    @Test
    public void aCurveIsSaidAgainOnTheSameRoadLater() {
        CurveAlerts a = new CurveAlerts();
        List<CurveAlerts.Ahead> c = list(curve(SHARP_LEFT, 100, 0));
        assertNotNull(a.next(c, KMH_90, CurveAlerts.SHARP, NONE, 0));
        assertNull(a.next(c, KMH_90, CurveAlerts.SHARP, NONE, CurveAlerts.FORGET_MS));
        assertNotNull(a.next(c, KMH_90, CurveAlerts.SHARP, NONE, CurveAlerts.FORGET_MS + 1));
    }

    @Test
    public void nothingAheadSaysNothing() {
        assertNull(new CurveAlerts().next(Collections.emptyList(), KMH_90, CurveAlerts.ALL, NONE, 0));
    }

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
