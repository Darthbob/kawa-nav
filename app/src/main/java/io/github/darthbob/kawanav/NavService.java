package io.github.darthbob.kawanav;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Turn-by-turn navigation along {@link Hub#route}: follows the GPS with a {@link Navigator}, publishes
 * the next maneuver in {@link Hub#nav} (the bridge sends it to the dash, the screens show it), speaks
 * the instructions in the phone's language and computes a new route when the rider leaves this one.
 * Runs as a location foreground service, so it goes on with the screen off and without the bike.
 */
@SuppressLint("MissingPermission") // the planner asks for the location permission before starting
public class NavService extends Service implements LocationListener {

    private static final String CHANNEL = "navigation";
    private static final int NOTIFICATION_ID = 5;
    private static final String ACTION_STOP = "io.github.darthbob.kawanav.NAV_STOP";
    /** After a failed new route, the next try waits this long. */
    private static final long REROUTE_RETRY_MS = 15_000;
    private static final double LOOKAHEAD_M = 3000;
    private static final double MIN_SPEED_FOR_BEARING = 2.0;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "nav-route"));
    private Navigator navigator;
    private RouteGraph graph;
    private CurveSound voice;
    private NavText text;
    private boolean rerouting;
    private long rerouteFailedAt;
    private Location lastBearingFix;
    private double heading = Double.NaN;
    private String lastNotification = "";

    public static void start(Context c) {
        c.startForegroundService(new Intent(c, NavService.class));
    }

    public static void stop(Context c) {
        c.startService(new Intent(c, NavService.class).setAction(ACTION_STOP));
    }

    public static boolean running() {
        return Hub.nav != null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Logger.init(this);
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Navigation", NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIFICATION_ID, notification("Starting navigation…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Logger.log("Navigation stopped by the rider");
            stopSelf();
            return START_NOT_STICKY;
        }
        Route route = Hub.route;
        if (route == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            graph = RoutingData.graph(this);
        } catch (Exception e) {
            Logger.log("Navigation: routing data unreadable: " + e);
            stopSelf();
            return START_NOT_STICKY;
        }
        text = NavText.phone();
        navigator = new Navigator(graph, route, text, leftHand(), Hub.prefs(this).getBoolean(Hub.KEY_IMPERIAL, false));
        Hub.nav = new Hub.Nav(KawaProtocol.TURN_NONE, -1, text.action(navigator.maneuvers().get(0)), route.length,
                route.time, Navigator.State.NAVIGATING);
        if (Hub.prefs(this).getBoolean(Hub.KEY_NAV_VOICE, true) && voice == null) voice = new CurveSound(this);
        ((LocationManager) getSystemService(Context.LOCATION_SERVICE))
                .requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this, Looper.getMainLooper());
        Logger.log(String.format(Locale.ROOT, "Navigation started: %.1f km, %s, %d maneuvers", route.length / 1000,
                route.profile.kind, navigator.maneuvers().size()));
        return START_NOT_STICKY;
    }

    private boolean leftHand() {
        return Hub.prefs(this).getBoolean(Hub.KEY_LEFT_HAND, false);
    }

    @Override
    public void onLocationChanged(Location loc) {
        if (navigator == null) return;
        Hub.lastFix = loc;
        updateHeading(loc);
        Navigator.Update u = navigator.onFix(loc.getLatitude(), loc.getLongitude(), heading,
                loc.hasSpeed() ? loc.getSpeed() : 0);
        String shown = u.state == Navigator.State.ARRIVED ? text.action(navigator.maneuvers().get(navigator.maneuvers().size() - 1))
                : u.state == Navigator.State.OFF_ROUTE ? text.recalculating()
                : u.next == null ? "" : text.upcoming(u.next);
        Hub.nav = new Hub.Nav(u.turn(), u.state == Navigator.State.ARRIVED ? 0 : (int) Math.round(u.toNext), shown,
                u.remaining, u.remainingTime, u.state);
        Hub.navAhead = u.state == Navigator.State.NAVIGATING ? navigator.pathAhead(LOOKAHEAD_M) : null;
        Hub.navRoundabouts = navigator.roundaboutsAhead(LOOKAHEAD_M);
        if (u.say != null) say(u.say);
        boolean imperial = Hub.prefs(this).getBoolean(Hub.KEY_IMPERIAL, false);
        String note = u.state != Navigator.State.NAVIGATING || u.next == null ? shown
                : text.distance(u.toNext, imperial) + " · " + shown + " · "
                + (imperial ? String.format(Locale.ROOT, "%.0f mi", u.remaining / 1609.344)
                : String.format(Locale.ROOT, "%.0f km", u.remaining / 1000));
        if (!note.equals(lastNotification)) {
            lastNotification = note;
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(note));
        }
        if (u.state == Navigator.State.OFF_ROUTE) reroute(loc);
        if (u.state == Navigator.State.ARRIVED) {
            Logger.log("Navigation: arrived");
            main.postDelayed(this::stopSelf, 20_000);
            navigator = null;
        }
    }

    private void say(String s) {
        if (voice == null) return;
        if (Hub.prefs(this).getBoolean(Hub.KEY_CURVE_SOUND_HEADSET, true) && !CurveSound.headsetConnected(this)) return;
        voice.say(s);
    }

    /** A new route from here to what is left of the old one, in the background. */
    private void reroute(Location loc) {
        if (rerouting || System.currentTimeMillis() - rerouteFailedAt < REROUTE_RETRY_MS) return;
        rerouting = true;
        Route old = navigator.route();
        List<double[]> pts = new ArrayList<>();
        pts.add(new double[]{loc.getLatitude(), loc.getLongitude()});
        pts.addAll(navigator.targets());
        double h = heading;
        boolean lh = leftHand();
        worker.execute(() -> {
            Route next = null;
            try {
                Router.Request req = new Router.Request(pts, old.profile);
                req.startHeading = h;
                req.leftHandTraffic = lh;
                next = new Router(graph).route(req);
                next.roundTrip = old.roundTrip;
            } catch (Exception e) {
                Logger.log("Navigation: no new route (" + e.getMessage() + ")");
            }
            Route found = next;
            main.post(() -> {
                rerouting = false;
                if (navigator == null) return;
                if (found == null) {
                    rerouteFailedAt = System.currentTimeMillis();
                    return;
                }
                navigator.replace(found);
                Hub.route = found;
                Logger.log(String.format(Locale.ROOT, "Navigation: new route, %.1f km", found.length / 1000));
            });
        });
    }

    private void updateHeading(Location loc) {
        if (loc.hasBearing() && loc.hasSpeed() && loc.getSpeed() >= MIN_SPEED_FOR_BEARING) {
            heading = loc.getBearing();
            lastBearingFix = loc;
        } else if (lastBearingFix != null && lastBearingFix.distanceTo(loc) > 8) {
            heading = lastBearingFix.bearingTo(loc);
            if (heading < 0) heading += 360;
            lastBearingFix = loc;
        } else if (lastBearingFix == null) {
            lastBearingFix = loc;
        }
    }

    @Override
    public void onDestroy() {
        ((LocationManager) getSystemService(Context.LOCATION_SERVICE)).removeUpdates(this);
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        if (voice != null) voice.shutdown();
        voice = null;
        navigator = null;
        Hub.nav = null;
        Hub.navAhead = null;
        Hub.navRoundabouts = java.util.Collections.emptyList();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification notification(String s) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, NavActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, NavService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_directions)
                .setContentTitle("KawaNav navigation")
                .setContentText(s)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    // LocationListener defaults for older callbacks
    @Override
    public void onProviderEnabled(String provider) {}

    @Override
    public void onProviderDisabled(String provider) {}
}
