package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Round trips on a grid of roads 2 km apart (28 x 28 km), started in its middle. */
public class RoundTripTest {

    private static RouteGraph grid;
    private static final double CX = 14_000, CY = 14_000;

    @BeforeClass
    public static void buildGrid() throws Exception {
        List<TestGraphs.Way> ways = new ArrayList<>();
        int n = 15;
        for (int row = 0; row < n; row++) {
            long[] ids = new long[n];
            double[] xy = new double[2 * n];
            for (int col = 0; col < n; col++) {
                ids[col] = row * 100 + col + 1;
                xy[2 * col] = col * 2000;
                xy[2 * col + 1] = row * 2000;
            }
            ways.add(TestGraphs.way(ids, xy).highway("tertiary").name("Row " + row));
        }
        for (int col = 0; col < n; col++) {
            long[] ids = new long[n];
            double[] xy = new double[2 * n];
            for (int row = 0; row < n; row++) {
                ids[row] = row * 100 + col + 1;
                xy[2 * row] = col * 2000;
                xy[2 * row + 1] = row * 2000;
            }
            ways.add(TestGraphs.way(ids, xy).highway("tertiary").name("Col " + col));
        }
        grid = TestGraphs.open(TestGraphs.build(ways));
    }

    private static RoundTrip.Request request(double km, double heading, long seed) {
        RoundTrip.Request r = new RoundTrip.Request(TestGraphs.lat(CY + 300), TestGraphs.lon(CX),
                km, RouteProfile.of(RouteProfile.Kind.CURVY));
        r.heading = heading;
        r.seed = seed;
        return r;
    }

    private static void assertLoop(Route r, double km) {
        assertTrue(r.roundTrip);
        assertEquals("length " + r.length / 1000, km, r.length / 1000, km * RoundTrip.TOLERANCE + 0.5);
        assertEquals(r.lat[0], r.lat[r.lat.length - 1], 1e-6);
        assertEquals(r.lon[0], r.lon[r.lon.length - 1], 1e-6);
        for (int i = 1; i < r.states.length; i++) {
            assertTrue("no U-turn at " + r.stateFrom[i], RouteGraph.edgeOf(r.states[i]) != RouteGraph.edgeOf(r.states[i - 1])
                    || r.states[i] == r.states[i - 1]);
        }
    }

    @Test
    public void lengthAndShape() throws Exception {
        Route r = RoundTrip.plan(grid, request(40, 0, 1));
        assertLoop(r, 40);
        assertEquals("triangle: two corners, then home", 3, r.vias.length);
        // it heads north: most of it lies north of the start
        double y = 0;
        for (double lat : r.lat) y += TestGraphs.y(lat);
        assertTrue(y / r.lat.length > CY + 2000);
    }

    @Test
    public void squareAndLongerTrip() throws Exception {
        RoundTrip.Request q = request(70, 90, 3);
        q.shape = RoundTrip.Shape.SQUARE;
        Route r = RoundTrip.plan(grid, q);
        assertLoop(r, 70);
        assertEquals(4, r.vias.length);
        double x = 0;
        for (double lon : r.lon) x += TestGraphs.x(lon);
        assertTrue("east", x / r.lon.length > CX + 3000);
    }

    @Test
    public void anotherSeedAnotherTrip() throws Exception {
        Route a = RoundTrip.plan(grid, request(40, Double.NaN, 1));
        Route b = RoundTrip.plan(grid, request(40, Double.NaN, 2));
        assertLoop(a, 40);
        assertLoop(b, 40);
        assertTrue(!Arrays.equals(a.states, b.states));
    }

    @Test
    public void viaPointsAreNotAnnounced() throws Exception {
        Route r = RoundTrip.plan(grid, request(40, 180, 5));
        for (Instructions.Maneuver m : Instructions.of(grid, r, false)) assertTrue(m.kind != Instructions.Kind.VIA);
    }

    @Test
    public void stubToADeadEndIsCut() throws Exception {
        // a road east with a dead-end arm north at x = 1000; the route goes up the arm to a via point and back
        RouteGraph g = TestGraphs.open(TestGraphs.build(Arrays.asList(
                TestGraphs.way(new long[]{1, 2, 3}, 0, 0, 1000, 0, 2000, 0).name("Main"),
                TestGraphs.way(new long[]{2, 11}, 1000, 0, 1000, 1000).name("Arm"))));
        List<double[]> pts = Arrays.asList(new double[]{TestGraphs.lat(0), TestGraphs.lon(100)},
                new double[]{TestGraphs.lat(800), TestGraphs.lon(1000)},
                new double[]{TestGraphs.lat(0), TestGraphs.lon(1900)});
        Route r = new Router(g).route(new Router.Request(pts, RouteProfile.of(RouteProfile.Kind.FASTEST)));
        double[] base = RoundTrip.stubBase(g, r, r.vias[0]);
        assertNotNull(base);
        assertEquals(1000, TestGraphs.x(base[1]), 5);
        assertEquals(0, TestGraphs.y(base[0]), 5);
        // no stub where the route goes on
        Route plain = new Router(g).route(new Router.Request(Arrays.asList(pts.get(0),
                new double[]{TestGraphs.lat(0), TestGraphs.lon(600)}, pts.get(2)), RouteProfile.of(RouteProfile.Kind.FASTEST)));
        assertNull(RoundTrip.stubBase(g, plain, plain.vias[0]));
    }
}
