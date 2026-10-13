package io.github.darthbob.kawanav;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps the bike's last values (files/bike.json) so the bike screen shows them while the bike is off,
 * like Rideology's vehicle info. Stores the raw frames and decodes them again on load, plus where the
 * bike was parked. Written on a background thread, atomically (tmp + rename).
 */
public final class BikeStore {
    private BikeStore() {}

    private static final String FILE = "bike.json";
    /** Frames kept, config frames first: they decide how the others are decoded. */
    private static final int[] KEPT = {BikeFrames.MODEL_INFO, BikeFrames.GENERAL_CAPS, BikeFrames.INFO_CONFIG,
            BikeFrames.TUNING_CONFIG, BikeFrames.SERVICE_CONFIG, BikeFrames.VEHICLE_INFO, BikeFrames.RIDING_LOG_EXT,
            BikeFrames.GENERAL_SETTINGS, BikeFrames.VEHICLE_SETTINGS, BikeFrames.SERVICE_INDICATOR};
    private static final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "bike-io"));
    private static boolean loaded;

    /** Loads the stored values into {@link Hub#bike} once per process (main thread; reads the small file directly). */
    public static void load(Context c) {
        if (loaded) return;
        loaded = true;
        File f = new File(c.getFilesDir(), FILE);
        if (!f.exists()) return;
        try {
            JSONObject o = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8));
            JSONObject frames = o.optJSONObject("frames");
            BikeFrames.Decoder d = new BikeFrames.Decoder();
            BikeData b = Hub.bike;
            if (frames != null) {
                for (int id : KEPT) {
                    JSONObject fr = frames.optJSONObject(key(id));
                    if (fr == null) continue;
                    byte[] raw = KawaProtocol.parseHex(fr.getString("hex"));
                    if (raw == null || raw.length == 0 || (raw[0] & 0xFF) != id) continue;
                    b.apply(d.decode(raw), fr.getLong("at"));
                }
                b.connected(); // stored frames are no "frames since connecting"
            }
            JSONObject p = o.optJSONObject("parked");
            if (p != null) b.parked = new BikeData.Place(p.getDouble("lat"), p.getDouble("lon"),
                    (float) p.optDouble("acc", 0), p.getLong("at"));
        } catch (Exception e) {
            Logger.log("Bike values unreadable, starting fresh: " + e);
            f.delete();
        }
    }

    /** Saves {@link Hub#bike} in the background (call on the main thread). */
    public static void save(Context c) {
        BikeData b = Hub.bike;
        JSONObject o = new JSONObject();
        try {
            JSONObject frames = new JSONObject();
            for (int id : KEPT) {
                if (b.last[id] == null) continue;
                frames.put(key(id), new JSONObject().put("hex", KawaProtocol.hex(b.last[id]).replace(" ", ""))
                        .put("at", b.lastAt[id]));
            }
            o.put("frames", frames);
            if (b.parked != null) {
                o.put("parked", new JSONObject().put("lat", b.parked.lat).put("lon", b.parked.lon)
                        .put("acc", b.parked.accuracyM).put("at", b.parked.at));
            }
        } catch (org.json.JSONException e) {
            Logger.log("Bike values not saved: " + e);
            return;
        }
        File dir = c.getFilesDir();
        String text = o.toString();
        io.execute(() -> {
            File tmp = new File(dir, FILE + ".tmp");
            try {
                java.nio.file.Files.write(tmp.toPath(), text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (!tmp.renameTo(new File(dir, FILE))) throw new java.io.IOException("cannot rename " + tmp);
            } catch (java.io.IOException e) {
                Logger.log("Bike values not saved: " + e);
            }
        });
    }

    private static String key(int id) {
        return String.format(java.util.Locale.ROOT, "%02X", id);
    }
}
