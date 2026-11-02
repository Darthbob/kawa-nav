package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Free ride: which road the rider is on and where it goes on. The roads are drawn in metres around
 * 46 N / 11 E (x east, y north); like in OpenStreetMap, two ways that share a node id are joined
 * there, two ways that merely cross at the same spot are not.
 */
public class RoadNetworkTest {

    private static final double LAT0 = 46.0, LON0 = 11.0;
    private static final double KY = 111_132.0, KX = 111_320.0 * Math.cos(Math.toRadians(LAT0));

    /** A way through the given (x, y) metres, one OSM node per point. */
    static final class Way {
        final long id;
        final long[] nodes;
        final double[] xs, ys;
        String highway = "secondary", name = "", ref = "", oneway = "", junction = "";

        Way(long id, long[] nodes, double[] xs, double[] ys) {
            this.id = id;
            this.nodes = nodes;
            this.xs = xs;
            this.ys = ys;
        }

        Way highway(String h) {
            highway = h;
            return this;
        }

        Way name(String n) {
            name = n;
            return this;
        }

        Way ref(String r) {
            ref = r;
            return this;
        }

        Way oneway(String o) {
            oneway = o;
            return this;
        }

        Way roundabout() {
            junction = "roundabout";
            return this;
        }

        RoadNetwork.RawWay raw() {
            double[] lats = new double[ys.length], lons = new double[xs.length];
            for (int i = 0; i < xs.length; i++) {
                lats[i] = LAT0 + ys[i] / KY;
                lons[i] = LON0 + xs[i] / KX;
            }
            return new RoadNetwork.RawWay(id, nodes, lats, lons, highway, name, ref, oneway, junction);
        }
    }

