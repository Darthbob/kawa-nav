package io.github.darthbob.kawanav;

import java.util.List;

/**
 * A computed route: the line to ride, the edges it uses and what it is like. Distances are metres from
 * the start along the route. Plain Java.
 */
public final class Route {

    /** The line to ride, from start to destination. */
    public final double[] lat, lon;
    /** Distance from the start at each point. */
    public final double[] dist;
    /** Graph states ridden ({@link RouteGraph}: edge * 2 + direction), in order. */
    public final int[] states;
    /** Where each state's part of the route starts and ends (distance from the start). */
    public final double[] stateFrom, stateTo;
    /** Distance from the start of each via point and, last, of the destination. */
    public final double[] vias;
    public final double length;
    /** Riding time, seconds. */
    public final double time;
    /** Kilometres per curviness level: straight, sweeping, winding, very winding. */
    public final double[] kmByLevel;
    public final RouteProfile profile;
    /** The points asked for (start, via points, destination) as lat, lon. */
    public final List<double[]> asked;
    /** A generated round trip: its via points are the planner's, not the rider's (they are not announced). */
    public boolean roundTrip;

    Route(double[] lat, double[] lon, double[] dist, int[] states, double[] stateFrom, double[] stateTo,
          double[] vias, double time, double[] kmByLevel, RouteProfile profile, List<double[]> asked) {
        this.lat = lat;
        this.lon = lon;
        this.dist = dist;
        this.states = states;
        this.stateFrom = stateFrom;
        this.stateTo = stateTo;
        this.vias = vias;
        this.length = dist.length == 0 ? 0 : dist[dist.length - 1];
        this.time = time;
        this.kmByLevel = kmByLevel;
        this.profile = profile;
        this.asked = asked;
    }

    /** Share of the route on winding and very winding roads, 0..1. */
    public double windingShare() {
        double total = 0;
        for (double k : kmByLevel) total += k;
        return total <= 0 ? 0 : (kmByLevel[2] + kmByLevel[3]) / total;
    }

    /** Index of the point at or before the distance. */
    public int indexAt(double d) {
        int lo = 0, hi = dist.length - 1;
        if (d <= 0) return 0;
        if (d >= dist[hi]) return hi;
        while (lo < hi - 1) {
            int mid = (lo + hi) >>> 1;
            if (dist[mid] <= d) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    /** {lat, lon} at the distance from the start. */
    public double[] pointAt(double d) {
        int i = indexAt(d);
        if (i >= dist.length - 1) return new double[]{lat[i], lon[i]};
        double seg = dist[i + 1] - dist[i];
        double t = seg <= 0 ? 0 : (d - dist[i]) / seg;
        return new double[]{lat[i] + (lat[i + 1] - lat[i]) * t, lon[i] + (lon[i + 1] - lon[i]) * t};
    }

    /** Index in {@link #states} of the state ridden at the distance. */
    public int stateAt(double d) {
        int lo = 0, hi = states.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (stateFrom[mid] <= d) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }
}
