package io.github.darthbob.kawanav;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Debug builds: every frame to and from the meter, one line each ({@code epochMs RX|TX uuid8 hex}), in
 * Android/data/io.github.darthbob.kawanav/files/captures/ (readable with adb pull, and replayed with
 * {@link FrameReplay}). One file per connection, all captures together at most {@link #MAX_BYTES}.
 * Written on a background thread.
 */
public final class RawCapture {
    private RawCapture() {}

    private static final long MAX_BYTES = 50_000_000;
    private static final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "capture-io"));
    private static volatile Writer out;
    private static long written;

    public static File dir(Context c) {
        File d = new File(c.getExternalFilesDir(null), "captures");
        d.mkdirs();
        return d;
    }

    /** Capturing is on (debug builds, switched on in the debug card or by adb). */
    public static boolean enabled(Context c) {
        return Hub.DEBUG_TOOLS && Hub.prefs(c).getBoolean(Hub.KEY_DEBUG_CAPTURE, false);
    }

    /** Starts a new capture file (on connect). */
    public static void start(Context c) {
        File d = dir(c);
        String name = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new java.util.Date()) + ".txt";
        io.execute(() -> {
            close();
            trim(d);
            try {
                out = new java.io.BufferedWriter(new FileWriter(new File(d, name)));
                written = 0;
            } catch (IOException e) {
                Logger.log("Capture: cannot write " + name + ": " + e);
            }
        });
    }

    public static void rx(java.util.UUID ch, byte[] v) {
        line("RX " + ch.toString().substring(0, 8), v);
    }

    public static void tx(byte[] v) {
        line("TX -", v);
    }

    /**
     * What the rider can mark in a capture while testing at the bike: tapped the moment it is done, so
     * tools/frame_bytes.py can show which bytes changed with it. These are the things the frames might
     * carry that neither Rideology nor KawaNav decodes yet. Switching the ignition on or off is not one:
     * the link comes up after the dash does and goes with it, so no capture is open at that moment.
     * Engine start, the indicators, the side stand, both brakes and the clutch are done, and the gear is
     * decoded already (docs/PROTOCOL.md, "Test marks").
     */
    public static final String[] MARKS = {
            "Engine stop", "High beam on", "High beam off", "Horn",
            "Riding mode changed on the dash", "KTRC changed on the dash", "Trip reset on the dash",
            "Dash page changed", "Cruise control on", "Cruise control off", "Something else"};

    /** A capture line for a mark: {@code epochMs MARK label}, the label in one word (spaces become _). */
    static String markLine(long at, String label) {
        String word = label == null || label.trim().isEmpty() ? "mark" : label.trim().replaceAll("\\s+", "_");
        return at + " MARK " + word + "\n";
    }

    /** A capture file is open (capturing is on and the bike connected since). */
    public static boolean writing() {
        return out != null;
    }

    /** Marks the moment in the open capture; false when none is open. Replays skip these lines. */
    public static boolean mark(String label) {
        if (out == null) return false;
        String l = markLine(System.currentTimeMillis(), label);
        io.execute(() -> {
            if (out == null) return;
            try {
                out.write(l);
                out.flush(); // a mark is rare and should survive the app being killed
            } catch (IOException e) {
                close();
            }
        });
        return true;
    }

    private static void line(String what, byte[] v) {
        String l = System.currentTimeMillis() + " " + what + " " + KawaProtocol.hex(v).replace(" ", "") + "\n";
        io.execute(() -> {
            if (out == null) return;
            try {
                out.write(l);
                written += l.length();
                if (written % 4096 < l.length()) out.flush();
            } catch (IOException e) {
                close();
            }
        });
    }

    /** Ends the current file (link lost, bridge stopped). */
    public static void stop() {
        io.execute(RawCapture::close);
    }

    private static void close() {
        if (out == null) return;
        try {
            out.close();
        } catch (IOException ignored) {
        }
        out = null;
    }

    /** Deletes the oldest captures until all together are under the limit. */
    private static void trim(File d) {
        File[] files = d.listFiles();
        if (files == null) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        long total = 0;
        for (File f : files) total += f.length();
        for (File f : files) {
            if (total <= MAX_BYTES) break;
            total -= f.length();
            f.delete();
        }
    }
}
