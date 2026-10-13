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
    private static final long NO_BIKE_RETRY_MS = 15_000;
    private static final int CLEAR_REPEATS = 3;
    /** Status icon (phone battery, headset) refresh, as often as Rideology sends it. */
    private static final long ICONS_MS = 5000;
    /** After an autostart: stop again when the bike has not been connected for this long. */
    private static final long AUTO_STOP_MS = 5 * 60_000;
    /** After connecting: the arrow goes out first, the vehicle-data requests this much later. */
    private static final long STARTUP_DELAY_MS = 1000;
    /** Vehicle info (odometer, fuel…) is asked for again when the last answer is this old. */
    private static final long INFO_POLL_MS = 20_000;
    /** The bike's values are saved at most this often while connected (and on link loss). */
    private static final long SAVE_MS = 60_000;
    /** A GPS fix this recent counts as "where the bike was parked" when the link is lost. */
    private static final long PARKED_FIX_MS = 5 * 60_000;
    /** Frame types that may be read on request (debug card, adb): reads only, never settings writes. */
    private static final int[] READABLE = {0x03, 0x1A, 0x1B, 0x1D, 0x1E, 0x40, 0x41, 0x45, 0x47, 0x48};

    private static BridgeService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private KawaBle ble;
    private CurveEngine curve;
    private RideRecorder recorder;
    /** Decodes the meter's frames; new per connection (it remembers the bike's config frames). */
    private BikeFrames.Decoder decoder = new BikeFrames.Decoder();
    private BikeRequests requests;
    /** The connect sequence was queued on this connection (vehicle info polling waits for it). */
    private boolean startupQueued;
    private boolean modelLogged;
    /** The clock was set on this connection (asked for at most once). */
    private boolean clockSynced;
    private long infoPolledAt;
    /** The link was up on this connection (a link loss then means: bike switched off or out of range). */
    private boolean wasReady;
    private long savedAt;
    /** onDestroy ran: late callbacks of the link (closed 500 ms later) must not touch anything. */
    private boolean destroyed;
    /** Config frames the meter flagged "not ready", asked for again (per type, this connection). */
    private final java.util.Map<Integer, Integer> configRetries = new java.util.HashMap<>();
    private static final int CONFIG_RETRIES = 3;
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

    /**
     * Debug and adb: asks the bike for one frame type (reads only, see {@link #READABLE}); false if the
     * bike is not connected or the type is not readable.
     */
    public static boolean read(int id) {
        if (instance == null || !instance.ble.isReady()) return false;
        boolean ok = false;
        for (int r : READABLE) ok |= r == id;
        if (!ok) return false;
        byte[] frame = id == BikeFrames.GENERAL_SETTINGS ? KawaProtocol.generalSettingsRead()
                : id == BikeFrames.VEHICLE_SETTINGS ? KawaProtocol.vehicleSettingsRead()
                : id == BikeFrames.SERVICE_INDICATOR ? KawaProtocol.serviceIndicatorRead()
                : KawaProtocol.readRequest(id);
        int expect = id == BikeFrames.RIDING_LOG_EXT ? BikeRequests.ACK_ONLY : BikeRequests.DATA_OR_ACK;
        instance.requests.submit(new BikeRequests.Request(frame, BikeFrames.name(id) + " read", expect, BikeRequests.USER, null));
        return true;
    }

    /** Progress of a settings write, "" when none is running; shown on the bike screen. */
    public static String settingsJob = "";

    /**
     * Writes ride settings to the bike: riding mode, power, KTRC, KQS and the profile name. Only from
     * debug builds for now (it has never been tried on a bike), only while standing still, in neutral and
     * with the throttle closed. Returns null when the job started, else why it did not.
     */
    public static String writeSettings(int mode, int power, int ktrcLevel, int kqs, int kebc, String name) {
        if (!Hub.DEBUG_TOOLS) return "writing settings is not enabled in this build yet";
        if (instance == null || !instance.ble.isReady()) return "the bike is not connected";
        if (!instance.requests.idle()) return "the bike is still answering another request, try again";
        long age = System.currentTimeMillis() - Hub.bike.liveAt;
        String blocked = SettingsWrite.blockedBecause(Hub.bike.live, age, Hub.bike.tuning);
        if (blocked != null) return blocked;
        int ktrc = SettingsWrite.ktrcWire(ktrcLevel);
        byte[] frame = SettingsWrite.frame(mode, power, ktrc, kqs, kebc, name);
        Hub.settingsWritePending = new PendingSettings(mode, power, ktrcLevel, kqs, kebc, name);
        settingsJob = "Sending…";
        Logger.log("Bike: sending settings (mode " + mode + ", power " + power + ", KTRC " + ktrcLevel
                + ", KQS " + kqs + ")");
        instance.requests.submit(new BikeRequests.Request(frame, "vehicle settings write",
                BikeRequests.DATA_OR_ACK, BikeRequests.USER, (r, ok, result) -> instance.afterSettingsWrite(ok, result)));
        return null;
    }

    /** What the rider asked for, to compare with the bike's answer and to re-read afterwards. */
    public static final class PendingSettings {
        public final int mode, power, ktrc, kqs, kebc;
        public final String name;

        PendingSettings(int mode, int power, int ktrc, int kqs, int kebc, String name) {
            this.mode = mode;
            this.power = power;
            this.ktrc = ktrc;
            this.kqs = kqs;
            this.kebc = kebc;
            this.name = name;
        }
    }

    /** The meter answered the write: 0 = done (re-read and check), 0x10 = it is applying it, else rejected. */
    private void afterSettingsWrite(boolean answered, int rawResult) {
        PendingSettings p = Hub.settingsWritePending;
        if (p == null) return;
        if (!answered) {
            settingsJob = "";
            Hub.settingsWritePending = null;
            Logger.log("Bike: no answer to the settings write; nothing was changed as far as we know");
            return;
        }
        int result = SettingsWrite.result(Hub.bike.last[BikeFrames.VEHICLE_SETTINGS]);
        if (result == SettingsWrite.RESULT_WAIT) {
            settingsJob = "The meter is applying it…";
            Logger.log("Bike: " + SettingsWrite.describeResult(result));
            return; // the meter finishes on its own and answers again (PROTOCOL.md)
        }
        if (result != SettingsWrite.RESULT_OK) {
            settingsJob = "";
            Hub.settingsWritePending = null;
            Logger.log("Bike: settings write " + SettingsWrite.describeResult(result));
            return;
        }
        settingsJob = "Reading the settings back…";
        Logger.log("Bike: settings write accepted, reading back");
        instance.requests.submit(new BikeRequests.Request(KawaProtocol.vehicleSettingsRead(), "vehicle settings read",
                BikeRequests.DATA_OR_ACK, BikeRequests.USER, (r, ok, res) -> instance.verifySettings()));
    }

    /** Compares the bike's settings with what was asked for, once the re-read arrived. */
    private void verifySettings() {
        PendingSettings p = Hub.settingsWritePending;
        Hub.settingsWritePending = null;
        settingsJob = "";
        if (p == null || Hub.bike.settings == null) return;
        BikeFrames.VehicleSettings s = Hub.bike.settings;
        boolean ok = s.ridingMode == p.mode && s.power == p.power && ktrcMatches(s.ktrc, p.ktrc)
                && (p.kqs == 0 || s.kqs == 1) && SettingsWrite.ktrcFromWire(s.ktrc) >= 0;
        Logger.log("Bike: settings read back: mode " + s.ridingMode + ", power " + s.power + ", KTRC "
                + s.ktrc + ", KQS " + s.kqs + " -> " + (ok ? "as asked" : "NOT as asked"));
    }

    /** The bike reports the KTRC field; the app sends OFF as 10. */
    private static boolean ktrcMatches(int onBike, int askedLevel) {
        return onBike == SettingsWrite.ktrcWire(askedLevel)
                || (askedLevel == 0 && onBike >= 1 && onBike <= 5 && onBike != 0);
    }

    /** "1 h 05 min" for the ride card and the notification. */
    private static String rideDuration(long ms) {
        long min = ms / 60_000;
        return min >= 60 ? min / 60 + " h " + String.format(java.util.Locale.ROOT, "%02d", min % 60) + " min"
                : min + " min";
    }

    // ------------------------------------------------------------------ ride recording

    /** Starts recording a ride; starts the bridge first when it is not running. */
    public static String startRide(Context c) {
        if (instance == null) {
            Hub.prefs(c).edit().putBoolean(Hub.KEY_RIDE_WANTED, true).apply();
            start(c); // onStartCommand picks the wish up once the service runs
            return null;
        }
        String problem = instance.recorder.start(true);
        instance.updateNotification();
        return problem;
    }

    /** Pause or continue the ride. */
    public static void toggleRidePause() {
        if (instance == null) return;
        if (instance.recorder.state() == RideRecorder.PAUSED) instance.recorder.resume();
        else instance.recorder.pause();
        instance.updateNotification();
    }

    /** Ends the ride and saves it. */
    public static void stopRide() {
        if (instance == null) return;
        instance.recorder.finish("stopped by hand");
        instance.updateNotification();
    }

    /** Shows or clears a dash icon (call, mail); false when the bike is not connected. */
    public static boolean sendDashAlert(int category, int event) {
        if (instance == null || !instance.ble.isReady()) return false;
        byte[] frame = DashAlerts.frame(category, event);
        return instance.ble.write(frame, "dash alert: " + DashAlerts.describe(category, event), true);
    }

    /** Debug: plays a capture file back as if the bike sent it (bridge running, bike data screens update). */
    public static boolean replay(java.io.File f) {
        if (instance == null || !f.isFile()) return false;
        Hub.bike.connected();
        instance.decoder = new BikeFrames.Decoder();
        FrameReplay.start(f, instance.handler, instance::onFrame);
        return true;
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
        BikeStore.load(this);
        ble = new KawaBle(this, this);
        requests = new BikeRequests(new BikeRequests.Io() {
            @Override
            public boolean send(byte[] frame, String label) {
                return ble.write(frame, label);
            }

            @Override
            public long now() {
                return android.os.SystemClock.elapsedRealtime();
            }

            @Override
            public void schedule(Runnable r, long delayMs) {
                handler.postDelayed(r, delayMs);
            }

            @Override
            public void cancel(Runnable r) {
                handler.removeCallbacks(r);
            }

            @Override
            public void log(String line) {
                Logger.log(line);
            }
        });
        curve = new CurveEngine(this);
        recorder = new RideRecorder(this);
        Ride open = recorder.store().resumeCandidate();
        if (open != null) {
            // a ride was still running when the app was killed: carry on with it
            Logger.log("Ride: " + (recorder.adopt(open) == null ? "carried on" : "could not carry on"));
        }
        if (Hub.prefs(this).getBoolean(Hub.KEY_RIDE_WANTED, false)) {
            Hub.prefs(this).edit().putBoolean(Hub.KEY_RIDE_WANTED, false).apply();
            Logger.log("Ride: " + (recorder.start(true) == null ? "started" : "could not start"));
        }
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
        destroyed = true;
        FrameReplay.stop(); // before the handler is cleared: a replay may still post frames
        instance = null;
        Hub.serviceRunning = false;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        handler.removeCallbacksAndMessages(null);
        requests.clear();
        RawCapture.stop();
        recorder.finish("bridge stopped");
        if (wasReady) {
            rememberParked();
            BikeStore.save(this);
        }
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
        if (destroyed) return;
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
        if (destroyed) return;
        if (ready) {
            BluetoothDevice d = findBike();
            Hub.bikeStatus = "Connected to " + (d != null ? d.getName() : "the bike");
            if (d != null && d.getName() != null) Hub.prefs(this).edit().putString(Hub.KEY_BIKE_NAME, d.getName()).apply();
            Hub.bikeHint = "";
            Hub.bikeConnected = true;
            clearsSent = 0;
            iconsSent = -1;
            Hub.bikeCapabilities = null;
            Hub.bikeMtu = ble.mtu();
            Hub.bike.connected();
            decoder = new BikeFrames.Decoder();
            requests.clear();
            startupQueued = false;
            modelLogged = false;
            wasReady = true;
            configRetries.clear();
            clockSynced = false;
            recorder.linkBack();
            if (RawCapture.enabled(this)) RawCapture.start(this);
            tick(); // the arrow first
            handler.postDelayed(startup, STARTUP_DELAY_MS);
        }
        updateNotification();
    }

    /**
     * Queues the connect sequence: the read requests Rideology sends (capabilities, vehicle info,
     * settings, maintenance), see {@link KawaProtocol#startup}.
     */
    private final Runnable startup = () -> {
        if (!ble.isReady()) return;
        int level = Hub.initLevel(this);
        String name = Hub.DEBUG_TOOLS ? Hub.prefs(this).getString(Hub.KEY_DEBUG_PHONE_NAME, "") : "";
        String phone = !name.isEmpty() ? name : level >= KawaProtocol.INIT_FULL ? android.os.Build.MODEL : null;
        for (BikeRequests.Request r : KawaProtocol.startup(level, phone, null)) requests.submit(r);
        startupQueued = true;
        Logger.log("Bike: connect sequence queued (" + new String[]{"capabilities only", "read-only", "full"}[level] + ")");
    };

    @Override
    public void onNotify(java.util.UUID characteristic, byte[] value) {
        if (destroyed) return;
        if (RawCapture.enabled(this)) RawCapture.rx(characteristic, value);
        requests.onFrame(value);
        onFrame(characteristic, value);
    }

    /** A frame from the meter (or from a replay): decoded into {@link Hub#bike}. */
    private void onFrame(java.util.UUID characteristic, byte[] value) {
        BikeFrames.Frame f = decoder.decode(value);
        long now = System.currentTimeMillis();
        Hub.bike.apply(f, now);
        if (f != null && f.id != BikeFrames.RIDING_LOG && f.id != BikeFrames.RIDING_LOG_HIGH && now - savedAt > SAVE_MS) {
            savedAt = now;
            BikeStore.save(this);
        }
        if (f instanceof BikeFrames.ModelInfo && !modelLogged && ((BikeFrames.ModelInfo) f).model != null) {
            modelLogged = true; // model code only: with the VIN's end it would give away nearly all of it
            Logger.log("Bike: model " + ((BikeFrames.ModelInfo) f).model);
        }
        retryConfigIfNotReady(f);
        syncClockOnce(f);
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
    public void onTx(byte[] frame) {
        if (RawCapture.enabled(this)) RawCapture.tx(frame);
    }

    @Override
    public void onServicesDump(String dump) {
        Hub.gattDump = dump;
    }

    @Override
    public void onLinkLost() {
        if (destroyed) return;
        recorder.linkLost();
        requests.clear();
        handler.removeCallbacks(startup);
        RawCapture.stop();
        if (wasReady) {
            wasReady = false;
            rememberParked();
            BikeStore.save(this);
        }
        Hub.bikeCapabilities = null;
        Hub.bikeStatus = "Connection lost, reconnecting…";
        Hub.bikeHint = "";
        Hub.bikeConnected = false;
        updateNotification();
        handler.postDelayed(connectRunnable, ble.reconnectDelayMs());
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

        // the ride state is for the UI, so it is updated whether or not the bike is connected
        Hub.rideState = recorder.state();
        Hub.rideText = recorder.state() == RideRecorder.IDLE ? "" : String.format(java.util.Locale.ROOT,
                "%.1f km · %s", recorder.distanceM() / 1000, rideDuration(recorder.activeMs()));

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
        pollVehicleInfo(now);
        if (Hub.prefs(this).getBoolean(Hub.KEY_RIDE_AUTO, false)) recorder.autoCheck();
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
        // the dash riding-log icon follows the recorder (the debug card can still override it)
        int ridingLog = icon(Hub.KEY_ICON_RIDING_LOG, recorder.state() == RideRecorder.RECORDING ? 1
                : recorder.state() == RideRecorder.PAUSED ? 2 : 0);
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

    /**
     * The link to the bike was lost: the phone is next to it (switched off, the rider walks away), so its
     * position is where the bike is parked. Uses the last fix of curves or the ride recorder, else
     * Android's last known position, if recent enough.
     */
    private void rememberParked() {
        if (!hasLocationPermission()) return;
        android.location.Location fix = Hub.lastFix;
        if (fix == null || System.currentTimeMillis() - fix.getTime() > PARKED_FIX_MS) {
            android.location.LocationManager lm = getSystemService(android.location.LocationManager.class);
            for (String p : new String[]{android.location.LocationManager.FUSED_PROVIDER,
                    android.location.LocationManager.GPS_PROVIDER}) {
                android.location.Location l = lm.getLastKnownLocation(p);
                if (l != null && (fix == null || l.getTime() > fix.getTime())) fix = l;
            }
        }
        if (fix == null || System.currentTimeMillis() - fix.getTime() > PARKED_FIX_MS) {
            Logger.log("Bike parked: no recent position");
            return;
        }
        Hub.bike.parked = new BikeData.Place(fix.getLatitude(), fix.getLongitude(), fix.getAccuracy(), System.currentTimeMillis());
        Logger.log(String.format(java.util.Locale.ROOT, "Bike parked: position saved (±%.0f m)", fix.getAccuracy()));
    }

    /**
     * Like Rideology: a config frame (capabilities, vehicle info config, maintenance config) the meter
     * flags as not ready yet is asked for again, a few times per connection.
     */
    private void retryConfigIfNotReady(BikeFrames.Frame f) {
        boolean notReady = f instanceof BikeFrames.GeneralCaps && !((BikeFrames.GeneralCaps) f).valid
                || f instanceof BikeFrames.InfoConfig && !((BikeFrames.InfoConfig) f).valid
                || f instanceof BikeFrames.ServiceConfig && !((BikeFrames.ServiceConfig) f).valid;
        if (!notReady || !ble.isReady()) return;
        int n = configRetries.merge(f.id, 1, Integer::sum);
        if (n > CONFIG_RETRIES) return;
        Logger.log("Bike: " + BikeFrames.name(f.id) + " not ready yet, asking again (" + n + ")");
        requests.submit(new BikeRequests.Request(KawaProtocol.readRequest(f.id), BikeFrames.name(f.id) + " request",
                BikeRequests.DATA_OR_ACK, BikeRequests.STARTUP, null));
    }

    /**
     * The rider asked KawaNav to set the meter clock: when the 0x1B read arrives, write the phone's local
     * time back, keeping the shift-lamp byte exactly as read (Rideology does this on every connect).
     * Off by default until it has been tried on the bike.
     */
    private void syncClockOnce(BikeFrames.Frame f) {
        if (clockSynced || !(f instanceof BikeFrames.GeneralSettings)) return;
        BikeFrames.GeneralSettings g = (BikeFrames.GeneralSettings) f;
        if (g.result < 0) return; // no answer to the read
        BikeFrames.GeneralCaps caps = Hub.bike.caps;
        if (!Hub.prefs(this).getBoolean(Hub.KEY_CLOCK_SYNC, false) || caps == null || !caps.clockSettable()) return;
        clockSynced = true;
        java.util.Calendar c = java.util.Calendar.getInstance();
        byte[] frame = KawaProtocol.generalSettingsWrite(Math.max(g.shiftLampStatus, 0), Math.max(g.shiftLampLevel, 0),
                c.get(java.util.Calendar.DAY_OF_MONTH), c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.HOUR_OF_DAY),
                c.get(java.util.Calendar.MINUTE), c.get(java.util.Calendar.SECOND));
        requests.submit(new BikeRequests.Request(frame, "clock sync", BikeRequests.DATA_OR_ACK,
                BikeRequests.USER, (r, ok, result) -> Logger.log("Bike: clock sync "
                        + (ok && result == 0 ? "done" : "failed (" + (ok ? "result " + result : "no answer") + ")"))));
    }

    /** Asks for the vehicle info (odometer, fuel, range…) again when the last answer is older than {@link #INFO_POLL_MS}. */
    private void pollVehicleInfo(long now) {
        if (!startupQueued || Hub.initLevel(this) == KawaProtocol.INIT_NONE
                || requests.unsupported(BikeFrames.VEHICLE_INFO) || now - Hub.bike.infoAt < INFO_POLL_MS
                || now - infoPolledAt < INFO_POLL_MS) return;
        infoPolledAt = now;
        requests.submitIfIdle(new BikeRequests.Request(KawaProtocol.readRequest(BikeFrames.VEHICLE_INFO),
                "vehicle info request", BikeRequests.DATA_OR_ACK, BikeRequests.POLL, null));
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
