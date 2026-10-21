package io.github.darthbob.kawanav;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenStreetMap roads around a point, in a local metric plane (x east, y north, metres).
 * Can match a GPS fix + heading to a road and follow that road ahead ("where does this road go").
 * Plain Java so it can be unit-tested off-device.
 */
public final class RoadNetwork {

    /** A way as delivered by Overpass ("out geom"). */
    public static final class RawWay {
        final long id;
        final long[] nodes;
        final double[] lats;
        final double[] lons;
        final String highway;
        final String name;
        final String ref;
        final String oneway;
        final String junction;

        public RawWay(long id, long[] nodes, double[] lats, double[] lons,
                      String highway, String name, String ref, String oneway, String junction) {
            this.id = id;
            this.nodes = nodes;
            this.lats = lats;
            this.lons = lons;
            this.highway = highway == null ? "" : highway;
            this.name = name == null ? "" : name;
            this.ref = ref == null ? "" : ref;
            this.oneway = oneway == null ? "" : oneway;
            this.junction = junction == null ? "" : junction;
        }
    }

    static final class Road {
        final long id;
        final long[] nodes;
        final double[] x;
        final double[] y;
        final String name;
        final String ref;
        final int rank;
        final int oneway; // 0 both ways, 1 only in node order, -1 only against node order
        final boolean roundabout;
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

        Road(long id, long[] nodes, double[] x, double[] y, String name, String ref, int rank, int oneway,
             boolean roundabout) {
            this.id = id;
            this.nodes = nodes;
            this.x = x;
            this.y = y;
            this.name = name;
            this.ref = ref;
            this.rank = rank;
            this.oneway = oneway;
            this.roundabout = roundabout;
            for (int i = 0; i < x.length; i++) {
                minX = Math.min(minX, x[i]);
                maxX = Math.max(maxX, x[i]);
                minY = Math.min(minY, y[i]);
                maxY = Math.max(maxY, y[i]);
            }
        }

        int size() {
            return x.length;
        }
    }

    /** Position on a road and the direction we travel along it. */
    public static final class Match {
        final int road;
        final int seg;     // segment seg -> seg+1
        final int dir;     // +1 along node order, -1 against
        final double px;
        final double py;
        final double dist; // metres from the fix to the road

        Match(int road, int seg, int dir, double px, double py, double dist) {
            this.road = road;
            this.seg = seg;
            this.dir = dir;
            this.px = px;
            this.py = py;
            this.dist = dist;
        }
    }

    /** A roundabout on the path ahead and the exit that continues the current road. */
    public static final class RoundaboutPass {
        public final double entryDist;  // metres from the rider to the entry (<= 0: already inside)
        public double exitDist;          // metres to where the path leaves it
        public final int exitNumber;     // 1 = first exit after the entry
        public final boolean ccw;        // counter-clockwise (right-hand traffic)

        RoundaboutPass(double entryDist, int exitNumber, boolean ccw) {
            this.entryDist = entryDist;
            this.exitDist = entryDist;
            this.exitNumber = exitNumber;
            this.ccw = ccw;
        }
    }

    /** The road the rider came from before a roundabout: decides which exit "continues the road". */
    public static final class Approach {
        final double heading;
        final String name;
        final String ref;
        final int rank;

        Approach(double heading, String name, String ref, int rank) {
            this.heading = heading;
            this.name = name;
            this.ref = ref;
            this.rank = rank;
        }
    }

    /** Chosen exit of a roundabout. */
    private static final class Plan {
        long exitNode;
        int exitRoad;
        int exitPos;
        int exitDir;
        int exitNumber;
        boolean ccw;
    }

    private static final double MAX_MATCH_DIST = 40;
    private static final double MAX_MATCH_HEADING = 60;

    public final double lat0;
    public final double lon0;
    private final double kx;
    private final double ky;
    final List<Road> roads = new ArrayList<>();
    private final Map<Long, List<int[]>> nodeIndex = new HashMap<>();

