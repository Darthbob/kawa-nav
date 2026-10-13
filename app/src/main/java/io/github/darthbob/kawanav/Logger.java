package io.github.darthbob.kawanav;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * App-wide log: logcat (tag KawaNav) and a file readable with
 * {@code adb shell run-as io.github.darthbob.kawanav cat files/kawanav.log} (rotated to .1 at 2 MB).
 * Lines are stamped on the main thread (keeps their order) and written to the file on a background
 * thread, so logging never blocks the bike link or the UI.
 */
public final class Logger {
    private Logger() {}

    private static final String TAG = "KawaNav";
    private static final String FILE = "kawanav.log";
    private static final long MAX_FILE_BYTES = 2_000_000;

    private static final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.ExecutorService io =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "log-io"));
    private static File dir;

    public static void init(Context context) {
        if (dir == null) dir = context.getApplicationContext().getFilesDir();
    }

    public static void log(String line) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> log(line));
            return;
        }
        Log.i(TAG, line);
        String stamped = fmt.format(new Date()) + "  " + line;
        File d = dir;
        if (d != null) io.execute(() -> appendToFile(d, stamped));
    }

    /** Runs {@code r} on the main thread once every line logged so far is in the file (e.g. before exporting it). */
    public static void afterWrites(Runnable r) {
        io.execute(() -> main.post(r));
    }

    private static void appendToFile(File dir, String line) {
        File f = new File(dir, FILE);
        if (f.length() > MAX_FILE_BYTES) {
            File old = new File(dir, FILE + ".1");
            old.delete();
            f.renameTo(old);
        }
        try (FileWriter w = new FileWriter(f, true)) {
            w.write(line + "\n");
        } catch (IOException e) {
            Log.w(TAG, "log file write failed", e);
        }
    }
}
