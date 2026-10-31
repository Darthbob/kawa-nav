package io.github.darthbob.kawanav;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Follows the rider along a {@link Route} from GPS fixes: how far along the route they are, the next
 * maneuver and its distance (what the dash shows), what to say and when, and whether they left the
 * route (then the caller computes a new one from {@link #targets} and hands it to {@link #replace}).
 *
 * The position is searched only a little behind and ahead of the last one, so a route that passes the
 * same place twice (a round trip) does not jump. Plain Java; one thread.
 */
public final class Navigator {

    public enum State { NAVIGATING, OFF_ROUTE, ARRIVED }

    /** Farther than this from the route (metres) a fix does not count as on it. */
    static final double ON_ROUTE_M = 35;
    /** This many fixes in a row off the route, and it is left. */
    static final int OFF_ROUTE_FIXES = 3;
    static final double ARRIVED_M = 30;

    /** What the navigation looks like after a fix. */
    public static final class Update {
        public final State state;
        /** The maneuver to do next (the destination at the end), or null when arrived. */
        public final Instructions.Maneuver next;
        /** Metres to it. */
        public final double toNext;
        public final double remaining;
        /** Seconds still to ride, by the route's own speeds. */
        public final double remainingTime;
        /** What to say now, or null. */
        public final String say;

        Update(State state, Instructions.Maneuver next, double toNext, double remaining, double remainingTime, String say) {
            this.state = state;
            this.next = next;
            this.toNext = toNext;
            this.remaining = remaining;
            this.remainingTime = remainingTime;
            this.say = say;
        }

        /** The dash's turn id. */
        public int turn() {
            return state == State.ARRIVED ? 43 : next == null ? KawaProtocol.TURN_NONE : next.turn;
        }
    }

    private final RouteGraph graph;
    private final NavText text;
    private final boolean leftHandTraffic;
    private final boolean imperial;
    private Route route;
    private List<Instructions.Maneuver> maneuvers;
    private double progress;
    private boolean matchedOnce;
    private int offCount;
    private State state = State.NAVIGATING;
    /** Announcements made: maneuver index * 4 + stage. */
    private final Set<Integer> said = new HashSet<>();
    private int viasPassed;

    public Navigator(RouteGraph graph, Route route, NavText text, boolean leftHandTraffic, boolean imperial) {
        this.graph = graph;
        this.text = text;
        this.leftHandTraffic = leftHandTraffic;
        this.imperial = imperial;
        set(route);
    }

    private void set(Route r) {
        route = r;
        maneuvers = Instructions.of(graph, r, leftHandTraffic);
        progress = 0;
        matchedOnce = false;
        offCount = 0;
        said.clear();
        viasPassed = 0;
        state = State.NAVIGATING;
    }

    public Route route() {
        return route;
    }

    public List<Instructions.Maneuver> maneuvers() {
        return maneuvers;
    }

    /** Metres from the route's start. */
    public double progress() {
        return progress;
    }

    /** A new route after leaving the old one: navigation goes on with it, and the first maneuver is said again. */
    public void replace(Route r) {
        set(r);
        said.add(0); // no "start the route" again
    }

    /**
     * Where a new route has to go when this one was left: the via points not reached yet, then the
     * destination ({lat, lon} each).
     */
    public List<double[]> targets() {
        List<double[]> t = new ArrayList<>();
        // asked: start, via..., destination; vias[i] is where via point i+1 (or the destination) lies
        for (int i = 0; i < route.vias.length; i++) {
            if (route.vias[i] > progress + 1 || i == route.vias.length - 1) t.add(route.asked.get(i + 1));
        }
        return t;
    }

    /** The route ahead of the rider, {lat, lon} points, for the curve warnings. */
    public List<double[]> pathAhead(double metres) {
        List<double[]> l = new ArrayList<>();
        l.add(route.pointAt(progress));
        int i = route.indexAt(progress) + 1;
        for (; i < route.dist.length && route.dist[i] <= progress + metres; i++) l.add(new double[]{route.lat[i], route.lon[i]});
        if (i < route.dist.length) l.add(route.pointAt(progress + metres));
        return l;
    }

    /** Where the route ahead runs on roundabouts: {from, to} metres ahead of the rider. */
    public List<double[]> roundaboutsAhead(double metres) {
        List<double[]> l = new ArrayList<>();
        for (int i = route.stateAt(progress); i < route.states.length && route.stateFrom[i] < progress + metres; i++) {
            if (!RouteGraph.roundabout(graph.flags(RouteGraph.edgeOf(route.states[i])))) continue;
            double a = Math.max(route.stateFrom[i], progress) - progress, b = route.stateTo[i] - progress;
            if (!l.isEmpty() && Math.abs(l.get(l.size() - 1)[1] - a) < 1) l.get(l.size() - 1)[1] = b;
            else l.add(new double[]{a, b});
        }
        return l;
    }

    /**
     * A new fix. {@code heading} in degrees (NaN when not known), {@code speed} m/s.
     */
    public Update onFix(double lat, double lon, double heading, double speed) {
        if (state == State.ARRIVED) return update(null);
        double[] m = match(lat, lon, heading, speed);
        String say = null;
        if (m == null) {
            if (++offCount >= OFF_ROUTE_FIXES && matchedOnce) {
                if (state != State.OFF_ROUTE) say = text.recalculating();
                state = State.OFF_ROUTE;
            }
            return update(say);
        }
        offCount = 0;
        matchedOnce = true;
        state = State.NAVIGATING;
        // small steps back are GPS noise; the route is ridden forwards
        if (m[0] > progress - 30) progress = Math.max(progress, m[0]);

        if (progress >= route.length - ARRIVED_M) {
            state = State.ARRIVED;
            return update(text.action(maneuvers.get(maneuvers.size() - 1)));
        }
        while (viasPassed < route.vias.length - 1 && progress >= route.vias[viasPassed]) {
            viasPassed++;
            if (!route.roundTrip) say = text.action(new Instructions.Maneuver(Instructions.Kind.VIA, 0, viasPassed,
                    route.vias[viasPassed - 1], 44, ""));
        }
        if (say == null) say = announcement(speed);
        return update(say);
    }

    private Update update(String say) {
        if (state == State.ARRIVED) return new Update(state, null, 0, 0, 0, say);
        Instructions.Maneuver next = Instructions.next(maneuvers, progress);
        double toNext = next == null ? 0 : next.at - progress;
        double remaining = Math.max(0, route.length - progress);
        double time = route.length > 0 ? route.time * remaining / route.length : 0;
        return new Update(state, next, toNext, remaining, time, say);
    }

    /**
     * The phrase due now, or null: a maneuver is said early (a distance that grows with speed), again
     * close to it, and once more at it; the very start says which road to follow.
     */
    private String announcement(double speed) {
        if (!said.contains(0)) {
            said.add(0);
            Instructions.Maneuver first = Instructions.next(maneuvers, progress);
            String start = text.action(maneuvers.get(0));
            if (first != null && first.at - progress < 400) start += text.then(first);
            return start;
        }
        int idx = -1;
        for (int i = 0; i < maneuvers.size(); i++) {
            if (maneuvers.get(i).at > progress + 0.5 && maneuvers.get(i).kind != Instructions.Kind.DEPART) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return null;
        Instructions.Maneuver m = maneuvers.get(idx);
        if (m.kind == Instructions.Kind.VIA || m.kind == Instructions.Kind.ARRIVE) return null;
        double d = m.at - progress;
        double v = Math.max(speed, 8);
        double early = Math.max(300, v * 18), near = Math.max(60, v * 6);
        int stage;
        if (d <= near) stage = 3;
        else if (d <= early) stage = 2;
        else if (v > 22 && d <= 2000 && d > early + 300) stage = 1; // fast roads: once more from far away
        else return null;
        int key = idx * 4 + stage;
        if (said.contains(key)) return null;
        // a later stage makes the earlier ones pointless
        for (int s = 1; s <= stage; s++) said.add(idx * 4 + s);
        String phrase = text.spoken(m, stage == 3 ? 0 : d, imperial);
        if (idx + 1 < maneuvers.size()) {
            Instructions.Maneuver after = maneuvers.get(idx + 1);
            if (after.at - m.at < 150 && after.kind != Instructions.Kind.ARRIVE && after.kind != Instructions.Kind.VIA) {
                phrase += text.then(after);
                // named already: only said again when it comes
                for (int s = 1; s <= 2; s++) said.add((idx + 1) * 4 + s);
            }
        }
        return phrase;
    }

    /** {distance along the route, metres off it} of the best place for the fix, or null when off the route. */
    private double[] match(double lat, double lon, double heading, double speed) {
        double from = matchedOnce ? Math.max(0, progress - 100) : 0;
        double to = matchedOnce ? progress + Math.max(400, speed * 15 + 200) : route.length;
        int i0 = route.indexAt(from), i1 = route.indexAt(to);
        double ky = 111_132.0, kx = 111_320.0 * Math.cos(Math.toRadians(lat));
        double best = Double.MAX_VALUE;
        double bestAlong = -1;
        boolean useHeading = !Double.isNaN(heading) && speed > 3;
        for (int i = i0; i < Math.min(i1 + 1, route.dist.length - 1); i++) {
            double ax = (route.lon[i] - lon) * kx, ay = (route.lat[i] - lat) * ky;
            double bx = (route.lon[i + 1] - lon) * kx, by = (route.lat[i + 1] - lat) * ky;
            double dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
            double t = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
            double d = Math.hypot(ax + t * dx, ay + t * dy);
            if (d > ON_ROUTE_M) continue;
            double score = d;
            if (useHeading && len2 > 1) {
                double dh = Math.abs(RoadNetwork.angleDiff(RoadNetwork.bearing(ax, ay, bx, by), heading));
                if (dh > 75) continue; // the other carriageway, or this road the other way in a loop
                score += dh * 0.2;
            }
            // nearer the expected position wins a tie
            double along = route.dist[i] + t * (route.dist[i + 1] - route.dist[i]);
            score += Math.abs(along - progress) * 0.01;
            if (score < best) {
                best = score;
                bestAlong = along;
            }
        }
        return bestAlong < 0 ? null : new double[]{bestAlong, best};
    }
}
