package io.github.darthbob.kawanav;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Finds routes on a {@link RouteGraph} for a {@link RouteProfile}: start, via points, destination.
 *
 * Each leg is an A* search over states (an edge in one direction), so turn costs, oneways and U-turns
 * are exact. The estimate is the straight distance at the fastest speed and the profile's lowest factor
 * (never more than the true cost); {@link Request#epsilon} above 1 trades a little optimality for
 * speed on long legs. Search state is kept in hash maps sized by what the search touches, not by the
 * graph, so a country-sized graph needs no country-sized arrays.
 *
 * Plain Java; one Router can serve one search at a time.
 */
public final class Router {

    public static final class NoRouteException extends Exception {
        NoRouteException(String m) {
            super(m);
        }
    }

    public static final class CancelledException extends Exception {
        CancelledException() {
            super("cancelled");
        }
    }

    public interface Cancel {
        boolean cancelled();
    }

    public static final class Request {
        /** Start, via points, destination: {lat, lon} each. */
        public final List<double[]> points;
        public final RouteProfile profile;
        /** Compass heading the rider is moving in at the start (NaN: none), e.g. when rerouting. */
        public double startHeading = Double.NaN;
        /** Edges whose cost is multiplied by {@link #penalty}. */
        public Set<Integer> penalised = Collections.emptySet();
        public double penalty = 1;
        /** Above 1: the edges of the legs before cost this much more (a round trip should not ride a road twice). */
        public double reusePenalty = 1;
        public boolean leftHandTraffic;
        /** Above 1: faster, slightly less optimal search; 0: chosen by the leg's length. */
        public double epsilon;
        /** How far from a road a point may be (metres). */
        public double snapDistance = 2000;
        public Cancel cancel;

        public Request(List<double[]> points, RouteProfile profile) {
            this.points = points;
            this.profile = profile;
        }
    }

    /** Cost (seconds of riding) of starting against the rider's heading. */
    static final double TURN_ROUND_COST = 300;
    /** ... and of turning round at a via point. */
    static final double VIA_TURN_ROUND_COST = 60;

    /** States a search may touch before it gives up: about 200 MB of search state at most. */
    static final int MAX_STATES = 3_000_000;

    private final RouteGraph g;

    public Router(RouteGraph g) {
        this.g = g;
    }

    // ------------------------------------------------------------------ snapping

    /**
     * The point put on the road it most likely means: the nearest usable road, but not a motorway or
     * a link road when an ordinary road is nearly as close (a point under a motorway bridge).
     */
    public RouteGraph.Snap snap(double lat, double lon, RouteProfile p, double maxDist) {
        RouteGraph.Snap any = g.nearest(lat, lon, maxDist, (e, f) -> p.usable(f));
        if (any == null) return null;
        int cls = RouteGraph.roadClass(g.flags(any.edge));
        if (cls != RouteGraph.C_MOTORWAY && !RouteGraph.isLink(cls)) return any;
        RouteGraph.Snap plain = g.nearest(lat, lon, Math.min(maxDist, any.distance + 40), (e, f) -> {
            int c = RouteGraph.roadClass(f);
            return p.usable(f) && c != RouteGraph.C_MOTORWAY && !RouteGraph.isLink(c);
        });
        return plain != null ? plain : any;
    }

    // ------------------------------------------------------------------ route

    public Route route(Request r) throws NoRouteException, CancelledException {
        if (r.points.size() < 2) throw new IllegalArgumentException("a route needs a start and a destination");
        List<RouteGraph.Snap> snaps = new ArrayList<>();
        for (int i = 0; i < r.points.size(); i++) {
            double[] p = r.points.get(i);
            RouteGraph.Snap s = snap(p[0], p[1], r.profile, r.snapDistance);
            if (s == null) {
                throw new NoRouteException(i == 0 ? "no road near the start" : i == r.points.size() - 1
                        ? "no road near the destination" : "no road near via point " + i);
            }
            snaps.add(s);
        }
        List<Leg> legs = new ArrayList<>();
        Set<Integer> penalised = r.penalised;
        double penalty = r.penalty;
        for (int i = 0; i + 1 < snaps.size(); i++) {
            // at a via point, carry on in the direction the rider arrives in rather than turning round
            double heading = i == 0 ? r.startHeading : g.endBearing(last(legs).states[last(legs).states.length - 1]);
            Leg leg = leg(snaps.get(i), snaps.get(i + 1), r, heading, i == 0 ? TURN_ROUND_COST : VIA_TURN_ROUND_COST,
                    penalised, penalty);
            legs.add(leg);
            if (r.reusePenalty > 1) {
                if (penalised == r.penalised) penalised = new java.util.HashSet<>(r.penalised);
                for (int s : leg.states) penalised.add(RouteGraph.edgeOf(s));
                penalty = Math.max(penalty, r.reusePenalty);
            }
        }
        return assemble(legs, r);
    }

    private static Leg last(List<Leg> legs) {
        return legs.get(legs.size() - 1);
    }

    /** States of one leg, with where the ride starts in the first and ends in the last (metres into each). */
    static final class Leg {
        final int[] states;
        final double startAlong, endAlong;

        Leg(int[] states, double startAlong, double endAlong) {
            this.states = states;
            this.startAlong = startAlong;
            this.endAlong = endAlong;
        }
    }

    private Set<Integer> penalised;
    private double penalty;

    private double edgeCost(Request r, int edge, int flags, double metres) {
        double c = r.profile.cost(flags, metres);
        return penalty > 1 && penalised.contains(edge) ? c * penalty : c;
    }

    Leg leg(RouteGraph.Snap from, RouteGraph.Snap to, Request r, double heading, double turnRoundCost,
            Set<Integer> penalised, double penalty) throws NoRouteException, CancelledException {
        this.penalised = penalised;
        this.penalty = penalty;
        RouteProfile p = r.profile;
        double straight = Ride.distanceM(from.lat, from.lon, to.lat, to.lon);
        double eps = r.epsilon > 0 ? r.epsilon : straight > 150_000 ? 1.6 : straight > 40_000 ? 1.3 : 1.0;
        double hFactor = p.minFactor() / RouteProfile.MAX_SPEED * eps;

        Labels labels = new Labels();
        Heap heap = new Heap();
        double best = Double.POSITIVE_INFINITY;
        int bestSlot = -1, bestFinal = -1;

        // the start lies inside an edge: ride to either end of it
        int fe = from.edge;
        int ff = g.flags(fe);
        double flen = g.length(fe);
        for (int dir = 0; dir < 2; dir++) {
            int s = fe * 2 + dir;
            if (!g.allowed(s)) continue;
            double part = dir == 0 ? flen - from.along : from.along;
            // turning round on the spot: rather ride on a few km and come back on other roads
            double round = !Double.isNaN(heading)
                    && Math.abs(RoadNetwork.angleDiff(heading, g.startBearing(s))) > 100 ? turnRoundCost : 0;
            double c = edgeCost(r, fe, ff, part) + round;
            if (to.edge == fe && (dir == 0 ? to.along >= from.along : to.along <= from.along)) {
                double direct = edgeCost(r, fe, ff, Math.abs(to.along - from.along)) + round;
                if (direct < best) {
                    best = direct;
                    bestSlot = -1;
                    bestFinal = s;
                }
            }
            int slot = labels.slot(s);
            labels.set(slot, c, -1);
            heap.push(c + hFactor * dist(g.to(s), to), slot);
        }

        int[] out = new int[g.degreeBound()];
        int pops = 0;
        while (heap.size > 0) {
            double key = heap.peekKey();
            int slot = heap.pop();
            if (key >= best) break;
            int s = labels.state[slot];
            double gc = labels.cost[slot];
            if (key > gc + hFactor * dist(g.to(s), to) + 1e-6) continue; // stale entry
            if (++pops % 4096 == 0 && r.cancel != null && r.cancel.cancelled()) throw new CancelledException();
            if (labels.size > MAX_STATES) throw new NoRouteException("route too long for this phone's search");

            int u = g.to(s);
            int inBearing = g.endBearing(s);
            int n = g.out(u, out);
            int usable = 0;
            for (int i = 0; i < n; i++) {
                int x = out[i];
                if (RouteGraph.edgeOf(x) != RouteGraph.edgeOf(s) && g.allowed(x) && p.usable(g.flags(RouteGraph.edgeOf(x)))) usable++;
            }
            for (int i = 0; i < n; i++) {
                int x = out[i];
                int xe = RouteGraph.edgeOf(x);
                boolean uTurn = xe == RouteGraph.edgeOf(s);
                if (uTurn && usable > 0) continue;  // only at a dead end
                if (!g.allowed(x)) continue;
                int xf = g.flags(xe);
                if (!p.usable(xf) && xe != to.edge) continue;
                double angle = uTurn ? 180 : RoadNetwork.angleDiff(inBearing, g.startBearing(x));
                double turn = p.turnCost(angle, r.leftHandTraffic);
                double xlen = g.length(xe);
                if (xe == to.edge) {
                    double part = RouteGraph.reversed(x) ? xlen - to.along : to.along;
                    double c = gc + turn + edgeCost(r, xe, xf, part);
                    if (c < best) {
                        best = c;
                        bestSlot = slot;
                        bestFinal = x;
                    }
                }
                double c = gc + turn + edgeCost(r, xe, xf, xlen);
                int xs = labels.slot(x);
                if (c < labels.cost[xs] - 1e-9) {
                    labels.set(xs, c, slot);
                    heap.push(c + hFactor * dist(g.to(x), to), xs);
                }
            }
        }
        if (bestFinal < 0) throw new NoRouteException("no road connects these points");

        List<Integer> chain = new ArrayList<>();
        chain.add(bestFinal);
        for (int sl = bestSlot; sl >= 0; sl = labels.parent[sl]) chain.add(labels.state[sl]);
        Collections.reverse(chain);
        int[] states = new int[chain.size()];
        for (int i = 0; i < states.length; i++) states[i] = chain.get(i);
        int first = states[0], last = states[states.length - 1];
        double startAlong = RouteGraph.reversed(first) ? g.length(RouteGraph.edgeOf(first)) - from.along : from.along;
        double endAlong = RouteGraph.reversed(last) ? g.length(RouteGraph.edgeOf(last)) - to.along : to.along;
        return new Leg(states, startAlong, endAlong);
    }

    private double dist(int node, RouteGraph.Snap to) {
        return Ride.distanceM(g.lat(node), g.lon(node), to.lat, to.lon);
    }

    // ------------------------------------------------------------------ assembling the route

    private Route assemble(List<Leg> legs, Request r) {
        DoubleList lat = new DoubleList(), lon = new DoubleList(), dist = new DoubleList();
        List<Integer> states = new ArrayList<>();
        DoubleList from = new DoubleList(), to = new DoubleList();
        double[] vias = new double[legs.size()];
        double[] km = new double[4];
        double time = 0;
        double total = 0;
        int prevState = -1;
        for (int li = 0; li < legs.size(); li++) {
            Leg leg = legs.get(li);
            for (int i = 0; i < leg.states.length; i++) {
                int s = leg.states[i];
                int e = RouteGraph.edgeOf(s);
                double len = g.length(e);
                double a = i == 0 ? leg.startAlong : 0;
                double b = i == leg.states.length - 1 ? leg.endAlong : len;
                if (b < a) b = a;
                int f = g.flags(e);
                if (prevState >= 0) {
                    double angle = RouteGraph.edgeOf(prevState) == e ? 180
                            : RoadNetwork.angleDiff(g.endBearing(prevState), g.startBearing(s));
                    time += RouteProfile.turnTime(angle);
                }
                prevState = s;
                states.add(s);
                from.add(total);
                appendPart(e, RouteGraph.reversed(s), a, b, total, lat, lon, dist);
                total += b - a;
                to.add(total);
                time += (b - a) / r.profile.speed(f);
                km[RouteGraph.curvy(f)] += (b - a) / 1000;
            }
            vias[li] = total;
        }
        int[] st = new int[states.size()];
        for (int i = 0; i < st.length; i++) st[i] = states.get(i);
        return new Route(lat.toArray(), lon.toArray(), dist.toArray(), st, from.toArray(), to.toArray(), vias, time,
                km, r.profile, new ArrayList<>(r.points));
    }

    /** Adds the points of the state between a and b metres into it (driving direction). */
    private void appendPart(int edge, boolean reversed, double a, double b, double base,
                            DoubleList lat, DoubleList lon, DoubleList dist) {
        int[] p = g.points(edge, reversed);
        int n = p.length / 2;
        double[] cum = new double[n];
        for (int i = 1; i < n; i++) {
            cum[i] = cum[i - 1] + Ride.distanceM(p[2 * i - 2] / 1e7, p[2 * i - 1] / 1e7, p[2 * i] / 1e7, p[2 * i + 1] / 1e7);
        }
        double scale = cum[n - 1] > 0 ? g.length(edge) / cum[n - 1] : 1;
        for (int i = 0; i < n; i++) cum[i] *= scale;
        addPoint(interpolate(p, cum, a), base - a, lat, lon, dist);
        for (int i = 0; i < n; i++) {
            if (cum[i] > a && cum[i] < b) addPoint(new double[]{p[2 * i] / 1e7, p[2 * i + 1] / 1e7, cum[i]}, base - a, lat, lon, dist);
        }
        addPoint(interpolate(p, cum, b), base - a, lat, lon, dist);
    }

    /** {lat, lon, along} at {@code d} metres into the polyline. */
    private static double[] interpolate(int[] p, double[] cum, double d) {
        int n = cum.length;
        for (int i = 0; i + 1 < n; i++) {
            if (d <= cum[i + 1] || i + 2 == n) {
                double seg = cum[i + 1] - cum[i];
                double t = seg <= 0 ? 0 : Math.max(0, Math.min(1, (d - cum[i]) / seg));
                return new double[]{(p[2 * i] + (p[2 * i + 2] - p[2 * i]) * t) / 1e7,
                        (p[2 * i + 1] + (p[2 * i + 3] - p[2 * i + 1]) * t) / 1e7, d};
            }
        }
        return new double[]{p[0] / 1e7, p[1] / 1e7, 0};
    }

    /** Appends {lat, lon, along} at distance offset + along, skipping a repeat of the last point. */
    private static void addPoint(double[] q, double offset, DoubleList lat, DoubleList lon, DoubleList dist) {
        double d = offset + q[2];
        if (lat.size > 0 && Math.abs(lat.last() - q[0]) < 1e-9 && Math.abs(lon.last() - q[1]) < 1e-9) return;
        if (dist.size > 0 && d < dist.last()) d = dist.last();
        lat.add(q[0]);
        lon.add(q[1]);
        dist.add(d);
    }

    // ------------------------------------------------------------------ search structures

    /** Labels of the states a search touched: open addressing from state to slot. */
    private static final class Labels {
        int[] keys = new int[1 << 14];
        int[] slots = new int[1 << 14];
        int[] state = new int[1 << 12];
        double[] cost = new double[1 << 12];
        int[] parent = new int[1 << 12];
        int size;

        Labels() {
            Arrays.fill(keys, -1);
        }

        /** The state's slot, a new one (cost infinite) when it was not touched yet. */
        static int hash(int s, int mask) {
            int m = s * 0x9E3779B1;
            return (m ^ (m >>> 15)) & mask;
        }

        int slot(int s) {
            int mask = keys.length - 1;
            int h = hash(s, mask);
            while (true) {
                int k = keys[h];
                if (k == s) return slots[h];
                if (k == -1) break;
                h = (h + 1) & mask;
            }
            if (size == state.length) {
                state = Arrays.copyOf(state, size * 2);
                cost = Arrays.copyOf(cost, size * 2);
                parent = Arrays.copyOf(parent, size * 2);
            }
            int slot = size++;
            state[slot] = s;
            cost[slot] = Double.POSITIVE_INFINITY;
            parent[slot] = -1;
            keys[h] = s;
            slots[h] = slot;
            if (size * 2 > keys.length) grow();
            return slot;
        }

        void set(int slot, double c, int par) {
            cost[slot] = c;
            parent[slot] = par;
        }

        private void grow() {
            int[] ok = keys, os = slots;
            keys = new int[ok.length * 2];
            slots = new int[ok.length * 2];
            Arrays.fill(keys, -1);
            int mask = keys.length - 1;
            for (int i = 0; i < ok.length; i++) {
                if (ok[i] == -1) continue;
                int h = hash(ok[i], mask);
                while (keys[h] != -1) h = (h + 1) & mask;
                keys[h] = ok[i];
                slots[h] = os[i];
            }
        }
    }

    /** Binary min-heap of (key, slot); outdated entries are skipped when they come up. */
    private static final class Heap {
        double[] key = new double[1 << 12];
        int[] val = new int[1 << 12];
        int size;

        void push(double k, int v) {
            if (size == key.length) {
                key = Arrays.copyOf(key, size * 2);
                val = Arrays.copyOf(val, size * 2);
            }
            int i = size++;
            while (i > 0) {
                int up = (i - 1) >>> 1;
                if (key[up] <= k) break;
                key[i] = key[up];
                val[i] = val[up];
                i = up;
            }
            key[i] = k;
            val[i] = v;
        }

        double peekKey() {
            return key[0];
        }

        int pop() {
            int top = val[0];
            size--;
            if (size > 0) {
                double k = key[size];
                int v = val[size];
                int i = 0;
                while (true) {
                    int c = 2 * i + 1;
                    if (c >= size) break;
                    if (c + 1 < size && key[c + 1] < key[c]) c++;
                    if (key[c] >= k) break;
                    key[i] = key[c];
                    val[i] = val[c];
                    i = c;
                }
                key[i] = k;
                val[i] = v;
            }
            return top;
        }
    }

    static final class DoubleList {
        double[] a = new double[256];
        int size;

        void add(double v) {
            if (size == a.length) a = Arrays.copyOf(a, size * 2);
            a[size++] = v;
        }

        double last() {
            return a[size - 1];
        }

        double[] toArray() {
            return Arrays.copyOf(a, size);
        }
    }
}
