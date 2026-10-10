package io.github.darthbob.kawanav;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

import java.io.File;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Downloads the Geofabrik extract of a state / country ({@link #EXTRA_REGION}), converts it with
 * {@link PbfImporter} into the tiles {@link CurveEngine} reads and records the region in
 * {@link OfflineRegions}. Downloading a region again updates it.
 */
public class OfflineService extends Service {

    public static final String EXTRA_REGION = "offline_region"; // Geofabrik id, e.g. "bayern"

    private static final String CHANNEL = "offline";
    private static final int NOTIFICATION_ID = 3;

    private static volatile boolean cancel;
    private Thread worker;

    public static void startRegion(Context c, String regionId) {
        c.startForegroundService(new Intent(c, OfflineService.class).putExtra(EXTRA_REGION, regionId));
    }

    public static void stop(Context c) {
        cancel = true;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Logger.init(this);
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Offline road download", NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIFICATION_ID, notification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String region = intent == null ? null : intent.getStringExtra(EXTRA_REGION);
        if (region == null || worker != null) return START_NOT_STICKY;
        cancel = false;
        Hub.offlineRunning = true;
        // keep the CPU on with the screen off: converting a state takes a few minutes
        android.os.PowerManager.WakeLock wl = getSystemService(android.os.PowerManager.class)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "KawaNav:offline");
        wl.acquire(3 * 3600_000L);
        worker = new Thread(() -> {
            try {
                runRegion(region);
            } catch (PbfImporter.CancelledException e) {
                status("Cancelled (the download resumes when you start it again)");
                Logger.log("Offline download cancelled");
            } catch (Throwable e) {
                status("Failed: " + (e instanceof OutOfMemoryError ? "region too large for this phone, pick a smaller one" : e.getMessage()));
                Logger.log("Offline download failed: " + e);
            } finally {
                if (wl.isHeld()) wl.release();
                Hub.offlineRunning = false;
                stopSelf();
            }
        }, "offline-download");
        worker.start();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        cancel = true;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void runRegion(String id) throws Exception {
        Geofabrik.Region r = null;
        status("Loading the region list…");
        for (Geofabrik.Region x : Geofabrik.regions(this)) if (x.id.equals(id)) r = x;
        if (r == null) throw new Exception("unknown region " + id);
        String label = r.name;
        Logger.log("Offline region: " + r.label() + " from " + r.pbfUrl);
        File dir = new File(getCacheDir(), "geofabrik");
        dir.mkdirs();
        File poly = new File(dir, id + ".poly");
        Geofabrik.download(r.polyUrl(), poly, null);
        File pbf = new File(dir, id + ".osm.pbf");
        long[] head = Geofabrik.head(r.pbfUrl);
        long size = head[0];
        long dataDate = head[1] > 0 ? head[1] : System.currentTimeMillis();
        // the file plus temporary files while converting (the file is deleted before the tiles are written)
        long free = dir.getUsableSpace() + (pbf.exists() ? pbf.length() : 0)
                + new File(pbf.getPath() + ".part").length();
        if (size > 0 && free < size * 1.25 + 500e6) {
            throw new Exception(String.format(Locale.ROOT, "not enough free storage: %s needs about %.1f GB, %.1f GB free",
                    label, (size * 1.25 + 500e6) / 1e9, free / 1e9));
        }
        long t0 = System.currentTimeMillis();
        // a complete file from a cancelled conversion today is reused
        if (!pbf.exists() || System.currentTimeMillis() - pbf.lastModified() > 12 * 3600_000L)
        Geofabrik.download(r.pbfUrl, pbf, new Geofabrik.Progress() {
            @Override
            public void bytes(long done, long total) {
                status(String.format(Locale.ROOT, "%s: downloading %.0f / %.0f MB", label, done / 1e6, total / 1e6));
            }

            @Override
            public boolean cancelled() {
                return cancel;
            }
        });
        Logger.log(String.format(Locale.ROOT, "Offline region: %.0f MB downloaded in %d s", pbf.length() / 1e6,
                (System.currentTimeMillis() - t0) / 1000));
        long t1 = System.currentTimeMillis();
        RoadStore store = new RoadStore(this);
        Set<String> tiles = new HashSet<>();
        store.record(tiles);
        int[] res;
        try {
            res = new PbfImporter(pbf, PbfImporter.Polygon.read(poly), store, new File(dir, "tmp"), new PbfImporter.Progress() {
                @Override
                public void status(String s) {
                    OfflineService.this.status(label + ": " + s);
                }

                @Override
                public boolean cancelled() {
                    return cancel;
                }
            }).deleteWhenRead().run();
        } finally {
            if (!cancel) {
                pbf.delete(); // converted (or broken): the next run downloads a fresh one
                poly.delete();
            }
        }
        OfflineRegions regions = new OfflineRegions(this, store);
        regions.save(id, r.label(), r.pbfUrl, dataDate, tiles);
        String msg = String.format(Locale.ROOT, "Done: %s, %d roads in %d tiles (+%d border tiles) · %.1f MB · %d s",
                label, res[0], res[1], res[2], regions.bytes(tiles) / 1e6, (System.currentTimeMillis() - t1) / 1000);
        status(msg);
        Logger.log("Offline region " + msg);
    }

    private void status(String s) {
        Hub.offlineStatus = s;
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(s));
    }

    private Notification notification(String text) {
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("KawaNav offline roads")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }
}
