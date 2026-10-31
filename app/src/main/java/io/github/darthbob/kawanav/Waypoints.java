package io.github.darthbob.kawanav;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The planner's points. A to B: the start (null: the rider's position), the via points in riding order
 * and the destination. A round trip: the same start, the place it heads out to (null: any direction)
 * and the corners the last trip went through, which the rider may move. Lives in {@link Hub#planner}, so
 * the planner shows them again when it is opened once more.
 */
public final class Waypoints {

    /** A place on the map with the name the planner shows. */
    public static final class Point {
        public final double lat, lon;
        public String name;
        /** The name is a place's or an address (not only the nearest road's or the coordinates). */
        public boolean named;

        public Point(double lat, double lon, String name) {
            this.lat = lat;
            this.lon = lon;
            this.name = name;
        }

        public double[] latLon() {
            return new double[]{lat, lon};
        }
    }

    public boolean roundTrip;
    public Point start;
    /** Via points in riding order; there are none without a destination. */
    public final List<Point> vias = new ArrayList<>();
    public Point dest;
    public Point towards;
    public final List<Point> corners = new ArrayList<>();
    /** The rider moved or removed corners: the trip goes through them instead of being planned anew. */
    public boolean cornersEdited;
    /** The round trip's draw: "another one" changes it, the other settings keep it. */
    public long seed;

    /** Start, via points and destination for the router; null while the start or the destination is missing. */
    public List<double[]> forRouter(double[] here) {
        double[] s = start != null ? start.latLon() : here;
        if (s == null || dest == null) return null;
        List<double[]> l = new ArrayList<>();
        l.add(s);
        for (Point p : vias) l.add(p.latLon());
        l.add(dest.latLon());
        return l;
    }

    /** The round trip through its corners, back to the start; null without a start. */
    public List<double[]> forLoop(double[] here) {
        double[] s = start != null ? start.latLon() : here;
        if (s == null) return null;
        List<double[]> l = new ArrayList<>();
        l.add(s);
        for (Point p : corners) l.add(p.latLon());
        l.add(s);
        return l;
    }

    /** A new via point where it makes the shortest detour (as the crow flies) between its neighbours. */
    public void insertVia(Point p, double[] here) {
        List<double[]> legs = new ArrayList<>();
        legs.add(start != null ? start.latLon() : here);
        for (Point v : vias) legs.add(v.latLon());
        legs.add(dest != null ? dest.latLon() : null);
        vias.add(bestGap(legs, p.latLon()) - 1, p);
    }

    /**
     * Where {@code p} goes into the ordered points (start first, destination last; an unknown one is
     * null): the index it takes, between 1 and the number of points - 1, where
     * d(before, p) + d(p, after) - d(before, after) is smallest.
     */
    static int bestGap(List<double[]> pts, double[] p) {
        int best = 1;
        double bestCost = Double.MAX_VALUE;
        for (int i = 1; i < pts.size(); i++) {
            double[] a = pts.get(i - 1), b = pts.get(i);
            double cost = (a == null ? 0 : d(a, p)) + (b == null ? 0 : d(p, b)) - (a == null || b == null ? 0 : d(a, b));
            if (cost < bestCost) {
                bestCost = cost;
                best = i;
            }
        }
        return best;
    }

    private static double d(double[] a, double[] b) {
        return Ride.distanceM(a[0], a[1], b[0], b[1]);
    }

    /**
     * Removes the destination: the last via point (if any) takes its place, so via points always lead
     * somewhere.
     */
    public void removeDest() {
        dest = vias.isEmpty() ? null : vias.remove(vias.size() - 1);
    }

    /**
     * Rides it the other way round. With the rider's position as the start, the destination becomes where
     * the rider is now. Returns false when that is not known.
     */
    public boolean reverse(double[] here, String hereName) {
        if (dest == null) return false;
        Point s = start;
        if (s == null) {
            if (here == null) return false;
            s = new Point(here[0], here[1], hereName);
        }
        start = dest;
        dest = s;
        Collections.reverse(vias);
        return true;
    }

    /** Compass bearing from the round trip's start to the place it heads for; NaN for any direction. */
    public double heading(double[] here) {
        double[] s = start != null ? start.latLon() : here;
        if (s == null || towards == null) return Double.NaN;
        double dx = (towards.lon - s[1]) * Math.cos(Math.toRadians(s[0])), dy = towards.lat - s[0];
        double b = Math.toDegrees(Math.atan2(dx, dy));
        return b < 0 ? b + 360 : b;
    }

    /** Forgets the corners: the round trip is planned anew. */
    public void newLoop() {
        corners.clear();
        cornersEdited = false;
    }

    public void clear() {
        start = null;
        vias.clear();
        dest = null;
        towards = null;
        newLoop();
    }
}
