package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Ways cut at junctions into edges, with their attributes, adjacency, index and region joins. */
public class RouteGraphBuilderTest {

    /** A road east (ids 1..5, 1 km apart) crossed at its middle by a oneway road north (10, 3, 11). */
    private static File cross() throws Exception {
        TestGraphs.Way east = TestGraphs.way(new long[]{1, 2, 3, 4, 5}, 0, 0, 1000, 0, 2000, 0, 3000, 0, 4000, 0)
                .name("Via Roma");
        TestGraphs.Way north = TestGraphs.way(new long[]{10, 3, 11}, 2000, -1500, 2000, 0, 2000, 1500)
                .highway("tertiary").oneway("yes").name("Via Nord");
        return TestGraphs.build(Arrays.asList(east, north));
    }

    @Test
    public void waysAreCutAtJunctions() throws Exception {
        RouteGraph g = TestGraphs.open(cross());
        assertEquals("ends 1, 5, 10, 11 and the junction 3", 5, g.nodeCount());
        assertEquals(4, g.edgeCount());
        double total = 0;
        for (int e = 0; e < g.edgeCount(); e++) total += g.length(e);
        assertEquals(2 * 2000 + 2 * 1500, total, 5);

        // the junction: 4 ways out
        int junction = -1;
        for (int n = 0; n < g.nodeCount(); n++) {
            if (Math.abs(g.lat(n) - TestGraphs.lat(0)) < 1e-6 && Math.abs(g.lon(n) - TestGraphs.lon(2000)) < 1e-6) junction = n;
        }
        assertTrue(junction >= 0);
        int[] out = new int[g.degreeBound()];
        assertEquals(4, g.out(junction, out));
    }

    @Test
    public void attributesOnwayAndBearings() throws Exception {
        RouteGraph g = TestGraphs.open(cross());
        for (int e = 0; e < g.edgeCount(); e++) {
            int f = g.flags(e);
            if (g.name(e).equals("Via Nord")) {
                assertEquals(RouteGraph.C_TERTIARY, RouteGraph.roadClass(f));
                assertTrue(g.allowed(e * 2));
                assertFalse("oneway north", g.allowed(e * 2 + 1));
                assertEquals(0, g.startBearing(e * 2));
                assertEquals(180, g.startBearing(e * 2 + 1));
            } else {
                assertEquals("Via Roma", g.name(e));
                assertEquals(RouteGraph.C_SECONDARY, RouteGraph.roadClass(f));
                assertTrue(g.allowed(e * 2) && g.allowed(e * 2 + 1));
                assertEquals(90, g.startBearing(e * 2));
                assertEquals(270, g.endBearing(e * 2 + 1));
                assertEquals("2 km, straight", 0, RouteGraph.curvy(f));
            }
        }
    }

    @Test
    public void edgeGeometryKeepsInnerPoints() throws Exception {
        RouteGraph g = TestGraphs.open(cross());
        for (int e = 0; e < g.edgeCount(); e++) {
            if (!g.name(e).equals("Via Roma")) continue;
            int[] p = g.points(e);
            assertEquals("a, the 1 km point, b", 6, p.length);
            int[] r = g.points(e, true);
            assertEquals(p[0], r[4]);
            assertEquals(p[5], r[1]);
        }
    }

    @Test
    public void nearestRoad() throws Exception {
        RouteGraph g = TestGraphs.open(cross());
        RouteGraph.Snap s = g.nearest(TestGraphs.lat(25), TestGraphs.lon(700), 200, null);
        assertNotNull(s);
        assertEquals("Via Roma", g.name(s.edge));
        assertEquals(25, s.distance, 1);
        double fromA = g.edgeA(s.edge) == 0 || g.lon(g.edgeA(s.edge)) < TestGraphs.lon(1000) ? 700 : 1300;
        assertEquals(fromA, s.along, 2);
        assertEquals(TestGraphs.lat(0), s.lat, 1e-6);
        assertNull("too far", g.nearest(TestGraphs.lat(900), TestGraphs.lon(700), 200, null));
        RouteGraph.Snap north = g.nearest(TestGraphs.lat(900), TestGraphs.lon(1950), 200, null);
        assertEquals("Via Nord", g.name(north.edge));
        // a filter can refuse a road: the next one is taken
        RouteGraph.Snap noTertiary = g.nearest(TestGraphs.lat(100), TestGraphs.lon(1990), 500,
                (e, f) -> RouteGraph.roadClass(f) != RouteGraph.C_TERTIARY);
        assertEquals("Via Roma", g.name(noTertiary.edge));
    }

    @Test
    public void windingRoadGetsItsLevel() throws Exception {
        TestGraphs.Way snake = TestGraphs.snake(100, 0, 0, 110, 10, 5).name("Passo");
        RouteGraph g = TestGraphs.open(TestGraphs.build(Arrays.asList(snake)));
        assertEquals(1, g.edgeCount());
        // 57296 / 110 = 521 degrees per km
        assertEquals(3, RouteGraph.curvy(g.flags(0)));
    }

