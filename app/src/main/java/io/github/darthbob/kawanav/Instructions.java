package io.github.darthbob.kawanav;

import java.util.ArrayList;
import java.util.List;

/**
 * The maneuvers of a {@link Route}: where the rider has to do something, as the dash shows it (a
 * {@link KawaProtocol} turn id) and as {@link NavText} says it.
 *
 * A junction only gets a maneuver when there is a choice to make: following the only road on, or the
 * obviously straight one, says nothing. Roundabouts are one maneuver with the exit number and the
 * direction they are driven in; link roads become ramps, exits and merges.
 *
 * Plain Java.
 */
public final class Instructions {
    private Instructions() {}

    public enum Kind { DEPART, CONTINUE, SLIGHT, TURN, SHARP, U_TURN, KEEP, RAMP, EXIT, MERGE, ROUNDABOUT, VIA, ARRIVE }

    public static final class Maneuver {
        public final Kind kind;
        /** -1 left, 1 right, 0 none. */
        public final int side;
        /** Roundabout exit (1 = first), or the via point's number (1 = first); 0 otherwise. */
        public final int number;
        /** Distance from the route's start. */
        public final double at;
        /** {@link KawaProtocol} turn id for the dash. */
        public final int turn;
        /** The road taken ("" when it has no name or ref). */
        public final String road;

