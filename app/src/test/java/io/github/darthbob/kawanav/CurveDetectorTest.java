package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The arrow on the dash and the curve warnings: which bends of the road ahead count as curves at
 * each sensitivity, which icon they get (slight, normal, sharp, hairpin, left or right), how far
 * away and how tight they are, and that neighbouring bends stay apart.
 * The roads are synthetic: straights and circular arcs in metres, sampled like dense OSM geometry.
 */
public class CurveDetectorTest {

    private static final int LEFT = 1, RIGHT = 2, SHARP_LEFT = 3, SHARP_RIGHT = 4,
            SLIGHT_LEFT = 5, SLIGHT_RIGHT = 6, HAIRPIN_LEFT = 7, HAIRPIN_RIGHT = 8;
    private static final int LOW = 0, NORMAL = 1, HIGH = 2, HIGHEST = 3;
    private static final int[] ALL_LEVELS = {LOW, NORMAL, HIGH, HIGHEST};

    /** Tolerance on the reported radius of an arc. */
    private static final double RADIUS_TOL = 0.15;
    /** Tolerance on the total angle of an arc, degrees. */
    private static final double ANGLE_TOL = 3;
    /**
     * How far the reported start may lie before the arc really begins (and the end after it ends):
     * the road is resampled every 5 m and the first sampled step that turns counts, so one step early
     * at most; and after the arc ends ({@link #EDGE_LATE_M}) likewise one step.
     */
    private static final double EDGE_EARLY_M = 2 * CurveDetector.STEP;
    private static final double EDGE_LATE_M = 2 * CurveDetector.STEP;

    /**
     * A synthetic road in the path's metres (x east, y north), starting at the rider at the origin
     * heading north, made of straights and circular arcs sampled every {@link #SAMPLE} metres.
     */
    private static final class Road {
        static final double SAMPLE = 6;
        final List<double[]> points = new ArrayList<>();
        double length;                 // metres of road built so far
        private double x, y, heading;  // heading in radians, 0 = north, + = clockwise

        Road() {
            points.add(new double[]{0, 0});
        }

        Road straight(double metres) {
            int n = (int) Math.ceil(metres / SAMPLE);
            double x0 = x, y0 = y;
            for (int k = 1; k <= n; k++) {
                double t = metres * k / n;
                add(x0 + t * Math.sin(heading), y0 + t * Math.cos(heading));
            }
            length += metres;
            return this;
        }

        /** A circular arc of {@code radius} metres turning {@code angleDeg} in total, + = right. */
        Road arc(double radius, double angleDeg) {
            double s = Math.signum(angleDeg);
            double cx = x + s * radius * Math.cos(heading), cy = y - s * radius * Math.sin(heading);
            double total = Math.toRadians(angleDeg);
            double len = radius * Math.abs(total);
            int n = (int) Math.ceil(len / SAMPLE);
            double h0 = heading;
            for (int k = 1; k <= n; k++) {
                double h = h0 + total * k / n;
                add(cx - s * radius * Math.cos(h), cy + s * radius * Math.sin(h));
                heading = h;
            }
            length += len;
            return this;
        }

        private void add(double px, double py) {
            x = px;
            y = py;
            points.add(new double[]{px, py});
        }
    }

    /** 200 m straight, one arc, 150 m straight. */
    private static Road bend(double radius, double angleDeg) {
        return new Road().straight(200).arc(radius, angleDeg).straight(150);
    }

    private static List<CurveDetector.Curve> detect(Road road, int level) {
        return CurveDetector.detect(road.points, CurveDetector.Sensitivity.of(level));
    }

    /** The one curve of the road at the level, failing when there is not exactly one. */
    private static CurveDetector.Curve only(Road road, int level) {
        List<CurveDetector.Curve> curves = detect(road, level);
        assertEquals("one curve expected, got " + curves, 1, curves.size());
        return curves.get(0);
    }