    private static Way way(long id, long[] nodes, double... xy) {
        double[] xs = new double[xy.length / 2], ys = new double[xy.length / 2];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = xy[2 * i];
            ys[i] = xy[2 * i + 1];
        }
        return new Way(id, nodes, xs, ys);
    }

    private static RoadNetwork net(Way... ways) {
        List<RoadNetwork.RawWay> raw = new ArrayList<>();
        for (Way w : ways) raw.add(w.raw());
        return new RoadNetwork(LAT0, LON0, raw);
    }

    /** Six nodes 1..6 from (0, 0) east to (500, 0). */
    private static Way straightRoad() {
        return way(1, new long[]{1, 2, 3, 4, 5, 6}, 0, 0, 100, 0, 200, 0, 300, 0, 400, 0, 500, 0);
    }

    private static double length(List<double[]> path) {
        double len = 0;
        for (int i = 1; i < path.size(); i++) {
            len += Math.hypot(path.get(i)[0] - path.get(i - 1)[0], path.get(i)[1] - path.get(i - 1)[1]);
        }
        return len;
    }

    private static boolean passesBy(List<double[]> path, double x, double y) {
        for (double[] p : path) if (Math.hypot(p[0] - x, p[1] - y) < 0.5) return true;
        return false;
    }

    private static void assertEndsAt(String message, List<double[]> path, double x, double y) {
        double[] last = path.get(path.size() - 1);
        assertEquals(message + " (x)", x, last[0], 0.5);
        assertEquals(message + " (y)", y, last[1], 0.5);
    }

    // ------------------------------------------------------------------ the network itself

    @Test
    public void sizeAndRoadsFollowTheWaysGiven() {
        Way a = way(10, new long[]{1, 2}, 0, 0, 100, 0).name("A");
        Way b = way(20, new long[]{2, 3}, 100, 0, 200, 0).name("B").ref("SS12");
        Way c = way(30, new long[]{4, 5, 6}, 0, 50, 100, 50, 200, 50).highway("residential");
        RoadNetwork net = net(a, b, c);
        assertEquals(3, net.size());
        assertEquals(3, net.roads.size());
        assertEquals(10, net.roads.get(0).id);
        assertEquals("A", net.roads.get(0).name);
        assertEquals("SS12", net.roads.get(1).ref);
        assertEquals(3, net.roads.get(2).size());
        assertEquals("secondary is class 3", 3, net.roads.get(0).rank);
        assertEquals("residential is class 6", 6, net.roads.get(2).rank);
        assertFalse(net.roads.get(0).roundabout);
        assertEquals("two-way by default", 0, net.roads.get(0).oneway);
    }

    @Test
    public void brokenWaysAreLeftOut() {
        // a single node is no road; coordinates that don't match the node count can't be trusted
        RoadNetwork.RawWay lonely = new RoadNetwork.RawWay(1, new long[]{1}, new double[]{LAT0}, new double[]{LON0},
                "secondary", "", "", "", "");
        RoadNetwork.RawWay torn = new RoadNetwork.RawWay(2, new long[]{1, 2, 3}, new double[]{LAT0, LAT0 + 0.001},
                new double[]{LON0, LON0 + 0.001}, "secondary", "", "", "", "");
        RoadNetwork net = new RoadNetwork(LAT0, LON0, Arrays.asList(lonely, torn, straightRoad().raw()));
        assertEquals(1, net.size());
        assertEquals(1, net.roads.get(0).id);
    }

    @Test
    public void everyHighwayIsKeptNoneIsIgnored() {
        // the network does not filter by highway: a footway counts like a residential road (class 6),
        // so whoever feeds the network must leave out what motorcycles can't ride
        RoadNetwork net = net(straightRoad().highway("footway"), way(2, new long[]{7, 8}, 0, 50, 100, 50).highway(""));
        assertEquals(2, net.size());
        assertEquals(6, net.roads.get(0).rank);
        assertEquals(6, net.roads.get(1).rank);
    }

    @Test
    public void roadClassesFollowTheHighwayTag() {
        assertEquals(0, RoadNetwork.rank("motorway"));
        assertEquals(1, RoadNetwork.rank("trunk"));
        assertEquals(2, RoadNetwork.rank("primary"));
        assertEquals(3, RoadNetwork.rank("secondary"));
        assertEquals(4, RoadNetwork.rank("tertiary"));
        assertEquals(5, RoadNetwork.rank("unclassified"));
        assertEquals(5, RoadNetwork.rank("road"));
        assertEquals(6, RoadNetwork.rank("residential"));
        assertEquals(7, RoadNetwork.rank("living_street"));
        assertEquals("ramps come last", 8, RoadNetwork.rank("primary_link"));
        assertEquals("unknown kinds count like residential", 6, RoadNetwork.rank("footway"));
    }

    @Test
    public void oneWayTagsAndDefaults() {
        RoadNetwork net = net(
                straightRoad().oneway("yes"),
                straightRoad().oneway("-1"),
                straightRoad().oneway("no"),
                straightRoad().highway("motorway"),
                straightRoad().highway("motorway").oneway("no"),
                straightRoad().roundabout());
        assertEquals(1, net.roads.get(0).oneway);
        assertEquals(-1, net.roads.get(1).oneway);
        assertEquals(0, net.roads.get(2).oneway);
        assertEquals("a motorway is one-way unless tagged otherwise", 1, net.roads.get(3).oneway);
        assertEquals(0, net.roads.get(4).oneway);
        assertEquals("a roundabout is one-way in node order", 1, net.roads.get(5).oneway);
        assertTrue(net.roads.get(5).roundabout);
    }

    @Test
    public void projectionPointsEastAndNorthAndRoundTrips() {
        RoadNetwork net = net(straightRoad());
        assertEquals(LAT0, net.lat0, 0);
        assertEquals(LON0, net.lon0, 0);
        double[] origin = net.project(LAT0, LON0);
        assertEquals(0, origin[0], 1e-9);
        assertEquals(0, origin[1], 1e-9);
        double[] north = net.project(LAT0 + 1e-5, LON0);
        assertEquals("1e-5 degrees of latitude is about 1.1 m north", 1.11, north[1], 0.01);
        assertEquals(0, north[0], 1e-9);
        double[] east = net.project(LAT0, LON0 + 1e-5);
        assertTrue("x grows eastwards", east[0] > 0);
        assertEquals("longitude is shrunk by cos(lat) at 46 N", 1.113 * Math.cos(Math.toRadians(46)), east[0], 0.01);
        assertEquals(0, east[1], 1e-9);
        // round trip in both directions within a centimetre
        double[] ll = net.unproject(1234.5, -678.9);
        double[] back = net.project(ll[0], ll[1]);
        assertEquals(1234.5, back[0], 0.01);
        assertEquals(-678.9, back[1], 0.01);
        assertTrue("south-east of the centre", ll[0] < LAT0 && ll[1] > LON0);
        double[] xy = net.project(LAT0 + 0.01, LON0 - 0.02);
        double[] ll2 = net.unproject(xy[0], xy[1]);
        assertEquals(LAT0 + 0.01, ll2[0], 1e-9);
        assertEquals(LON0 - 0.02, ll2[1], 1e-9);
        assertEquals(Math.hypot(1234.5, 678.9), net.distanceFromCenter(ll[0], ll[1]), 0.01);
    }

    @Test
    public void bearingsAndAngleDifferences() {
        assertEquals(0, RoadNetwork.bearing(0, 0, 0, 1), 1e-9);
        assertEquals(90, RoadNetwork.bearing(0, 0, 1, 0), 1e-9);
        assertEquals(180, RoadNetwork.bearing(0, 0, 0, -1), 1e-9);
        assertEquals(270, RoadNetwork.bearing(0, 0, -1, 0), 1e-9);
        assertEquals(45, RoadNetwork.bearing(10, 10, 11, 11), 1e-9);
        assertEquals(20, RoadNetwork.angleDiff(350, 10), 1e-9);
        assertEquals(-20, RoadNetwork.angleDiff(10, 350), 1e-9);
        assertEquals(180, RoadNetwork.angleDiff(0, 180), 1e-9);
        assertEquals(0, RoadNetwork.angleDiff(90, 450), 1e-9);
    }

    // ------------------------------------------------------------------ match

    @Test
    public void matchesTheRoadBesideTheRiderWithItsHeading() {
        RoadNetwork net = net(straightRoad());
        RoadNetwork.Match m = net.match(250, 5, 90, null);
        assertNotNull(m);
        assertEquals(0, m.road);
        assertEquals("segment 200 -> 300", 2, m.seg);
        assertEquals("riding in node order (east)", 1, m.dir);
        assertEquals(5, m.dist, 1e-6);
        assertEquals("snapped onto the road", 250, m.px, 1e-6);
        assertEquals(0, m.py, 1e-6);
        assertFalse(net.onRoundabout(m));
        // a heading a bit off the road is still fine (up to 60 degrees)
        assertNotNull(net.match(250, 5, 40, null));
        assertNotNull(net.match(250, -5, 140, null));
    }

    @Test
    public void ridingTheOtherWayMatchesAgainstNodeOrder() {
        RoadNetwork net = net(straightRoad());
        RoadNetwork.Match m = net.match(250, -5, 270, null);
        assertNotNull(m);
        assertEquals(-1, m.dir);
        assertEquals(2, m.seg);
        assertEquals(250, m.px, 1e-6);
    }

    @Test
    public void noMatchFarFromAnyRoad() {
        RoadNetwork net = net(straightRoad());
        assertNull(net.match(250, 100, 90, null));
        assertNull("41 m is too far", net.match(250, 41, 90, null));
        assertNotNull("39 m is still beside the road", net.match(250, 39, 90, null));
        assertNull("far beyond the end of the road", net.match(600, 0, 90, null));
        assertNull(net.match(-3000, 2000, 90, null));
    }

    @Test
    public void noMatchWhenHeadingAcrossTheRoad() {
        RoadNetwork net = net(straightRoad());
        assertNull("heading north across an east-west road", net.match(250, 5, 0, null));
        assertNull("70 degrees off is not along the road", net.match(250, 5, 20, null));
        assertNull(net.match(250, 5, 180, null));
    }

    @Test
    public void headingPicksTheRoadRunningTheRidersWayAtACrossing() {
        Way eastWest = way(1, new long[]{1, 2, 3}, -200, 0, 0, 0, 200, 0).name("East-West");
        Way northSouth = way(2, new long[]{4, 2, 5}, 0, -200, 0, 0, 0, 200).name("North-South");
        RoadNetwork net = net(eastWest, northSouth);
        // 4 m from the east-west road but only 2 m from the north-south one: the heading decides
        RoadNetwork.Match east = net.match(2, 4, 90, null);
        assertNotNull(east);
        assertEquals("East-West", net.roads.get(east.road).name);
        RoadNetwork.Match north = net.match(2, 4, 0, null);
        assertNotNull(north);
        assertEquals("North-South", net.roads.get(north.road).name);
        assertEquals(1, north.dir);
        RoadNetwork.Match south = net.match(2, 4, 180, null);
        assertNotNull(south);
        assertEquals("North-South", net.roads.get(south.road).name);
        assertEquals(-1, south.dir);
    }

    @Test
    public void staysOnThePreviousRoadBetweenParallelRoads() {
        Way lower = way(1, new long[]{1, 2}, 0, 0, 500, 0).name("Lower");
        Way upper = way(2, new long[]{3, 4}, 0, 10, 500, 10).name("Upper");
        RoadNetwork net = net(lower, upper);
        RoadNetwork.Match fresh = net.match(100, 4, 90, null);
        assertEquals("without history the nearer road wins", "Lower", net.roads.get(fresh.road).name);
        RoadNetwork.Match prev = net.match(100, 7, 90, null);
        assertEquals("Upper", net.roads.get(prev.road).name);
        RoadNetwork.Match kept = net.match(100, 4, 90, prev);
        assertEquals("6 m from the previous road beats 4 m from a new one", "Upper", net.roads.get(kept.road).name);
        RoadNetwork.Match left = net.match(100, 0.5, 90, prev);
        assertEquals("but not when the rider is clearly on the other road", "Lower", net.roads.get(left.road).name);
    }

    @Test
    public void oneWayRoadAgainstTheHeadingIsNotMatched() {
        RoadNetwork net = net(straightRoad().oneway("yes"));
        assertNull("one-way west to east, rider heading west", net.match(250, 5, 270, null));
        RoadNetwork.Match m = net.match(250, 5, 90, null);
        assertNotNull(m);
        assertEquals(1, m.dir);
        RoadNetwork reverse = net(straightRoad().oneway("-1"));
        assertNull(reverse.match(250, 5, 90, null));
        RoadNetwork.Match r = reverse.match(250, 5, 270, null);
        assertNotNull(r);
        assertEquals(-1, r.dir);
        RoadNetwork motorway = net(straightRoad().highway("motorway"));
        assertNull("a motorway is one-way even without the tag", motorway.match(250, 5, 270, null));
        assertNotNull(motorway.match(250, 5, 90, null));
    }

    // ------------------------------------------------------------------ pathAhead on one road

    @Test
    public void pathRunsAheadFromTheRiderToTheEndOfTheRoad() {
        RoadNetwork net = net(straightRoad());
        RoadNetwork.Match m = net.match(250, 5, 90, null);
        List<RoadNetwork.RoundaboutPass> passes = new ArrayList<>();
        List<double[]> path = net.pathAhead(m, 2000, net.approach(m, 90), passes);
        assertEquals("starts at the matched point", 250, path.get(0)[0], 1e-6);
        assertEquals(0, path.get(0)[1], 1e-6);
        for (double[] p : path) assertTrue("nothing behind the rider: x=" + p[0], p[0] >= 250);
        assertEndsAt("stops at the dead end", path, 500, 0);
        assertEquals(250, length(path), 1e-6);
        assertEquals("250, 300, 400, 500", 4, path.size());
        assertTrue("no roundabout on the way", passes.isEmpty());
        // the other way round
        List<double[]> back = net.pathAhead(net.match(250, 5, 270, null), 2000);
        for (double[] p : back) assertTrue(p[0] <= 250);
        assertEndsAt("", back, 0, 0);
        assertEquals(250, length(back), 1e-6);
    }

    @Test
    public void pathStopsAtTheFirstVertexAtOrBeyondTheLookAhead() {
        Way road = way(1, new long[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11},
                0, 0, 100, 0, 200, 0, 300, 0, 400, 0, 500, 0, 600, 0, 700, 0, 800, 0, 900, 0, 1000, 0);
        RoadNetwork net = net(road);
        RoadNetwork.Match m = net.match(250, 0, 90, null);
        // whole vertices are added while the path is shorter than the look-ahead: it ends at the first
        // vertex at or beyond it, so it may be up to one segment longer than asked
        assertEquals(250, length(net.pathAhead(m, 249)), 1e-6);
        List<double[]> path = net.pathAhead(m, 220);
        assertEquals("overshoots 220 m to the vertex at 500", 250, length(path), 1e-6);
        assertEndsAt("", path, 500, 0);
        assertEquals(350, length(net.pathAhead(m, 251)), 1e-6);
        assertEquals(350, length(net.pathAhead(m, 349)), 1e-6);
        assertEquals("the whole road when the look-ahead is longer", 750, length(net.pathAhead(m, 5000)), 1e-6);
    }

    // ------------------------------------------------------------------ pathAhead at junctions

    /** The road the rider comes in on: nodes 1, 2, 3 from (-300, 0) east to the junction at (0, 0). */
    private static Way inRoad() {
        return way(1, new long[]{1, 2, 3}, -300, 0, -100, 0, 0, 0);
    }

    /** A 400 m road out of the junction node 3 with this compass heading, nodes 3, n, n + 1. */
    private static Way leg(long id, long n, double heading) {
        double dx = Math.sin(Math.toRadians(heading)), dy = Math.cos(Math.toRadians(heading));
        return way(id, new long[]{3, n, n + 1}, 0, 0, 200 * dx, 200 * dy, 400 * dx, 400 * dy);
    }

    private static void assertEndsOnLeg(String message, List<double[]> path, double heading) {
        assertEndsAt(message, path, 400 * Math.sin(Math.toRadians(heading)), 400 * Math.cos(Math.toRadians(heading)));
    }

    private static List<double[]> ahead(RoadNetwork net) {
        RoadNetwork.Match m = net.match(-250, 2, 90, null);
        assertNotNull(m);
        assertEquals("the rider starts on the in-road", 1, net.roads.get(m.road).id);
        return net.pathAhead(m, 5000, net.approach(m, 90), new ArrayList<>());
    }

    @Test
    public void followsTheRoadWithTheSameNameAtAJunction() {
        // Via Roma bends 30 degrees left while an unnamed road goes straight on
        List<double[]> path = ahead(net(inRoad().name("Via Roma"), leg(2, 10, 60).name("Via Roma"), leg(3, 20, 90)));
        assertEndsOnLeg("the road with the rider's name, not the straight one", path, 60);
        assertFalse(passesBy(path, 400, 0));
        // same over a differently named road, and the same name straight on is of course followed
        path = ahead(net(inRoad().name("Via Roma"), leg(2, 10, 60).name("Via Roma"), leg(3, 20, 90).name("Via Milano")));
        assertEndsOnLeg("", path, 60);
        path = ahead(net(inRoad().name("Via Roma"), leg(2, 10, 60).name("Via Milano"), leg(3, 20, 90).name("Via Roma")));
        assertEndsOnLeg("", path, 90);
    }

    @Test
    public void followsTheRoadWithTheSameRefAtAJunction() {
        List<double[]> path = ahead(net(inRoad().ref("SS12"), leg(2, 10, 60).ref("SS12"), leg(3, 20, 90).ref("SP5")));
        assertEndsOnLeg("the road with the rider's number", path, 60);
        path = ahead(net(inRoad().ref("SS12"), leg(2, 10, 60).ref("SS12"), leg(3, 20, 90)));
        assertEndsOnLeg("", path, 60);
    }

    @Test
    public void withoutNameOrRefTheStraightestRoadIsFollowed() {
        List<double[]> path = ahead(net(inRoad(), leg(2, 10, 60), leg(3, 20, 90), leg(4, 30, 140)));
        assertEndsOnLeg("straight on", path, 90);
        path = ahead(net(inRoad(), leg(2, 10, 60), leg(3, 20, 140)));
        assertEndsOnLeg("30 degrees left beats 50 degrees right", path, 60);
        // the name only helps when the side road is not too far off: 70 degrees of turn cost more than the name is worth
        path = ahead(net(inRoad().name("Via Roma"), leg(2, 10, 160).name("Via Roma"), leg(3, 20, 90)));
        assertEndsOnLeg("a sharp turn with the same name loses to straight on", path, 90);
    }

    @Test
    public void aBiggerRoadTurningSlightlyBeatsALesserOneStraightOn() {
        List<double[]> path = ahead(net(inRoad(), leg(2, 10, 90).highway("residential"), leg(3, 20, 65)));
        assertEndsOnLeg("the secondary road 25 degrees left, not the residential one", path, 65);
        path = ahead(net(inRoad(), leg(2, 10, 90).highway("residential"), leg(3, 20, 50)));
        assertEndsOnLeg("40 degrees is too much of a turn for that", path, 90);
    }

    @Test
    public void aRoadThatReversesDirectionIsNotTaken() {
        // the only way on turns back 163 degrees: the path ends at the junction
        List<double[]> path = ahead(net(inRoad().name("Via Roma"), leg(2, 10, 253).name("Via Roma")));
        assertEndsAt("stops at the junction", path, 0, 0);
        assertEquals(250, length(path), 1e-6);
        // a tight hairpin of 140 degrees is still a road to follow
        path = ahead(net(inRoad(), leg(2, 10, 230)));
        assertEndsOnLeg("", path, 230);
    }

    @Test
    public void doesNotContinueIntoAOneWayRoadAgainstItsDirection() {
        // straight on is one-way towards the rider (nodes 11 -> 10 -> 3); a side road turns 30 degrees left
        Way against = way(2, new long[]{11, 10, 3}, 400, 0, 200, 0, 0, 0).oneway("yes");
        List<double[]> path = ahead(net(inRoad(), against, leg(3, 20, 60)));
        assertEndsOnLeg("takes the side road", path, 60);
        assertFalse(passesBy(path, 200, 0));
        path = ahead(net(inRoad(), against));
        assertEndsAt("nowhere to go: stops at the junction", path, 0, 0);
        // the same road one-way in the rider's direction is followed
        path = ahead(net(inRoad(), leg(2, 10, 90).oneway("yes"), leg(3, 20, 60)));
        assertEndsOnLeg("", path, 90);
        path = ahead(net(inRoad(), way(2, new long[]{11, 10, 3}, 400, 0, 200, 0, 0, 0).oneway("-1"), leg(3, 20, 60)));
        assertEndsOnLeg("oneway=-1 against node order is the rider's way here", path, 90);
    }

    @Test
    public void roadsThatCrossWithoutASharedNodeAreNotJoined() {
        // a bridge: the other road passes exactly over the end of the in-road but with its own node id
        Way bridge = way(2, new long[]{10, 11, 12}, 0, 0, 200, 0, 400, 0);
        List<double[]> path = ahead(net(inRoad(), bridge));
        assertEndsAt("no junction without a shared node", path, 0, 0);
        assertEquals(250, length(path), 1e-6);
    }

    @Test
    public void followsAcrossSeveralJunctionsUpToTheLookAhead() {
        // Via Roma in three ways, each with an unnamed side road turning off
        Way first = way(1, new long[]{1, 2}, -300, 0, 0, 0).name("Via Roma");
        Way second = way(2, new long[]{2, 3}, 0, 0, 300, 0).name("Via Roma");
        Way third = way(3, new long[]{3, 4}, 300, 0, 600, 0).name("Via Roma");
        Way side1 = way(4, new long[]{2, 5}, 0, 0, 100, 150);
        Way side2 = way(5, new long[]{3, 6}, 300, 0, 400, -150);
        RoadNetwork net = net(first, second, third, side1, side2);
        RoadNetwork.Match m = net.match(-250, 2, 90, null);
        List<double[]> path = net.pathAhead(m, 5000);
        assertEndsAt("all the way to the end of Via Roma", path, 600, 0);
        assertEquals(850, length(path), 1e-6);
        path = net.pathAhead(m, 400);
        assertEndsAt("the look-ahead ends on the second way", path, 300, 0);
    }

    // ------------------------------------------------------------------ roundabouts

    private static final double RING_R = 20;
    private static final long RING_FIRST = 100;

    /** Ring node at this angle (counter-clockwise from east, every 30 degrees). */
    private static long ringNode(int angle) {
        return RING_FIRST + (angle % 360) / 30;
    }

    private static double[] ringPoint(double angle) {
        return new double[]{RING_R * Math.cos(Math.toRadians(angle)), RING_R * Math.sin(Math.toRadians(angle))};
    }

    /** A closed roundabout way of 12 nodes around the origin, driven counter-clockwise (right-hand traffic) or clockwise. */
    private static Way ring(boolean ccw) {
        long[] ids = new long[13];
        double[] xy = new double[26];
        for (int k = 0; k <= 12; k++) {
            int angle = ((ccw ? k : 12 - k) % 12) * 30;
            ids[k] = ringNode(angle);
            double[] p = ringPoint(angle);
            xy[2 * k] = p[0];
            xy[2 * k + 1] = p[1];
        }
        return way(9, ids, xy).roundabout();
    }

    /** Part of the ring, counter-clockwise from one angle to another, as its own way. */
    private static Way arc(long id, int fromAngle, int toAngle) {
        int n = ((toAngle - fromAngle + 360) % 360) / 30 + 1;
        long[] ids = new long[n];
        double[] xy = new double[2 * n];
        for (int k = 0; k < n; k++) {
            int angle = (fromAngle + 30 * k) % 360;
            ids[k] = ringNode(angle);
            double[] p = ringPoint(angle);
            xy[2 * k] = p[0];
            xy[2 * k + 1] = p[1];
        }
        return way(id, ids, xy).roundabout();
    }

    /** The road the rider comes on: from (-300, 0) east to the west node of the ring. */
    private static Way fromWest(String name) {
        return way(1, new long[]{1, 2, ringNode(180)}, -300, 0, -100, 0, -RING_R, 0).name(name);
    }

    private static Way exitSouth(String name) {
        return way(2, new long[]{ringNode(270), 11, 12}, 0, -RING_R, 0, -100, 0, -300).name(name);
    }

    private static Way exitEast(String name) {
        return way(3, new long[]{ringNode(0), 21, 22}, RING_R, 0, 100, 0, 300, 0).name(name);
    }

    private static Way exitNorth(String name) {
        return way(4, new long[]{ringNode(90), 31, 32}, 0, RING_R, 0, 100, 0, 300).name(name);
    }

    /** Leaves the ring at its 30-degree node heading 60 degrees (east-north-east). */
    private static Way exitNorthEast(String name) {
        double[] p = ringPoint(30);
        double dx = 200 * Math.sin(Math.toRadians(60)), dy = 200 * Math.cos(Math.toRadians(60));
        return way(5, new long[]{ringNode(30), 41, 42}, p[0], p[1], p[0] + dx, p[1] + dy, p[0] + 2 * dx, p[1] + 2 * dy)
                .name(name);
    }

    /** The rider 200 m before the ring, heading east. */
    private static RoadNetwork.Match approaching(RoadNetwork net) {
        RoadNetwork.Match m = net.match(-200, 2, 90, null);
        assertNotNull(m);
        assertEquals(1, net.roads.get(m.road).id);
        return m;
    }

    @Test
    public void onRoundaboutTellsTheRingFromTheRoadLeadingIn() {
        RoadNetwork net = net(fromWest("Via Roma"), ring(true), exitSouth("Via Sud"), exitEast("Via Roma"), exitNorth("Via Nord"));
        assertFalse(net.onRoundabout(approaching(net)));
        assertFalse(net.onRoundabout(null));
        // in the south-west of the ring, going round counter-clockwise (heading south-east)
        double[] p = ringPoint(225);
        RoadNetwork.Match inside = net.match(p[0], p[1], 135, null);
        assertNotNull(inside);
        assertTrue(net.onRoundabout(inside));
        assertEquals(9, net.roads.get(inside.road).id);
        assertEquals(7, inside.seg);
        assertTrue("on the ring", inside.dist < 1);
        // the ring is one-way, so against it the near side does not match (here the two-way road
        // leading in, 15 m away, fits better); on a lone 20 m ring the far side, 39 m away and
        // running the rider's way, still matches
        RoadNetwork.Match other = net.match(p[0], p[1], 315, null);
        assertNotNull(other);
        assertEquals(1, net.roads.get(other.road).id);
        RoadNetwork lone = net(ring(true));
        RoadNetwork.Match wrongWay = lone.match(p[0], p[1], 315, null);
        assertNotNull(wrongWay);
        assertEquals("matched across the ring", 39.3, wrongWay.dist, 0.5);
        double[] far = ringPoint(45);
        assertEquals(far[0] * Math.cos(Math.toRadians(15)), wrongWay.px, 0.5);
        assertEquals(far[1] * Math.cos(Math.toRadians(15)), wrongWay.py, 0.5);
    }

    @Test
    public void approachCarriesTheRoadsNameRefAndClass() {
        RoadNetwork net = net(fromWest("Via Roma").ref("SS12").highway("primary"), ring(true), exitEast("Via Roma"));
        RoadNetwork.Approach a = net.approach(approaching(net), 88);
        assertEquals(88, a.heading, 0);
        assertEquals("Via Roma", a.name);
        assertEquals("SS12", a.ref);
        assertEquals(2, a.rank);
    }

    @Test
    public void drivesThroughTheRoundaboutToTheExitThatContinuesTheRoad() {
        RoadNetwork net = net(fromWest("Via Roma"), ring(true), exitSouth("Via Sud"), exitEast("Via Roma"), exitNorth("Via Nord"));
        RoadNetwork.Match m = approaching(net);
        List<RoadNetwork.RoundaboutPass> passes = new ArrayList<>();
        List<double[]> path = net.pathAhead(m, 2000, net.approach(m, 90), passes);
        assertEquals(1, passes.size());
        RoadNetwork.RoundaboutPass pass = passes.get(0);
        assertEquals("180 m from the rider to the ring", 180, pass.entryDist, 1);
        assertEquals("south exit is 1, Via Roma east is 2", 2, pass.exitNumber);
        assertTrue("counter-clockwise ring", pass.ccw);
        // half the ring: six chords of 30 degrees
        double halfRing = 6 * 2 * RING_R * Math.sin(Math.toRadians(15));
        assertEquals("leaves the ring on the far side", 180 + halfRing, pass.exitDist, 1);
        assertTrue("goes round the south side", passesBy(path, 0, -RING_R));
        assertFalse("not the north side", passesBy(path, 0, RING_R));
        assertFalse("does not take the south exit", passesBy(path, 0, -100));
        assertEndsAt("carries on along Via Roma", path, 300, 0);
        assertEquals(180 + halfRing + 280, length(path), 1);
    }

    @Test
    public void theExitWithTheSameNameBeatsStraightAcross() {
        // Via Roma leaves at the third exit (30 degrees left of straight on); straight on is unnamed
        RoadNetwork net = net(fromWest("Via Roma"), ring(true), exitSouth("Via Sud"), exitEast(""), exitNorthEast("Via Roma"));
        RoadNetwork.Match m = approaching(net);
        List<RoadNetwork.RoundaboutPass> passes = new ArrayList<>();
        List<double[]> path = net.pathAhead(m, 2000, net.approach(m, 90), passes);
        assertEquals(1, passes.size());
        assertEquals(3, passes.get(0).exitNumber);
        assertTrue(passes.get(0).ccw);
        double[] end = ringPoint(30);
        assertEndsAt("", path, end[0] + 400 * Math.sin(Math.toRadians(60)), end[1] + 400 * Math.cos(Math.toRadians(60)));
        assertFalse(passesBy(path, 300, 0));
        // the number works likewise
        net = net(fromWest("").ref("SS12"), ring(true), exitSouth("Via Sud"), exitEast("").ref("SP5"), exitNorthEast("").ref("SS12"));
        m = approaching(net);
        passes.clear();
        net.pathAhead(m, 2000, net.approach(m, 90), passes);
        assertEquals(3, passes.get(0).exitNumber);
    }

    @Test
    public void withoutANameTheExitStraightAcrossIsTaken() {
        RoadNetwork net = net(fromWest(""), ring(true), exitSouth("Via Sud"), exitEast("Via Est"), exitNorth("Via Nord"));
        RoadNetwork.Match m = approaching(net);
        List<RoadNetwork.RoundaboutPass> passes = new ArrayList<>();
        List<double[]> path = net.pathAhead(m, 2000, net.approach(m, 90), passes);
        assertEquals(2, passes.get(0).exitNumber);
        assertEndsAt("", path, 300, 0);
        // without the straight exit the closest to straight is taken: 60 degrees left over 90 right
        net = net(fromWest(""), ring(true), exitSouth("Via Sud"), exitNorthEast("Via Est"), exitNorth("Via Nord"));
        m = approaching(net);
        passes.clear();
        path = net.pathAhead(m, 2000, net.approach(m, 90), passes);
        assertEquals("south 1, north-east 2", 2, passes.get(0).exitNumber);
        double[] end = ringPoint(30);
        assertEndsAt("", path, end[0] + 400 * Math.sin(Math.toRadians(60)), end[1] + 400 * Math.cos(Math.toRadians(60)));
    }

    @Test
    public void aClockwiseRingIsReportedAsClockwise() {
        // left-hand traffic: the ring is driven clockwise, so the first exit from the west is the north one
        RoadNetwork net = net(fromWest("Via Roma"), ring(false), exitSouth("Via Sud"), exitEast("Via Roma"), exitNorth("Via Nord"));
        RoadNetwork.Match m = approaching(net);
        List<RoadNetwork.RoundaboutPass> passes = new ArrayList<>();
        List<double[]> path = net.pathAhead(m, 2000, net.approach(m, 90), passes);
        assertEquals(1, passes.size());
        assertFalse("clockwise", passes.get(0).ccw);
        assertEquals("north exit is 1, Via Roma east is 2", 2, passes.get(0).exitNumber);
        assertEquals(180, passes.get(0).entryDist, 1);
        assertTrue("goes round the north side", passesBy(path, 0, RING_R));
        assertFalse(passesBy(path, 0, -RING_R));
        assertEndsAt("", path, 300, 0);
    }

    @Test
    public void insideTheRoundaboutTheRoadTheRiderCameFromDecidesTheExit() {
        RoadNetwork net = net(fromWest("Via Roma"), ring(true), exitSouth("Via Sud"), exitEast("Via Roma"), exitNorth("Via Nord"));
        // the approach was taken before entering, as CurveEngine keeps it; the rider is now in the south-west of the ring
        RoadNetwork.Approach approach = net.approach(approaching(net), 90);
        double[] p = ringPoint(225);
        RoadNetwork.Match inside = net.match(p[0], p[1], 135, null);
        assertTrue(net.onRoundabout(inside));
        List<RoadNetwork.RoundaboutPass> passes = new ArrayList<>();
        List<double[]> path = net.pathAhead(inside, 2000, approach, passes);
        assertEquals(1, passes.size());
        RoadNetwork.RoundaboutPass pass = passes.get(0);
        assertEquals("already inside", 0, pass.entryDist, 0);
        assertEquals("south 1, Via Roma 2, counted from where the rider is", 2, pass.exitNumber);
        assertTrue(pass.ccw);
        assertTrue("a quarter ring plus a bit to the exit", pass.exitDist > 40 && pass.exitDist < 60);
        assertFalse(passesBy(path, 0, RING_R));
        assertEndsAt("", path, 300, 0);
        // without the approach the exit is unknown: no pass is reported
        passes.clear();
        net.pathAhead(inside, 2000, null, passes);
        assertTrue(passes.isEmpty());
    }

    @Test
    public void aOneWayLegIntoTheRoundaboutIsNotCountedAsAnExit() {
        // the south leg only leads in (one-way towards the ring): Via Roma east is the first exit
        Way inOnly = way(2, new long[]{11, ringNode(270)}, 0, -100, 0, -RING_R).oneway("yes");
        RoadNetwork net = net(fromWest("Via Roma"), ring(true), inOnly, exitEast("Via Roma"), exitNorth("Via Nord"));
        RoadNetwork.Match m = approaching(net);
        List<RoadNetwork.RoundaboutPass> passes = new ArrayList<>();
        List<double[]> path = net.pathAhead(m, 2000, net.approach(m, 90), passes);
        assertEquals(1, passes.get(0).exitNumber);
        assertEndsAt("", path, 300, 0);
    }

    @Test
    public void aRingMadeOfSeveralWaysIsDrivenRound() {
        // OSM often splits a roundabout into several ways: the south half and the north half here.
        // Via Roma leaves north (second exit), the east leg only leads in.
        Way south = arc(9, 180, 0);
        Way north = arc(10, 0, 180);
        Way eastIn = way(3, new long[]{21, ringNode(0)}, 100, 0, RING_R, 0).oneway("yes");
        RoadNetwork net = net(fromWest("Via Roma"), south, north, exitSouth("Via Sud"), eastIn, exitNorth("Via Roma"));
        assertEquals(7, net.roads.get(1).size());
        RoadNetwork.Match m = approaching(net);
        List<RoadNetwork.RoundaboutPass> passes = new ArrayList<>();
        List<double[]> path = net.pathAhead(m, 2000, net.approach(m, 90), passes);
        assertEquals(1, passes.size());
        RoadNetwork.RoundaboutPass pass = passes.get(0);
        assertEquals(180, pass.entryDist, 1);
        assertEquals("south 1, Via Roma north 2 (the east leg can't be driven out)", 2, pass.exitNumber);
        assertTrue(pass.ccw);
        double threeQuarters = 9 * 2 * RING_R * Math.sin(Math.toRadians(15));
        assertEquals(180 + threeQuarters, pass.exitDist, 1);
        assertTrue(passesBy(path, RING_R, 0));
        assertEndsAt("", path, 0, 300);
    }

    @Test
    public void aRoundaboutFurtherAlongTheRoadIsReportedAtItsDistance() {
        // Via Roma, then an unnamed stretch that bends slightly right, then the ring: the pass is
        // reported from the rider, not from the start of the last way
        Way first = way(11, new long[]{51, 52}, -700, 100, -400, 100).name("Via Roma");
        Way second = way(1, new long[]{52, 2, ringNode(180)}, -400, 100, -100, 0, -RING_R, 0);
        RoadNetwork net = net(first, second, ring(true), exitSouth("Via Sud"), exitEast("Via Est"), exitNorth("Via Nord"));
        RoadNetwork.Match m = net.match(-600, 102, 90, null);
        assertNotNull(m);
        assertEquals(11, net.roads.get(m.road).id);
        List<RoadNetwork.RoundaboutPass> passes = new ArrayList<>();
        List<double[]> path = net.pathAhead(m, 3000, net.approach(m, 90), passes);
        assertEquals(1, passes.size());
        double expected = 200 + Math.hypot(300, 100) + 80;
        assertEquals(expected, passes.get(0).entryDist, 1);
        assertEquals("the unnamed approach continues straight across", 2, passes.get(0).exitNumber);
        assertEndsAt("", path, 300, 0);
    }
}
