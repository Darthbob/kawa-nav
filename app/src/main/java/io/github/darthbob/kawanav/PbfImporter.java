package io.github.darthbob.kawanav;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Turns a Geofabrik extract (OpenStreetMap PBF) into {@link RoadStore} tiles, on the phone.
 * Plain Java without Android classes, so it can be tested on a PC (tools/pbftest).
 *
 * Memory stays small (a country fits) by working through temporary files:
 * 1. ways: roads (same highway types as the live download) go to a temporary file;
 * 2. nodes: coordinates of the road points, in rounds of at most {@link #ROUND_REFS} node ids
 *    (one round for a state, several for a country), stored in a coordinate file parallel to
 *    the road points;
 * 3. each road is written into every tile it touches. Geofabrik files are sorted (nodes, ways,
 * relations), so pass 1 starts at the first way block and pass 2 stops there.
 *
 * Tiles completely inside the region border are complete; tiles on the border only hold the roads
 * of this region and are stored as "partial" (merged with neighbour regions, and replaced by a live
 * download when riding there online).
 */
public final class PbfImporter {

    public interface Progress {
        void status(String s);

        boolean cancelled();
    }

    public static final class CancelledException extends Exception {
        CancelledException() {
            super("cancelled");
        }
    }

    private static final Set<String> HIGHWAYS = new HashSet<>(Arrays.asList(
            "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
            "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified", "residential",
            "living_street", "road"));
    /** Tile rows per temporary bucket file when sorting roads into tiles. */
    private static final int BAND_ROWS = 1;
    /** Road points resolved per round: ~200 MB of memory at most. */
    private static final long ROUND_REFS = 8_000_000;
    /** {@link #ROUND_REFS}, smaller in desktop tests to exercise several rounds. */
    static long roundRefs = ROUND_REFS;
    private static final int MISSING = Integer.MIN_VALUE;

    private final File pbf;
    private final Polygon region;
    private final RoadStore store;
    private final File tmpDir;
    private final Progress progress;
    private final Inflater inflater = new Inflater();

    private long[] blobPos; // file offset of each data blob
    private int[] blobLen;
    private int blobCount;

    private long totalRefs;
    private long[] need; // sorted node ids of the current round
    private int[] lat7, lon7; // their coordinates in 1e-7 degrees

    private boolean deleteWhenRead;

    /** Speed cameras seen while the nodes are read (they are read once: in the first round). */
    private final Map<Long, Hazards.Camera> cameras = new java.util.LinkedHashMap<>();
    private boolean scanCameras;

    /** Delete the PBF file once it is read completely (before the tiles are written). */
    public PbfImporter deleteWhenRead() {
        deleteWhenRead = true;
        return this;
    }

    public PbfImporter(File pbf, Polygon region, RoadStore store, File tmpDir, Progress progress) {
        this.pbf = pbf;
        this.region = region;
        this.store = store;
        this.tmpDir = tmpDir;
        this.progress = progress;
    }

    /** Speed cameras inside the region, for {@link HazardStore}. */
    public java.util.List<Hazards.Camera> cameras() {
        return new java.util.ArrayList<>(cameras.values());
    }

    /** @return {roads, complete tiles, partial tiles} */
    public int[] run() throws Exception {
        tmpDir.mkdirs();
        File waysFile = new File(tmpDir, "ways.bin");
        try {
            boolean sorted = indexBlobs();
            int firstWay = sorted ? firstBlobOf(1) : 0;
            int roads = readWays(firstWay, sorted, waysFile);
            File coords = resolve(waysFile, roads, sorted ? firstWay : blobCount);
            if (deleteWhenRead) pbf.delete(); // frees the storage the tiles are about to need
            return writeTiles(waysFile, coords, roads);
        } finally {
            inflater.end();
            File[] fs = tmpDir.listFiles();
            if (fs != null) for (File f : fs) f.delete();
        }
    }

    private void check() throws CancelledException {
        if (progress.cancelled()) throw new CancelledException();
    }

    // ------------------------------------------------------------------ blobs

    /** Lists the data blobs; returns whether the file is sorted by type then id. */
    private boolean indexBlobs() throws IOException {
        List<long[]> list = new ArrayList<>();
        boolean sorted = false;
        try (RandomAccessFile f = new RandomAccessFile(pbf, "r")) {
            long pos = 0, len = f.length();
            while (pos + 4 <= len) {
                f.seek(pos);
                int hl = f.readInt();
                byte[] h = new byte[hl];
                f.readFully(h);
                Pb hp = new Pb(h, 0, hl);
                String type = "";
                int size = 0;
                while (hp.more()) {
                    int k = hp.key();
                    if (k == (1 << 3 | 2)) type = hp.string();
                    else if (k == (3 << 3)) size = (int) hp.varint();
                    else hp.skip(k);
                }
                long data = pos + 4 + hl;
                if (type.equals("OSMHeader")) {
                    sorted = headerSorted(readBlob(f, data, size));
                } else if (type.equals("OSMData")) {
                    list.add(new long[]{data, size});
                }
                pos = data + size;
            }
        }
        blobCount = list.size();
        blobPos = new long[blobCount];
        blobLen = new int[blobCount];
        for (int i = 0; i < blobCount; i++) {
            blobPos[i] = list.get(i)[0];
            blobLen[i] = (int) list.get(i)[1];
        }
        return sorted;
    }

    private static boolean headerSorted(byte[] b) {
        Pb p = new Pb(b, 0, b.length);
        while (p.more()) {
            int k = p.key();
            if ((k >>> 3) == 4 || (k >>> 3) == 5) { // required / optional features
                if (p.string().equals("Sort.Type_then_ID")) return true;
            } else {
                p.skip(k);
            }
        }
        return false;
    }

    private byte[] readBlob(RandomAccessFile f, long pos, int size) throws IOException {
        byte[] raw = new byte[size];
        f.seek(pos);
        f.readFully(raw);
        Pb p = new Pb(raw, 0, size);
        int rawSize = 0, zs = -1, zl = 0;
        byte[] plain = null;
        while (p.more()) {
            int k = p.key();
            int field = k >>> 3;
            if (field == 1) { // raw
                int l = (int) p.varint();
                plain = Arrays.copyOfRange(raw, p.pos, p.pos + l);
                p.pos += l;
            } else if (field == 2) {
                rawSize = (int) p.varint();
            } else if (field == 3) { // zlib
                zl = (int) p.varint();
                zs = p.pos;
                p.pos += zl;
            } else if (field >= 4 && field <= 7) {
                throw new IOException("unsupported PBF compression (field " + field + ")");
            } else {
                p.skip(k);
            }
        }
        if (plain != null) return plain;
        if (zs < 0) throw new IOException("empty blob");
        byte[] out = new byte[rawSize];
        inflater.reset();
        inflater.setInput(raw, zs, zl);
        try {
            int n = 0;
            while (n < rawSize) {
                int r = inflater.inflate(out, n, rawSize - n);
                if (r == 0 && (inflater.finished() || inflater.needsInput())) break;
                n += r;
            }
        } catch (DataFormatException e) {
            throw new IOException("broken PBF block: " + e.getMessage());
        }
        return out;
    }

    // ------------------------------------------------------------------ primitive blocks

    /** A decoded PrimitiveBlock: string table, coordinate scaling and the group byte ranges. */
    private static final class Block {
        final byte[] b;
        String[] strings = new String[0];
        long granularity = 100, latOffset, lonOffset;
        final List<int[]> groups = new ArrayList<>(); // {start, end}

        Block(byte[] b) {
            this.b = b;
            Pb p = new Pb(b, 0, b.length);
            while (p.more()) {
                int k = p.key();
                int field = k >>> 3;
                if (field == 1) {
                    int l = (int) p.varint();
                    List<String> s = new ArrayList<>();
                    Pb sp = new Pb(b, p.pos, p.pos + l);
                    while (sp.more()) {
                        int sk = sp.key();
                        if ((sk >>> 3) == 1) s.add(sp.string());
                        else sp.skip(sk);
                    }
                    strings = s.toArray(new String[0]);
                    p.pos += l;
                } else if (field == 2) {
                    int l = (int) p.varint();
                    groups.add(new int[]{p.pos, p.pos + l});
                    p.pos += l;
                } else if (field == 17) {
                    granularity = p.varint();
                } else if (field == 19) {
                    latOffset = p.varint();
                } else if (field == 20) {
                    lonOffset = p.varint();
                } else {
                    p.skip(k);
                }
            }
        }

        /** 0 nodes, 1 ways, 2 relations / other, by the first group's content. */
        int kind() {
            for (int[] g : groups) {
                Pb p = new Pb(b, g[0], g[1]);
                if (!p.more()) continue;
                int field = p.key() >>> 3;
                return field <= 2 ? 0 : field == 3 ? 1 : 2;
            }
            return 2;
        }

        /** Degrees * 1e7 from a raw PBF coordinate. */
        int lat7(long raw) {
            return (int) Math.round((latOffset + granularity * raw) / 100.0);
        }

        int lon7(long raw) {
            return (int) Math.round((lonOffset + granularity * raw) / 100.0);
        }
    }

    private Block block(RandomAccessFile f, int i) throws IOException {
        return new Block(readBlob(f, blobPos[i], blobLen[i]));
    }

    /** First blob whose content is at least of the kind (binary search, file sorted by type). */
    private int firstBlobOf(int kind) throws IOException {
        int lo = 0, hi = blobCount;
        try (RandomAccessFile f = new RandomAccessFile(pbf, "r")) {
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (block(f, mid).kind() < kind) lo = mid + 1;
                else hi = mid;
            }
        }
        return lo;
    }

    // ------------------------------------------------------------------ pass 1: ways

    private int readWays(int from, boolean sorted, File out) throws Exception {
        int roads = 0;
        try (RandomAccessFile f = new RandomAccessFile(pbf, "r");
             DataOutputStream w = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(out), 1 << 16))) {
            for (int i = from; i < blobCount; i++) {
                check();
                if (i % 50 == 0) progress.status(String.format(java.util.Locale.ROOT,
                        "Reading roads… %d%% · %d roads", (i - from) * 100 / Math.max(1, blobCount - from), roads));
                Block bl = block(f, i);
                if (sorted && bl.kind() == 2) break; // relations follow: no more ways
                for (int[] g : bl.groups) {
                    Pb p = new Pb(bl.b, g[0], g[1]);
                    while (p.more()) {
                        int k = p.key();
                        if ((k >>> 3) == 3) {
                            int l = (int) p.varint();
                            int n = way(bl, p.pos, p.pos + l, w);
                            if (n > 0) {
                                roads++;
                                totalRefs += n;
                            }
                            p.pos += l;
                        } else {
                            p.skip(k);
                        }
                    }
                }
            }
        }
        return roads;
    }

    /** Writes the way to the temporary file if it is a road; returns its number of points (0: no road). */
    private static int way(Block bl, int start, int end, DataOutputStream w) throws IOException {
        Pb p = new Pb(bl.b, start, end);
        long id = 0;
        int keysS = -1, keysE = -1, valsS = -1, valsE = -1, refsS = -1, refsE = -1;
        while (p.more()) {
            int k = p.key();
            int field = k >>> 3;
            if (field == 1) {
                id = p.varint();
            } else if (field == 2 || field == 3 || field == 8) {
                int l = (int) p.varint();
                if (field == 2) { keysS = p.pos; keysE = p.pos + l; }
                else if (field == 3) { valsS = p.pos; valsE = p.pos + l; }
                else { refsS = p.pos; refsE = p.pos + l; }
                p.pos += l;
            } else {
                p.skip(k);
            }
        }
        if (keysS < 0 || refsS < 0) return 0;
        String highway = null, name = "", ref = "", oneway = "", junction = "";
        Pb kp = new Pb(bl.b, keysS, keysE), vp = new Pb(bl.b, valsS, valsE);
        while (kp.more() && vp.more()) {
            String key = bl.strings[(int) kp.varint()];
            String val = bl.strings[(int) vp.varint()];
            switch (key) {
                case "highway": highway = val; break;
                case "name": name = val; break;
                case "ref": ref = val; break;
                case "oneway": oneway = val; break;
                case "junction": junction = val; break;
                default: break;
            }
        }
        if (highway == null || !HIGHWAYS.contains(highway)) return 0;
        Pb rp = new Pb(bl.b, refsS, refsE);
        LongList nodes = new LongList(16);
        long node = 0;
        while (rp.more()) {
            node += rp.sint();
            nodes.add(node);
        }
        if (nodes.size < 2) return 0;
        w.writeLong(id);
        w.writeUTF(highway);
        w.writeUTF(name);
        w.writeUTF(ref);
        w.writeUTF(oneway);
        w.writeUTF(junction);
        w.writeInt(nodes.size);
        for (int i = 0; i < nodes.size; i++) w.writeLong(nodes.a[i]);
        return nodes.size;
    }

    /** Way record of the temporary file without its point ids (read separately). */
    private static final class WayHead {
        long id;
        String highway, name, ref, oneway, junction;
        int n;

        WayHead read(DataInputStream in) throws IOException {
            id = in.readLong();
            highway = in.readUTF();
            name = in.readUTF();
            ref = in.readUTF();
            oneway = in.readUTF();
            junction = in.readUTF();
            n = in.readInt();
            return this;
        }
    }

    private static DataInputStream in(File f) throws IOException {
        return new DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 16));
    }

    private static DataOutputStream out(File f) throws IOException {
        return new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f), 1 << 16));
    }

    // ------------------------------------------------------------------ pass 2: coordinates in rounds

    /**
     * Fills a coordinate file (lat7, lon7 per road point, in the order of the ways file). Round k
     * handles the node ids with id % rounds == k, so each round's id list fits in memory.
     */
    private File resolve(File waysFile, int roads, int nodeBlobs) throws Exception {
        int rounds = (int) Math.max(1, (totalRefs + roundRefs - 1) / roundRefs);
        File coords = null;
        WayHead h = new WayHead();
        for (int k = 0; k < rounds; k++) {
            // the node blobs are read once: look for cameras then (the other rounds read them again)
            scanCameras = k == 0;
            String round = rounds > 1 ? " (round " + (k + 1) + "/" + rounds + ")" : "";
            progress.status("Collecting road points" + round + "…");
            LongList ids = new LongList((int) Math.min(Integer.MAX_VALUE - 16, totalRefs / rounds + totalRefs / 20 + 16));
            try (DataInputStream w = in(waysFile)) {
                for (int r = 0; r < roads; r++) {
                    h.read(w);
                    for (int i = 0; i < h.n; i++) {
                        long id = w.readLong();
                        if (Math.floorMod(id, rounds) == k) ids.add(id);
                    }
                }
            }
            check();
            need = ids.sortedUnique();
            lat7 = new int[need.length];
            lon7 = new int[need.length];
            Arrays.fill(lat7, MISSING);
            hint = 0;
            readNodes(nodeBlobs, round);
            // merge this round's coordinates into the coordinate file
            File next = new File(tmpDir, "coords" + k + ".bin");
            try (DataInputStream w = in(waysFile);
                 DataInputStream old = coords == null ? null : in(coords);
                 DataOutputStream c = out(next)) {
                for (int r = 0; r < roads; r++) {
                    h.read(w);
                    for (int i = 0; i < h.n; i++) {
                        long id = w.readLong();
                        int la = MISSING, lo = MISSING;
                        if (old != null) {
                            la = old.readInt();
                            lo = old.readInt();
                        }
                        if (Math.floorMod(id, rounds) == k) {
                            int j = Arrays.binarySearch(need, id);
                            if (j >= 0) {
                                la = lat7[j];
                                lo = lon7[j];
                            }
                        }
                        c.writeInt(la);
                        c.writeInt(lo);
                    }
                }
            }
            if (coords != null) coords.delete();
            coords = next;
            need = null;
            lat7 = lon7 = null;
        }
        return coords;
    }

    // ------------------------------------------------------------------ pass 2: nodes

    private void readNodes(int until, String round) throws Exception {
        int found = 0;
        try (RandomAccessFile f = new RandomAccessFile(pbf, "r")) {
            for (int i = 0; i < until; i++) {
                check();
                if (i % 50 == 0) progress.status(String.format(java.util.Locale.ROOT,
                        "Reading coordinates%s… %d%%", round, i * 100 / Math.max(1, until)));
                Block bl = block(f, i);
                if (bl.kind() != 0 && until == blobCount) continue;
                for (int[] g : bl.groups) {
                    Pb p = new Pb(bl.b, g[0], g[1]);
                    while (p.more()) {
                        int k = p.key();
                        int field = k >>> 3;
                        if (field == 2) {
                            int l = (int) p.varint();
                            found += dense(bl, p.pos, p.pos + l);
                            p.pos += l;
                        } else if (field == 1) {
                            int l = (int) p.varint();
                            found += node(bl, p.pos, p.pos + l);
                            p.pos += l;
                        } else {
                            p.skip(k);
                        }
                    }
                }
            }
        }
        if (found < need.length) progress.status("Note: " + (need.length - found) + " road points without coordinates");
    }

    private int hint; // nodes come sorted: continue the search where the last id was

    private int store(long id, int la, int lo) {
        int i;
        if (hint < need.length && need[hint] == id) {
            i = hint;
        } else if (hint < need.length && id > need[hint]) {
            i = Arrays.binarySearch(need, hint, need.length, id);
        } else {
            i = Arrays.binarySearch(need, id);
        }
        if (i < 0) {
            hint = Math.min(need.length, -i - 1);
            return 0;
        }
        hint = i + 1;
        lat7[i] = la;
        lon7[i] = lo;
        return 1;
    }

    private int dense(Block bl, int start, int end) {
        Pb p = new Pb(bl.b, start, end);
        int idS = -1, idE = 0, laS = -1, laE = 0, loS = -1, loE = 0, kvS = -1, kvE = 0;
        while (p.more()) {
            int k = p.key();
            int field = k >>> 3;
            if ((k & 7) == 2) {
                int l = (int) p.varint();
                if (field == 1) { idS = p.pos; idE = p.pos + l; }
                else if (field == 8) { laS = p.pos; laE = p.pos + l; }
                else if (field == 9) { loS = p.pos; loE = p.pos + l; }
                else if (field == 10) { kvS = p.pos; kvE = p.pos + l; }   // tags of the dense nodes
                p.pos += l;
            } else {
                p.skip(k);
            }
        }
        if (idS < 0 || laS < 0 || loS < 0) return 0;
        Pb ip = new Pb(bl.b, idS, idE), ap = new Pb(bl.b, laS, laE), op = new Pb(bl.b, loS, loE);
        Pb kp = scanCameras && kvS >= 0 ? new Pb(bl.b, kvS, kvE) : null;
        long id = 0, la = 0, lo = 0;
        int n = 0;
        while (ip.more()) {
            id += ip.sint();
            la += ap.sint();
            lo += op.sint();
            int la7 = bl.lat7(la), lo7 = bl.lon7(lo);
            n += store(id, la7, lo7);
            if (kp != null) denseTags(bl, kp, id, la7, lo7);
        }
        return n;
    }

    private int node(Block bl, int start, int end) {
        Pb p = new Pb(bl.b, start, end);
        long id = 0, la = 0, lo = 0;
        int keysS = -1, keysE = 0, valsS = -1, valsE = 0;
        while (p.more()) {
            int k = p.key();
            int field = k >>> 3;
            if (field == 1) id = p.sint();
            else if (field == 8) la = p.sint();
            else if (field == 9) lo = p.sint();
            else if ((k & 7) == 2 && (field == 2 || field == 3)) {
                int l = (int) p.varint();
                if (field == 2) { keysS = p.pos; keysE = p.pos + l; }
                else { valsS = p.pos; valsE = p.pos + l; }
                p.pos += l;
            } else {
                p.skip(k);
            }
        }
        int la7 = bl.lat7(la), lo7 = bl.lon7(lo);
        if (scanCameras && keysS >= 0 && valsS >= 0) {
            cameraTags(bl, new Pb(bl.b, keysS, keysE), new Pb(bl.b, valsS, valsE), id, la7, lo7);
        }
        return store(id, la7, lo7);
    }

    // ------------------------------------------------------------------ speed cameras

    /**
     * One dense node's tags: string ids as key/value pairs one after the other, a 0 ending the node.
     * Every node of the block has its entry, also the ones without any tag.
     */
    private void denseTags(Block bl, Pb kp, long id, int lat7, int lon7) {
        int[] keys = new int[8], vals = new int[8];
        int n = 0;
        while (kp.more()) {
            long k = kp.varint();
            if (k == 0) break;                      // next node
            if (!kp.more()) break;
            long v = kp.varint();
            if (n == keys.length) {
                keys = Arrays.copyOf(keys, n * 2);
                vals = Arrays.copyOf(vals, n * 2);
            }
            keys[n] = (int) k;
            vals[n] = (int) v;
            n++;
        }
        cameraTags(bl, keys, vals, n, id, lat7, lon7);
    }

    /**
     * Keeps the node when its tags say it is a camera: OpenStreetMap has fixed ones as
     * {@code highway=speed_camera}, red-light ones as {@code camera:type=red_light} (or
     * {@code enforcement=traffic_signals}), and sections as {@code camera:type=average_speed}.
     */
    private void cameraTags(Block bl, int[] keys, int[] vals, int n, long id, int lat7, int lon7) {
        String highway = null, type = null, enforcement = null, maxspeed = null;
        for (int i = 0; i < n; i++) {
            if (keys[i] < 0 || keys[i] >= bl.strings.length || vals[i] < 0 || vals[i] >= bl.strings.length) continue;
            String key = bl.strings[keys[i]], val = bl.strings[vals[i]];
            switch (key) {
                case "highway": highway = val; break;
                case "camera:type": type = val; break;
                case "enforcement": enforcement = val; break;
                case "maxspeed": maxspeed = val; break;
                default: break;
            }
        }
        String kind = null;
        if ("speed_camera".equals(highway)) kind = type != null ? type : "fixed";
        else if (type != null) kind = type;
        else if ("maxspeed".equals(enforcement)) kind = "fixed";
        else if ("traffic_signals".equals(enforcement)) kind = "red_light";
        if (kind == null || kind.isEmpty()) return;
        if (!region.contains(lat7 / 1e7, lon7 / 1e7)) return;
        cameras.put(id, new Hazards.Camera(id, lat7, lon7, kind, maxspeedKmh(maxspeed)));
    }

    /** Key/value ids of a plain node, without separators. */
    private void cameraTags(Block bl, Pb kp, Pb vp, long id, int lat7, int lon7) {
        int[] keys = new int[8], vals = new int[8];
        int n = 0;
        while (kp.more() && vp.more()) {
            if (n == keys.length) {
                keys = Arrays.copyOf(keys, n * 2);
                vals = Arrays.copyOf(vals, n * 2);
            }
            keys[n] = (int) kp.varint();
            vals[n] = (int) vp.varint();
            n++;
        }
        cameraTags(bl, keys, vals, n, id, lat7, lon7);
    }

    /** "50" or "50 km/h" → 50; "50;70" → 50; anything else → 0. */
    static int maxspeedKmh(String s) {
        if (s == null) return 0;
        int i = 0;
        while (i < s.length() && !Character.isDigit(s.charAt(i))) i++;
        int v = 0, digits = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i)) && digits < 4) {
            v = v * 10 + (s.charAt(i) - '0');
            i++;
            digits++;
        }
        return v;
    }

    // ------------------------------------------------------------------ tiles

    private static int tileOf7(int v7) {
        return (int) Math.floor(v7 / 1e7 / RoadStore.TILE_DEG);
    }

    /** Sorts the roads into bucket files of {@link #BAND_ROWS} tile rows, then writes each band's tiles. */
    private int[] writeTiles(File waysFile, File coordsFile, int roads) throws Exception {
        Map<Integer, DataOutputStream> buckets = new HashMap<>();
        int done = 0;
        WayHead h = new WayHead();
        try (DataInputStream in = in(waysFile); DataInputStream cin = in(coordsFile)) {
            for (int r = 0; r < roads; r++) {
                if (r % 20000 == 0) {
                    check();
                    progress.status(String.format(java.util.Locale.ROOT, "Sorting roads into tiles… %d%%", r * 100 / Math.max(1, roads)));
                }
                h.read(in);
                int n = h.n;
                long[] ids = new long[n];
                int[] la = new int[n], lo = new int[n];
                int m = 0;
                for (int i = 0; i < n; i++) {
                    long node = in.readLong();
                    int a = cin.readInt(), o = cin.readInt();
                    if (a == MISSING) continue; // outside the extract
                    ids[m] = node;
                    la[m] = a;
                    lo[m] = o;
                    m++;
                }
                if (m < 2) continue;
                byte[] line = tsv(h.id, h.highway, h.name, h.ref, h.oneway, h.junction, ids, la, lo, m)
                        .getBytes(StandardCharsets.UTF_8);
                // tiles touched, grouped by band
                Map<Integer, Set<Long>> perBand = new HashMap<>();
                for (int i = 0; i < m; i++) {
                    int ty = tileOf7(la[i]), tx = tileOf7(lo[i]);
                    perBand.computeIfAbsent(Math.floorDiv(ty, BAND_ROWS), x -> new HashSet<>()).add(key(ty, tx));
                }
                for (Map.Entry<Integer, Set<Long>> e : perBand.entrySet()) {
                    DataOutputStream b = buckets.get(e.getKey());
                    if (b == null) {
                        // compressed (fast level): a country's roads as text would need GBs
                        b = new DataOutputStream(new BufferedOutputStream(new java.util.zip.DeflaterOutputStream(
                                new FileOutputStream(new File(tmpDir, "band" + e.getKey() + ".bin")),
                                new java.util.zip.Deflater(1), 1 << 15), 1 << 15));
                        buckets.put(e.getKey(), b);
                    }
                    b.writeInt(e.getValue().size());
                    for (long t : e.getValue()) b.writeLong(t);
                    b.writeInt(line.length);
                    b.write(line);
                }
                done++;
            }
        } finally {
            for (DataOutputStream b : buckets.values()) b.close();
        }
        waysFile.delete();
        coordsFile.delete();

        // every band of the region, also those without roads (empty tiles inside are complete too)
        double[] bb = region.bbox(); // minLat, minLon, maxLat, maxLon
        int ty0 = RoadStore.tileY(bb[0]), ty1 = RoadStore.tileY(bb[2]);
        int tx0 = RoadStore.tileX(bb[1]), tx1 = RoadStore.tileX(bb[3]);
        int complete = 0, partial = 0;
        int b0 = Math.floorDiv(ty0, BAND_ROWS), b1 = Math.floorDiv(ty1, BAND_ROWS);
        Set<Integer> bands = new HashSet<>(buckets.keySet());
        for (int b = b0; b <= b1; b++) bands.add(b);
        List<Integer> order = new ArrayList<>(bands);
        java.util.Collections.sort(order);
        int bi = 0;
        for (int band : order) {
            check();
            progress.status(String.format(java.util.Locale.ROOT, "Writing tiles… %d%%", bi++ * 100 / Math.max(1, order.size())));
            Map<Long, List<String>> tiles = new HashMap<>();
            File bf = new File(tmpDir, "band" + band + ".bin");
            if (bf.exists()) {
                try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                        new java.util.zip.InflaterInputStream(new FileInputStream(bf)), 1 << 16))) {
                    while (true) {
                        int nt;
                        try {
                            nt = in.readInt();
                        } catch (EOFException eof) {
                            break;
                        }
                        long[] ts = new long[nt];
                        for (int i = 0; i < nt; i++) ts[i] = in.readLong();
                        byte[] line = new byte[in.readInt()];
                        in.readFully(line);
                        String s = new String(line, StandardCharsets.UTF_8);
                        for (long t : ts) tiles.computeIfAbsent(t, x -> new ArrayList<>()).add(s);
                    }
                }
                bf.delete();
            }
            // complete tiles: inside the border; written even without roads
            for (int ty = Math.max(ty0, band * BAND_ROWS); ty <= Math.min(ty1, band * BAND_ROWS + BAND_ROWS - 1); ty++) {
                for (int tx = tx0; tx <= tx1; tx++) {
                    if (region.containsTile(ty, tx)) tiles.putIfAbsent(key(ty, tx), new ArrayList<>());
                }
            }
            for (Map.Entry<Long, List<String>> e : tiles.entrySet()) {
                int ty = (int) (e.getKey() >> 32), tx = (int) (long) e.getKey();
                if (region.containsTile(ty, tx)) {
                    store.writeTile(ty, tx, e.getValue(), false);
                    complete++;
                } else if (!store.has(ty, tx)) { // a complete tile stays; partial ones are merged
                    store.mergePartial(ty, tx, e.getValue());
                    partial++;
                }
            }
        }
        return new int[]{done, complete, partial};
    }

    private static long key(int ty, int tx) {
        return ((long) ty << 32) | (tx & 0xffffffffL);
    }

    private static String clean(String s) {
        return s.replace('\t', ' ').replace('\n', ' ');
    }

    /** Same line format as {@link RoadStore}. */
    private static String tsv(long id, String highway, String name, String ref, String oneway, String junction,
                              long[] ids, int[] la, int[] lo, int n) {
        StringBuilder sb = new StringBuilder(64 + n * 34);
        sb.append(id).append('\t').append(clean(highway)).append('\t').append(clean(name)).append('\t')
                .append(clean(ref)).append('\t').append(clean(oneway)).append('\t').append(clean(junction)).append('\t');
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append(ids[i]);
        }
        sb.append('\t');
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            deg6(sb, la[i]);
        }
        sb.append('\t');
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            deg6(sb, lo[i]);
        }
        return sb.append('\n').toString();
    }

    /** Appends 1e-7 degrees as a number with 6 decimals (like "%.6f", without String.format's cost). */
    static void deg6(StringBuilder sb, int v7) {
        long v = Math.round(v7 / 10.0);
        if (v < 0) {
            sb.append('-');
            v = -v;
        }
        sb.append(v / 1_000_000).append('.');
        String frac = Long.toString(v % 1_000_000);
        for (int i = frac.length(); i < 6; i++) sb.append('0');
        sb.append(frac);
    }

    // ------------------------------------------------------------------ helpers

    /** Minimal protobuf reader over a byte range. */
    static final class Pb {
        final byte[] b;
        int pos;
        final int end;

        Pb(byte[] b, int pos, int end) {
            this.b = b;
            this.pos = pos;
            this.end = end;
        }

        boolean more() {
            return pos < end;
        }

        int key() {
            return (int) varint();
        }

        long varint() {
            long r = 0;
            int shift = 0;
            while (true) {
                byte x = b[pos++];
                r |= (long) (x & 0x7f) << shift;
                if (x >= 0) return r;
                shift += 7;
            }
        }

        long sint() {
            long v = varint();
            return (v >>> 1) ^ -(v & 1);
        }

        String string() {
            int l = (int) varint();
            String s = new String(b, pos, l, StandardCharsets.UTF_8);
            pos += l;
            return s;
        }

        void skip(int key) {
            switch (key & 7) {
                case 0: varint(); break;
                case 1: pos += 8; break;
                case 2: { int l = (int) varint(); pos += l; break; }
                case 5: pos += 4; break;
                default: throw new IllegalStateException("bad protobuf wire type " + (key & 7));
            }
        }
    }

    static final class LongList {
        long[] a;
        int size;

        LongList(int cap) {
            a = new long[cap];
        }

        void add(long v) {
            if (size == a.length) a = Arrays.copyOf(a, a.length + (a.length >> 1) + 16);
            a[size++] = v;
        }

        long[] sortedUnique() {
            Arrays.sort(a, 0, size);
            int n = 0;
            for (int i = 0; i < size; i++) if (n == 0 || a[i] != a[n - 1]) a[n++] = a[i];
            long[] r = Arrays.copyOf(a, n);
            a = null;
            return r;
        }
    }

    /** Region border from a Geofabrik .poly file (rings of "lon lat"; rings starting with ! are holes). */
    public static final class Polygon {
        final List<double[]> rings = new ArrayList<>(); // x0,y0,x1,y1,... (lon, lat)

        public static Polygon read(File f) throws IOException {
            Polygon p = new Polygon();
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                r.readLine(); // region name
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.equals("END")) continue; // END of the file
                    List<Double> pts = new ArrayList<>();
                    while ((line = r.readLine()) != null && !line.trim().equals("END")) {
                        String[] s = line.trim().split("\\s+");
                        if (s.length < 2) continue;
                        pts.add(Double.parseDouble(s[0]));
                        pts.add(Double.parseDouble(s[1]));
                    }
                    double[] ring = new double[pts.size()];
                    for (int i = 0; i < ring.length; i++) ring[i] = pts.get(i);
                    if (ring.length >= 6) p.rings.add(ring);
                }
            }
            if (p.rings.isEmpty()) throw new IOException("empty region border");
            return p;
        }

        /** Even-odd rule over all rings, so holes work too. */
        public boolean contains(double lat, double lon) {
            boolean in = false;
            for (double[] r : rings) {
                int n = r.length / 2;
                for (int i = 0, j = n - 1; i < n; j = i++) {
                    double xi = r[2 * i], yi = r[2 * i + 1], xj = r[2 * j], yj = r[2 * j + 1];
                    if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) in = !in;
                }
            }
            return in;
        }

        /** Whether the whole tile lies inside: all corners inside and no border point within it. */
        public boolean containsTile(int ty, int tx) {
            double s = ty * RoadStore.TILE_DEG, w = tx * RoadStore.TILE_DEG;
            double n = s + RoadStore.TILE_DEG, e = w + RoadStore.TILE_DEG;
            if (!contains(s, w) || !contains(s, e) || !contains(n, w) || !contains(n, e)) return false;
            for (double[] r : rings) {
                for (int i = 0; i < r.length; i += 2) {
                    if (r[i] >= w && r[i] <= e && r[i + 1] >= s && r[i + 1] <= n) return false;
                }
            }
            return true;
        }

        /** {minLat, minLon, maxLat, maxLon} */
        public double[] bbox() {
            double[] b = {90, 180, -90, -180};
            for (double[] r : rings) {
                for (int i = 0; i < r.length; i += 2) {
                    b[0] = Math.min(b[0], r[i + 1]);
                    b[1] = Math.min(b[1], r[i]);
                    b[2] = Math.max(b[2], r[i + 1]);
                    b[3] = Math.max(b[3], r[i]);
                }
            }
            return b;
        }
    }
}
