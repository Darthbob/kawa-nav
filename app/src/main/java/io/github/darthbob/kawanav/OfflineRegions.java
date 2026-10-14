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

        Region(String id, String label, String pbfUrl, long dataDate, Set<String> tiles, boolean hazards) {
            this.id = id;
            this.label = label;
            this.pbfUrl = pbfUrl;
            this.dataDate = dataDate;
            this.tiles = tiles;
            this.hazards = hazards;
        }
    }

    private final File dir;
    private final RoadStore store;
    private final HazardStore hazards;

    public OfflineRegions(Context context, RoadStore store, HazardStore hazards) {
        this.dir = new File(context.getApplicationContext().getFilesDir(), "regions");
        if (!dir.isDirectory()) dir.mkdirs();
        this.store = store;
        this.hazards = hazards;
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
                out.add(new Region(o.getString("id"), o.getString("label"), o.getString("pbfUrl"),
                        o.getLong("dataDate"), tiles, o.optBoolean("hazards", false)));
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
                                 boolean hazards) throws Exception {
        Region old = find(id);
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("label", label);
        o.put("pbfUrl", pbfUrl);
        o.put("dataDate", dataDate);
        o.put("tiles", new JSONArray(tiles));
        o.put("hazards", hazards);
        File tmp = new File(dir, id + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(new File(dir, id + ".json"))) throw new Exception("cannot store the region record");
        if (old != null) {
            Set<String> gone = new HashSet<>(old.tiles);
            gone.removeAll(tiles);
            deleteUnused(gone);
        }
    }

    /** Removes the region and its tiles that no other region uses. */
    public synchronized void delete(String id) {
        Region r = find(id);
        new File(dir, id + ".json").delete();
        if (r != null) deleteUnused(r.tiles);
        Logger.log("Offline region deleted: " + (r != null ? r.label : id));
    }

    /** Tiles that belong to no downloaded region (older downloads, cancelled imports). */
    public synchronized Set<String> unassigned() {
        Set<String> keys = store.keys();
        for (Region r : list()) keys.removeAll(r.tiles);
        return keys;
    }

    public synchronized void deleteUnassigned() {
        Set<String> keys = unassigned();
        for (String k : keys) {
            store.delete(k);
            if (hazards != null) hazards.delete(k);   // camera tiles use the same keys
        }
        Logger.log("Deleted " + keys.size() + " tiles of no region");
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