        Maneuver(Kind kind, int side, int number, double at, int turn, String road) {
            this.kind = kind;
            this.side = side;
            this.number = number;
            this.at = at;
            this.turn = turn;
            this.road = road;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT, "%.0f m %s%s%s%s", at, kind,
                    side < 0 ? " left" : side > 0 ? " right" : "", number > 0 ? " " + number : "",
                    road.isEmpty() ? "" : " → " + road);
        }
    }

    /** Below this change of direction (degrees) the road goes straight on. */
    static final double STRAIGHT = 30;
    /** Two maneuvers this close (metres) on the same side are one (a dual carriageway's two halves). */
    static final double MERGE_M = 25;

    public static List<Maneuver> of(RouteGraph g, Route r, boolean leftHandTraffic) {
        List<Maneuver> list = new ArrayList<>();
        int n = r.states.length;
        list.add(new Maneuver(Kind.DEPART, 0, 0, 0, 42, n > 0 ? road(g, RouteGraph.edgeOf(r.states[0])) : ""));
        int[] out = new int[g.degreeBound()];
        int via = 0;
        for (int i = 1; i < n; i++) {
            int prev = r.states[i - 1], cur = r.states[i];
            double at = r.stateFrom[i];
            while (via < r.vias.length - 1 && r.vias[via] <= at + 0.5) {
                if (!r.roundTrip) list.add(new Maneuver(Kind.VIA, 0, via + 1, r.vias[via], 44 + Math.min(via, 11), ""));
                via++;
            }
            int pe = RouteGraph.edgeOf(prev), ce = RouteGraph.edgeOf(cur);
            int pf = g.flags(pe), cf = g.flags(ce);
            boolean prb = RouteGraph.roundabout(pf), crb = RouteGraph.roundabout(cf);

            if (crb && !prb) {
                // a roundabout: count the exits one could take until the one the route takes
                int exits = 0;
                double turnSum = 0;
                int j = i;
                for (; j < n && RouteGraph.roundabout(g.flags(RouteGraph.edgeOf(r.states[j]))); j++) {
                    int s = r.states[j];
                    turnSum += RoadNetwork.angleDiff(g.startBearing(s), g.endBearing(s));
                    if (j > i) turnSum += RoadNetwork.angleDiff(g.endBearing(r.states[j - 1]), g.startBearing(s));
                    if (hasExit(g, g.to(s), out)) exits++;
                }
                boolean ccw = turnSum < 0;
                int exit = Math.max(1, Math.min(12, exits));
                String road = j < n ? road(g, RouteGraph.edgeOf(r.states[j])) : "";
                list.add(new Maneuver(Kind.ROUNDABOUT, 0, exit, at, ccw ? 29 + exit : 17 + exit, road));
                i = j; // the ring and the way out are part of it
                continue;
            }
            if (crb) continue;

            int node = g.from(cur);
            int inBearing = g.endBearing(prev);
            double angle = pe == ce ? 180 : RoadNetwork.angleDiff(inBearing, g.startBearing(cur));
            int side = angle < 0 ? -1 : 1;
            // the other ways one could take here
            int k = g.out(node, out);
            List<Double> alts = new ArrayList<>();
            for (int a = 0; a < k; a++) {
                int x = out[a];
                int xe = RouteGraph.edgeOf(x);
                if (xe == pe || x == cur || !g.allowed(x) || RouteGraph.access(g.flags(xe)) == 2) continue;
                alts.add(RoadNetwork.angleDiff(inBearing, g.startBearing(x)));
            }
            int pcls = RouteGraph.roadClass(pf), ccls = RouteGraph.roadClass(cf);
            boolean fromFast = pcls == RouteGraph.C_MOTORWAY || pcls == RouteGraph.C_TRUNK;
            boolean toFast = ccls == RouteGraph.C_MOTORWAY || ccls == RouteGraph.C_TRUNK;
            String road = road(g, ce);
            Kind kind;
            if (pe == ce) {
                kind = Kind.U_TURN;
                side = leftHandTraffic ? 1 : -1;
            } else if (RouteGraph.isLink(ccls) && !RouteGraph.isLink(pcls) && fromFast) {
                kind = Kind.EXIT;
                side = sideAmong(angle, alts);
            } else if (RouteGraph.isLink(ccls) && !RouteGraph.isLink(pcls)) {
                if (alts.isEmpty()) continue;
                kind = Kind.RAMP;
                side = sideAmong(angle, alts);
            } else if (RouteGraph.isLink(pcls) && !RouteGraph.isLink(ccls) && toFast) {
                kind = Kind.MERGE;
            } else if (alts.isEmpty()) {
                continue;
            } else {
                double a = Math.abs(angle);
                boolean otherStraight = false;
                for (double x : alts) if (Math.abs(x) < 40) otherStraight = true;
                if (a < STRAIGHT && !otherStraight) continue;        // the obvious way on
                if (a < 40 && otherStraight) {
                    kind = Kind.KEEP;
                    side = sideAmong(angle, alts);
                } else if (a < 55) {
                    kind = Kind.SLIGHT;
                } else if (a < 125) {
                    kind = Kind.TURN;
                } else if (a < 165) {
                    kind = Kind.SHARP;
                } else {
                    kind = Kind.U_TURN;
                    side = leftHandTraffic ? 1 : -1;
                }
            }
            Maneuver last = list.get(list.size() - 1);
            if (at - last.at < MERGE_M && last.side == side && isTurn(last.kind) && isTurn(kind)) continue;
            list.add(new Maneuver(kind, side, 0, at, turnId(kind, side), road));
        }
        while (via < r.vias.length - 1) {
            if (!r.roundTrip) list.add(new Maneuver(Kind.VIA, 0, via + 1, r.vias[via], 44 + Math.min(via, 11), ""));
            via++;
        }
        list.add(new Maneuver(Kind.ARRIVE, 0, 0, r.length, 43, ""));
        return list;
    }

    private static boolean isTurn(Kind k) {
        return k == Kind.SLIGHT || k == Kind.TURN || k == Kind.SHARP;
    }

    /** Left or right of the other ways: for forks and links, whose own angle can be near zero. */
    private static int sideAmong(double angle, List<Double> alts) {
        if (alts.isEmpty()) return angle < 0 ? -1 : 1;
        double closest = alts.get(0);
        for (double a : alts) if (Math.abs(a - angle) < Math.abs(closest - angle)) closest = a;
        return angle < closest ? -1 : 1;
    }

    /** A way off the roundabout at this node. */
    private static boolean hasExit(RouteGraph g, int node, int[] out) {
        int k = g.out(node, out);
        for (int i = 0; i < k; i++) {
            int e = RouteGraph.edgeOf(out[i]);
            int f = g.flags(e);
            if (!RouteGraph.roundabout(f) && g.allowed(out[i]) && RouteGraph.access(f) != 2) return true;
        }
        return false;
    }

    static int turnId(Kind kind, int side) {
        boolean left = side < 0;
        switch (kind) {
            case CONTINUE: return 0;
            case TURN: return left ? 1 : 2;
            case SHARP: return left ? 3 : 4;
            case SLIGHT: return left ? 5 : 6;
            case U_TURN: return left ? 7 : 8;
            case KEEP: return left ? 9 : 10;
            case RAMP: return left ? 12 : 13;
            case EXIT: return left ? 14 : 15;
            case MERGE: return left ? 16 : 17;
            case DEPART: return 42;
            case ARRIVE: return 43;
            default: return KawaProtocol.TURN_NONE;
        }
    }

    /** "SS38 Via dello Stelvio", the ref or the name alone, or "". */
    static String road(RouteGraph g, int edge) {
        String name = g.name(edge), ref = g.ref(edge);
        if (ref.isEmpty()) return name;
        if (name.isEmpty()) return ref;
        return ref + " " + name;
    }

    /** The maneuver after the distance (the next one to do), or null past the destination. */
    public static Maneuver next(List<Maneuver> list, double at) {
        for (Maneuver m : list) if (m.at > at + 0.5 && m.kind != Kind.DEPART) return m;
        return null;
    }
}
