package io.github.darthbob.kawanav;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** The planner's points: via points where they detour least, the destination removed, the way reversed. */
public class WaypointsTest {

    private static Waypoints.Point p(double lat, double lon, String name) {
        return new Waypoints.Point(lat, lon, name);
    }

    @Test
    public void viaGoesWhereItDetoursLeast() {
        // start, then east along the equator to the destination 0.3° away
        Waypoints w = new Waypoints();
        w.start = p(0, 0, "A");
        w.dest = p(0, 0.3, "B");
        w.insertVia(p(0.01, 0.2, "near the end"), null);
        w.insertVia(p(0.01, 0.1, "near the start"), null);
        assertEquals("near the start", w.vias.get(0).name);
        assertEquals("near the end", w.vias.get(1).name);
        // beyond the destination: still before it (the destination stays the destination)
        w.insertVia(p(0, 0.5, "past the end"), null);
        assertEquals("past the end", w.vias.get(2).name);
        assertEquals("B", w.dest.name);
    }

    @Test
    public void viaFromTheRidersPosition() {
        Waypoints w = new Waypoints();
        w.dest = p(0, 0.3, "B");
        w.vias.add(p(0, 0.2, "V"));
        w.insertVia(p(0, 0.05, "early"), new double[]{0, 0});
        assertEquals("early", w.vias.get(0).name);
        List<double[]> r = w.forRouter(new double[]{0, 0});
        assertEquals(4, r.size());
        assertArrayEquals(new double[]{0, 0}, r.get(0), 0);
        assertArrayEquals(new double[]{0, 0.3}, r.get(3), 0);
    }

    @Test
    public void bestGapWithAnUnknownStart() {
        List<double[]> pts = Arrays.asList(null, new double[]{0, 0.1}, new double[]{0, 0.3});
        assertEquals("before the first via point when it is nearer", 1, Waypoints.bestGap(pts, new double[]{0, 0.05}));
        assertEquals(2, Waypoints.bestGap(pts, new double[]{0, 0.2}));
    }

    @Test
    public void noRouteWithoutStartOrDestination() {
        Waypoints w = new Waypoints();
        w.dest = p(0, 0.3, "B");
        assertNull("no start and no position", w.forRouter(null));
        w.dest = null;
        assertNull(w.forRouter(new double[]{0, 0}));
    }

    @Test
    public void removingTheDestinationKeepsTheLastViaAsDestination() {
        Waypoints w = new Waypoints();
        w.dest = p(0, 0.3, "B");
        Waypoints.Point v1 = p(0, 0.1, "V1"), v2 = p(0, 0.2, "V2");
        w.vias.addAll(Arrays.asList(v1, v2));
        w.removeDest();
        assertSame(v2, w.dest);
        assertEquals(1, w.vias.size());
        w.removeDest();
        w.removeDest();
        assertNull(w.dest);
    }

    @Test
    public void reverse() {
        Waypoints w = new Waypoints();
        w.dest = p(0, 0.3, "B");
        w.vias.addAll(Arrays.asList(p(0, 0.1, "V1"), p(0, 0.2, "V2")));
        assertFalse("from my position without a position", w.reverse(null, "Here"));
        assertTrue(w.reverse(new double[]{0, 0}, "Here"));
        assertEquals("B", w.start.name);
        assertEquals("Here", w.dest.name);
        assertEquals("V2", w.vias.get(0).name);
        assertTrue(w.reverse(null, "Here"));
        assertEquals("Here", w.start.name);
        assertEquals("B", w.dest.name);
    }

    @Test
    public void headingTowardsAPlace() {
        Waypoints w = new Waypoints();
        assertTrue("any direction", Double.isNaN(w.heading(new double[]{45, 7})));
        w.towards = p(45, 8, "east");
        assertEquals(90, w.heading(new double[]{45, 7}), 0.01);
        w.towards = p(44, 7, "south");
        assertEquals(180, w.heading(new double[]{45, 7}), 0.01);
        // a degree of longitude at 45° is shorter than one of latitude: north-west is nearer north
        w.start = p(45, 7, "start");
        w.towards = p(46, 6, "north-west");
        assertEquals(360 - Math.toDegrees(Math.atan(Math.cos(Math.toRadians(45)))), w.heading(null), 0.01);
    }

    @Test
    public void loopThroughTheCorners() {
        Waypoints w = new Waypoints();
        assertNull(w.forLoop(null));
        w.corners.addAll(Arrays.asList(p(0.1, 0, "C1"), p(0.1, 0.1, "C2")));
        List<double[]> l = w.forLoop(new double[]{0, 0});
        assertEquals(4, l.size());
        assertArrayEquals(l.get(0), l.get(3), 0);
        w.cornersEdited = true;
        w.newLoop();
        assertTrue(w.corners.isEmpty());
        assertFalse(w.cornersEdited);
    }
}
