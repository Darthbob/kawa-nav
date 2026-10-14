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
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Speed cameras from OpenStreetMap, in the same tiles as the roads ({@link RoadStore#TILE_DEG}, about
 * 5.5 x 3.7 km) but in their own folder: only tiles that hold a camera are written, and one line per
 * camera ({@code id lat7 lon7 type maxspeed}). Written by the offline region download
 * ({@link PbfImporter}), read by the curve engine while riding.
 *
 * Border tiles of a region are partial ({@code ty_tx.part.tsv.gz}) and merge with the neighbouring
 * region, like the road tiles do.
 */
public final class HazardStore {

    private final File dir;

    /** For desktop tests of the importer. */
    HazardStore(File dir) {
        this.dir = dir;
        if (!dir.isDirectory()) dir.mkdirs();
    }

    public HazardStore(Context context) {
        this(new File(context.getApplicationContext().getFilesDir(), "hazards"));
    }

    private File file(int ty, int tx) {
        return new File(dir, ty + "_" + tx + ".tsv.gz");
    }

    private File partFile(int ty, int tx) {
        return new File(dir, ty + "_" + tx + ".part.tsv.gz");
    }

    public boolean has(int ty, int tx) {
        return file(ty, tx).exists();
    }

    /** Complete or partial (region border) camera data is stored. */
    public boolean hasAny(int ty, int tx) {
        return has(ty, tx) || partFile(ty, tx).exists();
    }

    /** Cameras of the tile; complete data if stored, else the partial data of a region border. */
    public List<Hazards.Camera> load(int ty, int tx) {
        List<Hazards.Camera> cameras = new ArrayList<>();
        File f = has(ty, tx) ? file(ty, tx) : partFile(ty, tx);
        for (String line : readLines(f)) {
            Hazards.Camera c = fromTsv(line);
            if (c != null) cameras.add(c);
        }
        return cameras;
    }

    /** Cameras of several tiles, each once. */
    public List<Hazards.Camera> loadAll(List<int[]> tiles) {
        List<List<Hazards.Camera>> lists = new ArrayList<>();
        for (int[] t : tiles) lists.add(load(t[0], t[1]));
        return Hazards.merge(lists.toArray(new List[0]));
    }

    private static List<String> readLines(File f) {
        List<String> lines = new ArrayList<>();
        if (!f.exists() || f.length() == 0) return lines;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new FileInputStream(f)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) lines.add(line);
        } catch (Exception e) {
            Logger.log("Hazard tile " + f.getName() + " unreadable, deleted (download the region again): " + e);
            f.delete();
        }
        return lines;
    }

    /** Stores the tile's cameras. A complete tile replaces any partial data. */
    public void writeTile(int ty, int tx, java.util.Collection<String> lines, boolean partial) throws Exception {
        File target = partial ? partFile(ty, tx) : file(ty, tx);
        File tmp = new File(dir, ty + "_" + tx + (partial ? ".part" : "") + ".tmp");
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new GZIPOutputStream(new FileOutputStream(tmp), 1 << 15), StandardCharsets.UTF_8), 1 << 15)) {
            for (String l : lines) {
                w.write(l);
                if (!l.endsWith("\n")) w.write('\n');
            }
        }
        // replacing an existing tile: some filesystems refuse to rename onto it
        if (!tmp.renameTo(target) && !(target.delete() && tmp.renameTo(target))) {
            throw new Exception("cannot store hazard tile " + target.getName());
        }
        if (!partial) partFile(ty, tx).delete();
    }

    /** Adds cameras to the partial data of a border tile (e.g. the neighbour region's half). */
    public void mergePartial(int ty, int tx, List<String> lines) throws Exception {
        java.util.Map<String, String> byId = new java.util.LinkedHashMap<>();
        for (String l : readLines(partFile(ty, tx))) byId.put(l.substring(0, Math.max(0, l.indexOf('\t'))), l);
        for (String l : lines) byId.put(l.substring(0, Math.max(0, l.indexOf('\t'))), l);
        writeTile(ty, tx, byId.values(), true);
    }

    /**
     * Stores cameras by tile: a complete tile for the ones inside the region border, a partial
     * (merged) one for the border tiles that a neighbour region also covers. Only tiles that hold a
     * camera are written, so a missing tile simply means no camera there.
     *
     * @return {cameras stored, tiles written}
     */
    public int[] write(PbfImporter.Polygon region, List<Hazards.Camera> cameras) throws Exception {
        java.util.Map<Long, List<String>> byTile = new java.util.HashMap<>();
        for (Hazards.Camera c : cameras) {
            long k = ((long) RoadStore.tileY(c.lat()) << 32) | (RoadStore.tileX(c.lon()) & 0xffffffffL);
            byTile.computeIfAbsent(k, x -> new ArrayList<>()).add(tsv(c));
        }
        int tiles = 0;
        for (java.util.Map.Entry<Long, List<String>> e : byTile.entrySet()) {
            int ty = (int) (e.getKey() >> 32), tx = (int) (long) e.getKey();
            if (region.containsTile(ty, tx)) writeTile(ty, tx, e.getValue(), false);
            else mergePartial(ty, tx, e.getValue());
            tiles++;
        }
        return new int[]{cameras.size(), tiles};
    }

    /** One camera as a tile line. */
    public static String tsv(Hazards.Camera c) {
        return c.id + "\t" + c.lat7 + "\t" + c.lon7 + "\t" + c.type + "\t" + c.maxspeed;
    }

    private static Hazards.Camera fromTsv(String line) {
        String[] f = line.split("\t", -1);
        if (f.length < 4) return null;
        try {
            return new Hazards.Camera(Long.parseLong(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]),
                    f[3], f.length > 4 ? Integer.parseInt(f[4]) : 0);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public void delete(String key) {
        new File(dir, key + ".tsv.gz").delete();
        new File(dir, key + ".part.tsv.gz").delete();
    }
}
