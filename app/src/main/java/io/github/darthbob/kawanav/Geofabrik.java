package io.github.darthbob.kawanav;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Region extracts from download.geofabrik.de: OpenStreetMap files per country / state / district,
 * rebuilt daily. The region list comes from Geofabrik's index (cached for a week).
 */
public final class Geofabrik {

    private static final String INDEX_URL = "https://download.geofabrik.de/index-v1-nogeom.json";
    private static final long INDEX_MAX_AGE_MS = 7L * 24 * 3600 * 1000;
    private static final String USER_AGENT = "KawaNav/0.1 (personal motorcycle curve display)";

    public static final class Region {
        public final String id, name, parentName, pbfUrl;

        Region(String id, String name, String parentName, String pbfUrl) {
            this.id = id;
            this.name = name;
            this.parentName = parentName;
            this.pbfUrl = pbfUrl;
        }

        /** The region border, next to the extract: .../bayern-latest.osm.pbf -> .../bayern.poly */
        public String polyUrl() {
            return pbfUrl.replace("-latest.osm.pbf", ".poly");
        }

        /** "Oberbayern (Bayern)" */
        public String label() {
            return parentName.isEmpty() ? name : name + " (" + parentName + ")";
        }
    }

    public interface Progress {
        void bytes(long done, long total);

        boolean cancelled();
    }

    /** All regions, sorted by label; downloads the index if missing or old (network: not on the main thread). */
    public static List<Region> regions(Context c) throws Exception {
        File f = new File(c.getFilesDir(), "geofabrik-index.json");
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > INDEX_MAX_AGE_MS) {
            try {
                File tmp = new File(c.getFilesDir(), "geofabrik-index.tmp");
                download(INDEX_URL, tmp, null);
                if (!tmp.renameTo(f)) throw new IOException("cannot store the region list");
            } catch (Exception e) {
                if (!f.exists()) throw e; // an old list is better than none
            }
        }
        JSONArray features;
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[65536];
            int k;
            while ((k = in.read(b)) > 0) buf.write(b, 0, k);
            features = new JSONObject(buf.toString("UTF-8")).getJSONArray("features");
        }
        Map<String, String> names = new HashMap<>();
        for (int i = 0; i < features.length(); i++) {
            JSONObject p = features.getJSONObject(i).getJSONObject("properties");
            names.put(p.getString("id"), p.getString("name"));
        }
        List<Region> out = new ArrayList<>();
        for (int i = 0; i < features.length(); i++) {
            JSONObject p = features.getJSONObject(i).getJSONObject("properties");
            JSONObject urls = p.optJSONObject("urls");
            String pbf = urls == null ? null : urls.optString("pbf", null);
            if (pbf == null) continue;
            String parent = p.optString("parent", "");
            String parentName = names.containsKey(parent) ? names.get(parent) : "";
            out.add(new Region(p.getString("id"), p.getString("name"), parentName, pbf));
        }
        Collections.sort(out, (a, b) -> a.label().compareToIgnoreCase(b.label()));
        return out;
    }

    /** File size in bytes, or -1. */
    public static long size(String url) {
        return head(url)[0];
    }

    /** {size in bytes or -1, Last-Modified in ms or 0 (= when Geofabrik built this extract)}. */
    public static long[] head(String url) {
        try {
            HttpURLConnection c = open(url);
            c.setRequestMethod("HEAD");
            try {
                if (c.getResponseCode() != 200) return new long[]{-1, 0};
                return new long[]{c.getContentLengthLong(), c.getLastModified()};
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return new long[]{-1, 0};
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(60_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", USER_AGENT);
        return c;
    }

    /**
     * Downloads to a file, resuming a partial download of the same URL (".part" file kept on
     * failure or cancel). Returns the finished file.
     */
    public static void download(String url, File target, Progress progress) throws Exception {
        File part = new File(target.getPath() + ".part");
        // "<url>\n<ETag or Last-Modified>" of the partial download: resume only the same file version
        File meta = new File(target.getPath() + ".url");
        String[] m = meta.exists() ? readSmall(meta).split("\n", 2) : new String[0];
        String validator = m.length == 2 && m[0].equals(url) ? m[1] : "";
        if (validator.isEmpty()) part.delete();
        long have = part.exists() ? part.length() : 0;
        HttpURLConnection c = open(url);
        if (have > 0) {
            c.setRequestProperty("Range", "bytes=" + have + "-");
            c.setRequestProperty("If-Range", validator); // file changed since: the server sends it whole
        }
        try {
            int code = c.getResponseCode();
            boolean append = code == 206;
            if (code != 200 && code != 206) throw new IOException("HTTP " + code + " from " + new URL(url).getHost());
            if (!append) have = 0;
            String v = c.getHeaderField("ETag");
            if (v == null) v = c.getHeaderField("Last-Modified");
            writeSmall(meta, url + "\n" + (v == null ? "" : v));
            long total = c.getContentLengthLong();
            if (total >= 0) total += have;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part, append)) {
                byte[] b = new byte[1 << 16];
                int k;
                long done = have, lastReport = 0;
                while ((k = in.read(b)) > 0) {
                    out.write(b, 0, k);
                    done += k;
                    if (progress != null) {
                        if (progress.cancelled()) throw new PbfImporter.CancelledException();
                        if (done - lastReport > (1 << 20)) {
                            progress.bytes(done, total);
                            lastReport = done;
                        }
                    }
                }
                if (total >= 0 && done != total) throw new IOException("download interrupted");
            }
        } finally {
            c.disconnect();
        }
        target.delete();
        if (!part.renameTo(target)) throw new IOException("cannot store " + target.getName());
        meta.delete();
    }

    private static String readSmall(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 4096)];
            int n = in.read(b);
            return n <= 0 ? "" : new String(b, 0, n, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeSmall(File f, String s) throws IOException {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }
}
