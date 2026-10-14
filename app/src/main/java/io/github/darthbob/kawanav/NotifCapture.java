package io.github.darthbob.kawanav;

import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Debug builds: writes down what apps put in their notifications, so a navigation app that does not
 * follow Google Maps' pattern can be checked on a real phone before KawaNav learns to read it.
 * One file per run plus the icons as PNGs, in Android/data/io.github.darthbob.kawanav/files/notifications/
 * (adb pull, or the export button on the debug card). A notification is written when it first appears
 * and whenever its content changes, not on every update.
 *
 * <p>It copies notification text of other apps, so switch it off again after the test. Everything is
 * built and written on the {@code notif-io} thread: the notification listener's callbacks are on the
 * main thread, and loading another app's icon and hashing it is too much work for it.
 */
public final class NotifCapture {
    private NotifCapture() {}

    /** The app this was built for: it decides what to look at first. */
    public static final String WAZE = "com.waze";
    private static final long MAX_FILE_BYTES = 5_000_000;
    private static final long MAX_TOTAL_BYTES = 20_000_000;
    private static final int MAX_VALUE_CHARS = 200;
    private static final long STATUS_REFRESH_MS = 5000;

    private static final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "notif-io"));
    private static Context appContext;
    /** notif-io thread only. */
    private static Writer out;
    private static long written;
    /** package|id|tag -> last written content, so only changes reach the file. notif-io thread only. */
    private static final Map<String, String> lastState = new HashMap<>();
    /** What the debug card shows: written on the notif-io thread, read by the UI. */
    private static volatile String status = "Nothing captured yet";
    private static volatile long statusAt;

    public static File dir(Context c) {
        File d = new File(c.getExternalFilesDir(null), "notifications");
        d.mkdirs();
        return d;
    }

    public static File iconsDir(Context c) {
        File d = new File(dir(c), "icons");
        d.mkdirs();
        return d;
    }

    /** Capturing is on (debug builds, switched on in the debug card or by adb). */
    public static boolean enabled(Context c) {
        return Hub.DEBUG_TOOLS && Hub.prefs(c).getBoolean(Hub.KEY_DEBUG_NOTIF_CAPTURE, false);
    }

    /** Every app, not only the ones worth watching (the switch on the debug card). */
    public static boolean allApps(Context c) {
        return Hub.prefs(c).getBoolean(Hub.KEY_DEBUG_NOTIF_CAPTURE_ALL, false);
    }

    /**
     * True for notifications that get written down: with "every app" the whole list (minus KawaNav
     * itself), otherwise only the watched ones. The shell is included, so a test can post a
     * notification from adb.
     */
    public static boolean watching(Context c, String pkg) {
        if (!enabled(c) || c.getPackageName().equals(pkg)) return false;
        return allApps(c) || WAZE.equals(pkg) || "com.android.shell".equals(pkg);
    }

    public static void onPosted(Context c, StatusBarNotification sbn) {
        if (!watching(c, sbn.getPackageName())) return;
        appContext = c.getApplicationContext();
        final String pkg = sbn.getPackageName();
        final int id = sbn.getId();
        final String tag = sbn.getTag();
        io.execute(() -> {
            Notification n = sbn.getNotification();
            Bitmap small = bitmapOf(appContext, n.getSmallIcon());
            Bitmap large = bitmapOf(appContext, n.getLargeIcon());
            String body = describe(pkg, id, tag, n, small, large);
            String key = pkg + "|" + id + "|" + tag;
            if (body.equals(lastState.get(key))) return;   // the notification only ticked over
            lastState.put(key, body);
            line("\n" + stamp() + "  " + body);
            saveIcon(small);
            saveIcon(large);
        });
    }

    public static void onRemoved(Context c, StatusBarNotification sbn) {
        if (!watching(c, sbn.getPackageName())) return;
        appContext = c.getApplicationContext();
        final String pkg = sbn.getPackageName();
        final int id = sbn.getId();
        final String tag = sbn.getTag();
        io.execute(() -> {
            lastState.remove(pkg + "|" + id + "|" + tag);
            line(stamp() + "  REMOVED  " + pkg + "  id=" + id + " tag=" + tag);
        });
    }

    /**
     * Everything a reader needs to tell one notification from another and to see what changed. No
     * timestamp and no post time: those change on every update, and this string is what decides
     * whether the notification is written again.
     */
    private static String describe(String pkg, int id, String tag, Notification n, Bitmap small, Bitmap large) {
        StringBuilder sb = new StringBuilder(pkg)
                .append("  id=").append(id)
                .append(" tag=").append(tag)
                .append(" channel=").append(n.getChannelId())
                .append(" category=").append(n.category)
                .append(" flags=0x").append(Integer.toHexString(n.flags))
                .append(" ongoing=").append((n.flags & Notification.FLAG_ONGOING_EVENT) != 0)
                .append(" group=").append(n.getGroup())
                .append(" layout=").append(n.bigContentView != null ? "big" : n.contentView != null ? "content" : "no")
                .append(" progress=").append(progress(n));

        Bundle ex = n.extras;
        text(sb, "TITLE", ex.getCharSequence(Notification.EXTRA_TITLE));
        text(sb, "TEXT", ex.getCharSequence(Notification.EXTRA_TEXT));
        text(sb, "SUB", ex.getCharSequence(Notification.EXTRA_SUB_TEXT));
        text(sb, "BIG", ex.getCharSequence(Notification.EXTRA_BIG_TEXT));
        text(sb, "INFO", ex.getCharSequence(Notification.EXTRA_INFO_TEXT));
        text(sb, "SUMMARY", ex.getCharSequence(Notification.EXTRA_SUMMARY_TEXT));
        text(sb, "TICKER", n.tickerText);
        if (n.actions != null && n.actions.length > 0) {
            sb.append("\n    ACTIONS ").append(n.actions.length);
            for (Notification.Action a : n.actions) {
                sb.append(" [").append(a.title == null ? "" : a.title).append(']');
            }
        }
        if (n.visibility != Notification.VISIBILITY_PRIVATE) sb.append("\n    VISIBILITY ").append(n.visibility);
        icon(sb, "small", n.getSmallIcon(), small);
        icon(sb, "large", n.getLargeIcon(), large);
        for (String k : ex.keySet()) {
            Object v = ex.get(k);
            if (v == null || v instanceof CharSequence || v instanceof Bitmap) continue; // texts above, bitmaps below
            String s = String.valueOf(v);
            if (s.length() > MAX_VALUE_CHARS) s = s.substring(0, MAX_VALUE_CHARS) + "…";
            sb.append("\n    EXTRA ").append(k).append(" = ").append(s);
        }
        return sb.toString();
    }

    private static String progress(Notification n) {
        Bundle ex = n.extras;
        int max = ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0);
        int value = ex.getInt(Notification.EXTRA_PROGRESS, -1);
        boolean indeterminate = ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false);
        if (max == 0 && value < 0) return "none";
        return value + "/" + max + (indeterminate ? " indeterminate" : "");
    }

    /**
     * Records the icon's kind and, when it could be loaded, the shape hash and the name of the PNG
     * that {@link #saveIcon} writes beside the dump (a resource icon is the app's own logo, not a turn).
     */
    private static void icon(StringBuilder sb, String label, Icon ic, Bitmap b) {
        if (ic == null) return;
        sb.append("\n    ICON ").append(label).append('=');
        int type = ic.getType();
        if (type == Icon.TYPE_RESOURCE) {
            sb.append("resource ").append(ic.getResPackage()).append('/').append(ic.getResId());
        } else if (type == Icon.TYPE_URI) {
            sb.append("uri ").append(ic.getUri());
        } else {
            sb.append("type ").append(type);
        }
        if (b != null) sb.append(' ').append(b.getWidth()).append('x').append(b.getHeight())
                .append(" icons/").append(IconMemory.shortId(IconMemory.hash(b))).append(".png");
    }

    /** Writes an icon as PNG, named by its shape hash, so two of them can be compared later. */
    private static void saveIcon(Bitmap b) {
        if (b == null || appContext == null) return;
        File f = new File(iconsDir(appContext), IconMemory.shortId(IconMemory.hash(b)) + ".png");
        if (f.exists()) return;
        try (FileOutputStream o = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
        } catch (IOException e) {
            Logger.log("Notification capture: icon " + f.getName() + " not saved: " + e);
        }
    }

    private static Bitmap bitmapOf(Context c, Icon ic) {
        if (ic == null) return null;
        try {
            Drawable d = ic.loadDrawable(c);
            if (d == null) return null;
            if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
                return ((BitmapDrawable) d).getBitmap();
            }
            return IconMemory.render(d);
        } catch (RuntimeException e) {
            return null;   // another app's icon that cannot be loaded here
        }
    }

    private static void text(StringBuilder sb, String label, CharSequence value) {
        if (value == null || value.length() == 0) return;
        String s = value.toString();
        if (s.length() > MAX_VALUE_CHARS) s = s.substring(0, MAX_VALUE_CHARS) + "…";
        sb.append("\n    ").append(label).append(": ").append(s);
    }

    private static String stamp() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
                .format(new java.util.Date()) + "  " + System.currentTimeMillis();
    }

    /** Adds a line to the current file, opening it (and a header) on the first write. notif-io only. */
    private static void line(String text) {
        try {
            if (out == null) {
                File d = dir(appContext);
                trim(d);
                String name = "notif-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
                        .format(new java.util.Date()) + ".txt";
                out = new java.io.BufferedWriter(new FileWriter(new File(d, name)));
                written = 0;
                out.write("KawaNav " + BuildConfig.VERSION_NAME + " notification capture, "
                        + (allApps(appContext) ? "every app" : "Waze and the shell") + "\n"
                        + "Each block is one notification the first time it appears or when it changes;"
                        + " icons are in icons/<hash>.png.\n"
                        + "Copy the notification text of other apps: switch the capture off after the test.\n");
                Logger.log("Notification capture: writing " + name);
            }
            out.write(text);
            out.write('\n');
            written += text.length();
            out.flush();   // the phone can be killed while riding
            status = statusOf();
            statusAt = System.currentTimeMillis();
            if (written > MAX_FILE_BYTES) close();
        } catch (IOException e) {
            close();
        }
    }

    /** Ends the current file (the capture is switched off). */
    public static void stop() {
        io.execute(NotifCapture::close);
    }

    private static void close() {
        if (out == null) return;
        try {
            out.close();
        } catch (IOException ignored) {
        }
        out = null;
    }

    /**
     * What the debug card shows: the newest file, what is stored in total and how many icons there
     * are. The folder is listed on the notif-io thread, at most every {@link #STATUS_REFRESH_MS}, so
     * the card can ask for it from the UI every half second.
     */
    public static String status(Context c) {
        appContext = c.getApplicationContext();
        long now = System.currentTimeMillis();
        if (out == null && now - statusAt > STATUS_REFRESH_MS) {
            statusAt = now;
            io.execute(() -> status = statusOf());
        }
        return status;
    }

    /** notif-io thread only. */
    private static String statusOf() {
        Context c = appContext;
        if (c == null) return status;
        File d = dir(c);
        File[] files = d.listFiles((dir, name) -> name.startsWith("notif-") && name.endsWith(".txt"));
        File[] pngs = iconsDir(c).listFiles();
        int icons = pngs == null ? 0 : pngs.length;
        if (files == null || files.length == 0) return "Nothing captured yet. Icons: " + icons;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        long total = 0;
        for (File f : files) total += f.length();
        return files[0].getName() + " (" + total / 1024 + " kB in " + files.length + " files), " + icons + " icons";
    }

    /** Deletes the oldest files until all together are under the limit. notif-io thread only. */
    private static void trim(File d) {
        File[] files = d.listFiles((dir, name) -> name.startsWith("notif-") && name.endsWith(".txt"));
        if (files == null) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        long total = 0;
        for (File f : files) total += f.length();
        for (File f : files) {
            if (total <= MAX_TOTAL_BYTES) break;
            total -= f.length();
            f.delete();
        }
    }
}
