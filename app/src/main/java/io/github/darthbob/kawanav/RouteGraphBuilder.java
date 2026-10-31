package io.github.darthbob.kawanav;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Builds the routing graph of an offline region while {@link PbfImporter} writes its tiles: every road
 * way is cut at its junctions into edges, each with its length, attributes and curviness, and the
 * result is written as the flat files {@link RouteGraph} maps into memory.
 *
 * Plain Java without Android classes. Memory: the junction ids (8 bytes each) and their coordinates
 * while the edges are numbered, then 8 bytes per edge for the adjacency and the spatial index.
 *
 * Files of a region (all ints little-endian unless noted):
 * <ul>
 * <li>nodes.bin: lat7, lon7 per node (junctions and way ends);</li>
 * <li>edges.bin: a, b, length in dm, flags ({@link RouteGraph#F_CLASS} ...), bearings (leaving a in the
 *     low 16 bits, arriving at b in the high 16, degrees), geometry offset (in ints, -1 without inner
 *     points), name id;</li>
 * <li>geometry.bin: per edge with inner points: count, then lat7, lon7 of each, from a to b;</li>
 * <li>adjoff.bin + adj.bin: for each node the directed edges leaving it, edge * 2 + (0 a→b, 1 b→a);</li>
 * <li>cells.bin + celledges.bin: grid cells of {@link RouteGraph#CELL_DEG} (key, first index) and the
 *     edges touching each cell, for finding the road nearest to a point;</li>
 * <li>border.bin: OSM id (long) and node of the junctions in tiles on the region border, so neighbour
 *     regions can be joined;</li>
 * <li>names.txt: "name\tref" per name id (UTF-8); meta.properties.</li>
 * </ul>
 */
public final class RouteGraphBuilder {

    private final File dir;
    private final File tmp;
    private final DataOutputStream edgesOut;
    private final PbfImporter.LongList towerIds = new PbfImporter.LongList(1 << 16);
    private final Map<String, Integer> names = new HashMap<>();
    private int edgeCount;
    /** Kilometres of residential streets per town cell: many of them make the cell built-up. */
    private final LongFloatMap streets = new LongFloatMap();
    static final double TOWN_CELL_DEG = 0.005;
    static final float TOWN_STREET_KM = 1.5f;

    /** @param dir where the region's graph goes (replaced when the build completes) */
    public RouteGraphBuilder(File dir, File tmpDir) throws IOException {
        this.dir = dir;
        this.tmp = new File(tmpDir, "graph-edges.bin");
        tmpDir.mkdirs();
        edgesOut = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16));
        names.put("\t", 0);
    }

    // ------------------------------------------------------------------ way attributes

    /** Road class index in {@link RouteGraph#CLASSES}, or -1 for a highway type not routed on. */
    static int roadClass(String highway) {
        for (int i = 0; i < RouteGraph.CLASSES.length; i++) if (RouteGraph.CLASSES[i].equals(highway)) return i;
        return -1;
    }

    /**
     * The attribute bits of a way that do not depend on its geometry: surface, toll, speed limit,
     * built-up area and access, see {@link RouteGraph}. Tags that are missing are passed as "".
     */
    static int attributes(String surface, String tracktype, String smoothness, String maxspeed,
                          String access, String motorVehicle, String motorcycle, String toll) {
        return attributes(surface, tracktype, smoothness, maxspeed, access, motorVehicle, motorcycle, toll, "", "", "");
    }

    /**
     * As above, with the hints of a built-up area besides the speed limit: a sidewalk, or street lights
     * on a road that is not a motorway or trunk road.
     */
    static int attributes(String surface, String tracktype, String smoothness, String maxspeed,
                          String access, String motorVehicle, String motorcycle, String toll,
                          String sidewalk, String lit, String highway) {
        int s = surface(surface, tracktype, smoothness);
        int flags = s << RouteGraph.F_SURFACE_SHIFT;
        if (toll.equals("yes")) flags |= RouteGraph.F_TOLL;
        String ms = maxspeed.toLowerCase(java.util.Locale.ROOT);
        int kmh;
        boolean urban = false;
        if (ms.endsWith(":urban")) {
            kmh = 50;
            urban = true;
        } else if (ms.endsWith(":rural")) {
            kmh = 90;
        } else if (ms.endsWith(":motorway")) {
            kmh = 130;
        } else if (ms.endsWith(":living_street")) {
            kmh = 10;
            urban = true;
        } else {
            kmh = PbfImporter.maxspeedKmh(maxspeed);
            if (ms.contains("mph")) kmh = (int) Math.round(kmh * 1.609);
            urban = kmh > 0 && kmh <= 50;
        }
        flags |= Math.min(31, Math.max(0, Math.round(kmh / 5f))) << RouteGraph.F_MAXSPEED_SHIFT;
        switch (sidewalk) {
            case "both": case "left": case "right": case "yes": case "separate":
                urban = true;
                break;
            default:
                break;
        }
        if (lit.equals("yes") && !highway.startsWith("motorway") && !highway.startsWith("trunk")) urban = true;
        if (urban) flags |= RouteGraph.F_URBAN;
        // the most specific tag wins: motorcycle over motor_vehicle over access
        String a = !motorcycle.isEmpty() ? motorcycle : !motorVehicle.isEmpty() ? motorVehicle : access;
        flags |= access(a) << RouteGraph.F_ACCESS_SHIFT;
        return flags;
    }

    /** 0 paved, 1 rough (cobbles, paving stones), 2 unpaved, 3 bad. Unknown counts as paved. */
    static int surface(String surface, String tracktype, String smoothness) {
        int s;
        switch (surface) {
            case "cobblestone": case "sett": case "unhewn_cobblestone": case "cobblestone:flattened":
            case "paving_stones": case "concrete:lanes": case "concrete:plates": case "metal": case "wood":
            case "grass_paver":
                s = 1;
                break;
            case "unpaved": case "compacted": case "fine_gravel": case "gravel": case "pebblestone": case "dirt":
            case "earth": case "ground": case "grass": case "rock": case "stone": case "woodchips": case "salt":
            case "snow": case "ice":
                s = 2;
                break;
            case "mud": case "sand":
                s = 3;
                break;
            default:
                s = 0;
        }
        if (surface.isEmpty()) {
            if (tracktype.equals("grade2") || tracktype.equals("grade3")) s = 2;
            else if (tracktype.equals("grade4") || tracktype.equals("grade5")) s = 3;
        }
        switch (smoothness) {
            case "very_bad": case "horrible": case "very_horrible": case "impassable":
                s = 3;
                break;
            case "bad":
                s = Math.max(s, 1);
                break;
            default:
                break;
        }
        return s;
    }

    /** 0 open, 1 destination only, 2 closed (private, no, agricultural ...). */
    static int access(String v) {
        switch (v) {
            case "no": case "private": case "agricultural": case "forestry": case "delivery": case "emergency":
            case "military": case "restricted": case "bus": case "psv":
                return 2;
            case "destination": case "customers":
                return 1;
            default:
                return 0;
        }
    }

    /** 0 both ways, 1 only in node order, 2 only against node order. */
    static int oneway(String oneway, String highway, String junction) {
        switch (oneway) {
            case "yes": case "true": case "1": return 1;
            case "-1": case "reverse": return 2;
            case "no": case "false": case "0": return 0;
            default:
                return highway.equals("motorway") || junction.equals("roundabout") || junction.equals("circular") ? 1 : 0;
        }
    }

    // ------------------------------------------------------------------ edges

    /**
     * Adds a way: points {@code 0..n-1} with their OSM ids, coordinates (1e-7 degrees) and whether
     * another road uses the point too (a junction). Points the extract does not hold are already left
     * out by the caller; {@code gapBefore[i]} marks a point that follows such a hole, where the way is
     * cut instead of joining the two sides with a straight line.
     */
    void addWay(String highway, String name, String ref, String oneway, String junction, int attributes,
                long[] ids, int[] lat7, int[] lon7, boolean[] junctionAt, boolean[] gapBefore, int n) throws IOException {
        int cls = roadClass(highway);
        if (cls < 0 || n < 2) return;
        int start = 0;
        for (int i = 1; i <= n; i++) {
            if (i == n || gapBefore[i]) {
                if (i - start >= 2) addPiece(cls, name, ref, oneway, junction, highway, attributes,
                        ids, lat7, lon7, junctionAt, start, i - 1);
                start = i;
            }
        }
    }

    private void addPiece(int cls, String name, String ref, String oneway, String junction, String highway,
                          int attributes, long[] ids, int[] lat7, int[] lon7, boolean[] junctionAt,
                          int from, int to) throws IOException {
        int n = to - from + 1;
        double lat0 = lat7[from] / 1e7, lon0 = lon7[from] / 1e7;
        double ky = 111_132.0, kx = 111_320.0 * Math.cos(Math.toRadians(lat0));
        double[] x = new double[n], y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = (lon7[from + i] / 1e7 - lon0) * kx;
            y[i] = (lat7[from + i] / 1e7 - lat0) * ky;
        }
        boolean rb = junction.equals("roundabout") || junction.equals("circular");
        int base = cls | oneway(oneway, highway, junction) << RouteGraph.F_ONEWAY_SHIFT
                | (rb ? RouteGraph.F_ROUNDABOUT : 0) | attributes;
        int nameId = nameId(name, ref);
        // positions of the junctions along the way: many close together mean a village or town
        double[] along = new double[n];
        for (int i = 1; i < n; i++) along[i] = along[i - 1] + Math.hypot(x[i] - x[i - 1], y[i] - y[i - 1]);
        PbfImporter.LongList junctionPos = new PbfImporter.LongList(8);
        for (int i = 0; i < n; i++) if (junctionAt[from + i]) junctionPos.add(Math.round(along[i]));
        if (cls == RouteGraph.C_RESIDENTIAL || cls == RouteGraph.C_LIVING_STREET) {
            for (int i = 1; i < n; i++) {
                long c = townCell((lat7[from + i - 1] + lat7[from + i]) / 2.0, (lon7[from + i - 1] + lon7[from + i]) / 2.0);
                streets.add(c, (float) ((along[i] - along[i - 1]) / 1000));
            }
        }
        int s = 0;
        for (int i = 1; i < n; i++) {
            boolean tower = i == n - 1 || junctionAt[from + i];
            if (!tower) continue;
            double len = along[i] - along[s];
            int flags = base; // the curviness level is added once the whole graph is there, see applyCurviness
            if (!rb && !motorway(cls) && junctionsNear(junctionPos, (along[s] + along[i]) / 2) >= TOWN_JUNCTIONS) {
                flags |= RouteGraph.F_URBAN;
            }
            int hStart = (int) Math.round(RoadNetwork.bearing(x[s], y[s], x[s + 1], y[s + 1])) % 360;
            int hEnd = (int) Math.round(RoadNetwork.bearing(x[i - 1], y[i - 1], x[i], y[i])) % 360;
            edgesOut.writeLong(ids[from + s]);
            edgesOut.writeLong(ids[from + i]);
            edgesOut.writeInt(lat7[from + s]);
            edgesOut.writeInt(lon7[from + s]);
            edgesOut.writeInt(lat7[from + i]);
            edgesOut.writeInt(lon7[from + i]);
            edgesOut.writeInt((int) Math.max(1, Math.round(len * 10)));
            edgesOut.writeInt(flags);
            edgesOut.writeInt(hStart | hEnd << 16);
            edgesOut.writeInt(nameId);
            edgesOut.writeInt(i - s - 1);
            for (int k = s + 1; k < i; k++) {
                edgesOut.writeInt(lat7[from + k]);
                edgesOut.writeInt(lon7[from + k]);
            }
            towerIds.add(ids[from + s]);
            towerIds.add(ids[from + i]);
            edgeCount++;
            s = i;
        }
    }

    /** Junctions within {@link #TOWN_WINDOW_M} of a point of the way mark a built-up area from this many on. */
    static final int TOWN_JUNCTIONS = 5;
    /** Junctions of three or more roads in one town cell that make it built-up. */
    static final int TOWN_CELL_JUNCTIONS = 6;
    static final double TOWN_WINDOW_M = 300;

    private static boolean motorway(int cls) {
        return cls == RouteGraph.C_MOTORWAY || cls == RouteGraph.C_MOTORWAY_LINK;
    }

    static long townCell(double lat7, double lon7) {
        long cy = (long) Math.floor(lat7 / 1e7 / TOWN_CELL_DEG), cx = (long) Math.floor(lon7 / 1e7 / TOWN_CELL_DEG);
        return cy << 32 | (cx & 0xffffffffL);
    }

    /** Open addressing map from long keys to summed floats (the street lengths per town cell). */
    static final class LongFloatMap {
        long[] keys = new long[1 << 12];
        float[] vals = new float[1 << 12];
        boolean[] used = new boolean[1 << 12];
        int size;

        private int index(long k) {
            long h = k * 0x9E3779B97F4A7C15L;
            return (int) (h ^ (h >>> 29)) & (keys.length - 1);
        }

        void add(long k, float v) {
            int i = index(k);
            while (used[i] && keys[i] != k) i = (i + 1) & (keys.length - 1);
            if (!used[i]) {
                used[i] = true;
                keys[i] = k;
                if (++size * 2 > keys.length) {
                    grow();
                    add(k, v);
                    return;
                }
            }
            vals[i] += v;
        }

        float get(long k) {
            int i = index(k);
            while (used[i]) {
                if (keys[i] == k) return vals[i];
                i = (i + 1) & (keys.length - 1);
            }
            return 0;
        }

        private void grow() {
            long[] ok = keys;
            float[] ov = vals;
            boolean[] ou = used;
            keys = new long[ok.length * 2];
            vals = new float[ok.length * 2];
            used = new boolean[ok.length * 2];
            for (int i = 0; i < ok.length; i++) {
                if (!ou[i]) continue;
                int j = index(ok[i]);
                while (used[j]) j = (j + 1) & (keys.length - 1);
                used[j] = true;
                keys[j] = ok[i];
                vals[j] = ov[i];
            }
        }
    }

    private static int junctionsNear(PbfImporter.LongList pos, double at) {
        int c = 0;
        for (int i = 0; i < pos.size; i++) if (Math.abs(pos.a[i] - at) <= TOWN_WINDOW_M) c++;
        return c;
    }

    private int nameId(String name, String ref) {
        String key = name + "\t" + ref;
        Integer id = names.get(key);
        if (id == null) {
            id = names.size();
            names.put(key, id);
        }
        return id;
    }

    // ------------------------------------------------------------------ finish

    /** Something that tells whether a road tile lies completely inside the region (see {@link PbfImporter.Polygon}). */
    public interface Border {
        boolean containsTile(int ty, int tx);
    }

    /**
     * Numbers the junctions, writes the region's files and replaces the previous graph in {@code dir}.
     *
     * @return {nodes, edges}
     */
    public int[] finish(Border border, PbfImporter.Progress progress) throws Exception {
        edgesOut.close();
        File out = new File(dir.getPath() + ".new");
        deleteTree(out);
        out.mkdirs();
        try {
            progress.status("Building routing data: junctions…");
            long[] towers = towerIds.sortedUnique();
            int nodes = towers.length;
            int[] nlat = new int[nodes], nlon = new int[nodes];
            int[] ea = new int[edgeCount], eb = new int[edgeCount];
            PbfImporter.LongList cells = new PbfImporter.LongList(Math.max(16, edgeCount + edgeCount / 2));

            progress.status("Building routing data: roads…");
            int geoInts = 0;
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(tmp), 1 << 16));
                 DataOutputStream edges = le(new File(out, "edges.bin"));
                 DataOutputStream geo = le(new File(out, "geometry.bin"))) {
                for (int e = 0; e < edgeCount; e++) {
                    if (e % 200_000 == 0) {
                        if (progress.cancelled()) throw new PbfImporter.CancelledException();
                        progress.status(String.format(java.util.Locale.ROOT, "Building routing data: roads %d%%",
                                (long) e * 100 / Math.max(1, edgeCount)));
                    }
                    int a = Arrays.binarySearch(towers, in.readLong());
                    int b = Arrays.binarySearch(towers, in.readLong());
                    nlat[a] = in.readInt();
                    nlon[a] = in.readInt();
                    nlat[b] = in.readInt();
                    nlon[b] = in.readInt();
                    int len = in.readInt(), flags = in.readInt(), bearings = in.readInt(), name = in.readInt();
                    int inner = in.readInt();
                    int[] gl = new int[inner], go = new int[inner];
                    for (int k = 0; k < inner; k++) {
                        gl[k] = in.readInt();
                        go[k] = in.readInt();
                    }
                    ea[e] = a;
                    eb[e] = b;
                    double midLat = inner > 0 ? gl[inner / 2] : (nlat[a] + (double) nlat[b]) / 2;
                    double midLon = inner > 0 ? go[inner / 2] : (nlon[a] + (double) nlon[b]) / 2;
                    if (!motorway(flags & RouteGraph.F_CLASS) && streets.get(townCell(midLat, midLon)) >= TOWN_STREET_KM) {
                        flags |= RouteGraph.F_URBAN;
                    }
                    writeInts(edges, a, b, len, flags, bearings, inner == 0 ? -1 : geoInts, name);
                    if (inner > 0) {
                        writeInts(geo, inner);
                        for (int k = 0; k < inner; k++) writeInts(geo, gl[k], go[k]);
                        geoInts += 1 + 2 * inner;
                    }
                    addCells(cells, e, nlat[a], nlon[a], gl, go, nlat[b], nlon[b]);
                }
            }
            tmp.delete();

            try (DataOutputStream n = le(new File(out, "nodes.bin"))) {
                for (int i = 0; i < nodes; i++) writeInts(n, nlat[i], nlon[i]);
            }

            progress.status("Building routing data: border…");
            Map<Long, Boolean> inside = new HashMap<>();
            try (DataOutputStream bo = le(new File(out, "border.bin"))) {
                for (int i = 0; i < nodes; i++) {
                    int ty = (int) Math.floor(nlat[i] / 1e7 / RoadStore.TILE_DEG);
                    int tx = (int) Math.floor(nlon[i] / 1e7 / RoadStore.TILE_DEG);
                    long key = ((long) ty << 32) | (tx & 0xffffffffL);
                    Boolean in = inside.get(key);
                    if (in == null) {
                        in = border.containsTile(ty, tx);
                        inside.put(key, in);
                    }
                    if (!in) {
                        bo.writeLong(Long.reverseBytes(towers[i]));
                        writeInts(bo, i);
                    }
                }
            }
            towers = null;
            nlat = nlon = null;

            progress.status("Building routing data: connections…");
            int[] off = new int[nodes + 1];
            for (int e = 0; e < edgeCount; e++) {
                off[ea[e] + 1]++;
                off[eb[e] + 1]++;
            }
            for (int i = 0; i < nodes; i++) off[i + 1] += off[i];
            int[] fill = Arrays.copyOf(off, nodes);
            int[] adj = new int[2 * edgeCount];
            for (int e = 0; e < edgeCount; e++) {
                adj[fill[ea[e]]++] = e * 2;
                adj[fill[eb[e]]++] = e * 2 + 1;
            }
            ea = eb = null;
            fill = null;
            writeIntFile(new File(out, "adjoff.bin"), off, off.length);
            writeIntFile(new File(out, "adj.bin"), adj, adj.length);
            adj = null;
            off = null;

            progress.status("Building routing data: index…");
            long[] pairs = cells.sortedUnique();
            int cellCount = 0;
            for (int i = 0; i < pairs.length; i++) if (i == 0 || (pairs[i] >>> 32) != (pairs[i - 1] >>> 32)) cellCount++;
            try (DataOutputStream c = le(new File(out, "cells.bin"));
                 DataOutputStream ce = le(new File(out, "celledges.bin"))) {
                for (int i = 0; i < pairs.length; i++) {
                    if (i == 0 || (pairs[i] >>> 32) != (pairs[i - 1] >>> 32)) writeInts(c, (int) (pairs[i] >>> 32), i);
                    writeInts(ce, (int) pairs[i]);
                }
                writeInts(c, Integer.MAX_VALUE, pairs.length); // end marker: the last cell's list ends here
            }

            String[] byId = new String[names.size()];
            for (Map.Entry<String, Integer> e : names.entrySet()) byId[e.getValue()] = e.getKey();
            try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(new File(out, "names.txt")), StandardCharsets.UTF_8))) {
                for (String s : byId) {
                    w.write(s.replace('\n', ' '));
                    w.write('\n');
                }
            }

            Properties meta = new Properties();
            meta.setProperty("version", String.valueOf(RouteGraph.FORMAT));
            meta.setProperty("nodes", String.valueOf(nodes));
            meta.setProperty("edges", String.valueOf(edgeCount));
            meta.setProperty("cells", String.valueOf(cellCount));
            meta.setProperty("built", String.valueOf(System.currentTimeMillis()));
            try (FileOutputStream f = new FileOutputStream(new File(out, "meta.properties"))) {
                meta.store(f, "KawaNav routing graph");
            }

            deleteTree(dir);
            dir.getParentFile().mkdirs();
            if (!out.renameTo(dir)) throw new IOException("cannot store the routing graph in " + dir);
            applyCurviness(dir, progress);
            return new int[]{nodes, edgeCount};
        } finally {
            tmp.delete();
            deleteTree(out);
        }
    }

    // ------------------------------------------------------------------ curviness

    /** How far the window reaches beyond an edge along the road it belongs to (each side). */
    static final double CURVY_REACH_M = Curviness.WINDOW_M / 2;

    /**
     * Sets every edge's curviness level in the stored graph. An edge is measured together with up to
     * {@link #CURVY_REACH_M} of the road on either side: OSM cuts roads into many ways (at bridges, at
     * every change of a tag), and a short piece on its own would say little.
     */
    static void applyCurviness(File dir, PbfImporter.Progress progress) throws Exception {
        RouteGraph g = RouteGraph.open(java.util.Collections.singletonList(dir));
        int n = g.edgeCount();
        int[] out = new int[g.degreeBound()];
        // junctions per town cell: many close together are a village or a town
        LongFloatMap junctions = new LongFloatMap();
        for (int v = 0; v < g.nodeCount(); v++) {
            if (g.out(v, out) >= 3) junctions.add(townCell(g.lat7(v), g.lon7(v)), 1);
        }
        byte[] level = new byte[n];
        for (int e = 0; e < n; e++) {
            if (e % 50_000 == 0) {
                if (progress.cancelled()) throw new PbfImporter.CancelledException();
                progress.status(String.format(java.util.Locale.ROOT, "Building routing data: curves %d%%",
                        (long) e * 100 / Math.max(1, n)));
            }
            level[e] = (byte) Curviness.level(degreesPerKm(g, e, out));
            int[] p = g.points(e);
            int mid = (p.length / 4) * 2;
            if (!motorway(RouteGraph.roadClass(g.flags(e))) && junctions.get(townCell(p[mid], p[mid + 1])) >= TOWN_CELL_JUNCTIONS) {
                level[e] |= 4; // built-up
            }
        }
        // rewrite the flags in place, a block at a time
        File f = new File(dir, "edges.bin");
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "rw"); FileChannel ch = raf.getChannel()) {
            int perBlock = 1 << 14;
            ByteBuffer b = ByteBuffer.allocate(perBlock * RouteGraph.EDGE_INTS * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int first = 0; first < n; first += perBlock) {
                int count = Math.min(perBlock, n - first);
                long pos = (long) first * RouteGraph.EDGE_INTS * 4;
                b.clear();
                b.limit(count * RouteGraph.EDGE_INTS * 4);
                while (b.hasRemaining()) if (ch.read(b, pos + b.position()) < 0) throw new IOException("short edges file");
                for (int i = 0; i < count; i++) {
                    int at = (i * RouteGraph.EDGE_INTS + 3) * 4;
                    int flags = b.getInt(at) & ~(3 << RouteGraph.F_CURVY_SHIFT);
                    int lv = level[first + i];
                    flags |= (lv & 3) << RouteGraph.F_CURVY_SHIFT;
                    if ((lv & 4) != 0) flags |= RouteGraph.F_URBAN;
                    b.putInt(at, flags);
                }
                b.flip();
                while (b.hasRemaining()) ch.write(b, pos + b.position());
            }
        }
    }

    /** Degrees per km of the edge, measured over it and the road continuing on both sides. */
    static double degreesPerKm(RouteGraph g, int edge, int[] out) {
        int[] own = g.points(edge);
        List<int[]> ahead = continuation(g, edge * 2, out);
        List<int[]> behind = continuation(g, edge * 2 + 1, out);
        // behind (reversed, so it leads into a), the edge, ahead
        PbfImporter.LongList pts = new PbfImporter.LongList(own.length + 64);
        for (int k = behind.size() - 1; k >= 0; k--) {
            int[] p = behind.get(k);
            for (int i = p.length - 2; i >= 2; i -= 2) pts.add((long) p[i] << 32 | (p[i + 1] & 0xffffffffL));
        }
        int startIdx = pts.size;
        for (int i = 0; i < own.length; i += 2) pts.add((long) own[i] << 32 | (own[i + 1] & 0xffffffffL));
        int endIdx = pts.size - 1;
        for (int[] p : ahead) for (int i = 2; i < p.length; i += 2) pts.add((long) p[i] << 32 | (p[i + 1] & 0xffffffffL));

        int m = pts.size;
        double lat0 = (int) (pts.a[startIdx] >> 32) / 1e7;
        double lon0 = (int) pts.a[startIdx] / 1e7;
        double ky = 111_132.0, kx = 111_320.0 * Math.cos(Math.toRadians(lat0));
        double[] x = new double[m], y = new double[m];
        for (int i = 0; i < m; i++) {
            y[i] = ((int) (pts.a[i] >> 32) / 1e7 - lat0) * ky;
            x[i] = ((int) pts.a[i] / 1e7 - lon0) * kx;
        }
        double[] perVertex = Curviness.atVertices(x, y);
        return Curviness.between(perVertex, x, y, startIdx, endIdx);
    }

    /**
     * The road beyond the end of the state, up to {@link #CURVY_REACH_M}: the edges carrying the same
     * name or ref (the straightest of them), or the only way on. Each as its points in driving direction.
     */
    private static List<int[]> continuation(RouteGraph g, int state, int[] out) {
        List<int[]> l = new ArrayList<>();
        int s = state;
        int startEdge = RouteGraph.edgeOf(state);
        double reach = 0;
        for (int guard = 0; guard < 40 && reach < CURVY_REACH_M; guard++) {
            int e = RouteGraph.edgeOf(s);
            String name = g.name(e), ref = g.ref(e);
            boolean rb = RouteGraph.roundabout(g.flags(e));
            int k = g.out(g.to(s), out);
            int next = -1, others = 0;
            double bestTurn = Double.MAX_VALUE;
            int inBearing = g.endBearing(s);
            for (int i = 0; i < k; i++) {
                int x = out[i];
                int xe = RouteGraph.edgeOf(x);
                if (xe == e) continue;
                others++;
                if (RouteGraph.roundabout(g.flags(xe)) != rb) continue;
                boolean same = (!name.isEmpty() && name.equals(g.name(xe))) || (!ref.isEmpty() && ref.equals(g.ref(xe)));
                if (!same) continue;
                double turn = Math.abs(RoadNetwork.angleDiff(inBearing, g.startBearing(x)));
                if (turn < bestTurn) {
                    bestTurn = turn;
                    next = x;
                }
            }
            if (next < 0 && others == 1) {
                for (int i = 0; i < k; i++) {
                    if (RouteGraph.edgeOf(out[i]) != e && RouteGraph.roundabout(g.flags(RouteGraph.edgeOf(out[i]))) == rb) next = out[i];
                }
            }
            if (next < 0 || RouteGraph.edgeOf(next) == startEdge) break;
            l.add(g.points(RouteGraph.edgeOf(next), RouteGraph.reversed(next)));
            reach += g.length(RouteGraph.edgeOf(next));
            s = next;
        }
        return l;
    }

    /** Grid cells the edge touches; long segments are sampled so no cell in between is missed. */
    private static void addCells(PbfImporter.LongList cells, int edge, int la, int lo, int[] gl, int[] go, int lb, int ob) {
        int pla = la, plo = lo;
        long last = Long.MIN_VALUE;
        int n = gl.length + 1;
        for (int k = 0; k < n; k++) {
            int qla = k < gl.length ? gl[k] : lb, qlo = k < gl.length ? go[k] : ob;
            double dLat = Math.abs(qla - pla) / 1e7, dLon = Math.abs(qlo - plo) / 1e7;
            int steps = (int) Math.ceil(Math.max(dLat, dLon) / (RouteGraph.CELL_DEG / 2)) + 1;
            for (int s = 0; s <= steps; s++) {
                double t = (double) s / steps;
                int cell = RouteGraph.cell(pla + (qla - pla) * t, plo + (qlo - plo) * t);
                long p = ((long) cell << 32) | (edge & 0xffffffffL);
                if (p != last) {
                    cells.add(p);
                    last = p;
                }
            }
            pla = qla;
            plo = qlo;
        }
    }

    /** A little-endian int stream (what {@link RouteGraph} maps). */
    private static DataOutputStream le(File f) throws IOException {
        return new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f), 1 << 16));
    }

    private static void writeInts(DataOutputStream o, int... v) throws IOException {
        for (int x : v) o.writeInt(Integer.reverseBytes(x));
    }

    private static void writeIntFile(File f, int[] a, int n) throws IOException {
        try (FileChannel ch = new FileOutputStream(f).getChannel()) {
            ByteBuffer b = ByteBuffer.allocate(1 << 16).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < n; i++) {
                if (b.remaining() < 4) {
                    b.flip();
                    while (b.hasRemaining()) ch.write(b);
                    b.clear();
                }
                b.putInt(a[i]);
            }
            b.flip();
            while (b.hasRemaining()) ch.write(b);
        }
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    /** Stops a build that will not be finished (the import failed or was cancelled). */
    public void abandon() {
        try {
            edgesOut.close();
        } catch (IOException ignored) {
            // nothing to keep
        }
        tmp.delete();
    }
}
