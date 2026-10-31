package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Riding a route with simulated fixes: what is shown and said, leaving the route, arriving. */
public class NavigatorTest {

    private static RouteGraph crossing() throws Exception {
        return TestGraphs.open(TestGraphs.build(Arrays.asList(
                TestGraphs.way(new long[]{1, 2, 3}, 0, 0, 1000, 0, 2000, 0).name("Via Roma"),
                TestGraphs.way(new long[]{10, 2}, 1000, -1000, 1000, 0).name("Via Sud"),
                TestGraphs.way(new long[]{2, 11}, 1000, 0, 1000, 1000).name("Via Nord"))));
    }

    private static Route route(RouteGraph g, double... xy) throws Exception {
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) pts.add(new double[]{TestGraphs.lat(xy[i + 1]), TestGraphs.lon(xy[i])});
        return new Router(g).route(new Router.Request(pts, RouteProfile.of(RouteProfile.Kind.FASTEST)));
    }

    /** Rides the route at the speed, a fix a second; returns what was said, in order. */
    private static List<String> ride(Navigator nav, Route r, double speed, List<Navigator.Update> updates) {
        List<String> said = new ArrayList<>();
        for (double d = 0; d <= r.length + speed; d += speed) {
            double[] p = r.pointAt(Math.min(d, r.length));
            double[] q = r.pointAt(Math.min(d + 5, r.length));
            double heading = RoadNetwork.bearing(TestGraphs.x(p[1]), TestGraphs.y(p[0]), TestGraphs.x(q[1]), TestGraphs.y(q[0]));
            Navigator.Update u = nav.onFix(p[0], p[1], d + 5 > r.length ? Double.NaN : heading, speed);
            if (updates != null) updates.add(u);
            if (u.say != null) said.add(u.say);
            if (u.state == Navigator.State.ARRIVED) break;
        }
        return said;
    }

    @Test
    public void saysTheTurnEarlyAndAtIt() throws Exception {
        RouteGraph g = crossing();
        Route r = route(g, 100, 0, 1000, 900);
        Navigator nav = new Navigator(g, r, NavText.of("en"), false, false);
        List<Navigator.Update> updates = new ArrayList<>();
        List<String> said = ride(nav, r, 15, updates);
        assertEquals(Arrays.asList("Follow Via Roma", "In 300 metres, turn left onto Via Nord", "Turn left onto Via Nord",
                "You have arrived"), said);
        // the dash: left turn with a shrinking distance, then the flag
        Navigator.Update first = updates.get(0);
        assertEquals(1, first.turn());
        assertEquals(900, first.toNext, 20);
        for (int i = 1; i < updates.size() && updates.get(i).next != null && updates.get(i).next.turn == 1; i++) {
            assertTrue(updates.get(i).toNext <= updates.get(i - 1).toNext + 1e-6);
        }
        Navigator.Update last = updates.get(updates.size() - 1);
        assertEquals(Navigator.State.ARRIVED, last.state);
        assertEquals(43, last.turn());
    }

    @Test
    public void fastRoadsAreAnnouncedFromFarToo() throws Exception {
        RouteGraph g = TestGraphs.open(TestGraphs.build(Arrays.asList(
                TestGraphs.way(new long[]{1, 2, 3}, 0, 0, 5000, 0, 9000, 0).name("Statale"),
                TestGraphs.way(new long[]{2, 11}, 5000, 0, 5000, 2000).name("Via Monte"))));
        Route r = route(g, 100, 0, 5000, 1500);
        List<String> said = ride(new Navigator(g, r, NavText.of("it"), false, false), r, 27, null);
        assertEquals("Segui Statale", said.get(0));
        assertTrue(said.get(1), said.get(1).startsWith("Tra 2 chilometri, svolta a sinistra in Via Monte")
                || said.get(1).startsWith("Tra 1,9 chilometri, svolta a sinistra in Via Monte"));
        assertTrue(said.get(2), said.get(2).startsWith("Tra 500 metri"));
        assertEquals("Svolta a sinistra in Via Monte", said.get(3));
    }

    @Test
    public void leavingTheRouteAsksForANewOne() throws Exception {
        RouteGraph g = crossing();
        Route r = route(g, 100, 0, 1000, 900);
        Navigator nav = new Navigator(g, r, NavText.of("en"), false, false);
        nav.onFix(TestGraphs.lat(0), TestGraphs.lon(150), 90, 10);
        nav.onFix(TestGraphs.lat(0), TestGraphs.lon(400), 90, 10);
        String said = null;
        Navigator.Update u = null;
        for (int i = 0; i < 3; i++) {
            u = nav.onFix(TestGraphs.lat(-120), TestGraphs.lon(450 + 10 * i), 90, 10);
            if (u.say != null) said = u.say;
        }
        assertEquals(Navigator.State.OFF_ROUTE, u.state);
        assertEquals("Recalculating the route", said);
        assertNull("said once", nav.onFix(TestGraphs.lat(-130), TestGraphs.lon(500), 90, 10).say);
        List<double[]> targets = nav.targets();
        assertEquals(1, targets.size());
        assertEquals(TestGraphs.lon(1000), targets.get(0)[1], 1e-6);

        // back on a new route: navigation goes on without "start" again
        Route again = route(g, 500, 0, 1000, 900);
        nav.replace(again);
        Navigator.Update back = nav.onFix(TestGraphs.lat(0), TestGraphs.lon(520), 90, 10);
        assertEquals(Navigator.State.NAVIGATING, back.state);
        assertTrue(back.say == null || !back.say.startsWith("Follow"));
        assertEquals(480, back.toNext, 25);
    }

    @Test
    public void pathAheadFollowsTheRoute() throws Exception {
        RouteGraph g = crossing();
        Route r = route(g, 100, 0, 1000, 900);
        Navigator nav = new Navigator(g, r, NavText.of("en"), false, false);
        nav.onFix(TestGraphs.lat(0), TestGraphs.lon(800), 90, 10);
        List<double[]> ahead = nav.pathAhead(400);
        double[] end = ahead.get(ahead.size() - 1);
        assertEquals("200 m east, then 200 m north", 1000, TestGraphs.x(end[1]), 2);
        assertEquals(200, TestGraphs.y(end[0]), 2);
    }

    @Test
    public void viaPointsAreSaidWhenPassed() throws Exception {
        RouteGraph g = crossing();
        Route r = route(g, 100, 0, 600, 0, 1000, 900);
        List<String> said = ride(new Navigator(g, r, NavText.of("de"), false, false), r, 12, null);
        assertTrue(said.toString(), said.contains("Zwischenziel erreicht: 1"));
        assertEquals("Sie haben Ihr Ziel erreicht", said.get(said.size() - 1));
    }
}
