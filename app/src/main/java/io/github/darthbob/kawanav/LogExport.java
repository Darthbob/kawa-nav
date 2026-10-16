package io.github.darthbob.kawanav;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Copies the log, and with the notification capture on its files and icons, to Download/KawaNav,
 * where adb and file managers can reach them: {@code adb pull /sdcard/Download/KawaNav/}.
 * Runs on the log's own thread (see {@link Logger#withLogFiles}), so no line is written or the file
 * rotated while it is copied.
 */
public final class LogExport {
    private LogExport() {}

    /** Copies everything of the last runs; returns how many files were written. */
    public static int export(Context c) {
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new java.util.Date());
        int n = 0;
        for (String name : new String[]{"kawanav.log.1", "kawanav.log"}) {
            File f = new File(c.getFilesDir(), name);
            if (f.exists() && copy(c, f, stamp + "-" + name + ".txt")) n++;
        }
        File dir = NotifCapture.dir(c);
        File[] dumps = dir.listFiles((d, name) -> name.startsWith("notif-") && name.endsWith(".txt"));
        if (dumps != null) {
            java.util.Arrays.sort(dumps, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (File f : dumps) if (copy(c, f, stamp + "-" + f.getName())) n++;
        }
        File[] icons = NotifCapture.iconsDir(c).listFiles((d, name) -> name.endsWith(".png"));
        if (icons != null) {
            java.util.Arrays.sort(icons, (a, b) -> a.getName().compareTo(b.getName()));
            for (File f : icons) if (copy(c, f, stamp + "-icons-" + f.getName())) n++;
        }
        Logger.log("Exported " + n + " files to Download/KawaNav");
        return n;
    }

    private static boolean copy(Context c, File f, String name) {
        ContentResolver cr = c.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, name);
        v.put(MediaStore.Downloads.MIME_TYPE, name.endsWith(".png") ? "image/png" : "text/plain");
        v.put(MediaStore.Downloads.RELATIVE_PATH, "Download/KawaNav");
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) return false;
        try (OutputStream out = cr.openOutputStream(uri)) {
            java.nio.file.Files.copy(f.toPath(), out);
            return true;
        } catch (IOException e) {
            Logger.log("Export of " + name + " failed: " + e);
            return false;
        }
    }
}