    public RoadNetwork(double lat0, double lon0, List<RawWay> ways) {
        this.lat0 = lat0;
        this.lon0 = lon0;
        this.ky = 111_132.0;
        this.kx = 111_320.0 * Math.cos(Math.toRadians(lat0));
        for (RawWay w : ways) {
            if (w.nodes.length < 2 || w.nodes.length != w.lats.length) continue;
            double[] x = new double[w.lats.length];
            double[] y = new double[w.lats.length];
            for (int i = 0; i < x.length; i++) {
                double[] p = project(w.lats[i], w.lons[i]);
                x[i] = p[0];
                y[i] = p[1];
            }
            int idx = roads.size();
            boolean rb = w.junction.equals("roundabout") || w.junction.equals("circular");
            roads.add(new Road(w.id, w.nodes, x, y, w.name, w.ref, rank(w.highway), oneway(w), rb));
            for (int i = 0; i < w.nodes.length; i++) {
                nodeIndex.computeIfAbsent(w.nodes[i], k -> new ArrayList<>()).add(new int[]{idx, i});
            }
        }
    }

    public int size() {
        return roads.size();
    }

    public double[] project(double lat, double lon) {
        return new double[]{(lon - lon0) * kx, (lat - lat0) * ky};
    }

    /** Latitude and longitude of a point in this network's metres: the reverse of {@link #project}. */
    public double[] unproject(double x, double y) {
        return new double[]{lat0 + y / ky, lon0 + x / kx};
    }

    /** Distance from the data centre, to decide when to fetch a new area. */
    public double distanceFromCenter(double lat, double lon) {
        double[] p = project(lat, lon);
        return Math.hypot(p[0], p[1]);
    }

    static int rank(String highway) {
        switch (highway) {
            case "motorway": return 0;
            case "trunk": return 1;
            case "primary": return 2;
            case "secondary": return 3;
            case "tertiary": return 4;
            case "unclassified":
            case "road": return 5;
            case "residential": return 6;
            case "living_street": return 7;
            default: return highway.endsWith("_link") ? 8 : 6;
        }
    }

    private static int oneway(RawWay w) {
        switch (w.oneway) {
            case "yes":
            case "true":
            case "1": return 1;
            case "-1":
            case "reverse": return -1;
            case "no":
            case "false":
            case "0": return 0;
            default:
                return w.highway.equals("motorway") || w.junction.equals("roundabout")
                        || w.junction.equals("circular") ? 1 : 0;
        }
    }

    public boolean onRoundabout(Match m) {
        return m != null && roads.get(m.road).roundabout;
    }

    /** Approach info for the matched (non-roundabout) road, travelling with this heading. */
    public Approach approach(Match m, double heading) {
        Road r = roads.get(m.road);
        return new Approach(heading, r.name, r.ref, r.rank);
    }

    /** Compass bearing in degrees (0 = north, 90 = east) from a to b. */
    static double bearing(double ax, double ay, double bx, double by) {
        double b = Math.toDegrees(Math.atan2(bx - ax, by - ay));
        return b < 0 ? b + 360 : b;
    }

    /** Signed smallest difference b - a in degrees, in (-180, 180]. */
    static double angleDiff(double a, double b) {
        double d = (b - a) % 360;
        if (d > 180) d -= 360;
        if (d <= -180) d += 360;
        return d;
    }

    private boolean allowed(Road r, int dir) {
        return r.oneway == 0 || r.oneway == dir;
    }