    /** The only curve of {@link #bend} at normal sensitivity has the icon, the angle and the radius of the arc. */
    private static CurveDetector.Curve assertBend(int turn, double radius, double angleDeg) {
        CurveDetector.Curve c = only(bend(radius, angleDeg), NORMAL);
        assertEquals("icon of " + c, turn, c.turn);
        assertEquals("angle of " + c, angleDeg, c.angle, ANGLE_TOL);
        assertEquals("radius of " + c, radius, c.minRadius, radius * RADIUS_TOL);
        return c;
    }

    private static void assertReported(Road road, int level, boolean expected) {
        List<CurveDetector.Curve> curves = detect(road, level);
        assertEquals("at sensitivity " + level + ": " + curves, expected, !curves.isEmpty());
    }

    private static CurveDetector.Curve curveStartingAt(double startDist) {
        return new CurveDetector.Curve(RIGHT, startDist, startDist + 40, 60, 100, 0, startDist);
    }

    @Test
    public void aStraightRoadHasNoCurves() {
        Road road = new Road().straight(1_000);
        for (int level : ALL_LEVELS) assertReported(road, level, false);
    }

    @Test
    public void aMotorwaySweepIsNotACurveAtAnyLevel() {
        for (int level : ALL_LEVELS) assertReported(bend(1_500, 20), level, false);
    }

    @Test
    public void aGentleBendIsSlight() {
        assertBend(SLIGHT_RIGHT, 150, 40);
        assertBend(SLIGHT_LEFT, 150, -40);
    }

    @Test
    public void aNormalBendIsLeftOrRight() {
        assertBend(RIGHT, 100, 75);
        assertBend(LEFT, 100, -75);
    }

    @Test
    public void aBendTurningFarIsSharp() {
        assertBend(SHARP_RIGHT, 45, 110);
        assertBend(SHARP_LEFT, 45, -110);
    }

    @Test
    public void aTightBendIsSharpEvenWhenItDoesNotTurnFar() {
        // radius under 30 m: sharp although 80 degrees alone would only be a normal bend
        assertBend(SHARP_RIGHT, 25, 80);
        assertBend(SHARP_LEFT, 25, -80);
    }

    @Test
    public void aHairpinIsAUTurn() {
        assertBend(HAIRPIN_RIGHT, 20, 170);
        assertBend(HAIRPIN_LEFT, 20, -170);
    }

    @Test
    public void aWideTurnAroundIsOnlySharpNotAHairpin() {
        // 170 degrees but 60 m radius: the hairpin icon is for tight ones only
        assertBend(SHARP_RIGHT, 60, 170);
        assertBend(SHARP_LEFT, 60, -170);
    }

    @Test
    public void theCurveStartsAndEndsWhereTheArcDoes() {
        Road road = new Road().straight(200);
        double start = road.length;
        road.arc(100, 75);
        double end = road.length;
        road.straight(150);
        CurveDetector.Curve c = only(road, NORMAL);
        assertTrue("start " + c.startDist + " is not shortly before the arc at " + start,
                c.startDist >= start - EDGE_EARLY_M && c.startDist <= start + EDGE_LATE_M);
        assertTrue("end " + c.endDist + " is not shortly after the arc ends at " + end,
                c.endDist >= end - EDGE_LATE_M && c.endDist <= end + EDGE_EARLY_M);
        // the start point is on the straight leading north to the arc, at the reported distance
        assertEquals(0, c.startX, 1);
        assertEquals(c.startDist, c.startY, 1);
    }

    @Test
    public void twoBendsWithAStraightBetweenAreTwoCurvesInOrder() {
        Road road = new Road().straight(100);
        double first = road.length;
        road.arc(80, 70).straight(100);
        double second = road.length;
        road.arc(80, -70).straight(100);
        List<CurveDetector.Curve> curves = detect(road, NORMAL);
        assertEquals("two curves expected, got " + curves, 2, curves.size());
        assertEquals(RIGHT, curves.get(0).turn);
        assertEquals(LEFT, curves.get(1).turn);
        assertEquals(first, curves.get(0).startDist, EDGE_EARLY_M);
        assertEquals(second, curves.get(1).startDist, EDGE_EARLY_M);
        assertTrue("not in order of distance: " + curves, curves.get(0).endDist <= curves.get(1).startDist);
    }

