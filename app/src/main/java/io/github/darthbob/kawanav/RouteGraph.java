package io.github.darthbob.kawanav;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The routing graph of the downloaded regions, as written by {@link RouteGraphBuilder}: files mapped
 * read-only into memory (outside the Java heap, so a country fits), several regions numbered one
 * after the other. Where two regions overlap, the junctions they share (same OSM node in both
 * border files) are joined: driving into a junction of one region can leave it on a road of the other.
 *
 * Edges are undirected; a search moves along <em>states</em>, an edge in one direction:
 * {@code edge * 2} from a to b, {@code edge * 2 + 1} from b to a.
 *
 * Plain Java; safe to read from several threads.
 */
public final class RouteGraph implements Closeable {

    static final int FORMAT = 1;
    static final int EDGE_INTS = 7;
    /** Road classes in flag order (the routed highway types of {@link PbfImporter}). */
    static final String[] CLASSES = {"motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
            "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified", "residential",
            "living_street", "road"};
    static final int C_MOTORWAY = 0, C_MOTORWAY_LINK = 1, C_TRUNK = 2, C_TRUNK_LINK = 3, C_PRIMARY = 4,
            C_PRIMARY_LINK = 5, C_SECONDARY = 6, C_SECONDARY_LINK = 7, C_TERTIARY = 8, C_TERTIARY_LINK = 9,
            C_UNCLASSIFIED = 10, C_RESIDENTIAL = 11, C_LIVING_STREET = 12, C_ROAD = 13;

    // edge flags
    static final int F_CLASS = 0x1F;
    static final int F_ONEWAY_SHIFT = 5;      // 2 bits: 0 both, 1 a→b only, 2 b→a only
    static final int F_ROUNDABOUT = 1 << 7;
    static final int F_CURVY_SHIFT = 8;       // 2 bits: Curviness.level
    static final int F_SURFACE_SHIFT = 10;    // 2 bits: 0 paved, 1 rough, 2 unpaved, 3 bad
    static final int F_TOLL = 1 << 12;
    static final int F_MAXSPEED_SHIFT = 13;   // 5 bits: km/h / 5, 0 unknown
    static final int F_URBAN = 1 << 18;
    static final int F_ACCESS_SHIFT = 19;     // 2 bits: 0 open, 1 destination, 2 closed

    /** Grid of the spatial index, degrees. */
    static final double CELL_DEG = 0.01;

    static int roadClass(int flags) {
        return flags & F_CLASS;
    }

    static int oneway(int flags) {
        return flags >>> F_ONEWAY_SHIFT & 3;
    }

    static boolean roundabout(int flags) {
        return (flags & F_ROUNDABOUT) != 0;
    }

    static int curvy(int flags) {
        return flags >>> F_CURVY_SHIFT & 3;
    }

    static int surface(int flags) {
        return flags >>> F_SURFACE_SHIFT & 3;
    }

    static int maxspeedKmh(int flags) {
        return (flags >>> F_MAXSPEED_SHIFT & 31) * 5;
    }

    static int access(int flags) {
        return flags >>> F_ACCESS_SHIFT & 3;
    }

    static boolean isLink(int cls) {
        return cls == C_MOTORWAY_LINK || cls == C_TRUNK_LINK || cls == C_PRIMARY_LINK || cls == C_SECONDARY_LINK
                || cls == C_TERTIARY_LINK;
    }

    /** Spatial index cell of a position in 1e-7 degrees. */
    static int cell(double lat7, double lon7) {
        int cy = (int) Math.floor(lat7 / 1e7 / CELL_DEG) + 9000;
        int cx = (int) Math.floor(lon7 / 1e7 / CELL_DEG) + 18000;
        return cy * 36001 + cx;
    }

    // ------------------------------------------------------------------ regions

    private static final class Region {
        final File dir;
        final IntBuffer nodes, edges, geo, adjoff, adj, cells, celledges;
        final String[] names, refs;
        final int nodeCount, edgeCount, cellCount;
        int nodeOff, edgeOff;

