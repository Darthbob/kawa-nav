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

/** The maintenance entries in {@code files/maintenance.json}, written whole on a background thread. */
public final class MaintenanceStore {
    private static final String FILE = "maintenance.json";
    private static final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "maint-io"));

    private final File file;

    public MaintenanceStore(Context c) {
        file = new File(c.getFilesDir(), FILE);
    }

    /** The entries, oldest first. */
    public List<MaintenanceLog.Entry> list() {
        List<MaintenanceLog.Entry> out = new ArrayList<>();
        if (!file.isFile()) return out;
        try {
            JSONArray a = new JSONArray(new String(java.nio.file.Files.readAllBytes(file.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                MaintenanceLog.Entry e = new MaintenanceLog.Entry();
                e.at = o.optLong("at");
                e.odoKm = o.has("odoKm") ? o.optDouble("odoKm", Double.NaN) : Double.NaN;
                e.item = o.optString("item", "");
                e.note = o.optString("note", "");
                if (!e.item.isEmpty()) out.add(e);
            }
        } catch (Exception e) {
            Logger.log("Maintenance: unreadable log: " + e);
        }
        return MaintenanceLog.sorted(out);
    }

    public void save(List<MaintenanceLog.Entry> entries) {
        JSONArray a = new JSONArray();
        for (MaintenanceLog.Entry e : MaintenanceLog.sorted(entries)) {
            JSONObject o = new JSONObject();
            try {
                o.put("at", e.at);
                if (e.odoKm == e.odoKm) o.put("odoKm", e.odoKm);
                o.put("item", e.item);
                if (!e.note.isEmpty()) o.put("note", e.note);
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
                Logger.log("Maintenance: cannot save the log: " + e);
            }
        });
    }
}