    @Test
    public void anSBendIsTwoCurvesNotOne() {
        Road road = new Road().straight(100).arc(100, -60).arc(100, 60).straight(100);
        List<CurveDetector.Curve> curves = detect(road, NORMAL);
        assertEquals("two curves expected, got " + curves, 2, curves.size());
        assertEquals(LEFT, curves.get(0).turn);
        assertEquals(-60, curves.get(0).angle, ANGLE_TOL);
        assertEquals(RIGHT, curves.get(1).turn);
        assertEquals(60, curves.get(1).angle, ANGLE_TOL);
        assertTrue("the right bend must come after the left one: " + curves,
                curves.get(1).startDist > curves.get(0).startDist);
    }

    @Test
    public void aRiderAlreadyInABendIsShownTheNextOne() {
        Road road = new Road().arc(100, 60).straight(150).arc(100, -60).straight(100);
        List<CurveDetector.Curve> curves = detect(road, NORMAL);
        assertEquals("two curves expected, got " + curves, 2, curves.size());
        CurveDetector.Curve inside = curves.get(0);
        assertEquals("a bend the rider is in starts at 0: " + inside, 0, inside.startDist, 0);
        assertEquals(0, inside.startX, 0);
        assertEquals(0, inside.startY, 0);
        assertEquals(RIGHT, inside.turn);
        assertSame(curves.get(1), CurveDetector.next(curves));
        assertEquals(LEFT, CurveDetector.next(curves).turn);
    }

    @Test
    public void lowSensitivityIgnoresAWideBendThatNormalReports() {
        // 240 m radius: about 8 degrees per window, between the low (10) and normal (7) thresholds
        Road road = bend(240, 60);
        assertReported(road, LOW, false);
        assertReported(road, NORMAL, true);
        assertReported(road, HIGH, true);
        assertReported(road, HIGHEST, true);
    }

    @Test
    public void lowSensitivityIgnoresAShortBendThatNormalReports() {
        // tight enough for the low level, but under its 45 degrees
        Road road = bend(100, 40);
        assertReported(road, LOW, false);
        assertReported(road, NORMAL, true);
        assertEquals(SLIGHT_RIGHT, only(road, NORMAL).turn);
    }

    @Test
    public void onlyHighSensitivityReportsAWiderBend() {
        // 340 m radius: about 6 degrees per window, over the high (5) but under the normal (7) threshold
        Road road = bend(340, 45);
        assertReported(road, LOW, false);
        assertReported(road, NORMAL, false);
        assertReported(road, HIGH, true);
        assertReported(road, HIGHEST, true);
        assertEquals(SLIGHT_RIGHT, only(road, HIGH).turn);
    }

    @Test
    public void onlyTheHighestLevelReportsALongGentleBend() {
        // 550 m radius: under 4 degrees per window, only the gentle threshold (3) of level 3 sees it
        Road road = bend(550, 30);
        assertReported(road, LOW, false);
        assertReported(road, NORMAL, false);
        assertReported(road, HIGH, false);
        CurveDetector.Curve c = only(road, HIGHEST);
        assertEquals(SLIGHT_RIGHT, c.turn);
        assertEquals(30, c.angle, ANGLE_TOL);
        assertEquals(550, c.minRadius, 550 * RADIUS_TOL);
    }