        Region(File dir) throws IOException {
            this.dir = dir;
            Properties meta = meta(dir);
            nodeCount = Integer.parseInt(meta.getProperty("nodes"));
            edgeCount = Integer.parseInt(meta.getProperty("edges"));
            cellCount = Integer.parseInt(meta.getProperty("cells"));
            nodes = map(new File(dir, "nodes.bin"));
            edges = map(new File(dir, "edges.bin"));
            geo = map(new File(dir, "geometry.bin"));
            adjoff = map(new File(dir, "adjoff.bin"));
            adj = map(new File(dir, "adj.bin"));
            cells = map(new File(dir, "cells.bin"));
            celledges = map(new File(dir, "celledges.bin"));
            List<String> n = new ArrayList<>();
            List<String> r = new ArrayList<>();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    new FileInputStream(new File(dir, "names.txt")), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    int t = line.indexOf('\t');
                    n.add(t < 0 ? line : line.substring(0, t));
                    r.add(t < 0 ? "" : line.substring(t + 1));
                }
            }
            names = n.toArray(new String[0]);
            refs = r.toArray(new String[0]);
        }
    }

    private final List<Region> regions = new ArrayList<>();
    private final int nodeCount, edgeCount;
    /** Global node → the same junction in other regions. */
    private final Map<Integer, int[]> twins = new HashMap<>();

    private static Properties meta(File dir) throws IOException {
        Properties p = new Properties();
        try (FileInputStream f = new FileInputStream(new File(dir, "meta.properties"))) {
            p.load(f);
        }
        return p;
    }

    /** A complete graph of the current format is stored in the directory. */
    public static boolean exists(File dir) {
        try {
            return String.valueOf(FORMAT).equals(meta(dir).getProperty("version"));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static IntBuffer map(File f) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r"); FileChannel ch = raf.getChannel()) {
            MappedByteBuffer m = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            return m.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        }
    }

    /** Opens the graphs of the given region directories (those without one are skipped). */
    public static RouteGraph open(List<File> dirs) throws IOException {
        return new RouteGraph(dirs);
    }

    private RouteGraph(List<File> dirs) throws IOException {
        int no = 0, eo = 0;
        for (File d : dirs) {
            if (!exists(d)) continue;
            Region r = new Region(d);
            r.nodeOff = no;
            r.edgeOff = eo;
            no += r.nodeCount;
            eo += r.edgeCount;
            regions.add(r);
        }
        nodeCount = no;
        edgeCount = eo;
        if (regions.size() > 1) joinBorders();
    }

    private void joinBorders() throws IOException {
        Map<Long, List<Integer>> byOsm = new HashMap<>();
        for (Region r : regions) {
            File f = new File(r.dir, "border.bin");
            if (!f.exists()) continue;
            try (RandomAccessFile raf = new RandomAccessFile(f, "r"); FileChannel ch = raf.getChannel()) {
                java.nio.ByteBuffer b = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()).order(ByteOrder.LITTLE_ENDIAN);
                while (b.remaining() >= 12) {
                    long osm = b.getLong();
                    int node = b.getInt() + r.nodeOff;
                    byOsm.computeIfAbsent(osm, k -> new ArrayList<>(2)).add(node);
                }
            }
        }
        for (List<Integer> same : byOsm.values()) {
            if (same.size() < 2) continue;
            for (int n : same) {
                int[] others = new int[same.size() - 1];
                int k = 0;
                for (int m : same) if (m != n) others[k++] = m;
                twins.put(n, others);
            }
        }
    }

    @Override
    public void close() {
        regions.clear(); // the mappings go with the garbage collector
    }

    public boolean isEmpty() {
        return edgeCount == 0;
    }

    public int nodeCount() {
        return nodeCount;
    }

    public int edgeCount() {
        return edgeCount;
    }

    private Region regionOfNode(int node) {
        for (int i = regions.size() - 1; i >= 0; i--) {
            Region r = regions.get(i);
            if (node >= r.nodeOff) return r;
        }
        throw new IllegalArgumentException("node " + node);
    }

    private Region regionOfEdge(int edge) {
        for (int i = regions.size() - 1; i >= 0; i--) {
            Region r = regions.get(i);
            if (edge >= r.edgeOff) return r;
        }
        throw new IllegalArgumentException("edge " + edge);
    }

    // ------------------------------------------------------------------ nodes and edges

    public int lat7(int node) {
        Region r = regionOfNode(node);
        return r.nodes.get((node - r.nodeOff) * 2);
    }

    public int lon7(int node) {
        Region r = regionOfNode(node);
        return r.nodes.get((node - r.nodeOff) * 2 + 1);
    }

    public double lat(int node) {
        return lat7(node) / 1e7;
    }

    public double lon(int node) {
        return lon7(node) / 1e7;
    }

    private int edgeInt(int edge, int field) {
        Region r = regionOfEdge(edge);
        return r.edges.get((edge - r.edgeOff) * EDGE_INTS + field);
    }

    public int edgeA(int edge) {
        Region r = regionOfEdge(edge);
        return r.edges.get((edge - r.edgeOff) * EDGE_INTS) + r.nodeOff;
    }

    public int edgeB(int edge) {
        Region r = regionOfEdge(edge);
        return r.edges.get((edge - r.edgeOff) * EDGE_INTS + 1) + r.nodeOff;
    }

    /** Length in metres. */
    public double length(int edge) {
        return edgeInt(edge, 2) / 10.0;
    }

    public int flags(int edge) {
        return edgeInt(edge, 3);
    }

    public String name(int edge) {
        Region r = regionOfEdge(edge);
        return r.names[r.edges.get((edge - r.edgeOff) * EDGE_INTS + 6)];
    }

    public String ref(int edge) {
        Region r = regionOfEdge(edge);
        return r.refs[r.edges.get((edge - r.edgeOff) * EDGE_INTS + 6)];
    }

    // ------------------------------------------------------------------ states (an edge in one direction)

    static int edgeOf(int state) {
        return state >>> 1;
    }

    static boolean reversed(int state) {
        return (state & 1) != 0;
    }

    public int from(int state) {
        return reversed(state) ? edgeB(edgeOf(state)) : edgeA(edgeOf(state));
    }

    public int to(int state) {
        return reversed(state) ? edgeA(edgeOf(state)) : edgeB(edgeOf(state));
    }

    /** May be driven: the oneway direction (access is the profile's business). */
    public boolean allowed(int state) {
        int ow = oneway(flags(edgeOf(state)));
        return ow == 0 || (ow == 1) != reversed(state);
    }

    /** Compass bearing when leaving the start of the state. */
    public int startBearing(int state) {
        int b = edgeInt(edgeOf(state), 4);
        return reversed(state) ? ((b >>> 16) + 180) % 360 : b & 0xFFFF;
    }

    /** Compass bearing when arriving at the end of the state. */
    public int endBearing(int state) {
        int b = edgeInt(edgeOf(state), 4);
        return reversed(state) ? ((b & 0xFFFF) + 180) % 360 : b >>> 16;
    }

    /**
     * The states leaving the node (also those of the same junction in a joined region) into {@code out};
     * returns how many. {@code out} must hold {@link #degreeBound} entries.
     */
    public int out(int node, int[] out) {
        int n = outLocal(node, out, 0);
        int[] t = twins.get(node);
        if (t != null) for (int m : t) n = outLocal(m, out, n);
        return n;
    }

    /** Room {@link #out} needs at most for any node. */
    public int degreeBound() {
        return 256;
    }

    private int outLocal(int node, int[] out, int n) {
        Region r = regionOfNode(node);
        int local = node - r.nodeOff;
        int a = r.adjoff.get(local), b = r.adjoff.get(local + 1);
        for (int i = a; i < b && n < out.length; i++) {
            int v = r.adj.get(i);
            out[n++] = ((v >>> 1) + r.edgeOff) * 2 + (v & 1);
        }
        return n;
    }

    /** Same junction (same node, or the joined node of another region). */
    public boolean sameNode(int a, int b) {
        if (a == b) return true;
        int[] t = twins.get(a);
        if (t != null) for (int m : t) if (m == b) return true;
        return false;
    }

    // ------------------------------------------------------------------ geometry

    /** Points of the edge (both ends included), in 1e-7 degrees: {lat0, lon0, lat1, lon1, ...}. */
    public int[] points(int edge) {
        Region r = regionOfEdge(edge);
        int base = (edge - r.edgeOff) * EDGE_INTS;
        int a = r.edges.get(base) + r.nodeOff, b = r.edges.get(base + 1) + r.nodeOff;
        int geoOff = r.edges.get(base + 5);
        int inner = geoOff < 0 ? 0 : r.geo.get(geoOff);
        int[] p = new int[(inner + 2) * 2];
        p[0] = lat7(a);
        p[1] = lon7(a);
        for (int k = 0; k < inner; k++) {
            p[2 + 2 * k] = r.geo.get(geoOff + 1 + 2 * k);
            p[3 + 2 * k] = r.geo.get(geoOff + 2 + 2 * k);
        }
        p[p.length - 2] = lat7(b);
        p[p.length - 1] = lon7(b);
        return p;
    }

    /** Points of the state in driving direction. */
    public int[] points(int edge, boolean reversed) {
        int[] p = points(edge);
        if (!reversed) return p;
        int[] q = new int[p.length];
        for (int i = 0, j = p.length - 2; j >= 0; i += 2, j -= 2) {
            q[i] = p[j];
            q[i + 1] = p[j + 1];
        }
        return q;
    }

    // ------------------------------------------------------------------ nearest road

    /** Decides which edges a point may be put on (e.g. no motorway for a start in town). */
    public interface EdgeFilter {
        boolean accept(int edge, int flags);
    }

    /** A point put on the nearest road. */
    public static final class Snap {
        public final int edge;
        /** Metres from the edge's start (a) to the point along the edge. */
        public final double along;
        public final double lat, lon;
        /** Metres from the asked position to the road. */
        public final double distance;

        Snap(int edge, double along, double lat, double lon, double distance) {
            this.edge = edge;
            this.along = along;
            this.lat = lat;
            this.lon = lon;
            this.distance = distance;
        }
    }

    /**
     * The point on the nearest accepted road within {@code maxDist} metres, or null. Searches the
     * index cells in growing rings around the position.
     */
    public Snap nearest(double lat, double lon, double maxDist, EdgeFilter filter) {
        double ky = 111_132.0, kx = 111_320.0 * Math.cos(Math.toRadians(lat));
        int rings = (int) Math.ceil(maxDist / (CELL_DEG * Math.min(kx, ky))) + 1;
        int cy = (int) Math.floor(lat / CELL_DEG) + 9000, cx = (int) Math.floor(lon / CELL_DEG) + 18000;
        Snap best = null;
        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
        for (int ring = 0; ring <= rings; ring++) {
            for (int dy = -ring; dy <= ring; dy++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dy), Math.abs(dx)) != ring) continue;
                    int key = (cy + dy) * 36001 + (cx + dx);
                    for (Region r : regions) {
                        int[] range = cellRange(r, key);
                        if (range == null) continue;
                        for (int i = range[0]; i < range[1]; i++) {
                            int e = r.celledges.get(i) + r.edgeOff;
                            if (!seen.add(e)) continue;
                            int f = flags(e);
                            if (filter != null && !filter.accept(e, f)) continue;
                            Snap s = project(e, lat, lon, kx, ky);
                            if (s.distance <= maxDist && (best == null || s.distance < best.distance)) best = s;
                        }
                    }
                }
            }
            // a ring further out cannot hold anything closer than this
            if (best != null && best.distance < ring * CELL_DEG * Math.min(kx, ky)) break;
        }
        return best;
    }

    private static int[] cellRange(Region r, int key) {
        int lo = 0, hi = r.cellCount - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int k = r.cells.get(mid * 2);
            if (k < key) lo = mid + 1;
            else if (k > key) hi = mid - 1;
            else return new int[]{r.cells.get(mid * 2 + 1), r.cells.get(mid * 2 + 3)};
        }
        return null;
    }

    private Snap project(int edge, double lat, double lon, double kx, double ky) {
        int[] p = points(edge);
        double bestD = Double.MAX_VALUE, bestAlong = 0, bx = 0, by = 0, along = 0;
        for (int i = 0; i + 3 < p.length; i += 2) {
            double ax = (p[i + 1] / 1e7 - lon) * kx, ay = (p[i] / 1e7 - lat) * ky;
            double cx = (p[i + 3] / 1e7 - lon) * kx, cy = (p[i + 2] / 1e7 - lat) * ky;
            double dx = cx - ax, dy = cy - ay, len2 = dx * dx + dy * dy;
            double t = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
            double px = ax + t * dx, py = ay + t * dy;
            double d = Math.hypot(px, py);
            double seg = Math.sqrt(len2);
            if (d < bestD) {
                bestD = d;
                bestAlong = along + t * seg;
                bx = px;
                by = py;
            }
            along += seg;
        }
        // the edge's stored length is the reference for costs: scale the projected position onto it
        double scale = along > 0 ? length(edge) / along : 1;
        return new Snap(edge, bestAlong * scale, lat + by / ky, lon + bx / kx, bestD);
    }
}
