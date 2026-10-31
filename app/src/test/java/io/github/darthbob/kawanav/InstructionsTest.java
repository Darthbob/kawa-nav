package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Maneuvers from routes on small drawn junctions, and the dash's turn ids for them. */
public class InstructionsTest {

    private static List<Instructions.Maneuver> route(List<TestGraphs.Way> ways, double... xy) throws Exception {
        RouteGraph g = TestGraphs.open(TestGraphs.build(ways));
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) pts.add(new double[]{TestGraphs.lat(xy[i + 1]), TestGraphs.lon(xy[i])});
        Route r = new Router(g).route(new Router.Request(pts, RouteProfile.of(RouteProfile.Kind.FASTEST)));
        return Instructions.of(g, r, false);
    }

    /** Without the departure and the arrival. */
    private static List<Instructions.Maneuver> inner(List<Instructions.Maneuver> l) {
        assertEquals(Instructions.Kind.DEPART, l.get(0).kind);
        assertEquals(Instructions.Kind.ARRIVE, l.get(l.size() - 1).kind);
        assertEquals(43, l.get(l.size() - 1).turn);
        return l.subList(1, l.size() - 1);
    }

    /** A road east with a crossing road at x = 1000 (north and south arms). */
    private static List<TestGraphs.Way> crossing() {
        return new ArrayList<>(Arrays.asList(
                TestGraphs.way(new long[]{1, 2, 3}, 0, 0, 1000, 0, 2000, 0).name("Via Roma"),
                TestGraphs.way(new long[]{10, 2}, 1000, -1000, 1000, 0).name("Via Sud"),
                TestGraphs.way(new long[]{2, 11}, 1000, 0, 1000, 1000).name("Via Nord")));
    }

    @Test
    public void turnLeftAndRight() throws Exception {
        List<Instructions.Maneuver> left = inner(route(crossing(), 100, 0, 1000, 900));
        assertEquals(1, left.size());
        Instructions.Maneuver m = left.get(0);
        assertEquals(Instructions.Kind.TURN, m.kind);
        assertEquals(-1, m.side);
        assertEquals(1, m.turn);
        assertEquals("Via Nord", m.road);
        assertEquals(900, m.at, 1);

        Instructions.Maneuver right = inner(route(crossing(), 100, 0, 1000, -900)).get(0);
        assertEquals(2, right.turn);
        assertEquals("Via Sud", right.road);
    }

    @Test
    public void straightOnSaysNothing() throws Exception {
        assertEquals(0, inner(route(crossing(), 100, 0, 1900, 0)).size());
    }

    /** A roundabout of 30 m radius at the origin, driven counter-clockwise, with arms S, E, N, W. */
    private static List<TestGraphs.Way> roundabout() {
        List<Double> xy = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        long[] at = {1, 2, 3, 4}; // S (270°), E (0°), N (90°), W (180°)
        long next = 100;
        for (int q = 0; q < 4; q++) {
            double a0 = 270 + 90 * q;
            for (int k = 0; k < 6; k++) {
                double a = Math.toRadians(a0 + 15 * k);
                xy.add(30 * Math.cos(a));
                xy.add(30 * Math.sin(a));
                ids.add(k == 0 ? at[q] : next++);
            }
        }
        xy.add(xy.get(0));
        xy.add(xy.get(1));
        ids.add(1L);
        long[] ringIds = new long[ids.size()];
        double[] ringXy = new double[xy.size()];
        for (int i = 0; i < ringIds.length; i++) ringIds[i] = ids.get(i);
        for (int i = 0; i < ringXy.length; i++) ringXy[i] = xy.get(i);
        TestGraphs.Way ring = TestGraphs.way(ringIds, ringXy).roundabout().highway("tertiary");
        return new ArrayList<>(Arrays.asList(ring,
                TestGraphs.way(new long[]{10, 1}, 0, -600, 0, -30).name("South"),
                TestGraphs.way(new long[]{2, 20}, 30, 0, 600, 0).name("East"),
                TestGraphs.way(new long[]{3, 30}, 0, 30, 0, 600).name("North"),
                TestGraphs.way(new long[]{4, 40}, -30, 0, -600, 0).name("West")));
    }

    @Test
    public void roundaboutExitsCountedCounterClockwise() throws Exception {
        Instructions.Maneuver east = inner(route(roundabout(), 0, -500, 500, 0)).get(0);
        assertEquals(Instructions.Kind.ROUNDABOUT, east.kind);
        assertEquals(1, east.number);
        assertEquals("CCW exit 1", 30, east.turn);
        assertEquals("East", east.road);

        Instructions.Maneuver west = inner(route(roundabout(), 0, -500, -500, 0)).get(0);
        assertEquals(3, west.number);
        assertEquals(32, west.turn);
        assertEquals(KawaProtocol.TURN_NAMES[32], "Rbt CCW exit 3");
        assertEquals(1, inner(route(roundabout(), 0, -500, -500, 0)).size());
    }

    @Test
    public void motorwayExitAndMerge() throws Exception {
        List<TestGraphs.Way> ways = new ArrayList<>(Arrays.asList(
                TestGraphs.way(new long[]{1, 2, 3}, 0, 0, 2000, 0, 4000, 0).highway("motorway").name("A1"),
                // exit: leaves to the right, then goes south
                TestGraphs.way(new long[]{2, 50, 51}, 2000, 0, 2300, -40, 2600, -400).highway("motorway_link"),
                TestGraphs.way(new long[]{51, 52}, 2600, -400, 2600, -2000).highway("secondary").name("SP12")));
        List<Instructions.Maneuver> m = inner(route(ways, 500, 0, 2600, -1500));
        assertEquals(Instructions.Kind.EXIT, m.get(0).kind);
        assertEquals("exit right", 15, m.get(0).turn);

        List<TestGraphs.Way> in = new ArrayList<>(Arrays.asList(
                TestGraphs.way(new long[]{1, 2, 3}, 0, 0, 2000, 0, 4000, 0).highway("motorway").name("A1"),
                TestGraphs.way(new long[]{60, 61, 2}, 1400, -600, 1700, -40, 2000, 0).highway("motorway_link").oneway("yes"),
                TestGraphs.way(new long[]{62, 60}, 1400, -2000, 1400, -600).highway("secondary").name("SP12")));
        List<Instructions.Maneuver> merge = inner(route(in, 1400, -1500, 3500, 0));
        assertEquals(Instructions.Kind.MERGE, merge.get(merge.size() - 1).kind);
    }

    @Test
    public void viaPointAndTurnBack() throws Exception {
        List<Instructions.Maneuver> m = inner(route(crossing(), 100, 0, 1800, 0, 300, 0));
        assertEquals(Instructions.Kind.VIA, m.get(0).kind);
        assertEquals(1, m.get(0).number);
        assertEquals(44, m.get(0).turn);
        assertEquals(Instructions.Kind.U_TURN, m.get(1).kind);
        assertEquals(7, m.get(1).turn);
        assertTrue(Instructions.next(route(crossing(), 100, 0, 1000, 900), 0).kind == Instructions.Kind.TURN);
    }
}