    @Test
    public void theGentleThresholdNeverMergesTwoBendsIntoOne() {
        // two real bends joined by a long gentle arc the same way: the gentle threshold alone would
        // see one 140 degree curve (a sharp one), the rider must still get two normal bends
        Road road = new Road().straight(100).arc(100, 60).arc(500, 20).arc(100, 60).straight(100);
        for (int level : new int[]{NORMAL, HIGHEST}) {
            List<CurveDetector.Curve> curves = detect(road, level);
            assertEquals("at sensitivity " + level + ": " + curves, 2, curves.size());
            for (CurveDetector.Curve c : curves) {
                assertEquals("at sensitivity " + level + ": " + c, RIGHT, c.turn);
                assertTrue("at sensitivity " + level + " the bends were merged: " + c, c.angle < 90);
            }
            assertTrue("not in order of distance: " + curves, curves.get(0).endDist <= curves.get(1).startDist);
        }
    }

    @Test
    public void nextSkipsTheCurvesAlreadyEntered() {
        CurveDetector.Curve entered = curveStartingAt(-10);
        CurveDetector.Curve atTheThreshold = curveStartingAt(CurveDetector.ENTERED_M);
        CurveDetector.Curve ahead = curveStartingAt(80);
        CurveDetector.Curve further = curveStartingAt(300);
        assertSame(ahead, CurveDetector.next(Arrays.asList(entered, atTheThreshold, ahead, further)));
        assertSame(further, CurveDetector.next(Arrays.asList(entered, further)));
        assertSame(ahead, CurveDetector.next(Collections.singletonList(ahead)));
    }

    @Test
    public void nextIsNullWhenNoCurveIsAhead() {
        assertNull(CurveDetector.next(Collections.emptyList()));
        assertNull(CurveDetector.next(Arrays.asList(curveStartingAt(-30), curveStartingAt(CurveDetector.ENTERED_M))));
    }

    @Test
    public void aVeryShortPathGivesNoCurves() {
        CurveDetector.Sensitivity sens = CurveDetector.Sensitivity.of(HIGHEST);
        assertTrue(CurveDetector.detect(Collections.emptyList(), sens).isEmpty());
        assertTrue(CurveDetector.detect(Collections.singletonList(new double[]{0, 0}), sens).isEmpty());
        // two points too close to resample into three
        assertTrue(CurveDetector.detect(Arrays.asList(new double[]{0, 0}, new double[]{0, 3}), sens).isEmpty());
        // two points far apart: a straight
        assertTrue(CurveDetector.detect(Arrays.asList(new double[]{0, 0}, new double[]{0, 300}), sens).isEmpty());
    }

    @Test
    public void aCornerDrawnWithThreeNodesIsSharpNotAHairpin() {
        // sparse OSM geometry: a right angle as two straight ways meeting at a node
        List<double[]> path = Arrays.asList(new double[]{0, 0}, new double[]{0, 100}, new double[]{100, 100});
        List<CurveDetector.Curve> curves = CurveDetector.detect(path, CurveDetector.Sensitivity.of(NORMAL));
        assertEquals("one curve expected, got " + curves, 1, curves.size());
        CurveDetector.Curve c = curves.get(0);
        assertEquals(SHARP_RIGHT, c.turn);
        assertEquals(90, c.angle, ANGLE_TOL);
        // the whole heading change sits on one node: the bend starts and ends at that node, not a
        // smoothing window before and after it
        assertEquals(100, c.startDist, 1);
        assertEquals(100, c.endDist, 1);
    }

    @Test
    public void everyKindOfBendDescribesItself() {
        double[][] bends = {{150, 40}, {100, 75}, {45, 110}, {20, 170}};
        List<Integer> seen = new ArrayList<>();
        for (double[] b : bends) {
            for (double sign : new double[]{1, -1}) {
                CurveDetector.Curve c = only(bend(b[0], sign * b[1]), NORMAL);
                String s = c.toString();
                assertNotNull(s);
                assertTrue(s + " does not name its icon", s.contains(KawaProtocol.TURN_NAMES[c.turn]));
                seen.add(c.turn);
            }
        }
        assertEquals(Arrays.asList(SLIGHT_RIGHT, SLIGHT_LEFT, RIGHT, LEFT, SHARP_RIGHT, SHARP_LEFT,
                HAIRPIN_RIGHT, HAIRPIN_LEFT), seen);
    }
}
