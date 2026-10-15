package io.github.darthbob.kawanav;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * In-app updates from GitHub. The newest release (not drafts or pre-releases) is the update; its
 * tag is "v" + versionName and it carries the APK as asset. What changed comes from changelog.json
 * on main: every entry newer than the installed version up to the release.
 *
 * Releasing (see AGENTS.md): raise versionCode/versionName in app/build.gradle and add the entry
 * on top of changelog.json; on main, GitHub Actions builds, tags and publishes the release.
 *
 * The APK is written into a PackageInstaller session; Android's installer asks the user to confirm
 * and only accepts it when it is signed with the same key as the installed app.
 */
public final class Updater {
    private Updater() {}

    private static final String REPO = "Darthbob/kawa-nav";
    private static final String LATEST_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String CHANGELOG_URL = "https://raw.githubusercontent.com/" + REPO + "/main/changelog.json";
    private static final String USER_AGENT = "KawaNav/" + BuildConfig.VERSION_NAME;

    public static final class Release {
        public final String version, apkUrl, changes;
        public final long apkSize; // -1 = unknown

        Release(String version, String apkUrl, long apkSize, String changes) {
            this.version = version;
            this.apkUrl = apkUrl;
            this.apkSize = apkSize;
            this.changes = changes;
        }
    }

    public interface Progress {
        void bytes(long done, long total);
    }

    /** The newest release if it is newer than this app, else null (network: not on the main thread). */
    public static Release check() throws Exception {
        JSONObject latest = new JSONObject(get(LATEST_URL));
        String version = latest.getString("tag_name").replaceFirst("^v", "");
        if (compare(version, BuildConfig.VERSION_NAME) <= 0) return null;
        JSONArray assets = latest.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (!a.getString("name").endsWith(".apk")) continue;
            return new Release(version, a.getString("browser_download_url"), a.optLong("size", -1), changes(version));
        }
        Logger.log("Update " + version + " has no APK");
        return null;
    }

    /** "Version 0.4\n• …\n\nVersion 0.3\n• …" for the versions after this app's up to {@code upTo}. */
    private static String changes(String upTo) {
        StringBuilder sb = new StringBuilder();
        try {
            JSONArray versions = new JSONObject(get(CHANGELOG_URL)).getJSONArray("versions");
            for (int i = 0; i < versions.length(); i++) {
                JSONObject v = versions.getJSONObject(i);
                String version = v.getString("version");
                if (compare(version, BuildConfig.VERSION_NAME) <= 0 || compare(version, upTo) > 0) continue;
                if (sb.length() > 0) sb.append("\n\n");
                sb.append("Version ").append(version);
                JSONArray list = v.getJSONArray("changes");
                for (int k = 0; k < list.length(); k++) sb.append("\n• ").append(list.getString(k));
            }
        } catch (Exception e) {
            Logger.log("Changelog not available: " + e);
        }
        return sb.length() > 0 ? sb.toString() : "No list of changes for this version.";
    }

    /** Version names compared by their numbers: 0.10 &gt; 0.9 &gt; 0.4.1 &gt; 0.4. */
    static int compare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int d = Integer.compare(number(x, i), number(y, i));
            if (d != 0) return d;
        }
        return 0;
    }

    private static int number(String[] parts, int i) {
        if (i >= parts.length) return 0;
        try {
            return Integer.parseInt(parts[i].replaceAll("\\D.*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Downloads the APK into an install session and commits it: Android then shows its install
     * dialog (see {@link InstallResult}). Network: not on the main thread.
     */
    public static void install(Context c, Release r, Progress progress) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        for (PackageInstaller.SessionInfo s : pi.getMySessions()) pi.abandonSession(s.getSessionId()); // earlier tries
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE);
        if (r.apkSize > 0) params.setSize(r.apkSize);
        int id = pi.createSession(params);
        try (PackageInstaller.Session session = pi.openSession(id)) {
            HttpURLConnection conn = open(r.apkUrl);
            try {
                if (conn.getResponseCode() != 200) throw new IOException("HTTP " + conn.getResponseCode());
                long total = conn.getContentLengthLong();
                try (InputStream in = conn.getInputStream(); OutputStream out = session.openWrite("KawaNav.apk", 0, total)) {
                    byte[] b = new byte[1 << 16];
                    long done = 0;
                    int k;
                    while ((k = in.read(b)) > 0) {
                        out.write(b, 0, k);
                        done += k;
                        progress.bytes(done, total);
                    }
                    if (total >= 0 && done != total) throw new IOException("download interrupted");
                    session.fsync(out);
                }
            } finally {
                conn.disconnect();
            }
            Intent result = new Intent(c, InstallResult.class);
            session.commit(PendingIntent.getBroadcast(c, id, result,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE).getIntentSender());
            Logger.log("Update " + r.version + " downloaded, asking to install");
        } catch (Exception e) {
            pi.abandonSession(id);
            throw e;
        }
    }

    /** Answers of the install session: opens Android's install dialog, reports failures. */
    public static class InstallResult extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
                if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
            if (status == PackageInstaller.STATUS_SUCCESS) return; // the new version is running now
            String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Logger.log("Update not installed: " + status + " " + msg);
            if (status == PackageInstaller.STATUS_FAILURE_ABORTED) return; // the user tapped Cancel
            Toast.makeText(c, status == PackageInstaller.STATUS_FAILURE_CONFLICT
                            || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE
                            ? "Update failed: this KawaNav is not signed like the release (debug build?)"
                            : "Update failed" + (msg == null ? "" : ": " + msg),
                    Toast.LENGTH_LONG).show();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", USER_AGENT);
        return c;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = open(url);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode() + " from " + url);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int k;
                while ((k = in.read(b)) > 0) buf.write(b, 0, k);
                return buf.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }
}
