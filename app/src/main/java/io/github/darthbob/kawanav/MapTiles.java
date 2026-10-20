package io.github.darthbob.kawanav;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * OpenStreetMap's map pictures (256 px tiles) under a ride's track. Fetched from tile.openstreetmap.org
 * only for what the rider is looking at, kept in the app's cache and asked for again only once OSM says
 * they are out of date — what the OSM tile usage policy asks of an app: a User-Agent naming it, the cache
 * headers honoured (a week when there are none), no downloading ahead, the attribution on the map.
 * Positions are in Web Mercator scaled to 0..1, the projection the tiles are drawn in.
 */
public final class MapTiles {

    public static final int TILE = 256;
    public static final int MAX_ZOOM = 19;
    /** Kept at least this long when OSM's answer carries no cache header. */
    static final long DEFAULT_KEEP_MS = 7L * 24 * 3600 * 1000;
    /** The tiles in the app's cache are trimmed back to this size, the oldest first. */
    static final long CACHE_MAX_BYTES = 80L * 1024 * 1024;
    /** After a failed download (no network) a tile is not asked for again for this long. */
    static final long RETRY_MS = 30_000;
    private static final String URL = "https://tile.openstreetmap.org/%d/%d/%d.png";
    private static final String USER_AGENT = "KawaNav/" + BuildConfig.VERSION_NAME
            + " (+https://github.com/Darthbob/kawa-nav)";
    private static final double MAX_LAT = 85.0511287798;

    public interface Listener {
        /** A tile arrived (on a background thread): draw again. */
        void tileReady();
    }

    private final File dir;
    private final Listener listener;
    private final LruCache<String, Bitmap> memory;
    private final ThreadPoolExecutor pool;
    private final Set<String> pending = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, Long> failedAt = Collections.synchronizedMap(new HashMap<>());
    /** What the last frame drew: a queued tile scrolled away before its turn is not fetched any more. */
    private volatile Set<String> wanted = Collections.emptySet();
    private volatile boolean closed;

