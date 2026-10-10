package io.github.darthbob.kawanav;

import android.content.Context;


import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * On-device OpenStreetMap road storage in fixed tiles of {@link #TILE_DEG} degrees
 * (about 5.5 x 3.7 km in Germany), one gzipped TSV file per tile. A way is stored in every tile it
 * touches, with its full geometry. Tiles are only written by the offline region download
 * ({@link PbfImporter}); an empty file marks a tile without roads.
 *
 * A "partial" tile (ty_tx.part.tsv.gz) lies on the border of an imported region and only holds
 * the roads of the regions downloaded so far (neighbour regions merge into it).
 */
public final class RoadStore {

    public static final double TILE_DEG = 0.05;

    private final File dir;
    /** Keys ("ty_tx") of the tiles written while recording, see {@link #record}. */
    private Set<String> recorded;

    /** For desktop tests of the importer. */
    RoadStore(File dir) {
        this.dir = dir;
        if (!dir.isDirectory()) dir.mkdirs();
    }

    public RoadStore(Context context) {
        dir = new File(context.getApplicationContext().getFilesDir(), "tiles");
        if (!dir.isDirectory()) dir.mkdirs();
        // the older per-position cache (files/roads/*.json) is replaced by tiles
        File old = new File(context.getApplicationContext().getFilesDir(), "roads");
        File[] olds = old.listFiles();
        if (olds != null) for (File f : olds) f.delete();
        old.delete();
    }

    // ------------------------------------------------------------------ tile math

    public static int tileY(double lat) {
        return (int) Math.floor(lat / TILE_DEG);
    }

    public static int tileX(double lon) {
        return (int) Math.floor(lon / TILE_DEG);
    }

    private File file(int ty, int tx) {
        return new File(dir, ty + "_" + tx + ".tsv.gz");
    }

    private File partFile(int ty, int tx) {
        return new File(dir, ty + "_" + tx + ".part.tsv.gz");
    }

    /** A complete tile is stored (live download or inside an imported region). */
    public boolean has(int ty, int tx) {
        return file(ty, tx).exists();
    }

    /** Complete or partial (region border) data is stored. */
    public boolean hasAny(int ty, int tx) {
        return has(ty, tx) || partFile(ty, tx).exists();
    }

    // ------------------------------------------------------------------ read / write

    /** Ways of the tile: complete data if stored, else the partial data of a region border. */
    public List<RoadNetwork.RawWay> load(int ty, int tx) {
        List<RoadNetwork.RawWay> ways = new ArrayList<>();
        File f = has(ty, tx) ? file(ty, tx) : partFile(ty, tx);
        for (String line : readLines(f)) {
            RoadNetwork.RawWay w = fromTsv(line);
            if (w != null) ways.add(w);
        }
        return ways;
    }

    private static List<String> readLines(File f) {
        List<String> lines = new ArrayList<>();
        if (!f.exists() || f.length() == 0) return lines;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new FileInputStream(f)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) lines.add(line);
        } catch (Exception e) {
            Logger.log("Tile " + f.getName() + " unreadable, deleted (download the region again): " + e);
            f.delete();
        }
        return lines;
    }

    /**
     * Stores the tile's ways (TSV lines, one per way, ending in a newline or not). A complete tile
     * replaces any partial data.
     */
    public void writeTile(int ty, int tx, java.util.Collection<String> lines, boolean partial) throws Exception {
        File target = partial ? partFile(ty, tx) : file(ty, tx);
        File tmp = new File(dir, ty + "_" + tx + (partial ? ".part" : "") + ".tmp");
        if (lines.isEmpty()) {
            new FileOutputStream(tmp).close();
        } else {
            try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                    new GZIPOutputStream(new FileOutputStream(tmp), 1 << 15), StandardCharsets.UTF_8), 1 << 15)) {
                for (String l : lines) {
                    w.write(l);
                    if (!l.endsWith("\n")) w.write('\n');
                }
            }
        }
        if (!tmp.renameTo(target)) throw new Exception("cannot store tile " + target.getName());
        if (!partial) partFile(ty, tx).delete();
        if (recorded != null) recorded.add(key(ty, tx));
    }

    /** From now on, adds the key of every tile written to {@code into} (null: stop). */
    public void record(Set<String> into) {
        recorded = into;
    }

    /** Adds ways to the partial data of a border tile (e.g. the neighbour region's half). */
    public void mergePartial(int ty, int tx, List<String> lines) throws Exception {
        Map<String, String> byId = new java.util.LinkedHashMap<>();
        for (String l : readLines(partFile(ty, tx))) byId.put(l.substring(0, Math.max(0, l.indexOf('\t'))), l);
        for (String l : lines) byId.put(l.substring(0, Math.max(0, l.indexOf('\t'))), l);
        writeTile(ty, tx, byId.values(), true);
    }

    /** Ways of several tiles, each way once. */
    public List<RoadNetwork.RawWay> loadAll(List<int[]> tiles) {
        Map<Long, RoadNetwork.RawWay> byId = new HashMap<>();
        for (int[] t : tiles) {
            for (RoadNetwork.RawWay w : load(t[0], t[1])) byId.put(w.id, w);
        }
        return new ArrayList<>(byId.values());
    }

    private static RoadNetwork.RawWay fromTsv(String line) {
        String[] f = line.split("\t", -1);
        if (f.length < 9) return null;
        String[] n = f[6].split(","), la = f[7].split(","), lo = f[8].split(",");
        if (n.length != la.length || n.length != lo.length) return null;
        long[] nodes = new long[n.length];
        double[] lats = new double[n.length], lons = new double[n.length];
        for (int i = 0; i < n.length; i++) {
            nodes[i] = Long.parseLong(n[i]);
            lats[i] = Double.parseDouble(la[i]);
            lons[i] = Double.parseDouble(lo[i]);
        }
        return new RoadNetwork.RawWay(Long.parseLong(f[0]), nodes, lats, lons, f[1], f[2], f[3], f[4], f[5]);
    }

    // ------------------------------------------------------------------ tiles by key

    static String key(int ty, int tx) {
        return ty + "_" + tx;
    }

    /** Keys of all stored tiles, complete or partial. */
    public Set<String> keys() {
        Set<String> keys = new HashSet<>();
        String[] names = dir.list();
        if (names == null) return keys;
        for (String n : names) {
            if (n.endsWith(".tsv.gz")) keys.add(n.substring(0, n.indexOf('.')));
        }
        return keys;
    }

    /** Bytes stored for the tile (complete and partial file). */
    public long bytes(String key) {
        return new File(dir, key + ".tsv.gz").length() + new File(dir, key + ".part.tsv.gz").length();
    }

    public void delete(String key) {
        new File(dir, key + ".tsv.gz").delete();
        new File(dir, key + ".part.tsv.gz").delete();
    }
}