    /**
     * Best road for a fix travelling with the given heading, or null.
     * Staying on the previously matched road is preferred, so parallel roads don't make it flicker.
     */
    public Match match(double x, double y, double heading, Match prev) {
        Match best = null;
        double bestScore = Double.MAX_VALUE;
        for (int ri = 0; ri < roads.size(); ri++) {
            Road r = roads.get(ri);
            if (x < r.minX - MAX_MATCH_DIST || x > r.maxX + MAX_MATCH_DIST
                    || y < r.minY - MAX_MATCH_DIST || y > r.maxY + MAX_MATCH_DIST) continue;
            for (int s = 0; s < r.size() - 1; s++) {
                double ax = r.x[s], ay = r.y[s], bx = r.x[s + 1], by = r.y[s + 1];
                double dx = bx - ax, dy = by - ay;
                double len2 = dx * dx + dy * dy;
                if (len2 < 1e-6) continue;
                double t = Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / len2));
                double px = ax + t * dx, py = ay + t * dy;
                double dist = Math.hypot(x - px, y - py);
                if (dist > MAX_MATCH_DIST) continue;
                double segH = bearing(ax, ay, bx, by);
                for (int dir = 1; dir >= -1; dir -= 2) {
                    if (!allowed(r, dir)) continue;
                    double h = dir == 1 ? segH : (segH + 180) % 360;
                    double dh = Math.abs(angleDiff(h, heading));
                    if (dh > MAX_MATCH_HEADING) continue;
                    double score = dist + dh * 0.4 - (prev != null && prev.road == ri ? 8 : 0);
                    if (score < bestScore) {
                        bestScore = score;
                        best = new Match(ri, s, dir, px, py, dist);
                    }
                }
            }
        }
        return best;
    }

    public List<double[]> pathAhead(Match m, double maxLen) {
        return pathAhead(m, maxLen, null, null);
    }

    /**
     * Polyline (x, y pairs) starting at the matched point and following the road for maxLen metres.
     * At the end of a way it continues on the way that keeps the same name/ref, else the straightest
     * one, preferring roads of the same or higher class. Stops at dead ends.
     *
     * Roundabouts are driven through to the exit that continues the approach road (same name/ref,
     * else closest to straight on); each one is reported in {@code passes}. {@code approach} is the
     * road the rider came from, needed when the match is already inside a roundabout.
     */
    public List<double[]> pathAhead(Match m, double maxLen, Approach approach, List<RoundaboutPass> passes) {
        List<double[]> pts = new ArrayList<>();
        pts.add(new double[]{m.px, m.py});
        int ri = m.road;
        int dir = m.dir;
        int idx = dir == 1 ? m.seg + 1 : m.seg;
        double len = 0;
        Plan plan = null;
        RoundaboutPass pass = null;
        if (roads.get(ri).roundabout && approach != null) {
            int from = dir == 1 ? m.seg : m.seg + 1;
            plan = planRoundabout(ri, from, dir, approach, roads.get(ri).nodes[from]);
            if (plan != null) {
                pass = new RoundaboutPass(0, plan.exitNumber, plan.ccw);
                if (passes != null) passes.add(pass);
            }
        }
        for (int guard = 0; guard < 20_000 && len < maxLen; guard++) {
            Road r = roads.get(ri);
            double[] last = pts.get(pts.size() - 1);
            double[] v = {r.x[idx], r.y[idx]};
            double step = Math.hypot(v[0] - last[0], v[1] - last[1]);
            if (step > 0.01) {
                len += step;
                pts.add(v);
            }
            if (pass != null) pass.exitDist = len;
            if (plan != null && r.roundabout && r.nodes[idx] == plan.exitNode) {
                // leave the roundabout here
                ri = plan.exitRoad;
                dir = plan.exitDir;
                idx = plan.exitPos + dir;
                plan = null;
                pass = null;
                continue;
            }
            boolean atEnd = dir == 1 ? idx == r.size() - 1 : idx == 0;
            if (!atEnd) {
                idx += dir;
                continue;
            }
            if (r.roundabout && plan != null) {
                int[] n = nextRoundaboutSegment(ri, idx);
                if (n == null) break;
                ri = n[0];
                dir = n[2];
                idx = n[1] + dir;
                continue;
            }
            // direction of the road's last segment; the rider's position may coincide with the end vertex
            int pi = idx - dir;
            double inH = bearing(r.x[pi], r.y[pi], r.x[idx], r.y[idx]);
            int[] next = chooseContinuation(r, ri, idx, inH);
            if (next == null) break;
            if (roads.get(next[0]).roundabout && !r.roundabout) {
                plan = planRoundabout(next[0], next[1], next[2], new Approach(inH, r.name, r.ref, r.rank), r.nodes[idx]);
                if (plan != null) {
                    pass = new RoundaboutPass(len, plan.exitNumber, plan.ccw);
                    if (passes != null) passes.add(pass);
                }
            }
            ri = next[0];
            dir = next[2];
            idx = next[1] + dir;
        }
        return pts;
    }

    /**
     * Walks once around the roundabout from the entry and picks the exit that best continues the
     * approach road. Exits are counted like a navigation system: only roads you may drive out on.
     */
    private Plan planRoundabout(int ri, int pos, int dir, Approach ap, long entryNode) {
        int r = ri, p = pos, d = dir;
        double turnSum = 0;
        double prevH = Double.NaN;
        int exitCount = 0;
        Plan best = null;
        double bestScore = Double.MAX_VALUE;
        for (int guard = 0; guard < 500; guard++) {
            Road road = roads.get(r);
            int q = p + d;
            if (q < 0 || q >= road.size()) {
                int[] n = nextRoundaboutSegment(r, p);
                if (n == null) break;
                r = n[0];
                p = n[1];
                d = n[2];
                continue;
            }
            double h = bearing(road.x[p], road.y[p], road.x[q], road.y[q]);
            if (!Double.isNaN(prevH)) turnSum += angleDiff(prevH, h);
            prevH = h;
            p = q;
            long node = road.nodes[p];
            if (node == entryNode) break; // once around
            List<int[]> at = nodeIndex.get(node);
            if (at == null) continue;
            boolean counted = false;
            for (int[] e : at) {
                Road x = roads.get(e[0]);
                if (x.roundabout) continue;
                for (int xd = 1; xd >= -1; xd -= 2) {
                    int xq = e[1] + xd;
                    if (xq < 0 || xq >= x.size() || !allowed(x, xd)) continue;
                    if (!counted) {
                        exitCount++;
                        counted = true;
                    }
                    double outH = bearing(x.x[e[1]], x.y[e[1]], x.x[xq], x.y[xq]);
                    boolean same = (!ap.name.isEmpty() && ap.name.equals(x.name))
                            || (!ap.ref.isEmpty() && ap.ref.equals(x.ref));
                    double score = Math.abs(angleDiff(ap.heading, outH)) - (same ? 60 : 0)
                            + 10 * Math.max(0, x.rank - ap.rank);
                    if (score < bestScore) {
                        bestScore = score;
                        best = new Plan();
                        best.exitNode = node;
                        best.exitRoad = e[0];
                        best.exitPos = e[1];
                        best.exitDir = xd;
                        best.exitNumber = exitCount;
                        best.ccw = turnSum < 0;
                    }
                }
            }
        }
        if (best != null && Math.abs(turnSum) > 1e-6) best.ccw = turnSum < 0; // direction of the whole ring
        return best;
    }

    /** Next roundabout way at the end of roundabout way {@code ri} (vertex {@code pos}). */
    private int[] nextRoundaboutSegment(int ri, int pos) {
        Road from = roads.get(ri);
        List<int[]> at = nodeIndex.get(from.nodes[pos]);
        if (at == null) return null;
        for (int[] e : at) {
            Road r = roads.get(e[0]);
            if (!r.roundabout || (e[0] == ri && e[1] == pos)) continue;
            for (int d = 1; d >= -1; d -= 2) {
                int q = e[1] + d;
                if (q >= 0 && q < r.size() && allowed(r, d)) return new int[]{e[0], e[1], d};
            }
        }
        return null;
    }

    /** {road, position of the shared node, dir} or null. */
    private int[] chooseContinuation(Road from, int fromIdx, int nodePos, double inH) {
        List<int[]> at = nodeIndex.get(from.nodes[nodePos]);
        if (at == null) return null;
        int[] best = null;
        double bestScore = Double.MAX_VALUE;
        for (int[] e : at) {
            Road r = roads.get(e[0]);
            int p = e[1];
            if (e[0] == fromIdx && p == nodePos) continue;
            for (int dir = 1; dir >= -1; dir -= 2) {
                int q = p + dir;
                if (q < 0 || q >= r.size() || !allowed(r, dir)) continue;
                double outH = bearing(r.x[p], r.y[p], r.x[q], r.y[q]);
                double turn = Math.abs(angleDiff(inH, outH));
                if (turn > 150) continue;
                boolean same = (!from.name.isEmpty() && from.name.equals(r.name))
                        || (!from.ref.isEmpty() && from.ref.equals(r.ref));
                double score = turn - (same ? 45 : 0) + 10 * Math.max(0, r.rank - from.rank);
                if (score < bestScore) {
                    bestScore = score;
                    best = new int[]{e[0], p, dir};
                }
            }
        }
        return best;
    }
}