    public MapTiles(Context c, Listener listener) {
        this.dir = new File(c.getCacheDir(), "tiles");
        this.listener = listener;
        int kb = (int) Math.min(48 * 1024, Runtime.getRuntime().maxMemory() / 1024 / 8);
        memory = new LruCache<>(kb) {
            @Override
            protected int sizeOf(String key, Bitmap b) {
                return b.getAllocationByteCount() / 1024;
            }
        };
        // two at a time, the newest request first: the tiles the rider is looking at now come before
        // those of a moment ago
        pool = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new LinkedBlockingDeque<Runnable>() {
            @Override
            public boolean offer(Runnable r) {
                return offerFirst(r);
            }
        }, r -> {
            Thread t = new Thread(r, "map-tiles");
            t.setDaemon(true);
            return t;
        });
        pool.allowCoreThreadTimeOut(true);
        pool.execute(this::trim);
    }

    /** The tile if it is in memory; otherwise null, and it is loaded (cache, else OSM) and announced. */
    public Bitmap get(int z, int x, int y) {
        String key = key(z, x, y);
        Bitmap b = memory.get(key);
        if (b != null || closed) return b;
        Long failed = failedAt.get(key);
        if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) return null;
        if (pending.add(key)) pool.execute(() -> load(key, z, x, y));
        return null;
    }

    /** The tile if it is in memory, without loading it: a coarser stand-in while the right one comes. */
    public Bitmap peek(int z, int x, int y) {
        return memory.get(key(z, x, y));
    }

    /** The tiles the frame just drawn needed; anything else still queued is dropped. */
    public void frame(Set<String> keys) {
        wanted = keys;
    }

    /** The screen is gone: nothing more is loaded. */
    public void close() {
        closed = true;
        pool.shutdownNow();
        memory.evictAll();
    }

    static String key(int z, int x, int y) {
        return z + "/" + x + "/" + y;
    }

    private void load(String key, int z, int x, int y) {
        try {
            if (closed || !wanted.contains(key)) return;
            File png = new File(dir, key + ".png");
            File meta = new File(dir, key + ".meta");
            Bitmap cached = png.exists() ? BitmapFactory.decodeFile(png.getPath()) : null;
            String[] m = cached != null && meta.exists() ? readMeta(meta) : new String[]{"0", "", ""};
            long expires = parseLong(m[0]);
            if (cached != null) {
                show(key, cached);
                if (System.currentTimeMillis() < expires) return;
            }
            fetch(key, z, x, y, png, meta, cached != null ? m : null);
        } catch (Exception e) {
            failedAt.put(key, System.currentTimeMillis());
        } finally {
            pending.remove(key);
        }
    }

    /** Downloads the tile, or asks whether the cached one still holds ({@code stale}: its meta lines). */
    private void fetch(String key, int z, int x, int y, File png, File meta, String[] stale) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(String.format(Locale.ROOT, URL, z, x, y)).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("User-Agent", USER_AGENT);
        if (stale != null && !stale[1].isEmpty()) c.setRequestProperty("If-None-Match", stale[1]);
        if (stale != null && !stale[2].isEmpty()) c.setRequestProperty("If-Modified-Since", stale[2]);
        try {
            int code = c.getResponseCode();
            long now = System.currentTimeMillis();
            long expires = expiry(c.getHeaderField("Cache-Control"), c.getExpiration(), now);
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED && stale != null) {
                writeMeta(meta, expires, header(c, "ETag", stale[1]), header(c, "Last-Modified", stale[2]));
                png.setLastModified(now);
                return;
            }
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
            byte[] bytes;
            try (InputStream in = c.getInputStream()) {
                bytes = readAll(in);
            }
            Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (b == null) throw new IOException("not a picture");
            show(key, b);
            File parent = png.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;
            File tmp = new File(png.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
            }
            Files.move(tmp.toPath(), png.toPath(), StandardCopyOption.REPLACE_EXISTING);
            writeMeta(meta, expires, header(c, "ETag", ""), header(c, "Last-Modified", ""));
        } finally {
            c.disconnect();
        }
    }

    private void show(String key, Bitmap b) {
        if (closed) return;
        memory.put(key, b);
        failedAt.remove(key);
        listener.tileReady();
    }

    /**
     * When a tile fetched {@code now} goes out of date: the Cache-Control max-age, else the Expires
     * header ({@code expiresHeader}, 0 when there is none), else {@link #DEFAULT_KEEP_MS}.
     */
    static long expiry(String cacheControl, long expiresHeader, long now) {
        if (cacheControl != null) {
            for (String part : cacheControl.split(",")) {
                String p = part.trim().toLowerCase(Locale.ROOT);
                if (!p.startsWith("max-age=")) continue;
                try {
                    return now + Math.max(0, Long.parseLong(p.substring(8).trim())) * 1000;
                } catch (NumberFormatException e) {
                    break;
                }
            }
        }
        if (expiresHeader > 0) return expiresHeader;
        return now + DEFAULT_KEEP_MS;
    }

    /** Trims the cache back to {@link #CACHE_MAX_BYTES}, deleting the tiles fetched longest ago. */
    private void trim() {
        File[] zooms = dir.listFiles();
        if (zooms == null) return;
        java.util.List<File> pngs = new java.util.ArrayList<>();
        long total = 0;
        for (File zd : zooms) {
            File[] xs = zd.listFiles();
            if (xs == null) continue;
            for (File xd : xs) {
                File[] ys = xd.listFiles();
                if (ys == null) continue;
                for (File f : ys) {
                    total += f.length();
                    if (f.getName().endsWith(".png")) pngs.add(f);
                }
            }
        }
        if (total <= CACHE_MAX_BYTES) return;
        File[] byAge = pngs.toArray(new File[0]);
        long[] age = new long[byAge.length];
        for (int i = 0; i < byAge.length; i++) age[i] = byAge[i].lastModified();
        Integer[] order = new Integer[byAge.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Long.compare(age[a], age[b]));
        for (Integer i : order) {
            if (total <= CACHE_MAX_BYTES * 3 / 4) break;
            File png = byAge[i];
            File meta = new File(png.getPath().substring(0, png.getPath().length() - 4) + ".meta");
            total -= png.length() + meta.length();
            png.delete();
            meta.delete();
        }
    }

    private static String header(HttpURLConnection c, String name, String fallback) {
        String v = c.getHeaderField(name);
        return v == null ? fallback : v;
    }

    /** {expires ms, ETag, Last-Modified}, "" for what OSM did not send. */
    private static String[] readMeta(File f) throws IOException {
        String[] lines = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n", -1);
        return new String[]{lines[0], lines.length > 1 ? lines[1] : "", lines.length > 2 ? lines[2] : ""};
    }

    private static void writeMeta(File f, long expires, String etag, String lastModified) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("no cache folder");
        // no line breaks from a header can end up in the file and shift the lines after them
        String text = expires + "\n" + etag.replace('\n', ' ') + "\n" + lastModified.replace('\n', ' ');
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int k;
        while ((k = in.read(b)) > 0) buf.write(b, 0, k);
        return buf.toByteArray();
    }

    // ------------------------------------------------------------------ projection

    /** Longitude to Web Mercator x, 0 (180° W) .. 1 (180° E). */
    static double x(double lon) {
        return (lon + 180) / 360;
    }

    /** Latitude to Web Mercator y, 0 (85° N) .. 1 (85° S): down the screen, as the tiles are numbered. */
    static double y(double lat) {
        double s = Math.sin(Math.toRadians(Math.max(-MAX_LAT, Math.min(MAX_LAT, lat))));
        return 0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI);
    }

    /**
     * The tile zoom to draw when the whole world is {@code worldPx} screen pixels wide and a tile should
     * be about {@code tilePx} on screen: the level whose tiles come closest to that size.
     */
    static int zoomFor(double worldPx, double tilePx) {
        int z = (int) Math.round(Math.log(worldPx / tilePx) / Math.log(2));
        return Math.max(0, Math.min(MAX_ZOOM, z));
    }
}
