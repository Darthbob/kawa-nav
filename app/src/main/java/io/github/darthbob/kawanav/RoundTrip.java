package io.github.darthbob.kawanav;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Round trips from a start and a length: the corners of a shape (triangle, square, pentagon or a
 * near-circle) are laid out around the start in the chosen direction, each is put on a nearby road
 * (winding ones preferred), and the router joins them, start to start, avoiding roads already ridden
 * on the way. Out-and-back stubs to a corner are cut away, and the shape is scaled until the length
 * comes out within {@link #TOLERANCE} of the one asked for.
 *
 * Plain Java.
 */
public final class RoundTrip {
    private RoundTrip() {}

    public enum Shape { AUTO, TRIANGLE, SQUARE, PENTAGON, CIRCLE }

    public static final class Request {
        public final double lat, lon;
        /** Wanted length in km. */
        public final double lengthKm;
        public final RouteProfile profile;
        /** Compass direction the trip heads out to (its far side), NaN: any. */
        public double heading = Double.NaN;
        public Shape shape = Shape.AUTO;
        /** Another seed, another trip ("Another one"). */
        public long seed;
        public boolean leftHandTraffic;
        public Router.Cancel cancel;

        public Request(double lat, double lon, double lengthKm, RouteProfile profile) {
            this.lat = lat;
            this.lon = lon;
            this.lengthKm = lengthKm;
            this.profile = profile;
        }
    }

    /** Accepted deviation from the wanted length. */
    static final double TOLERANCE = 0.15;
    /** Roads are this much longer than the straight lines between the corners, to begin with. */
    static final double ROAD_FACTOR = 1.35;
    static final int ATTEMPTS = 6;
    /** Edges ridden on one side of the trip cost this much more for the rest of it. */
    static final double REUSE_PENALTY = 4;

    /** Corners besides the start. */
    static int corners(Shape shape, double km) {
        switch (shape) {
            case TRIANGLE: return 2;
            case SQUARE: return 3;
            case PENTAGON: return 4;
            case CIRCLE: return 7;
            default: return km < 50 ? 2 : km < 140 ? 3 : 4;
        }
    }

    public static Route plan(RouteGraph g, Request r) throws Router.NoRouteException, Router.CancelledException {
        Router router = new Router(g);
        Random rnd = new Random(r.seed * 7919 + 17);
        double heading = Double.isNaN(r.heading) ? rnd.nextDouble() * 360 : r.heading;
        int k = corners(r.shape, r.lengthKm);
        // the jitter of each corner, drawn once so that scaling keeps the shape
        double[] jitterAngle = new double[k], jitterRadius = new double[k];
        for (int i = 0; i < k; i++) {
            jitterAngle[i] = (rnd.nextDouble() - 0.5) * 16;
            jitterRadius[i] = 1 + (rnd.nextDouble() - 0.5) * 0.2;
        }
        double target = r.lengthKm * 1000;
        double factor = ROAD_FACTOR;
        Route best = null;
        Router.NoRouteException lastError = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            List<double[]> pts = corners(r.lat, r.lon, target / factor, k, heading, jitterAngle, jitterRadius);
            List<double[]> stops = new ArrayList<>();
            stops.add(new double[]{r.lat, r.lon});
            for (double[] p : pts) {
                double[] s = onRoad(g, router, p, r.profile);
                if (s != null) stops.add(s);
            }
            stops.add(new double[]{r.lat, r.lon});
            if (stops.size() < 3) {
                lastError = new Router.NoRouteException("no roads around the start for a round trip");
                factor *= 1.3; // a smaller shape may reach roads
                continue;
            }
            Route route;
            try {
                route = cutStubs(g, router, stops, r);
            } catch (Router.NoRouteException e) {
                lastError = e;
                factor *= 1.15;
                continue;
            }
            route.roundTrip = true;
            if (best == null || Math.abs(route.length - target) < Math.abs(best.length - target)) best = route;
            double ratio = route.length / target;
            if (Math.abs(ratio - 1) <= TOLERANCE) break;
            factor *= ratio; // too long: shrink the shape, too short: widen it
        }
        if (best == null) throw lastError != null ? lastError : new Router.NoRouteException("no round trip found");
        return best;
    }

    /** The corners of a regular polygon through the start, its far side in the heading (clockwise). */
    static List<double[]> corners(double lat, double lon, double perimeter, int k, double heading,
                                  double[] jitterAngle, double[] jitterRadius) {
        int n = k + 1;
        double side = perimeter / n;
        double radius = side / (2 * Math.sin(Math.PI / n));
        double ky = 111_132.0, kx = 111_320.0 * Math.cos(Math.toRadians(lat));
        double h = Math.toRadians(heading);
        double cx = radius * Math.sin(h), cy = radius * Math.cos(h); // centre, metres from the start
        List<double[]> l = new ArrayList<>();
        for (int j = 1; j <= k; j++) {
            double phi = Math.toRadians(heading + 180 + j * 360.0 / n + jitterAngle[j - 1]);
            double rr = radius * jitterRadius[j - 1];
            double x = cx + rr * Math.sin(phi), y = cy + rr * Math.cos(phi);
            l.add(new double[]{lat + y / ky, lon + x / kx});
        }
        return l;
    }

    /**
     * A point on a road near the corner: a winding road within a few km if there is one, else an
     * ordinary road (no motorway, no residential street), else any road.
     */
    static double[] onRoad(RouteGraph g, Router router, double[] p, RouteProfile profile) {
        RouteGraph.EdgeFilter base = (e, f) -> {
            int c = RouteGraph.roadClass(f);
            return profile.usable(f) && c != RouteGraph.C_MOTORWAY && !RouteGraph.isLink(c) && c != RouteGraph.C_RESIDENTIAL
                    && c != RouteGraph.C_LIVING_STREET && (f & RouteGraph.F_URBAN) == 0 && RouteGraph.surface(f) < 2;
        };
        boolean curvy = profile.kind != RouteProfile.Kind.FASTEST;
        RouteGraph.Snap s = null;
        if (curvy) s = g.nearest(p[0], p[1], 3000, (e, f) -> base.accept(e, f) && RouteGraph.curvy(f) >= 2);
        if (s == null && curvy) s = g.nearest(p[0], p[1], 3000, (e, f) -> base.accept(e, f) && RouteGraph.curvy(f) >= 1);
        if (s == null) s = g.nearest(p[0], p[1], 4000, base);
        if (s == null) {
            RouteGraph.Snap any = router.snap(p[0], p[1], profile, 6000);
            return any == null ? null : new double[]{any.lat, any.lon};
        }
        return new double[]{s.lat, s.lon};
    }

    /**
     * Routes through the stops; where the route rides out to a stop and back on the same road, the stop
     * is moved to where the way out and the way back part (a few times at most).
     */
    static Route cutStubs(RouteGraph g, Router router, List<double[]> stops, Request r)
            throws Router.NoRouteException, Router.CancelledException {
        Route route = null;
        for (int round = 0; round < 3; round++) {
            Router.Request req = new Router.Request(stops, r.profile);
            req.reusePenalty = REUSE_PENALTY;
            req.leftHandTraffic = r.leftHandTraffic;
            req.cancel = r.cancel;
            route = router.route(req);
            boolean moved = false;
            List<double[]> next = new ArrayList<>(stops);
            for (int v = 0; v < route.vias.length - 1; v++) {
                double[] base = stubBase(g, route, route.vias[v]);
                if (base != null) {
                    next.set(v + 1, base);
                    moved = true;
                }
            }
            if (!moved) break;
            stops = next;
        }
        return route;
    }

    /**
     * Where the route comes back along the edges it took to the via point at distance {@code at}: the
     * point where the stub begins, or null when it leaves another way (or the stub is under 150 m).
     */
    static double[] stubBase(RouteGraph g, Route route, double at) {
        int i = route.stateAt(at - 0.5), j = route.stateAt(at + 0.5);
        if (i == j && i + 1 < route.states.length) j = i + 1;
        int back = i, ahead = j;
        while (back >= 0 && ahead < route.states.length
                && RouteGraph.edgeOf(route.states[back]) == RouteGraph.edgeOf(route.states[ahead])
                && route.states[back] != route.states[ahead]) {
            back--;
            ahead++;
        }
        if (back == i) return null;
        double startOfStub = back >= 0 ? route.stateTo[back] : 0;
        if (at - startOfStub < 150) return null;
        return route.pointAt(Math.max(0, startOfStub - 1));
    }
}
