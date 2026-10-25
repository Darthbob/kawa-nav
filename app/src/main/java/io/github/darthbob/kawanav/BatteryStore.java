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
 * The 12 V readings in {@code files/battery.json}, written whole on a background thread. A file that
 * cannot be parsed is kept as battery.json.bad rather than overwritten with an empty log.
 */
public final class BatteryStore {
    private static final String FILE = "battery.json";
    private static final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "battery-io"));

    private final File file;

    public BatteryStore(Context c) {
        file = new File(c.getFilesDir(), FILE);
    }

    /** The readings, oldest first; null when the file is there but cannot be read. */
    public List<BatteryLog.Reading> list() {
        List<BatteryLog.Reading> out = new ArrayList<>();
        if (!file.isFile()) return out;
        try {
            JSONArray a = new JSONArray(new String(java.nio.file.Files.readAllBytes(file.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                out.add(new BatteryLog.Reading(o.optLong("at"), o.optDouble("volts", Double.NaN)));
            }
        } catch (Exception e) {
            Logger.log("Battery: unreadable log, kept as .bad: " + e);
            keepAside();
            return null;
        }
        return BatteryLog.restingOnly(out);
    }

    /** Moves a file that could not be read out of the way, so a later save cannot erase it. */
    private void keepAside() {
        File bad = new File(file.getParentFile(), file.getName() + ".bad");
        if (bad.exists()) bad.delete();
        if (!file.renameTo(bad)) Logger.log("Battery: cannot keep the unreadable file aside");
    }

    /** Writes the readings (background; the file is small, so it is rewritten rather than appended). */
    public void save(List<BatteryLog.Reading> readings) {
        JSONArray a = new JSONArray();
        for (BatteryLog.Reading r : readings) {
            if (!Double.isFinite(r.volts)) continue;
            JSONObject o = new JSONObject();
            try {
                o.put("at", r.at);
                o.put("volts", r.volts);
            } catch (org.json.JSONException ignored) {
                continue;
            }
            a.put(o);
        }
        write(a.toString());
    }

    private void write(String text) {
        io.execute(() -> {
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            try {
                java.nio.file.Files.write(tmp.toPath(), text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                java.nio.file.Files.move(tmp.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Logger.log("Battery: cannot save the log: " + e);
            }
        });
    }
}
