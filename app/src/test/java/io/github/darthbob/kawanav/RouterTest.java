package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Two roads join the junctions W (0, 0) and E (5000, 0): a straight one and a snake of 22 tight bends
 * (7.9 km). Short roads lead in from the west and out to the east.
 */
public class RouterTest {

    private static final double STRAIGHT_KM = 5.0 + 0.8, SNAKE_KM = 7.85 + 0.8;

    private static List<TestGraphs.Way> twoRoads(String straightHighway, int straightAttributes) {
        TestGraphs.Way straight = TestGraphs.way(new long[]{1, 101, 102, 103, 104, 2},
                0, 0, 1000, 0, 2000, 0, 3000, 0, 4000, 0, 5000, 0).highway(straightHighway).name("Straight")
                .attributes(straightAttributes);
        TestGraphs.Way snake = TestGraphs.snake(1000, 0, 0, 5000 / 44.0, 22, 6).highway("tertiary").name("Snake");
        snake.ids[0] = 1;
        snake.ids[snake.ids.length - 1] = 2;
        TestGraphs.Way west = TestGraphs.way(new long[]{50, 1}, -500, 0, 0, 0).name("West");
        TestGraphs.Way east = TestGraphs.way(new long[]{2, 60}, 5000, 0, 5500, 0).name("East");
        return new ArrayList<>(Arrays.asList(straight, snake, west, east));
    }

