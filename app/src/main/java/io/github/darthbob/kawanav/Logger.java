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
    private static final Handler main = mainHandler();
    private static final java.util.concurrent.ExecutorService io =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "log-io"));
    private static File dir;

    /**
     * The main-thread handler, or null where there is none. On a plain JVM (the unit tests) there is no
     * Android looper at all: asking for one throws, which would take the class down on its first use and
     * with it every store that logs. {@link #log} prints to the console there instead.
     */
    private static Handler mainHandler() {
        try {
            return new Handler(Looper.getMainLooper());
        } catch (Throwable noAndroid) {
            return null;
        }
    }

    public static void init(Context context) {
        if (dir == null) dir = context.getApplicationContext().getFilesDir();
    }

    /**
     * From then on an uncaught exception, on any thread, goes into the log file before Android ends the
     * process, so "Export the log" carries the crash with what led up to it. Written on the crashing
     * thread itself, not through {@link #log}: that one posts to the main thread and a background
     * writer, neither of which runs again once the process is being killed. Android's own handler
     * (the "KawaNav keeps stopping" dialog, the tombstone) runs afterwards as before.
     */
    public static void installCrashHandler() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        if (previous instanceof CrashHandler) return;
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(previous));
    }

    private static final class CrashHandler implements Thread.UncaughtExceptionHandler {
        private final Thread.UncaughtExceptionHandler previous;

        CrashHandler(Thread.UncaughtExceptionHandler previous) {
            this.previous = previous;
        }

        @Override
        public void uncaughtException(Thread t, Throwable e) {
            try {
                warn("crash", e);
                File d = dir;
                if (d != null) appendToFile(d, crashLine(t.getName(), e));
            } catch (Throwable ignored) {
                // nothing left to do for it; the crash itself must still reach Android
            }
            if (previous != null) previous.uncaughtException(t, e);
        }
    }

    /**
     * The log entry for a crash: the stamp, the thread and the whole stack trace with its causes, as
     * {@link Throwable#printStackTrace} prints it. Package-private for the tests.
     */
    static String crashLine(String thread, Throwable e) {
        java.io.StringWriter trace = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(trace));
        String stamp;
        synchronized (fmt) { // SimpleDateFormat is not thread-safe, and the main thread may be stamping a line
            stamp = fmt.format(new Date());
        }
        return stamp + "  CRASH on thread \"" + thread + "\": " + trace.toString().trim();
    }

    public static void log(String line) {
        if (main == null) {
            System.out.println(line);
            return;
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> log(line));
            return;
        }
        Log.i(TAG, line);
        String stamped;
        synchronized (fmt) {
            stamped = fmt.format(new Date()) + "  " + line;
        }
        File d = dir;
        if (d != null) io.execute(() -> appendToFile(d, stamped));
    }

    /**
     * Runs {@code r} on the log's background thread, after every line logged so far is in the file and
     * before any later one (e.g. to copy the files without them changing underneath).
     */
    public static void withLogFiles(Runnable r) {
        io.execute(() -> {
            try {
                r.run();
            } catch (Throwable e) {
                // this is the log's only thread: let the caller's failure be read, not the thread's death
                warn("log export failed", e);
            }
        });
    }

    /** Rotates at the size cap, then appends. Package-private so the tests can drive the rotation. */
    static void appendToFile(File dir, String line) {
        File f = new File(dir, FILE);
        if (f.length() > MAX_FILE_BYTES) {
            // One move, replacing the old .1 in the same step. Deleting .1 first and then ignoring the
            // true/false of renameTo meant a failed rename threw the previous backup away *and* left the
            // full file in place, so the cap never took effect again and the log grew without bound.
            File old = new File(dir, FILE + ".1");
            try {
                java.nio.file.Files.move(f.toPath(), old.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                warn("log rotation failed", e); // keep appending; it is retried on the next line
            }
        }
        try (FileWriter w = new FileWriter(f, true)) {
            w.write(line + "\n");
        } catch (IOException e) {
            warn("log file write failed", e);
        }
    }

    /** {@link Log} throws on a plain JVM, where it is a stub: a diagnostic must not take the log down. */
    private static void warn(String message, Throwable e) {
        try {
            Log.w(TAG, message, e);
        } catch (Throwable noAndroid) {
            System.out.println(message + ": " + e);
        }
    }
}
