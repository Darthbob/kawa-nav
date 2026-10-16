package io.github.darthbob.kawanav;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The refuels in {@code files/fuel.json}: a small JSON array, written whole on a background thread
 * (temporary file then rename, as the ride store does). Plain enough to be read back on the main thread.
 */
public final class FuelStore {
    private static final String FILE = "fuel.json";
    private static final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "fuel-io"));

    private final File file;

    public FuelStore(Context c) {
        file = new File(c.getFilesDir(), FILE);
    }

    /**
     * The refuels, oldest first, or null when the file is there but cannot be read: the caller must then
     * not save over it. Nothing is ever written on top of a file that could not be parsed.
     */
    public List<FuelLog.Entry> list() {
        List<FuelLog.Entry> out = new ArrayList<>();
        String text = read();
        if (text.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(text);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                FuelLog.Entry e = new FuelLog.Entry();
                e.at = o.optLong("at");
                e.odoKm = num(o, "odoKm");
                e.litres = num(o, "litres");
                e.estimated = o.optBoolean("estimated", false);
                e.barsBefore = num(o, "barsBefore");
                e.barsAfter = num(o, "barsAfter");
                e.rangeBeforeKm = num(o, "rangeBeforeKm");
                e.rangeAfterKm = num(o, "rangeAfterKm");
                e.note = o.optString("note", "");
                out.add(e);
            }
        } catch (Exception e) {
            Logger.log("Fuel: unreadable log, kept as .bad: " + e);
            keepAside();
            return null;
        }
        return FuelLog.sorted(out);
    }

    /** Writes the whole log (background; the file is small, so it is rewritten rather than appended). */
    public void save(List<FuelLog.Entry> entries) {
        JSONArray a = new JSONArray();
        for (FuelLog.Entry e : FuelLog.sorted(entries)) {
            JSONObject o = new JSONObject();
            try {
                o.put("at", e.at);
                put(o, "odoKm", e.odoKm);
                put(o, "litres", e.litres);
                o.put("estimated", e.estimated);
                put(o, "barsBefore", e.barsBefore);
                put(o, "barsAfter", e.barsAfter);
                put(o, "rangeBeforeKm", e.rangeBeforeKm);
                put(o, "rangeAfterKm", e.rangeAfterKm);
                o.put("note", e.note);
            } catch (org.json.JSONException ignored) {
                continue;
            }
            a.put(o);
        }
        String text = a.toString();
        io.execute(() -> {
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            try {
                java.nio.file.Files.write(tmp.toPath(), text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                // move over the old file: never delete it first, a failure here would lose the whole log
                java.nio.file.Files.move(tmp.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Logger.log("Fuel: cannot save the log: " + e);
            }
        });
    }

    /** Moves a file that could not be read out of the way, so a later save cannot erase it. */
    private void keepAside() {
        File bad = new File(file.getParentFile(), file.getName() + ".bad");
        if (bad.exists()) bad.delete();
        if (!file.renameTo(bad)) Logger.log("Fuel: cannot keep the unreadable file aside");
    }

    private String read() {
        if (!file.isFile()) return "";
        try {
            return new String(java.nio.file.Files.readAllBytes(file.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            Logger.log("Fuel: cannot read the log: " + e);
            return "";
        }
    }

    private static void put(JSONObject o, String key, double v) throws org.json.JSONException {
        if (Double.isFinite(v)) o.put(key, v);
    }

    private static double num(JSONObject o, String key) {
        return o.has(key) ? o.optDouble(key, Double.NaN) : Double.NaN;
    }
}
