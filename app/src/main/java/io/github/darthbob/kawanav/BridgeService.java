package io.github.darthbob.kawanav;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.Locale;

/**
 * Keeps the link to the bike and, once a second, sends whatever should be on the meter:
 * the Google Maps maneuver if Maps is navigating, else the next curve in free mode, else nothing.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is required before MainActivity starts us
public class BridgeService extends Service implements KawaBle.Listener {

    private static final String CHANNEL = "bridge";
    private static final int NOTIFICATION_ID = 1;
    private static final long TICK_MS = 1000;
    private static final long RECONNECT_MS = 3000;
    private static final long NO_BIKE_RETRY_MS = 15_000;
    private static final int CLEAR_REPEATS = 3;
    /** Status icon (phone battery, headset) refresh, as often as Rideology sends it. */
    private static final long ICONS_MS = 5000;
    /** After an autostart: stop again when the bike has not been connected for this long. */
    private static final long AUTO_STOP_MS = 5 * 60_000;

    private static BridgeService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private KawaBle ble;
    private CurveEngine curve;
    /** Decodes the meter's frames; new per connection (it remembers the bike's config frames). */
    private BikeFrames.Decoder decoder = new BikeFrames.Decoder();
    private int clearsSent;
    /** Status icon fields last sent to the meter (one per 4 bits, -1: none since connecting) and when. */
    private int iconsSent = -1;
    private long iconsSentAt;
    /** Android's headset (HFP) and A2DP profiles, to see whether the chosen helmet is connected. */
    private BluetoothProfile headsetProfile, a2dpProfile;
    private long lastTick;
    private String lastNotificationText = "";
    private String lastShownKey = "";
    private boolean locationAllowed;
    /** Keeps the CPU running with the screen off so the meter keeps getting updates every second. */
    private PowerManager.WakeLock wakeLock;

    private static final String ACTION_STOP = "io.github.darthbob.kawanav.STOP_BRIDGE";
    private static final String EXTRA_AUTO = "auto";

    /** Started by {@link BikePresence} (bike seen), not by the user. */
    private boolean autoStarted;
    private long bikeAbsentSince;

    // ------------------------------------------------------------------ static API

    /** Started by the user: also ends a pause of autostart after a manual stop. */
    public static void start(Context c) {
        Hub.setStoppedByUser(c, false);
        c.startForegroundService(new Intent(c, BridgeService.class));
    }

    /** Autostart when the bike appears; stops by itself if the bike does not connect. */
    public static void startAuto(Context c) {
        c.startForegroundService(new Intent(c, BridgeService.class).putExtra(EXTRA_AUTO, true));
    }

    /** Stopped by the user: autostart waits until the bike is switched off, see {@link Hub#autostartPaused}. */
    public static void stop(Context c) {
        Hub.setStoppedByUser(c, true);
        Logger.log("Bridge stopped by hand: autostart paused until the bike is switched off");
        c.stopService(new Intent(c, BridgeService.class));
    }

    /**
     * Called when the app comes to the foreground: if the bridge was restarted by Android in the
     * background (GPS not allowed then), switch GPS on now that the app is visible.
     */
    public static void appVisible() {
        if (instance != null && !instance.locationAllowed) instance.enableLocation();
    }

    /** Re-read settings (free mode on/off etc.) if running. */
    public static void settingsChanged() {
        if (instance != null) instance.applySettings();
    }

    /** Debug: sends the phone name from the settings now; false if the bike is not connected. */
    public static boolean sendPhoneName() {
        if (instance == null || !instance.ble.isReady()) return false;
        return instance.writePhoneName();
    }

    /** New data from Maps: update the meter now instead of waiting for the next tick. */
    public static void poke() {
        if (instance != null) instance.tick();
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Logger.init(this);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Bike connection", NotificationManager.IMPORTANCE_LOW));
        locationAllowed = false;
        enableLocation();
        if (!locationAllowed) {
            startForeground(NOTIFICATION_ID, buildNotification("Starting…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        }
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KawaNav:bridge");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();

        Hub.serviceRunning = true;
        ble = new KawaBle(this, this);
        curve = new CurveEngine(this);
        openAudioProfiles();
        applySettings();
        connectBike();
        handler.post(tickLoop);
        Logger.log("Bridge started");
    }

    /**
     * Foreground service with GPS. Android allows that only while the app is visible (or was when
     * the service started); after a restart in the background the bridge runs without GPS
     * (bike link and Maps turns still work) until the app is opened, see {@link #appVisible()}.
     */
    private void enableLocation() {
        if (!hasLocationPermission()) return;
        try {
            startForeground(NOTIFICATION_ID, buildNotification(lastNotificationText.isEmpty() ? "Starting…" : lastNotificationText),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE | ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            boolean was = locationAllowed;
            locationAllowed = true;
            if (!was && curve != null) {
                Logger.log("GPS enabled for curve detection");
                applySettings();
            }
        } catch (SecurityException e) {
            Logger.log("GPS not allowed in the background right now: open KawaNav once to enable curve detection");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Logger.log("Stop tapped in the notification: autostart paused until the bike is switched off");
            Hub.setStoppedByUser(this, true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null) {
            // a manual start keeps the bridge running; an autostart only while the bike is around
            autoStarted = intent.getBooleanExtra(EXTRA_AUTO, false);
            bikeAbsentSince = System.currentTimeMillis();
            if (autoStarted) Logger.log("Bridge started automatically (bike or helmet appeared)");
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        Hub.serviceRunning = false;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        handler.removeCallbacksAndMessages(null);
        curve.stop();
        closeAudioProfiles();
        KawaBle b = ble;
        if (b.isReady()) {
            // let the meter drop the arrow and the status icons, then close the link
            b.write(KawaProtocol.clearFrame(), "clear", true);
            b.write(KawaProtocol.meterIndicationFrame(KawaProtocol.BATTERY_NONE, 0, 0, 0), "icons off", true);
            new Handler(Looper.getMainLooper()).postDelayed(b::disconnect, 500);
        } else {
            b.disconnect();
        }
        Hub.bikeStatus = "Not running";
        Hub.bikeHint = "";
        Hub.bikeConnected = false;
        Hub.output = null;
        Hub.outputStatus = "–";
        Hub.iconsStatus = "–";
        Hub.bikeCapabilities = null;
        Logger.log("Bridge stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ bike connection

    private void connectBike() {
        handler.removeCallbacks(connectRunnable);
        BluetoothDevice d = findBike();
        if (d == null) {
            Hub.bikeStatus = "No paired Kawasaki found";
            Hub.bikeHint = "Pair the bike once: \"Pair your bike\" in the orange banner (ignition on).";
            handler.postDelayed(connectRunnable, NO_BIKE_RETRY_MS);
            return;
        }
        Hub.bikeStatus = "Looking for " + d.getName() + "…";
        Hub.bikeHint = "Switch on the ignition. Rideology must be closed: the bike talks to one app at a time.";
        ble.setDumpServices(Hub.DEBUG_TOOLS && Hub.prefs(this).getBoolean(Hub.KEY_DEBUG_ICONS, false));
        ble.connect(d, true);
    }

    private final Runnable connectRunnable = this::connectBike;

    private BluetoothDevice findBike() {
        return pairedBike(this);
    }

    /** The saved bike, else the first paired device named "Kawasaki…"; null if none / Bluetooth off. */
    public static BluetoothDevice pairedBike(Context c) {
        BluetoothAdapter a = ((BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
        if (a == null || !a.isEnabled()) return null;
        String saved = Hub.prefs(c).getString(Hub.KEY_DEVICE, null);
        BluetoothDevice kawasaki = null;
        for (BluetoothDevice d : a.getBondedDevices()) {
            if (d.getAddress().equals(saved)) return d;
            String n = d.getName();
            if (kawasaki == null && n != null && n.toLowerCase(Locale.ROOT).contains("kawasaki")) kawasaki = d;
        }
        return kawasaki;
    }

    @Override
    public void onLog(String line) {
        Logger.log(line);
    }

    @Override
    public void onStateChanged(boolean ready) {
        if (ready) {
            BluetoothDevice d = findBike();
            Hub.bikeStatus = "Connected to " + (d != null ? d.getName() : "the bike");
            Hub.bikeHint = "";
            Hub.bikeConnected = true;
            clearsSent = 0;
            iconsSent = -1;
            Hub.bikeCapabilities = null;
            decoder = new BikeFrames.Decoder();
            ble.write(KawaProtocol.CAPABILITIES_REQUEST, "capabilities request");
            writePhoneName();
            tick();
        }
        updateNotification();
    }

    @Override
    public void onNotify(java.util.UUID characteristic, byte[] value) {
        BikeFrames.Frame f = decoder.decode(value);
        if (!(f instanceof BikeFrames.GeneralCaps)) return;
        BikeFrames.GeneralCaps c = (BikeFrames.GeneralCaps) f;
        Hub.bikeCapabilities = c;
        Logger.log("Bike display: turn-by-turn " + (c.turnByTurn ? "supported" : "NOT supported")
                + ", status icons " + (c.statusIcons ? "supported" : "NOT supported"));
        if (!c.turnByTurn) {
            // only a warning: Rideology stops sending arrows here, KawaNav keeps sending in case the flag is misread
            Hub.bikeHint = "This bike's display reports no turn-by-turn support: arrows and curves will probably not appear.";
        }
        updateNotification();
    }

    @Override
    public void onServicesDump(String dump) {
        Hub.gattDump = dump;
    }

    @Override
    public void onLinkLost() {
        Hub.bikeCapabilities = null;
        Hub.bikeStatus = "Connection lost, reconnecting…";
        Hub.bikeHint = "";
        Hub.bikeConnected = false;
        updateNotification();
        handler.postDelayed(connectRunnable, RECONNECT_MS);
    }

    // ------------------------------------------------------------------ output

    private void applySettings() {
        boolean free = Hub.freeMode(this);
        boolean navCurves = Hub.prefs(this).getInt(Hub.KEY_NAV_CURVES, 0) > 0;
        if ((free || navCurves) && locationAllowed) {
            curve.start();
        } else {
            curve.stop();
        }
        tick();
    }

    private final Runnable tickLoop = new Runnable() {
        @Override
        public void run() {
            tick();
            handler.postDelayed(this, TICK_MS);
        }
    };

    private void tick() {
        long now = System.currentTimeMillis();
        if (now - lastTick < 200) return; // poke() right after a tick
        lastTick = now;

        if (autoStarted) {
            if (ble.isReady()) {
                bikeAbsentSince = now;
            } else if (now - bikeAbsentSince > AUTO_STOP_MS) {
                Logger.log("Bike not connected for " + AUTO_STOP_MS / 60_000 + " min after autostart: stopping the bridge");
                stopSelf();
                return;
            }
        }

        Hub.Output out = decide();
        Hub.output = out;
        // log every change of what the meter shows (also without bike)
        String key = out == null ? "nothing" : out.source + ":" + out.turn;
        if (!key.equals(lastShownKey)) {
            lastShownKey = key;
            Logger.log("Meter -> " + (out == null ? "nothing" : out.describe()) + (ble.isReady() ? "" : "  [bike not connected]"));
        }
        if (!ble.isReady()) {
            Hub.outputStatus = (out == null ? "Nothing to show" : out.describe()) + " (bike not connected)";
            updateNotification();
            return;
        }
        sendIcons(now);
        if (out == null) {
            Hub.outputStatus = "Nothing to show";
            if (clearsSent < CLEAR_REPEATS) {
                ble.write(KawaProtocol.clearFrame(), "clear", true);
                clearsSent++;
            }
        } else {
            clearsSent = 0;
            Hub.outputStatus = out.describe();
            SharedPreferences p = Hub.prefs(this);
            boolean imperial = p.getBoolean(Hub.KEY_IMPERIAL, false);
            int dist = Math.max(0, out.distanceM);
            int unit = 1; // metres, like Rideology
            if (imperial) {
                unit = 3; // feet
                dist = (int) Math.round(dist / 0.3048);
            }
            ble.write(KawaProtocol.naviFrame(0, out.turn, unit, dist), out.describe(), true);
        }
        updateNotification();
    }

    /**
     * Status icons on the meter, refreshed every 5 s: the phone battery (4 bars, Rideology's
     * thresholds) unless switched off, and the headset icon while the helmet chosen for autostart
     * is connected to the phone. Each field can be fixed in the hidden debug card ({@link Hub#iconSetting}).
     */
    private void sendIcons(long now) {
        boolean batteryOn = Hub.prefs(this).getBoolean(Hub.KEY_PHONE_BATTERY, true);
        int percent = getSystemService(BatteryManager.class).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        boolean charging = pluggedIn();
        int battery = icon(Hub.KEY_ICON_BATTERY, batteryOn ? KawaProtocol.batteryLevel(percent, charging) : KawaProtocol.BATTERY_NONE);
        int voice = icon(Hub.KEY_ICON_VOICE, 0);
        int headset = icon(Hub.KEY_ICON_HEADSET, helmetConnected() ? 1 : 0);
        int ridingLog = icon(Hub.KEY_ICON_RIDING_LOG, 0);
        int icons = battery << 12 | voice << 8 | headset << 4 | ridingLog;
        if (icons == iconsSent && now - iconsSentAt < ICONS_MS) return;
        byte[] frame = KawaProtocol.meterIndicationFrame(battery, voice, headset, ridingLog);
        String label = "battery " + battery + " (phone " + percent + "%" + (charging ? " charging" : "") + "), voice " + voice
                + ", headset " + headset + ", riding log " + ridingLog;
        ble.write(frame, "icons: " + label, true);
        Hub.iconsStatus = label + "\n" + KawaProtocol.hex(frame);
        iconsSent = icons;
        iconsSentAt = now;
    }

    /** Debug: the text set in the debug card as the phone's name, if any (Rideology sends Build.MODEL). */
    private boolean writePhoneName() {
        if (!Hub.DEBUG_TOOLS) return false;
        String name = Hub.prefs(this).getString(Hub.KEY_DEBUG_PHONE_NAME, "");
        if (name.isEmpty()) return false;
        return ble.write(KawaProtocol.phoneNameFrame(name), "phone name \"" + name + "\"");
    }

    /**
     * The phone is on a charger (USB, wireless…). Plugged in rather than "charging": the icon should
     * not flip to normal when the phone pauses charging (full, adaptive charging at 80 %, hot).
     */
    private boolean pluggedIn() {
        Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return b != null && b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    /** A status icon field: the debug value if one is set, else the automatic one. */
    private int icon(String key, int auto) {
        int v = Hub.iconSetting(this, key);
        return v >= 0 ? v : auto;
    }

    /** The helmet picked under "When my helmet connects" is connected to the phone (call or audio profile). */
    private boolean helmetConnected() {
        String helmet = Hub.prefs(this).getString(Hub.KEY_HELMET, null);
        if (helmet == null) return false;
        for (BluetoothProfile p : new BluetoothProfile[]{headsetProfile, a2dpProfile}) {
            if (p == null) continue;
            for (BluetoothDevice d : p.getConnectedDevices()) {
                if (helmet.equals(d.getAddress())) return true;
            }
        }
        return false;
    }

    private final BluetoothProfile.ServiceListener audioProfiles = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.HEADSET) headsetProfile = proxy;
            else a2dpProfile = proxy;
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.HEADSET) headsetProfile = null;
            else a2dpProfile = null;
        }
    };

    private void openAudioProfiles() {
        BluetoothAdapter a = ((BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
        if (a == null) return;
        a.getProfileProxy(this, audioProfiles, BluetoothProfile.HEADSET);
        a.getProfileProxy(this, audioProfiles, BluetoothProfile.A2DP);
    }

    private void closeAudioProfiles() {
        BluetoothAdapter a = ((BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
        if (a == null) return;
        if (headsetProfile != null) a.closeProfileProxy(BluetoothProfile.HEADSET, headsetProfile);
        if (a2dpProfile != null) a.closeProfileProxy(BluetoothProfile.A2DP, a2dpProfile);
        headsetProfile = null;
        a2dpProfile = null;
    }

    private Hub.Output decide() {
        Hub.MapsTurn m = Hub.mapsEnabled(this) ? Hub.maps : null;
        if (m != null) {
            // e.g. "Navigation wird gestartet…": nothing useful to show yet
            if (m.turn == KawaProtocol.TURN_NONE && m.distanceM < 0) return null;
            // between Maps instructions: warn about curves on the way to the next maneuver
            int navCurves = Hub.prefs(this).getInt(Hub.KEY_NAV_CURVES, 0);
            if (navCurves > 0) {
                Hub.Output c = curve.curveBeforeManeuver(m.distanceM, navCurves == 1);
                if (c != null) return c;
            }
            return new Hub.Output(m.turn, m.distanceM, "Maps");
        }
        if (Hub.freeMode(this)) return curve.current();
        return null;
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    // ------------------------------------------------------------------ notification

    private void updateNotification() {
        String text = Hub.bikeStatus + " · " + Hub.outputStatus
                + (locationAllowed ? "" : "\nCurve detection paused: tap to open KawaNav");
        if (text.equals(lastNotificationText)) return;
        lastNotificationText = text;
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, BridgeService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_directions)
                .setContentTitle("KawaNav")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }
}