    @Test
    public void windingRoadCutIntoShortWaysKeepsItsLevel() throws Exception {
        // OSM often cuts a mountain road into short ways: the measure must follow the road across them
        TestGraphs.Way snake = TestGraphs.snake(100, 0, 0, 110, 10, 5);
        java.util.List<TestGraphs.Way> pieces = new java.util.ArrayList<>();
        for (int s = 0; s < snake.ids.length - 1; s += 8) {
            int e = Math.min(snake.ids.length - 1, s + 8);
            double[] xy = new double[(e - s + 1) * 2];
            for (int i = s; i <= e; i++) {
                xy[2 * (i - s)] = snake.xs[i];
                xy[2 * (i - s) + 1] = snake.ys[i];
            }
            pieces.add(TestGraphs.way(Arrays.copyOfRange(snake.ids, s, e + 1), xy).name("Passo"));
        }
        RouteGraph g = TestGraphs.open(TestGraphs.build(pieces));
        assertTrue(g.edgeCount() > 10);
        for (int e = 0; e < g.edgeCount(); e++) assertEquals("edge " + e, 3, RouteGraph.curvy(g.flags(e)));
    }

    @Test
    public void aStreetCornerIsNotAWindingRoad() throws Exception {
        TestGraphs.Way corner = TestGraphs.way(new long[]{1, 2, 3}, 0, 0, 120, 0, 120, 120).name("Via Corta");
        RouteGraph g = TestGraphs.open(TestGraphs.build(Arrays.asList(corner)));
        assertEquals(0, RouteGraph.curvy(g.flags(0)));
    }

    @Test
    public void wayAttributesFromTags() {
        int a = RouteGraphBuilder.attributes("gravel", "", "", "IT:urban", "", "", "", "yes");
        assertEquals(2, a >>> RouteGraph.F_SURFACE_SHIFT & 3);
        assertTrue((a & RouteGraph.F_URBAN) != 0);
        assertTrue((a & RouteGraph.F_TOLL) != 0);
        assertEquals(50, RouteGraph.maxspeedKmh(a));
        int b = RouteGraphBuilder.attributes("", "grade4", "", "30 mph", "private", "", "yes", "");
        assertEquals(3, RouteGraph.surface(b));
        assertEquals(50, RouteGraph.maxspeedKmh(b));
        assertEquals("motorcycle=yes beats access=private", 0, RouteGraph.access(b));
        int c = RouteGraphBuilder.attributes("asphalt", "", "", "90", "", "destination", "", "");
        assertEquals(0, RouteGraph.surface(c));
        assertEquals(1, RouteGraph.access(c));
        assertEquals(0, c & RouteGraph.F_URBAN);
        assertEquals(1, RouteGraphBuilder.oneway("", "motorway", ""));
        assertEquals(2, RouteGraphBuilder.oneway("-1", "secondary", ""));
        assertEquals(1, RouteGraphBuilder.oneway("", "secondary", "roundabout"));
    }

    @Test
    public void neighbourRegionsAreJoinedAtSharedJunctions() throws Exception {
        File dir = Files.createTempDirectory("regions").toFile();
        // west region: road 1-2-3 ending at junction 3; east region: road 3-4-5 plus a side road 3-6
        TestGraphs.Way west = TestGraphs.way(new long[]{1, 2, 3}, 0, 0, 1000, 0, 2000, 0);
        TestGraphs.Way westStub = TestGraphs.way(new long[]{3, 7}, 2000, 0, 2000, 500); // makes 3 a junction there
        TestGraphs.Way east = TestGraphs.way(new long[]{3, 4, 5}, 2000, 0, 3000, 0, 4000, 0);
        TestGraphs.Way side = TestGraphs.way(new long[]{3, 6}, 2000, 0, 2000, -500);
        File w = TestGraphs.build(Arrays.asList(west, westStub), (ty, tx) -> false, new File(dir, "w"));
        File e = TestGraphs.build(Arrays.asList(east, side), (ty, tx) -> false, new File(dir, "e"));
        RouteGraph g = TestGraphs.open(w, e);
        assertEquals(4, g.edgeCount());
        int[] out = new int[g.degreeBound()];
        Set<String> names = new HashSet<>();
        int j = -1;
        for (int n = 0; n < g.nodeCount(); n++) {
            if (Math.abs(g.lon(n) - TestGraphs.lon(2000)) < 1e-6 && Math.abs(g.lat(n) - TestGraphs.lat(0)) < 1e-6) {
                j = n;
                break;
            }
        }
        int k = g.out(j, out);
        assertEquals("both regions' roads leave the shared junction", 4, k);
        for (int i = 0; i < k; i++) names.add(g.from(out[i]) + "");
        assertEquals(2, names.size());
        assertTrue(g.sameNode(g.from(out[0]), g.from(out[k - 1])));
    }
}
