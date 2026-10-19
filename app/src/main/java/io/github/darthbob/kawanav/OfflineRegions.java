package io.github.darthbob.kawanav;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The downloaded regions: one record per region (files/regions/&lt;id&gt;.json) with its data
 * date and the keys of the tiles its import wrote. Regions overlap (a state inside its country)
 * and share border tiles, so a tile is only deleted when no remaining region uses it.
 */
public final class OfflineRegions {

    /** Geofabrik rebuilds every extract daily: an update is offered once the data is this much newer. */
    public static final long UPDATE_AFTER_MS = 30L * 24 * 3600_000;

    public static final class Region {
        public final String id, label, pbfUrl;
        /** Date of the downloaded data (the extract's Last-Modified), ms. */
        public final long dataDate;
        public final Set<String> tiles;
        /** The import looked for speed cameras too (regions from before that have none stored). */
        public final boolean hazards;
        /** The camera tiles this region's import wrote, so a re-download can drop the ones it no longer has. */
        public final Set<String> hazardTiles;

        Region(String id, String label, String pbfUrl, long dataDate, Set<String> tiles, boolean hazards,
               Set<String> hazardTiles) {
            this.id = id;
            this.label = label;
            this.pbfUrl = pbfUrl;
            this.dataDate = dataDate;
            this.tiles = tiles;
            this.hazards = hazards;
            this.hazardTiles = hazardTiles;
        }
    }

    private final File dir;
    private final RoadStore store;
    private final HazardStore hazards;

    public OfflineRegions(Context context, RoadStore store, HazardStore hazards) {
        this(new File(context.getApplicationContext().getFilesDir(), "regions"), store, hazards);
    }

    /** Plain-directory constructor, so the record files can be tested without a phone. */
    OfflineRegions(File dir, RoadStore store, HazardStore hazards) {
        this.dir = dir;
        if (!dir.isDirectory()) dir.mkdirs();
        this.store = store;
        this.hazards = hazards;
    }

    /**
     * A region id may contain a path separator — Geofabrik's ids do, e.g. {@code us/alabama} — so it can
     * never be used as a file name: {@code us/alabama.poly} would need a directory that is not there, and
     * {@code regions/us/alabama.json} would be invisible to {@link #list()}, which reads one flat folder.
     * The real id stays inside the record; only the file name is folded.
     */
    public static String safeKey(String id) {
        if (id == null) return "";
        String key = id.replace('/', '_').replace('\\', '_').replace("..", "");
        // a dot at either end names the folder itself ("." → ".json"), so it carries nothing
        while (!key.isEmpty() && (key.charAt(0) == '.' || key.charAt(key.length() - 1) == '.')) {
            key = key.charAt(0) == '.' ? key.substring(1) : key.substring(0, key.length() - 1);
        }
        return key;
    }

    /** All downloaded regions, sorted by label. */
    public synchronized List<Region> list() {
        List<Region> out = new ArrayList<>();
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (fs == null) return out;
        for (File f : fs) {
            try {
                JSONObject o = new JSONObject(read(f));
                JSONArray t = o.getJSONArray("tiles");
                Set<String> tiles = new HashSet<>(t.length() * 2);
                for (int i = 0; i < t.length(); i++) tiles.add(t.getString(i));
                JSONArray h = o.optJSONArray("hazardTiles");
                Set<String> hazardTiles = new HashSet<>();
                if (h != null) for (int i = 0; i < h.length(); i++) hazardTiles.add(h.getString(i));
                out.add(new Region(o.getString("id"), o.getString("label"), o.getString("pbfUrl"),
                        o.getLong("dataDate"), tiles, o.optBoolean("hazards", false), hazardTiles));
            } catch (Exception e) {
                Logger.log("Region record " + f.getName() + " unreadable: " + e);
            }
        }
        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    /**
     * Stores the record of a finished (re)download. Tiles the old download of this region wrote and
     * the new one did not are deleted, unless another region uses them.
     */
    public synchronized void save(String id, String label, String pbfUrl, long dataDate, Set<String> tiles,
                                 boolean camerasScanned, Set<String> hazardTiles) throws Exception {
        Region old = find(id);
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("label", label);
        o.put("pbfUrl", pbfUrl);
        o.put("dataDate", dataDate);
        o.put("tiles", new JSONArray(tiles));
        o.put("hazards", camerasScanned);
        o.put("hazardTiles", new JSONArray(hazardTiles));
        String file = safeKey(id);
        File tmp = new File(dir, file + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(new File(dir, file + ".json"))) throw new Exception("cannot store the region record");
        if (old != null) {
            Set<String> gone = new HashSet<>(old.tiles);
            gone.removeAll(tiles);
            deleteUnused(gone);
            // a camera tile this download no longer writes is gone: the camera is not in the data any more
            Set<String> hazardGone = new HashSet<>(old.hazardTiles);
            hazardGone.removeAll(hazardTiles);
            for (String k : hazardGone) {
                if (!usedHazard(k)) hazards.delete(k);
            }
        }
    }

    /** Removes the region and its tiles that no other region uses. */
    public synchronized void delete(String id) {
        Region r = find(id);
        new File(dir, safeKey(id) + ".json").delete();
        if (r != null) {
            deleteUnused(r.tiles);
            for (String k : r.hazardTiles) if (!usedHazard(k)) hazards.delete(k);
        }
        Logger.log("Offline region deleted: " + (r != null ? r.label : id));
    }

    /** True while another downloaded region still writes camera data into that tile. */
    private boolean usedHazard(String key) {
        for (Region r : list()) if (r.hazardTiles.contains(key)) return true;
        return false;
    }

    /** Tiles that belong to no downloaded region (older downloads, cancelled imports). */
    public synchronized Set<String> unassigned() {
        Set<String> keys = store.keys();
        for (Region r : list()) keys.removeAll(r.tiles);
        return keys;
    }

    /** Camera tiles of no downloaded region (they are few and keyed like the road tiles). */
    private Set<String> unassignedHazards() {
        Set<String> keys = hazards.keys();
        for (Region r : list()) keys.removeAll(r.hazardTiles);
        return keys;
    }

    public synchronized void deleteUnassigned() {
        Set<String> keys = unassigned();
        for (String k : keys) store.delete(k);
        Set<String> orphanHazards = unassignedHazards();
        for (String k : orphanHazards) hazards.delete(k);
        Logger.log("Deleted " + keys.size() + " tiles and " + orphanHazards.size() + " camera tiles of no region");
    }

    public long bytes(Set<String> tiles) {
        long b = 0;
        for (String k : tiles) b += store.bytes(k);
        return b;
    }

    private Region find(String id) {
        for (Region r : list()) if (r.id.equals(id)) return r;
        return null;
    }

    private void deleteUnused(Set<String> candidates) {
        Set<String> used = new HashSet<>();
        for (Region r : list()) used.addAll(r.tiles);
        for (String k : candidates) {
            if (used.contains(k)) continue;
            store.delete(k);
            if (hazards != null) hazards.delete(k);
        }
    }

    private static String read(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[65536];
            int k;
            while ((k = in.read(b)) > 0) buf.write(b, 0, k);
            return buf.toString("UTF-8");
        }
    }
}
