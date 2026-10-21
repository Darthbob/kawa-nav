package io.github.darthbob.kawanav;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The recorded rides in {@code files/rides/}: a small JSON summary per ride and the samples as a TSV
 * (plain while the ride runs, gzipped once it is finished). Writes happen on a background thread and
 * atomically where it matters, like the offline map store; listing and deleting are synchronized.
 */
public final class RideStore {
    private static final String DIR = "rides";
    /** Samples are flushed this often while recording (the app may be killed at any time). */
    private static final long FLUSH_MS = 5000;
    /** A ride file whose last sample is older than this is not continued after a restart. */
    private static final long RESUME_MS = 10 * 60_000;

    private static final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "ride-io"));

    /**
     * Guards the one open sample file. The writer is static because only one ride can be recorded at a
     * time in a process, while several {@code RideStore}s exist at once (the recorder's and one per
     * screen): with the state per instance, the ride screen's store could not tell that the ride it was
     * asked to delete is the one being written, and deleted it from under the recorder.
     */
    private static final Object LOCK = new Object();
    private static Writer out;
    /** Header width of a file being appended to that an older version wrote (0 = today's). */
    private static int appendedWidth;
    private static long writtenAt;
    private static String openId;

    private final File dir;

    public RideStore(Context c) {
        this(new File(c.getFilesDir(), DIR));
    }

    /** The rides live in {@code files/rides/}; a plain directory keeps this testable without a phone. */
    RideStore(File dir) {
        this.dir = dir;
        dir.mkdirs();
    }

    /** Waits for the store's own thread: tests only (the app never blocks on a ride write). */
    void awaitIdle() {
        try {
            io.submit(() -> { }).get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Forgets the open sample file: tests only, so one case cannot leak into the next. */
    static void resetForTests() {
        synchronized (LOCK) {
            out = null;
            openId = null;
            appendedWidth = 0;
            writtenAt = 0;
        }
    }

    // ------------------------------------------------------------------ recording

    /** Opens (or creates) the sample file of a ride and writes its header. */
    public void open(Ride ride, boolean append) {
        synchronized (LOCK) {
            openLocked(ride, append);
        }
    }

    private void openLocked(Ride ride, boolean append) {
        try {
            close();
            File f = tsv(ride.id);
            // a ride started by an older version has a shorter header: its rows keep that
            // width, so the newest columns are missing from that one ride instead of the file
            // turning ragged
            appendedWidth = append ? olderHeaderWidth(f) : 0;
            out = new FileWriter(f, append);
            if (!append) {
                out.write(RideTsv.magic() + "\n");
                out.write(RideTsv.header() + "\n");
                out.flush();
            }
            openId = ride.id;
            writtenAt = System.currentTimeMillis();
        } catch (IOException e) {
            Logger.log("Ride: cannot write " + ride.id + ": " + e);
        }
    }

    /** The header width of an older file (0 when it matches today's columns or cannot be read). */
    private static int olderHeaderWidth(File f) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
            String first = r.readLine(), second = r.readLine();
            if (first == null || second == null || !RideTsv.magic().equals(first.trim())) return 0;
            int width = second.split("\t", -1).length;
            return width == RideTsv.header().split("\t", -1).length ? 0 : width;
        } catch (IOException e) {
            return 0;
        }
    }

    /** Cuts a row to the given number of tab-separated cells (a file from an older version). */
    private static String cutToColumns(String line, int columns) {
        int at = -1;
        for (int i = 1; i < columns; i++) {
            at = line.indexOf('\t', at + 1);
            if (at < 0) return line;
        }
        int end = line.indexOf('\t', at + 1);
        return end < 0 ? line : line.substring(0, end);
    }

    /** Appends one recorded second. */
    public void append(RideTsv.Sample s) {
        synchronized (LOCK) {
            appendLocked(s);
        }
    }

    private void appendLocked(RideTsv.Sample s) {
        if (out == null) return;
        try {
            String line = s.line();
            if (appendedWidth > 0) line = cutToColumns(line, appendedWidth);
            out.write(line + "\n");
            long now = System.currentTimeMillis();
            if (now - writtenAt >= FLUSH_MS) {
                out.flush();
                writtenAt = now;
            }
        } catch (IOException e) {
            Logger.log("Ride: write failed: " + e);
            close();
        }
    }

    /** Closes the sample file (nothing is compressed here: that happens later, off the main thread). */
    public void close() {
        synchronized (LOCK) {
            closeLocked();
        }
    }

    private void closeLocked() {
        if (out == null) return;
        try {
            out.flush();
            out.close();
        } catch (IOException ignored) {
        }
        out = null;
        openId = null;
    }

    /**
     * Gzips a finished ride's samples on the background thread and removes the plain file. The gzip is
     * written to a temporary name and renamed into place: an app killed (or a full disk) halfway would
     * otherwise leave a truncated {@code .tsv.gz} beside the whole {@code .tsv}, and the truncated one
     * is the one a reader picks, so the ride would be lost for good. The plain file goes only after the
     * compressed one is complete.
     */
    public void compact(String id) {
        io.execute(() -> {
            File plain = tsv(id);
            File gz = new File(dir, id + ".tsv.gz");
            File tmp = new File(dir, id + ".tsv.gz.tmp");
            if (!plain.exists()) {
                tmp.delete();
                return;
            }
            try {
                try (java.io.InputStream in = new java.io.FileInputStream(plain);
                     java.util.zip.GZIPOutputStream zip = new java.util.zip.GZIPOutputStream(
                             new java.io.FileOutputStream(tmp))) {
                    byte[] buf = new byte[8192];
                    for (int n; (n = in.read(buf)) > 0; ) zip.write(buf, 0, n);
                    zip.finish(); // writes the deflate tail and the CRC: the file was not whole before
                }
                if (!tmp.renameTo(gz)) throw new IOException("cannot rename " + tmp);
            } catch (IOException e) {
                Logger.log("Ride: cannot compress " + id + ": " + e);
                tmp.delete();
                return; // the plain file stays: the ride is still readable
            }
            if (!plain.delete()) Logger.log("Ride: " + id + " left as both .tsv and .tsv.gz");
        });
    }

    // ------------------------------------------------------------------ files

    private File tsv(String id) {
        return new File(dir, id + ".tsv");
    }

    private File gz(String id) {
        return new File(dir, id + ".tsv.gz");
    }

    private File sampleFile(String id) {
        File gz = gz(id);
        return gz.exists() ? gz : tsv(id);
    }

    /**
     * The samples of a ride, whatever state the file is in (plain or gzipped). When the compressed file
     * cannot be read (a truncated one left by an older version), the plain one is tried before giving
     * up: the ride is still there, and one unreadable file must not hide it.
     */
    public List<RideTsv.Sample> samples(String id) {
        File f = sampleFile(id);
        List<RideTsv.Sample> out = read(f, id);
        File plain = tsv(id);
        if (!out.isEmpty() || f.equals(plain) || !plain.exists()) return out;
        Logger.log("Ride: " + f.getName() + " unreadable, falling back to " + plain.getName());
        return read(plain, id);
    }

    private static List<RideTsv.Sample> read(File f, String id) {
        if (!f.exists()) return new ArrayList<>();
        try (BufferedReader r = f.getName().endsWith(".gz")
                ? new BufferedReader(new java.io.InputStreamReader(new java.util.zip.GZIPInputStream(new java.io.FileInputStream(f)),
                java.nio.charset.StandardCharsets.UTF_8))
                : new BufferedReader(new FileReader(f))) {
            List<String> lines = new ArrayList<>();
            for (String line; (line = r.readLine()) != null; ) lines.add(line);
            return RideTsv.parse(lines);
        } catch (Exception e) {
            Logger.log("Ride: cannot read " + f.getName() + ": " + e);
            return new ArrayList<>();
        }
    }

    /** True while the sample file of this ride is still being written. */
    public static boolean isOpen(String id) {
        synchronized (LOCK) {
            return id != null && id.equals(openId);
        }
    }

    // ------------------------------------------------------------------ summaries

    public synchronized List<Ride> list() {
        List<Ride> out = new ArrayList<>();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return out;
        for (File f : files) {
            Ride r = read(f);
            if (r != null) out.add(r);
        }
        out.sort(Comparator.comparingLong((Ride r) -> r.start).reversed());
        return out;
    }

    public Ride get(String id) {
        File f = new File(dir, id + ".json");
        return f.exists() ? read(f) : null;
    }

    private Ride read(File f) {
        try {
            JSONObject o = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8));
            Ride r = new Ride();
            r.id = o.getString("id");
            r.name = o.optString("name", "");
            r.state = o.optString("state", "done");
            r.start = o.optLong("start");
            r.end = o.optLong("end");
            r.activeMs = o.optLong("activeMs");
            r.distGpsM = o.optDouble("distGpsM", 0);
            r.distBikeM = o.optDouble("distBikeM", 0);
            r.distM = o.has("distM") ? o.optDouble("distM") : Double.NaN;
            r.maxKmh = o.optDouble("maxKmh", 0);
            r.avgKmh = o.optDouble("avgKmh", 0);
            r.maxRpm = o.optInt("maxRpm");
            r.maxLeanL = o.optDouble("maxLeanL", 0);
            r.maxLeanR = o.optDouble("maxLeanR", 0);
            r.fuelUsedL = o.optDouble("fuelUsedL", 0);
            r.odoStartKm = o.has("odoStartKm") ? o.optDouble("odoStartKm") : Double.NaN;
            r.odoEndKm = o.has("odoEndKm") ? o.optDouble("odoEndKm") : Double.NaN;
            r.hasGps = o.optBoolean("hasGps");
            r.hasBike = o.optBoolean("hasBike");
            r.samples = o.optInt("samples");
            r.endReason = o.optString("endReason", "");
            r.summaryVersion = o.optInt("summaryVersion", 0);
            JSONObject metrics = o.optJSONObject("metrics");
            if (metrics != null) {
                for (Ride.Metric m : r.metrics) {
                    org.json.JSONArray v = metrics.optJSONArray(m.key);
                    if (v == null || v.length() < 4) continue;   // a channel this file does not have
                    m.min = v.optDouble(0, Double.NaN);
                    m.mean = v.optDouble(1, Double.NaN);
                    m.max = v.optDouble(2, Double.NaN);
                    m.n = v.optInt(3);
                }
            }
            r.tcsOnS = o.has("tcsOnS") ? o.optDouble("tcsOnS") : Double.NaN;
            r.absOnS = o.has("absOnS") ? o.optDouble("absOnS") : Double.NaN;
            r.wheelieS = o.has("wheelieS") ? o.optDouble("wheelieS") : Double.NaN;
            r.cruiseOnS = o.has("cruiseOnS") ? o.optDouble("cruiseOnS") : Double.NaN;
            org.json.JSONArray gears = o.optJSONArray("gearS");
            if (gears != null) {
                r.gearS = new int[Ride.MAX_GEAR + 1];
                for (int g = 0; g < Math.min(gears.length(), r.gearS.length); g++) r.gearS[g] = gears.optInt(g);
            }
            r.shiftsUp = o.optInt("shiftsUp");
            r.shiftsDown = o.optInt("shiftsDown");
            org.json.JSONArray bends = o.optJSONArray("bendLean");
            if (bends != null) {
                r.bendLean = new int[Ride.Bends.MAX_PEAK + 1];
                for (int d = 0; d < Math.min(bends.length(), r.bendLean.length); d++) r.bendLean[d] = bends.optInt(d);
            }
            r.kqsUp = o.has("kqsUp") ? o.optDouble("kqsUp") : Double.NaN;
            r.kqsDown = o.has("kqsDown") ? o.optDouble("kqsDown") : Double.NaN;
            org.json.JSONArray bbox = o.optJSONArray("bbox");
            if (bbox != null && bbox.length() == 4) {
                r.bbox = new double[]{bbox.getDouble(0), bbox.getDouble(1), bbox.getDouble(2), bbox.getDouble(3)};
            }
            return r;
        } catch (Exception e) {
            Logger.log("Ride: " + f.getName() + " unreadable: " + e);
            return null;
        }
    }

    /**
     * Gives the rides that were saved before the summary kept its numbers (metrics, electronics time) theirs:
     * one pass per ride, on the store's own thread, at most {@code max} of them. A ride whose file is open,
     * or still being recorded, is left alone. Returns how many were done.
     */
    public int summariseOutdated(List<Ride> rides, int max) {
        int done = 0;
        for (Ride r : rides) {
            if (done >= max) break;
            if (r.summaryVersion >= Ride.SUMMARY_VERSION || r.isRecording() || isOpen(r.id)) continue;
            List<RideTsv.Sample> samples = samples(r.id);
            if (samples.isEmpty()) continue;
            r.summarize(samples);
            save(r);
            done++;
        }
        if (done > 0) Logger.log("Ride: " + done + " older rides summarised");
        return done;
    }

    /**
     * The lean the rider usually takes a bend at, from the latest rides ({@link RideStats#usualBendLean}).
     * Those of them saved before the summary counted bends are summarised first, a few per call: reads
     * files, so not on the main thread.
     */
    public RideStats.BendLean usualBendLean() {
        List<Ride> rides = list();
        summariseOutdated(rides.subList(0, Math.min(rides.size(), RideStats.BEND_RIDES)), RideStats.BEND_RIDES);
        return RideStats.usualBendLean(rides);
    }

    /** Saves the summary (background, atomically: temporary file then rename). */
    public void save(Ride r) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", r.id);
            o.put("name", r.name);
            o.put("state", r.state);
            o.put("start", r.start);
            o.put("end", r.end);
            o.put("activeMs", r.activeMs);
            o.put("distGpsM", round(r.distGpsM));
            o.put("distBikeM", round(r.distBikeM));
            if (r.distM == r.distM) o.put("distM", round(r.distM));
            o.put("maxKmh", round(r.maxKmh));
            o.put("avgKmh", round(r.avgKmh));
            o.put("maxRpm", r.maxRpm);
            o.put("maxLeanL", round(r.maxLeanL));
            o.put("maxLeanR", round(r.maxLeanR));
            o.put("fuelUsedL", round(r.fuelUsedL));
            if (r.odoStartKm == r.odoStartKm) o.put("odoStartKm", round(r.odoStartKm));
            if (r.odoEndKm == r.odoEndKm) o.put("odoEndKm", round(r.odoEndKm));
            o.put("hasGps", r.hasGps);
            o.put("hasBike", r.hasBike);
            o.put("samples", r.samples);
            o.put("endReason", r.endReason);
            o.put("summaryVersion", r.summaryVersion);
            // the channels are keyed by name, so adding one later cannot shift an old file's values.
            // A channel the ride does not have is left out: org.json throws on NaN, and that would
            // abandon the whole save (the periodic one included).
            JSONObject metrics = new JSONObject();
            for (Ride.Metric m : r.metrics) {
                if (!m.known()) continue;
                org.json.JSONArray v = new org.json.JSONArray();
                v.put(round(m.min));
                v.put(round(m.mean));
                v.put(round(m.max));
                v.put(m.n);
                metrics.put(m.key, v);
            }
            if (metrics.length() > 0) o.put("metrics", metrics);
            if (r.tcsOnS == r.tcsOnS) o.put("tcsOnS", round(r.tcsOnS));
            if (r.absOnS == r.absOnS) o.put("absOnS", round(r.absOnS));
            if (r.wheelieS == r.wheelieS) o.put("wheelieS", round(r.wheelieS));
            if (r.cruiseOnS == r.cruiseOnS) o.put("cruiseOnS", round(r.cruiseOnS));
            if (r.gearS != null) {
                org.json.JSONArray g = new org.json.JSONArray();
                for (int s : r.gearS) g.put(s);
                o.put("gearS", g);
                o.put("shiftsUp", r.shiftsUp);
                o.put("shiftsDown", r.shiftsDown);
            }
            if (r.bendLean != null) {
                org.json.JSONArray b = new org.json.JSONArray();
                for (int n : r.bendLean) b.put(n);
                o.put("bendLean", b);
            }
            if (r.kqsUp == r.kqsUp) o.put("kqsUp", round(r.kqsUp));
            if (r.kqsDown == r.kqsDown) o.put("kqsDown", round(r.kqsDown));
            if (r.bbox != null) {
                org.json.JSONArray b = new org.json.JSONArray();
                for (double d : r.bbox) b.put(d);
                o.put("bbox", b);
            }
        } catch (org.json.JSONException e) {
            Logger.log("Ride: cannot save " + r.id + ": " + e);
            return;
        }
        String text = o.toString();
        io.execute(() -> {
            File tmp = new File(dir, r.id + ".json.tmp");
            try {
                java.nio.file.Files.write(tmp.toPath(), text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (!tmp.renameTo(new File(dir, r.id + ".json"))) throw new IOException("cannot rename " + tmp);
            } catch (IOException e) {
                Logger.log("Ride: cannot save " + r.id + ": " + e);
            }
        });
    }

    private static double round(double v) {
        return v != v ? 0 : Math.round(v * 100) / 100.0;
    }

    /** Renames a ride. */
    public void rename(String id, String name) {
        Ride r = get(id);
        if (r == null) return;
        r.name = name;
        save(r);
    }

    /**
     * Deletes a ride's summary and samples. A ride that is still being recorded is refused: deleting it
     * unlinks the file the recorder keeps writing into, every later sample goes nowhere, and the summary
     * is written back as a finished ride with no track at all. Better to keep a ride the rider can stop
     * and delete afterwards than to lose it without a word.
     */
    public void delete(String id) {
        synchronized (LOCK) {
            if (isOpen(id)) {
                Logger.log("Ride: refused to delete " + id + ": it is being recorded");
                return;
            }
            closeLocked();
            new File(dir, id + ".json").delete();
            tsv(id).delete();
            gz(id).delete();
            new File(dir, id + ".tsv.gz.tmp").delete(); // a compression that never finished
        }
    }

    /** A ride the app was still writing (killed): continues it if it was a moment ago, else recovers it. */
    public Ride resumeCandidate() {
        for (Ride r : list()) {
            if (!r.isRecording()) continue;
            List<RideTsv.Sample> s = samples(r.id);
            long lastAt = s.isEmpty() ? r.end : s.get(s.size() - 1).epochMs;
            if (System.currentTimeMillis() - lastAt <= RESUME_MS) return r;
            recover(r, s);
            save(r);
            compact(r.id);
            Logger.log("Ride: " + r.id + " " + r.state + " after a restart (" + r.samples + " samples)");
        }
        return null;
    }

    /**
     * What a ride the app was still writing becomes now that it is not: the samples are what the summary
     * is worked out from, so only when they are there does the state say recovered. With no readable
     * track the periodic summary is all that is left, and its own sample count must stand — writing 0
     * over it would report a ride that recorded nothing, which reads like a ride that simply had no GPS.
     * Separate from {@link #resumeCandidate} because that one reads and writes the ride's JSON file,
     * which the JVM tests cannot do; this is the decision itself.
     */
    static void recover(Ride r, List<RideTsv.Sample> samples) {
        if (samples.isEmpty()) {
            r.state = "incomplete";
        } else {
            r.state = "recovered";
            r.summarize(samples);
            r.samples = samples.size();
        }
    }
}