    private static Route route(RouteGraph g, RouteProfile p, double... xy) throws Exception {
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) pts.add(new double[]{TestGraphs.lat(xy[i + 1]), TestGraphs.lon(xy[i])});
        return new Router(g).route(new Router.Request(pts, p));
    }

    private static Set<String> names(RouteGraph g, Route r) {
        Set<String> s = new HashSet<>();
        for (int st : r.states) s.add(g.name(RouteGraph.edgeOf(st)));
        return s;
    }

    @Test
    public void fastestTakesTheStraightRoad() throws Exception {
        RouteGraph g = TestGraphs.open(TestGraphs.build(twoRoads("secondary", 0)));
        Route r = route(g, RouteProfile.of(RouteProfile.Kind.FASTEST), -400, 20, 5400, 20);
        assertTrue(names(g, r).contains("Straight"));
        assertEquals(STRAIGHT_KM, r.length / 1000, 0.05);
        assertEquals(0, r.kmByLevel[3], 0.01);
        // the line starts and ends on the road, next to the points asked for
        assertEquals(TestGraphs.lon(-400), r.lon[0], 1e-6);
        assertEquals(TestGraphs.lat(0), r.lat[0], 1e-6);
        assertEquals(TestGraphs.lon(5400), r.lon[r.lon.length - 1], 1e-6);
        for (int i = 1; i < r.dist.length; i++) assertTrue(r.dist[i] >= r.dist[i - 1]);
        assertEquals(r.length, r.vias[r.vias.length - 1], 1e-6);
        assertTrue(r.time > 0);
    }

    @Test
    public void extraCurvyTakesTheBends() throws Exception {
        RouteGraph g = TestGraphs.open(TestGraphs.build(twoRoads("secondary", 0)));
        Route r = route(g, RouteProfile.of(RouteProfile.Kind.EXTRA_CURVY), -400, 20, 5400, 20);
        assertTrue(names(g, r).contains("Snake"));
        assertEquals(SNAKE_KM, r.length / 1000, 0.15);
        assertTrue(r.windingShare() > 0.8);
    }

    @Test
    public void curvyLeavesTheTownForTheBends() throws Exception {
        int town = RouteGraphBuilder.attributes("", "", "", "50", "", "", "", "");
        RouteGraph g = TestGraphs.open(TestGraphs.build(twoRoads("secondary", town)));
        assertTrue(names(g, route(g, RouteProfile.of(RouteProfile.Kind.CURVY), -400, 0, 5400, 0)).contains("Snake"));
        assertTrue(names(g, route(g, RouteProfile.of(RouteProfile.Kind.FASTEST), -400, 0, 5400, 0)).contains("Straight"));
    }

    @Test
    public void avoidMotorways() throws Exception {
        RouteGraph g = TestGraphs.open(TestGraphs.build(twoRoads("motorway", 0)));
        // the motorway is a oneway eastwards by default
        assertTrue(names(g, route(g, RouteProfile.of(RouteProfile.Kind.FASTEST), -400, 0, 5400, 0)).contains("Straight"));
        RouteProfile avoid = new RouteProfile(RouteProfile.Kind.FASTEST, true, false, true);
        assertTrue(names(g, route(g, avoid, -400, 0, 5400, 0)).contains("Snake"));
        // westwards the motorway cannot be used at all
        assertTrue(names(g, route(g, RouteProfile.of(RouteProfile.Kind.FASTEST), 5400, 0, -400, 0)).contains("Snake"));
    }

    @Test
    public void gravelIsAvoidedUnlessAllowed() throws Exception {
        int gravel = RouteGraphBuilder.attributes("gravel", "", "", "", "", "", "", "");
        RouteGraph g = TestGraphs.open(TestGraphs.build(twoRoads("secondary", gravel)));
        assertTrue(names(g, route(g, RouteProfile.of(RouteProfile.Kind.FASTEST), -400, 0, 5400, 0)).contains("Snake"));
        // allowing gravel makes it far less costly (it stays slow)
        RouteProfile rough = new RouteProfile(RouteProfile.Kind.FASTEST, false, false, false);
        int flags = RouteGraph.C_SECONDARY | gravel;
        assertTrue(RouteProfile.of(RouteProfile.Kind.FASTEST).factor(flags) >= 5 * rough.factor(flags));
        assertTrue(rough.speed(flags) < rough.speed(RouteGraph.C_SECONDARY) * 0.6);
    }

    @Test
    public void viaPointsAreVisitedInOrder() throws Exception {
        RouteGraph g = TestGraphs.open(TestGraphs.build(twoRoads("secondary", 0)));
        // west → east end → back to a point on the straight road (between two of the snake's crossings)
        Route r = route(g, RouteProfile.of(RouteProfile.Kind.FASTEST), -400, 0, 5400, 0, 2610, 10);
        assertEquals(2, r.vias.length);
        assertEquals(STRAIGHT_KM, r.vias[0] / 1000, 0.05);
        assertEquals(STRAIGHT_KM + 0.4 + 2.39, r.length / 1000, 0.05);
        double[] end = r.pointAt(r.length);
        assertEquals(TestGraphs.lon(2610), end[1], 1e-6);
    }

    @Test
    public void startAndDestinationOnTheSameRoad() throws Exception {
        RouteGraph g = TestGraphs.open(TestGraphs.build(twoRoads("secondary", 0)));
        Route east = route(g, RouteProfile.of(RouteProfile.Kind.FASTEST), 1200, 5, 3700, 5);
        assertEquals(2500, east.length, 1);
        Route west = route(g, RouteProfile.of(RouteProfile.Kind.FASTEST), 3700, 5, 1200, 5);
        assertEquals(2500, west.length, 1);
    }

    @Test
    public void startHeadingRidesOnRatherThanTurningRound() throws Exception {
        // a 1 km square ring, ridden clockwise from its south side; the destination is 100 m behind
        TestGraphs.Way south = TestGraphs.way(new long[]{1, 2}, 0, 0, 1000, 0).name("South");
        TestGraphs.Way east = TestGraphs.way(new long[]{2, 3}, 1000, 0, 1000, 1000).name("East");
        TestGraphs.Way north = TestGraphs.way(new long[]{3, 4}, 1000, 1000, 0, 1000).name("North");
        TestGraphs.Way west = TestGraphs.way(new long[]{4, 1}, 0, 1000, 0, 0).name("West");
        RouteGraph g = TestGraphs.open(TestGraphs.build(Arrays.asList(south, east, north, west)));
        List<double[]> pts = Arrays.asList(new double[]{TestGraphs.lat(0), TestGraphs.lon(500)},
                new double[]{TestGraphs.lat(0), TestGraphs.lon(400)});
        Router.Request req = new Router.Request(pts, RouteProfile.of(RouteProfile.Kind.FASTEST));
        assertEquals(100, new Router(g).route(req).length, 1);
        req.startHeading = 90;
        Route on = new Router(g).route(req);
        assertEquals("first move is eastwards", 90, g.startBearing(on.states[0]));
        assertEquals("round the ring", 3900, on.length, 5);
    }

    @Test
    public void penalisedEdgesAreLeftForTheAlternative() throws Exception {
        RouteGraph g = TestGraphs.open(TestGraphs.build(twoRoads("secondary", 0)));
        Set<Integer> straightEdges = new HashSet<>();
        for (int e = 0; e < g.edgeCount(); e++) if (g.name(e).equals("Straight")) straightEdges.add(e);
        List<double[]> pts = Arrays.asList(new double[]{TestGraphs.lat(0), TestGraphs.lon(-400)},
                new double[]{TestGraphs.lat(0), TestGraphs.lon(5400)});
        Router.Request req = new Router.Request(pts, RouteProfile.of(RouteProfile.Kind.FASTEST));
        req.penalised = straightEdges;
        req.penalty = 5;
        assertTrue(names(g, new Router(g).route(req)).contains("Snake"));
    }

    @Test
    public void disconnectedRoadsHaveNoRoute() throws Exception {
        List<TestGraphs.Way> ways = twoRoads("secondary", 0);
        ways.add(TestGraphs.way(new long[]{900, 901}, 0, 3000, 1000, 3000).name("Island"));
        RouteGraph g = TestGraphs.open(TestGraphs.build(ways));
        try {
            route(g, RouteProfile.of(RouteProfile.Kind.FASTEST), -400, 0, 500, 3000);
            fail("no road connects the island");
        } catch (Router.NoRouteException expected) {
            // fine
        }
        try {
            route(g, RouteProfile.of(RouteProfile.Kind.FASTEST), -400, 0, 20000, 20000);
            fail("nothing near the destination");
        } catch (Router.NoRouteException expected) {
            assertTrue(expected.getMessage().contains("destination"));
        }
    }

    @Test
    public void routeStatesFollowEachOther() throws Exception {
        File dir = TestGraphs.build(twoRoads("secondary", 0));
        RouteGraph g = TestGraphs.open(dir);
        Route r = route(g, RouteProfile.of(RouteProfile.Kind.EXTRA_CURVY), -400, 0, 5400, 0);
        for (int i = 1; i < r.states.length; i++) {
            assertTrue(g.sameNode(g.to(r.states[i - 1]), g.from(r.states[i])));
            assertEquals(r.stateTo[i - 1], r.stateFrom[i], 1e-6);
        }
        assertEquals(r.length, r.stateTo[r.states.length - 1], 1e-6);
    }
}
