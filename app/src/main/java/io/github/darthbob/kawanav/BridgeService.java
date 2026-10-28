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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Keeps the link to the bike and, once a second, sends whatever should be on the meter:
 * the Google Maps maneuver if Maps is navigating, else the next curve in free mode, else nothing.
 */
@SuppressLint("MissingPermission") // every Bluetooth call is behind Hub.bluetoothAllowed / a SecurityException catch
public class BridgeService extends Service implements KawaBle.Listener {

    private static final String CHANNEL = "bridge";
    /** A speed camera warning: its own channel, so it can be silenced on its own. */
    private static final String CHANNEL_ALERTS = "alerts";
    /** Rides and refuels: things that happened, not things that are wrong. */
    private static final String CHANNEL_RIDES = "rides";
    private static final int CAMERA_NOTIFICATION_ID = 2;
    /** While navigating, a camera takes the arrow over only this close (the maneuver matters more). */
    private static final double CAMERA_NAV_M = 250;
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
    /** At Stop: how long the "clear"/"icons off" frames get before the link is closed anyway. */
    private static final long DISCONNECT_AFTER_MS = KawaBle.OP_TIMEOUT_MS + 1000;

    /** Frame types that may be read on request (debug card, adb): reads only, never settings writes. */
    private static final int[] READABLE = {0x03, 0x1A, 0x1B, 0x1D, 0x1E, 0x40, 0x41, 0x45, 0x47, 0x48};

    private static BridgeService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private KawaBle ble;
    private CurveEngine curve;
    /** Says the curves while the spoken curve warnings are on, else null. */
    private CurveSound curveSound;
    private final CurveAlerts curveAlerts = new CurveAlerts();
    /** The rider's usual lean for the "From my rides" pace, worked out once per start; null until then. */
    private RideStats.BendLean ridesLean;
    private boolean ridesLeanAsked;
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
    /** The phone's battery percentage to report: the last plausible reading (issue #7). */
    private final PhoneBattery phoneBattery = new PhoneBattery();
    /** The missing first battery reading is logged once, not every 5 s. */
    private boolean batteryUnknownLogged;
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

    /**
     * Debug: "Capture frames" was switched. Switched on with the bike connected, a capture starts now
     * rather than at the next connect (without the connect sequence, then); switched off, it ends.
     */
    public static void captureChanged(boolean on) {
        if (!on) {
            RawCapture.stop();
            return;
        }
        if (instance == null || !instance.ble.isReady() || RawCapture.writing()) return;
        Logger.log("Capture started on a link already up");
        RawCapture.start(instance);
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
        // every read is answered either with the frame itself or with an ACK naming it. The 0x45 ask used
        // to accept only the ACK: a meter that answers with the 0x45 frame left it retrying for nine seconds
        if (instance.requests.unsupported(id)) return false;
        // submitIfIdle: asking twice while one is in flight is the same ask, not a second one
        instance.requests.submitIfIdle(new BikeRequests.Request(frame, BikeFrames.name(id) + " read",
                BikeRequests.DATA_OR_ACK, BikeRequests.USER, null));
        return true; // true also when the same read was already queued
    }

    /** A log that could not be read counts as empty here: the screens are the ones that say so. */
    private static <T> java.util.List<T> orEmpty(java.util.List<T> list) {
        return list == null ? java.util.Collections.emptyList() : list;
    }

    /** Progress of a settings write, "" when none is running; shown on the bike screen. */
    public static String settingsJob = "";
    /**
     * What came of the last ride-settings and shift-lamp write, or why it was not sent, and when: shown on
     * its card for {@link SettingsWrite#OUTCOME_MS}, so the rider learns whether the bike took it.
     */
    public static String settingsOutcome = "", lampOutcome = "";
    public static long settingsOutcomeAt, lampOutcomeAt;

    /** Notes what came of a ride-settings write, or why it was not sent; a running job is left alone. */
    public static void noteSettings(String outcome) {
        settingsOutcome = outcome;
        settingsOutcomeAt = System.currentTimeMillis();
    }

    /** Notes what came of a shift-lamp write, or why it was not sent; a running job is left alone. */
    public static void noteLamp(String outcome) {
        lampOutcome = outcome;
        lampOutcomeAt = System.currentTimeMillis();
    }

    /**
     * Writes ride settings to the bike: riding mode, power, KTRC, KQS and the profile name. Tried on a Z900
     * (2026). Only while standing still, in neutral and with the throttle closed, and the settings are
     * read back to compare. Returns null when the job started, else why it did not.
     */
    public static String writeSettings(int mode, int power, int ktrcLevel, int kqs, int kebc, String name) {
        if (instance == null || !instance.ble.isReady()) return "the bike is not connected";
        if (!instance.requests.idle()) return "the bike is still answering another request, try again";
        long age = System.currentTimeMillis() - Hub.bike.liveAt;
        String blocked = SettingsWrite.blockedBecause(Hub.bike.live, age, Hub.bike.tuning);
        if (blocked != null) return blocked;
        String bad = SettingsWrite.invalid(SettingsWrite.modeCount(Hub.bike.tuning), mode, power,
                ktrcLevel, kqs, kebc);
        if (bad != null) return bad;
        int ktrc = SettingsWrite.ktrcWire(ktrcLevel);
        byte[] frame = SettingsWrite.frame(mode, power, ktrc, kqs, kebc, name);
        Hub.settingsWritePending = new PendingSettings(mode, power, ktrcLevel, kqs, kebc, name);
        settingsJob = "Sending…";
        settingsOutcome = "";
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
            noteSettings("No answer from the bike: nothing was changed as far as KawaNav knows. Check the dash.");
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
            noteSettings("Not taken: the bike " + SettingsWrite.describeResult(result) + ".");
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
        if (p == null) return;
        if (Hub.bike.settings == null) {
            noteSettings("Sent, but the settings could not be read back: check the dash.");
            return;
        }
        BikeFrames.VehicleSettings s = Hub.bike.settings;
        // the bike's own numbers on both sides (1 = Sport / Full / OFF …), as written
        boolean ok = s.ridingMode == p.mode && s.power == p.power && ktrcMatches(s.ktrc, p.ktrc)
                && s.kqs == p.kqs && SettingsWrite.ktrcFromWire(s.ktrc) >= 0;
        Logger.log("Bike: settings read back: mode " + s.ridingMode + ", power " + s.power + ", KTRC "
                + s.ktrc + ", KQS " + s.kqs + " -> " + (ok ? "as asked" : "NOT as asked"));
        noteSettings(ok ? "Done: the bike reads back what was sent."
                : "Sent, but the bike reads back mode " + s.ridingMode + ", power " + s.power + ", KTRC " + s.ktrc
                + ", KQS " + s.kqs + " (its own numbers), not what was sent. Check the dash.");
    }

    /** The bike reports the KTRC field; the app sends OFF as 10. */
    private static boolean ktrcMatches(int onBike, int askedLevel) {
        return onBike == SettingsWrite.ktrcWire(askedLevel)
                || (askedLevel == 0 && onBike >= 1 && onBike <= 5 && onBike != 0);
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
        if (instance == null) {
            clearRideState();   // no service, no ride: the screens were showing a state left behind
            return;
        }
        if (instance.recorder.state() == RideRecorder.PAUSED) instance.recorder.resume();
        else instance.recorder.pause();
        instance.updateNotification();
    }

    /** Ends the ride and saves it. */
    public static void stopRide() {
        if (instance == null) {
            clearRideState();
            return;
        }
        instance.recorder.finish("stopped by hand");
        instance.updateNotification();
    }

    /** No ride is being recorded: what the record button, Home and the ride list read from {@link Hub}. */
    private static void clearRideState() {
        Hub.rideState = RideRecorder.IDLE;
        Hub.rideText = "";
        Hub.rideId = null;
    }

    /**
     * Sets the shift lamp on the meter: when it lights (status) and at which engine speed (level code
     * 1..61). The clock is left as it is, the way Rideology's lamp-only write does it. The meter's answer
     * carries the result in byte 7 and is read back afterwards. Tried on a Z900 (2026).
     */
    public static String writeShiftLamp(int status, int level) {
        if (instance == null || !instance.ble.isReady()) return "the bike is not connected";
        BikeFrames.GeneralCaps caps = Hub.bike.caps;
        if (caps == null || !caps.shiftLampSettable) return "this dash does not take shift-lamp settings";
        if (level < caps.shiftLampMin || level > caps.shiftLampMax) {
            return "the dash takes levels " + caps.shiftLampMin + " to " + caps.shiftLampMax;
        }
        if (!instance.requests.idle()) return "the bike is still answering another request, try again";
        byte[] frame = KawaProtocol.shiftLampWrite(status, level);
        Logger.log("Bike: sending shift lamp (status " + status + ", " + SettingsWrite.lampRpm(level) + " rpm)");
        settingsJob = "Sending the shift lamp…";
        lampOutcome = "";
        instance.requests.submit(new BikeRequests.Request(frame, "shift lamp write", BikeRequests.DATA_OR_ACK,
                BikeRequests.USER, (r, ok, result) -> instance.afterShiftLamp(ok)));
        return null;
    }

    /**
     * Brings the call/mail icons on the dash in line with the phone. Needed on every connection: the
     * listener only reports changes, so an alert posted while the bridge was down was never sent, and a
     * dash that was off (or another bike) may show an icon from before. Called after connecting.
     */
    private void reconcileDashIcons() {
        for (int icon : new int[]{DashAlerts.CALL, DashAlerts.MAIL}) {
            int event = DashAlerts.desiredEvent(icon, DashAlerts.active(icon), DashAlerts.enabled(this, icon));
            if (event == DashAlerts.NOTHING) continue;
            if (sendDashAlert(icon, event)) DashAlerts.sent(icon, event);
        }
    }

    /** The meter answered the shift-lamp write: read the general settings back to show what it holds now. */
    private void afterShiftLamp(boolean answered) {
        BikeFrames.GeneralSettings g = Hub.bike.general;
        int result = g == null ? -1 : g.result;
        settingsJob = "";
        if (!answered || result != 0) {
            noteLamp(answered ? "Not taken: the dash refused it (result " + result + ")."
                    : "No answer from the dash: check it.");
            Logger.log("Bike: shift lamp " + (answered ? "refused (" + result + ")" : "not answered"));
            return;
        }
        noteLamp("Taken by the dash: check its shift lamp.");
        Logger.log("Bike: shift lamp accepted, reading back");
        requests.submit(new BikeRequests.Request(KawaProtocol.generalSettingsRead(), "general settings read",
                BikeRequests.DATA_OR_ACK, BikeRequests.USER, null));
    }

    /** Shows or clears a dash icon (call, mail); false when the bike is not connected. */
    public static boolean sendDashAlert(int category, int event) {
        if (instance == null || !instance.ble.isReady()) return false;
        byte[] frame = DashAlerts.frame(category, event);
        boolean ok = instance.ble.write(frame, "dash alert: " + DashAlerts.describe(category, event), true);
        if (ok) DashAlerts.sent(category, event); // so the reconnect reconcile does not repeat it
        return ok;
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
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ALERTS, "Warnings",
                NotificationManager.IMPORTANCE_DEFAULT));
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_RIDES, "Rides and refuels",
                NotificationManager.IMPORTANCE_DEFAULT));
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
        // null means the file is there but could not be read: those screens then refuse to save over it.
        // Read before the first tick (applySettings runs one), which checks them.
        Hub.fuelEntries = orEmpty(new FuelStore(this).list());
        Hub.batteryReadings = orEmpty(new BatteryStore(this).list());
        Hub.maintenanceEntries = orEmpty(new MaintenanceStore(this).list());
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
            if (!was && recorder != null) {
                // a ride started in the background had no GPS: let it join now that the app is visible
                recorder.gpsBecameUsable();
            }
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
        // only the tick writes the ride state the screens read, and there is no tick any more: a red or
        // amber record button left behind would offer a pause and a stop that have no service to reach
        clearRideState();
        if (wasReady) {
            rememberParked();
            BikeStore.save(this);
        }
        curve.stop();
        if (curveSound != null) curveSound.shutdown();
        closeAudioProfiles();
        KawaBle b = ble;
        if (b.isReady()) {
            // let the meter drop the arrow and the status icons, then close the link. Sent even when the
            // dash says it has neither: clearing is always safe, and a dash that is showing something
            // because of a misread capability must not be left with it on screen.
            b.write(KawaProtocol.clearFrame(), "clear", true);
            b.write(KawaProtocol.meterIndicationFrame(KawaProtocol.BATTERY_NONE, 0, 0, 0), "icons off", true);
            // close the link only once both frames are on the wire: disconnecting on a fixed delay cut
            // them off (they are still queued, and disconnect() empties the queue), which left the last
            // arrow and the status icons on the dash
            b.disconnectAfterQueueFrames(b::disconnect, DISCONNECT_AFTER_MS);
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
        // the permission can be revoked while the service runs (autostart path): every Bluetooth call
        // below would throw SecurityException on the main thread
        if (!Hub.bluetoothAllowed(this)) {
            Hub.bikeStatus = "Bluetooth not allowed";
            Hub.bikeHint = Hub.BLUETOOTH_HINT;
            Logger.log("Bridge: " + Hub.BLUETOOTH_HINT);
            return;
        }
        BluetoothDevice d = findBike();
        if (d == null) {
            Hub.bikeStatus = "No paired Kawasaki found";
            Hub.bikeHint = "Pair the bike once: \"Pair your bike\" in the orange banner (ignition on).";
            handler.postDelayed(connectRunnable, NO_BIKE_RETRY_MS);
            return;
        }
        Hub.bikeStatus = "Looking for " + d.getName() + "…";
        Hub.bikeHint = bikeGuessed
                ? "Several Kawasakis are paired with this phone. Set up \"Start automatically\" with yours, next "
                        + "to it with the ignition on, so KawaNav knows which one; or remove the other in Bluetooth settings."
                : "Switch on the ignition. Rideology must be closed: the bike talks to one app at a time.";
        if (bikeGuessed) Logger.log("Bridge: several Kawasakis paired and none picked: trying " + d.getAddress());
        ble.setDumpServices(Hub.DEBUG_TOOLS && Hub.prefs(this).getBoolean(Hub.KEY_DEBUG_ICONS, false));
        ble.connect(d, true);
    }

    private final Runnable connectRunnable = this::connectBike;

    private BluetoothDevice findBike() {
        return pairedBike(this);
    }

    /**
     * Set when {@link #pairedBike} had to guess: several Kawasakis are paired and none was picked in
     * KawaNav (autostart or pairing). The bike status then says how to pick one.
     */
    static volatile boolean bikeGuessed;

    /** The bike among the paired devices ({@link #pickBike}); null if none / Bluetooth off. */
    public static BluetoothDevice pairedBike(Context c) {
        try {
            BluetoothAdapter a = ((BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
            if (a == null || !a.isEnabled()) return null;
            android.companion.AssociationInfo info = BikePresence.association(c);
            String linked = info != null && info.getDeviceMacAddress() != null ? info.getDeviceMacAddress().toString() : null;
            String saved = Hub.prefs(c).getString(Hub.KEY_DEVICE, null);
            List<BluetoothDevice> bonded = new ArrayList<>(a.getBondedDevices());
            List<String> addresses = new ArrayList<>(), names = new ArrayList<>();
            for (BluetoothDevice d : bonded) {
                addresses.add(d.getAddress());
                names.add(d.getName());
            }
            int i = pickBike(linked, saved, addresses, names);
            bikeGuessed = i >= 0 && !addresses.get(i).equalsIgnoreCase(linked == null ? "" : linked)
                    && !addresses.get(i).equalsIgnoreCase(saved == null ? "" : saved) && kawasakis(names) > 1;
            return i < 0 ? null : bonded.get(i);
        } catch (SecurityException e) {
            // the permission can be revoked between the check and here (or while scanning): the caller
            // is told "no bike", the app says why, and nothing crashes on the main thread
            Logger.log("Bluetooth not allowed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Which paired device is the bike, as an index into {@code addresses} and {@code names}, -1 for none:
     * the one autostart is linked to, else the one KawaNav paired or was last linked to, else a device
     * named "Kawasaki…". Issue #22: with an old demo bike and the rider's own both paired, both called
     * "Kawasaki-ZR900S", the first Kawasaki was taken while autostart was linked to the other, and the
     * bridge waited for a bike that was not there.
     */
    static int pickBike(String linked, String saved, List<String> addresses, List<String> names) {
        for (int i = 0; i < addresses.size(); i++) if (addresses.get(i).equalsIgnoreCase(linked == null ? "" : linked)) return i;
        for (int i = 0; i < addresses.size(); i++) if (addresses.get(i).equalsIgnoreCase(saved == null ? "" : saved)) return i;
        for (int i = 0; i < names.size(); i++) if (isKawasaki(names.get(i))) return i;
        return -1;
    }

    static int kawasakis(List<String> names) {
        int n = 0;
        for (String name : names) if (isKawasaki(name)) n++;
        return n;
    }

    private static boolean isKawasaki(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).contains("kawasaki");
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
            coldEngine.linkBack(System.currentTimeMillis());
            if (RawCapture.enabled(this)) RawCapture.start(this);
            // a new link may be a dash that was off (or another bike): it can show anything, so forget
            // what the previous connection was told and reconcile from scratch
            DashAlerts.forgetSent();
            tick(); // the arrow first
            reconcileDashIcons(); // a call/mail that arrived while the bridge was down is still shown
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
        for (BikeRequests.Request r : KawaProtocol.startup(level, phone, Hub.askRidingLogExt(this), null)) requests.submit(r);
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
        if (c.valid && !c.turnByTurn && !ignoreCaps()) {
            // the frames stop going out: say so, and say how to get them back
            Hub.bikeHint = "This bike's display reports no turn-by-turn support, so arrows and curves are not "
                    + "being sent to it. If they used to work, turn on \"Send arrows and icons anyway\" in "
                    + "Settings.";
        } else if (c.valid && !c.statusIcons && !ignoreCaps()) {
            Hub.bikeHint = "This bike's display reports no status icons, so the call, mail and phone icons are "
                    + "not being sent to it. \"Send arrows and icons anyway\" in Settings overrides this.";
        } else {
            Hub.bikeHint = "";
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
        coldEngine.linkLost(System.currentTimeMillis());
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
        boolean cameras = Hub.camerasEnabled(this);
        int pace = Hub.prefs(this).getInt(Hub.KEY_CURVE_PACE, CurveAlerts.PACE_OFF);
        boolean spoken = Hub.prefs(this).getInt(Hub.KEY_CURVE_SOUND, CurveAlerts.OFF) > CurveAlerts.OFF
                || pace > CurveAlerts.PACE_OFF || (cameras && Hub.prefs(this).getBoolean(Hub.KEY_CAMERA_VOICE, false))
                || Hub.prefs(this).getBoolean(Hub.KEY_COLD_ENGINE, false);
        if (pace == CurveAlerts.FROM_RIDES && !ridesLeanAsked) {
            ridesLeanAsked = true;
            RideStore store = recorder.store();
            new Thread(() -> {
                RideStats.BendLean l = store.usualBendLean(); // reads the rides' files
                handler.post(() -> {
                    ridesLean = l;
                    Logger.log(l.known()
                            ? String.format(Locale.ROOT, "Curve pace from rides: %.0f° (%d bends, %d rides)", l.degrees, l.bends, l.rides)
                            : "Curve pace from rides: " + l.bends + " bends so far, leaning as Normal");
                });
            }, "rides-lean").start();
        }
        if ((free || navCurves || cameras || spoken) && locationAllowed) {
            curve.start();
        } else {
            curve.stop();
        }
        // the speech engine starts with the setting, so the first curve is not said late
        if (spoken && curveSound == null) {
            curveSound = new CurveSound(this);
        } else if (!spoken && curveSound != null) {
            curveSound.shutdown();
            curveSound = null;
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
            } else if (now - bikeAbsentSince > AUTO_STOP_MS && !recorder.waitingForBike()) {
                // a ride waiting for the bike ends by the rider's choice first (RideEnd), then the bridge
                Logger.log("Bike not connected for " + AUTO_STOP_MS / 60_000 + " min after autostart: stopping the bridge");
                stopSelf();
                return;
            }
        }

        // the ride state is for the UI, so it is updated whether or not the bike is connected
        Hub.rideState = recorder.state();
        Hub.rideText = recorder.state() == RideRecorder.IDLE ? ""
                : RideStats.rideStatusLine(recorder.distanceM(), recorder.activeMs(), recorder.partialGps());
        // which ride is being written: the ride list marks that row, and refuses to delete it
        Ride current = recorder.state() == RideRecorder.IDLE ? null : recorder.ride();
        Hub.rideId = current == null ? null : current.id;

        Hub.Output out = decide();
        Hub.output = out;
        if (out != null && out.cameraId >= 0) cameraAlert(out);
        else cameraGone();
        sayCurve(now);
        coldEngineCheck(now);
        bikeAlerts(now);
        refuelCheck(now);
        batteryCheck(now);
        maintenanceCheck(now);
        askRidingLogExtIfWanted();
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
            if (clearsSent < CLEAR_REPEATS && navFramesAllowed()) {
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
            if (navFramesAllowed()) {
                ble.write(KawaProtocol.naviFrame(0, out.turn, unit, dist), out.describe(), true);
            }
        }
        updateNotification();
    }

    /**
     * Status icons on the meter, refreshed every 5 s: the phone battery (4 bars, Rideology's
     * thresholds) unless switched off, and the headset icon while the helmet chosen for autostart
     * is connected to the phone. Each field can be fixed in the hidden debug card ({@link Hub#iconSetting}).
     */
    private void sendIcons(long now) {
        if (!framesAllowed(Hub.bikeCapabilities != null && Hub.bikeCapabilities.statusIcons, "status icons")) return;
        boolean batteryOn = Hub.prefs(this).getBoolean(Hub.KEY_PHONE_BATTERY, true);
        // The sticky battery broadcast is there as soon as the process starts; the battery service's
        // capacity can answer 0 or unknown for a while after a reconnect on some phones (issue #7).
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int sticky = battery == null ? -1 : PhoneBattery.fromLevel(
                battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1));
        int property = getSystemService(BatteryManager.class).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        int reading = sticky > 0 && sticky <= 100 ? sticky : property;
        if (!phoneBattery.accept(reading, now)) {
            // the dash draws a low or unknown battery with an exclamation: hold the last good reading
            Logger.log("Icons: phone battery reading " + reading + " (sticky " + sticky + ", property "
                    + property + ") ignored, keeping " + phoneBattery.percent() + " %");
        }
        int percent = phoneBattery.percent();
        if (batteryOn && percent < 0) {
            // 15 ("n/a") is drawn with the warning icon too: send no icon frame until Android gives a
            // reading, instead of flashing an empty battery at every start (issue #7)
            if (!batteryUnknownLogged) Logger.log("Icons: no phone battery reading yet, holding the status icons");
            batteryUnknownLogged = true;
            return;
        }
        batteryUnknownLogged = false;
        // plugged in rather than "charging": the icon should not flip when the phone pauses charging
        // (full, adaptive charging at 80 %, hot)
        boolean charging = battery != null && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        int batteryLevel = icon(Hub.KEY_ICON_BATTERY,
                batteryOn ? KawaProtocol.batteryLevel(percent, charging) : KawaProtocol.BATTERY_NONE);
        int voice = icon(Hub.KEY_ICON_VOICE, 0);
        int headset = icon(Hub.KEY_ICON_HEADSET, helmetConnected() ? 1 : 0);
        // the dash riding-log icon follows the recorder (the debug card can still override it)
        int ridingLog = icon(Hub.KEY_ICON_RIDING_LOG, recorder.state() == RideRecorder.RECORDING ? 1
                : recorder.state() == RideRecorder.PAUSED ? 2 : 0);
        int icons = batteryLevel << 12 | voice << 8 | headset << 4 | ridingLog;
        if (icons == iconsSent && now - iconsSentAt < ICONS_MS) return;
        byte[] frame = KawaProtocol.meterIndicationFrame(batteryLevel, voice, headset, ridingLog);
        String label = "battery " + batteryLevel + " (phone " + percent + "%" + (charging ? " charging" : "") + "), voice " + voice
                + ", headset " + headset + ", riding log " + ridingLog;
        ble.write(frame, "icons: " + label, true);
        Hub.iconsStatus = label + "\n" + KawaProtocol.hex(frame);
        iconsSent = icons;
        iconsSentAt = now;
    }

    /**
     * The dash said (in its 0x1A answer) whether it has status icons and turn-by-turn at all. Rideology
     * stops sending what it has not; KawaNav does the same, but only when the answer is a ready one, so a
     * frame that has not arrived yet never silences anything. The debug switch overrides it either way.
     */
    private boolean framesAllowed(boolean supported, String what) {
        boolean ok = BikeFrames.GeneralCaps.allowed(Hub.bikeCapabilities, supported, ignoreCaps());
        if (!ok) {
            if (quiet.add(what)) Logger.log("Dash says it has no " + what + ": not sending them (the "
                    + "\"Send arrows and icons anyway\" setting overrides this)");
        } else {
            quiet.remove(what);
        }
        return ok;
    }

    private boolean navFramesAllowed() {
        return framesAllowed(Hub.bikeCapabilities != null && Hub.bikeCapabilities.turnByTurn, "turn-by-turn");
    }

    /** What is being held back right now, one entry per kind: logged when it starts, not every tick. */
    private final java.util.Set<String> quiet = new java.util.HashSet<>();

    private boolean ignoreCaps() {
        return Hub.prefs(this).getBoolean(Hub.KEY_IGNORE_CAPS, false);
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
     * Like Rideology: a config frame (capabilities, vehicle info config, maintenance config, tuning
     * config) the meter flags as not ready yet is asked for again, a few times per connection.
     */
    private void retryConfigIfNotReady(BikeFrames.Frame f) {
        boolean notReady = f instanceof BikeFrames.GeneralCaps && !((BikeFrames.GeneralCaps) f).valid
                || f instanceof BikeFrames.InfoConfig && !((BikeFrames.InfoConfig) f).valid
                || f instanceof BikeFrames.ServiceConfig && !((BikeFrames.ServiceConfig) f).valid
                || f instanceof BikeFrames.TuningConfig && !((BikeFrames.TuningConfig) f).valid;
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
        if (!Hub.bluetoothAllowed(this)) return; // the permission can be gone on an autostart
        BluetoothAdapter a = ((BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
        if (a == null) return;
        a.getProfileProxy(this, audioProfiles, BluetoothProfile.HEADSET);
        a.getProfileProxy(this, audioProfiles, BluetoothProfile.A2DP);
    }

    private void closeAudioProfiles() {
        BluetoothAdapter a = Hub.bluetoothAllowed(this)
                ? ((BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter() : null;
        if (a == null) return;
        if (headsetProfile != null) a.closeProfileProxy(BluetoothProfile.HEADSET, headsetProfile);
        if (a2dpProfile != null) a.closeProfileProxy(BluetoothProfile.A2DP, a2dpProfile);
        headsetProfile = null;
        a2dpProfile = null;
    }

    private Hub.Output decide() {
        Hub.MapsTurn m = Hub.mapsEnabled(this) ? Hub.maps : null;
        // a camera warning comes first: it is short, and it is the one thing the rider cannot see coming
        Hub.Output cam = cameraWarning(m != null);
        if (cam != null) return cam;
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

    /**
     * The camera ahead, when the warnings are on and it is close enough: the whole warning distance
     * in free mode, {@link #CAMERA_NAV_M} while navigating (the Maps arrow matters more there).
     */
    private Hub.Output cameraWarning(boolean navigating) {
        if (!Hub.camerasEnabled(this) || !locationAllowed) return null;
        Hazards.Ahead a = curve.cameraAhead();
        if (a == null) return null;
        double window = navigating ? Math.min(Hub.cameraWarnM(this), CAMERA_NAV_M) : Hub.cameraWarnM(this);
        if (a.distanceM > window) return null;
        return new Hub.Output(KawaProtocol.CAMERA_TURN, CurveEngine.roundDistance(a.distanceM), "Camera",
                a.camera.id, Hazards.describe(a.camera, a.distanceM));
    }

    /**
     * One notification per camera: the rider hears it once, on the way to it. Standing still in front of
     * a camera does not repeat it — the alert is armed again only once the camera is no longer the one
     * ahead (it was passed, or the rider turned away). A minimum spacing damps a heading that flaps.
     */
    private void cameraAlert(Hub.Output out) {
        long now = System.currentTimeMillis();
        if (out.cameraId == lastCameraId) return;
        if (now - lastCameraAt < CAMERA_ALERT_MIN_GAP_MS) return;
        lastCameraId = out.cameraId;
        lastCameraAt = now;
        Logger.log("Camera: " + out.alert);
        sayCamera(out.cameraId);
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL_ALERTS)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(out.alert)
                .setContentText("From the offline map data of KawaNav")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(CAMERA_NOTIFICATION_ID, n);
    }

    /**
     * Spoken curve warnings: the next curve of the chosen kinds a few seconds before it, or one the rider
     * comes at too fast for the chosen pace, once each (see {@link CurveAlerts}). Whatever the dash shows:
     * during navigation too, except at Maps' maneuver.
     */
    private void sayCurve(long now) {
        if (!soundAllowed()) return;
        SharedPreferences p = Hub.prefs(this);
        Hub.MapsTurn m = Hub.mapsEnabled(this) ? Hub.maps : null;
        double maneuver = m == null ? Double.POSITIVE_INFINITY : m.distanceM;
        BikeFrames.Telemetry live = Hub.bike.live;
        double speed = CurveAlerts.speed(live == null ? Double.NaN : live.speedKmh, now - Hub.bike.liveAt, curve.speed());
        CurveAlerts.Warning w = curveAlerts.next(curve.curvesAhead(), speed,
                p.getInt(Hub.KEY_CURVE_SOUND, CurveAlerts.OFF), paceLean(p.getInt(Hub.KEY_CURVE_PACE, CurveAlerts.PACE_OFF)),
                maneuver, now);
        if (w == null) return;
        String text = w.text();
        Logger.log(String.format(Locale.ROOT, "Curve voice: %s, %.0f m ahead at %.0f km/h (GPS %.0f, radius %.0f m%s)",
                text, w.curve.startDist, speed * 3.6, curve.speed() * 3.6, w.curve.minRadius,
                Double.isInfinite(w.okSpeed) ? "" : String.format(Locale.ROOT, ", pace %.0f km/h", w.okSpeed * 3.6)));
        if (beeps()) curveSound.beep(CurveBeeps.of(w));
        else curveSound.say(text);
    }

    /** A warning may be played: the sound is on, and a headset is connected unless that is not asked for. */
    private boolean soundAllowed() {
        return locationAllowed && voiceAllowed();
    }

    /** Like {@link #soundAllowed}, for a warning about the bike: it needs no position. */
    private boolean voiceAllowed() {
        if (curveSound == null) return false;
        return !Hub.prefs(this).getBoolean(Hub.KEY_CURVE_SOUND_HEADSET, true) || CurveSound.headsetConnected(this);
    }

    /**
     * The cold engine warning ({@link ColdEngine}): the coolant from the riding log extension (0x45), the
     * engine speed from the live frame (0x4A), each only while it is fresh. Said after a bend being said,
     * not over it.
     */
    private void coldEngineCheck(long now) {
        SharedPreferences p = Hub.prefs(this);
        int limit = p.getBoolean(Hub.KEY_COLD_ENGINE, false) ? p.getInt(Hub.KEY_COLD_ENGINE_RPM, ColdEngine.DEFAULT_RPM) : 0;
        int warmC = p.getInt(Hub.KEY_COLD_ENGINE_C, ColdEngine.DEFAULT_WARM_C);
        BikeFrames.LogExt ext = Hub.bike.ext;
        BikeFrames.Telemetry live = Hub.bike.live;
        double coolant = ext != null && now - Hub.bike.extAt <= BikeAlerts.FRESH_MS ? ext.waterC : Double.NaN;
        int rpm = live != null && now - Hub.bike.liveAt <= ColdEngine.FRESH_MS ? live.rpm : -1;
        if (!coldEngine.check(coolant, rpm, limit, warmC, now)) return;
        Logger.log(String.format(Locale.ROOT, "Cold engine: %d rpm with the coolant at %.0f °C", rpm, coolant));
        if (!voiceAllowed()) return;
        if (beeps()) curveSound.beep(CurveBeeps.coldEngine());
        else curveSound.sayAfter(ColdEngine.PHRASE);
    }

    /** Beeps instead of words: asked for, or the phone cannot speak. */
    private boolean beeps() {
        return Hub.prefs(this).getBoolean(Hub.KEY_CURVE_BEEPS, false) || !curveSound.canSpeak();
    }

    /** The camera just announced, said too when "Say speed cameras" is on; after a bend being said, not over it. */
    private void sayCamera(long cameraId) {
        if (!Hub.prefs(this).getBoolean(Hub.KEY_CAMERA_VOICE, false) || !soundAllowed()) return;
        Hazards.Ahead a = curve.cameraAhead();
        if (a == null || a.camera.id != cameraId) return;
        if (beeps()) {
            curveSound.beep(CurveBeeps.camera());
        } else {
            curveSound.sayAfter(Hazards.spoken(a.camera));
        }
    }

    /** The lean of the chosen pace, degrees, 0 for none. "From my rides" leans as Normal until the rides say more. */
    private double paceLean(int pace) {
        if (pace == CurveAlerts.FROM_RIDES) {
            RideStats.BendLean l = ridesLean;
            return l != null && l.known() ? l.degrees : CurveAlerts.PACE_LEAN[CurveAlerts.NORMAL];
        }
        return pace > CurveAlerts.PACE_OFF && pace < CurveAlerts.PACE_LEAN.length ? CurveAlerts.PACE_LEAN[pace] : 0;
    }

    /** Tyre, ABS and battery warnings: shown on Home while they last, announced once each. */
    private void bikeAlerts(long now) {
        java.util.List<BikeAlerts.Alert> alerts = bikeAlertMonitor.current(Hub.bike, now);
        if (!Hub.prefs(this).getBoolean(Hub.KEY_BATTERY_WARNINGS, true)) {
            alerts = BikeAlerts.withoutBattery(alerts);
        }
        Hub.bikeAlerts = alerts;
        // a warning that went away (tyre pumped up, "low" became "not charging") leaves no stale notification
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (BikeAlerts.Alert a : alerts) ids.add(a.id);
        for (java.util.Iterator<String> it = postedAlerts.iterator(); it.hasNext(); ) {
            String id = it.next();
            if (ids.contains(id)) continue;
            getSystemService(NotificationManager.class).cancel(alertNotificationId(id));
            it.remove();
        }
        for (BikeAlerts.Alert a : alertTracker.fresh(alerts)) {
            Logger.log("Bike warning: " + a.title + " (" + a.detail + ")");
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) continue;
            PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(this, CHANNEL_ALERTS)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(a.title)
                    .setContentText(a.detail)
                    .setStyle(new Notification.BigTextStyle().bigText(a.detail))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build();
            // one notification per kind of warning, so a tyre and the battery do not replace each other
            getSystemService(NotificationManager.class).notify(alertNotificationId(a.id), n);
            postedAlerts.add(a.id);
        }
    }

    private static int alertNotificationId(String id) {
        return BIKE_ALERT_NOTIFICATION_BASE + Math.floorMod(id.hashCode(), 1000);
    }

    private final java.util.Set<String> postedAlerts = new java.util.HashSet<>();

    /**
     * A ride asked for the riding log extension before there was anything to talk to (the ride starts the
     * bridge itself): the ask is kept and sent once the link is up.
     */
    private boolean extWanted;

    /** The ride recorder asks for this; true when the request went out now. */
    public static boolean askRidingLogExt(Context c) {
        if (!Hub.askRidingLogExt(c)) return false;
        BridgeService s = instance;
        if (s == null || !s.ble.isReady()) {
            if (s != null) s.extWanted = true;
            return false;
        }
        return s.sendExtRequest();
    }

    private boolean sendExtRequest() {
        if (!Hub.askRidingLogExt(this) || requests.unsupported(BikeFrames.RIDING_LOG_EXT)) return false;
        extWanted = false;
        return requests.submitIfIdle(new BikeRequests.Request(
                KawaProtocol.readRequest(BikeFrames.RIDING_LOG_EXT), "riding log ext request",
                BikeRequests.DATA_OR_ACK, BikeRequests.STARTUP, null));
    }

    private void askRidingLogExtIfWanted() {
        if (extWanted && ble.isReady() && sendExtRequest()) {
            Logger.log("Ride: asked the bike for the riding log extension (0x45)");
        }
    }

    /** Litres come from the gauge jumping up while the bike stands still; see {@link FuelLog}. */
    private final FuelLog.Detector refuel = new FuelLog.Detector();

    private void refuelCheck(long now) {
        // no link check here: what matters is that the meter sent something a moment ago, which is the
        // same condition a replay satisfies (debug). The freshness checks below are the cheap part.
        BikeFrames.VehicleInfo info = Hub.bike.info;
        BikeFrames.Telemetry live = Hub.bike.live;
        if (info == null || now - Hub.bike.infoAt > BikeAlerts.FRESH_MS) return;
        // only at a standstill: the gauge moves as the bike leans and as fuel sloshes otherwise
        if (live == null || now - Hub.bike.liveAt > BikeAlerts.FRESH_MS) return;
        if (live.speedKmh != live.speedKmh || live.speedKmh >= FuelLog.STILL_KMH) {
            refuel.reset();
            return;
        }
        FuelLog.Entry e = refuel.offer(info.fuelBars, info.rangeKm, info.odometerKm, now,
                Hub.prefs(this).getFloat(FuelLog.KEY_TANK_L, (float) FuelLog.DEFAULT_TANK_L));
        if (e == null) return;
        java.util.List<FuelLog.Entry> entries = new java.util.ArrayList<>(Hub.fuelEntries);
        entries.add(e);
        Hub.fuelEntries = FuelLog.sorted(entries);
        new FuelStore(this).save(entries);
        Logger.log("Fuel: refuel recorded at " + e.odoKm + " km: " + e.describe());
        announceOn(CHANNEL_RIDES, REFUEL_NOTIFICATION_ID, "Refuel recorded", FuelActivity.class,
                e.describe() + " — correct the litres if they are wrong.");
    }

    /**
     * Keeps the 12 V readings: one per stop, taken while the engine is off and has been for a while,
     * since a running engine's 13-14 V says nothing about the battery's health
     * ({@link BikeAlerts#restingVoltage}). Warns once a day at most.
     */
    private void batteryCheck(long now) {
        double v = BikeAlerts.restingVoltage(Hub.bike, now);
        if (v != v) return;
        List<BatteryLog.Reading> readings = BatteryLog.record(Hub.batteryReadings, now, v, false);
        if (readings.size() == Hub.batteryReadings.size()) return;
        Hub.batteryReadings = readings;
        new BatteryStore(this).save(readings);
        Logger.log(String.format(java.util.Locale.ROOT, "Battery: %.1f V at rest (%d readings)", v, readings.size()));
        long warned = Hub.prefs(this).getLong(Hub.KEY_BATTERY_WARNED_AT, 0);
        if (BatteryLog.declining(readings) && now - warned > 24 * 60 * 60_000L) {
            Hub.prefs(this).edit().putLong(Hub.KEY_BATTERY_WARNED_AT, now).apply();
            double decline = BatteryLog.decline(readings);
            // a warning about the bike, on the warnings channel, with an id of its own: the refuel
            // confirmation is not a warning and must not replace it
            announceOn(CHANNEL_ALERTS, BIKE_ALERT_NOTIFICATION_BASE + 1, "Battery is going down",
                    BatteryActivity.class, String.format(java.util.Locale.ROOT,
                            "The 12 V battery has lost %.1f V over the time logged: have it checked before "
                                    + "it leaves you somewhere.", decline));
        }
    }

    private long maintenanceCheckedAt;
    private static final int MAINTENANCE_NOTIFICATION_ID = 9_003;

    /**
     * The rider's own maintenance reminders ({@link MaintenanceLog.Reminder}): a notification when one is
     * due, once for each time its job was logged. Looked at once a minute. The kilometres count from the
     * odometer the bike last sent (kept with the ignition off), the months need no bike at all.
     */
    private void maintenanceCheck(long now) {
        if (now - maintenanceCheckedAt < 60_000) return;
        maintenanceCheckedAt = now;
        SharedPreferences p = Hub.prefs(this);
        List<MaintenanceLog.Reminder> reminders = MaintenanceLog.reminders(p.getString(Hub.KEY_MAINT_REMINDERS, ""));
        if (reminders.isEmpty()) return;
        BikeFrames.VehicleInfo info = Hub.bike.info;
        double odo = info != null && info.odometerKm >= 0 ? info.odometerKm : Double.NaN;
        java.util.Set<String> told = new java.util.HashSet<>(p.getStringSet(Hub.KEY_MAINT_TOLD,
                java.util.Collections.emptySet()));
        java.text.DateFormat df = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM);
        List<String> lines = new java.util.ArrayList<>();
        boolean fresh = false;
        for (MaintenanceLog.Due d : MaintenanceLog.due(Hub.maintenanceEntries, reminders, odo, now)) {
            if (!d.due()) continue;
            // the title says "due": "Oil change: since 22 Sept 2026", "Chain: 57 km over"
            lines.add(d.reminder.item + ": " + d.describe(df).replaceFirst("^due: ", ""));
            fresh |= told.add(MaintenanceLog.toldKey(d));
        }
        if (!fresh) return;
        p.edit().putStringSet(Hub.KEY_MAINT_TOLD, told).apply();
        Logger.log("Maintenance due: " + String.join("; ", lines));
        // all that is due in one note, which the next one replaces: it is a list to work through
        announceOn(CHANNEL_ALERTS, MAINTENANCE_NOTIFICATION_ID, lines.size() == 1 ? "Maintenance due"
                : lines.size() + " maintenance jobs due", MaintenanceActivity.class, String.join("\n", lines));
    }

    /**
     * Tells the rider what a finished ride came to. Called by the recorder, which is not a service: this
     * is the only part of the app that has a notification channel.
     */
    public static void announceRide(Context c, String name, String line, String rideId) {
        if (c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        Intent open = new Intent(c, RideActivity.class).putExtra(RideActivity.EXTRA_ID, rideId);
        PendingIntent tap = PendingIntent.getActivity(c, rideId.hashCode(), open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CHANNEL_RIDES)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(name)
                .setContentText(line)
                .setStyle(new Notification.BigTextStyle().bigText(line))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build();
        c.getSystemService(NotificationManager.class).notify(RIDE_NOTIFICATION_ID, n);
    }

    private static final int RIDE_NOTIFICATION_ID = 9_002;

    /**
     * A finished ride whose clutch slipped under load ({@link Ride#clutchSlipping}): a warning of its own
     * on the warnings channel, which opens the ride. Called by the recorder, like {@link #announceRide}.
     */
    public static void warnClutch(Context c, Ride r) {
        Logger.log(String.format(java.util.Locale.ROOT, "Ride: clutch may be slipping, %d s in %d roll-ons, "
                + "up to %+.1f %%", r.clutchSlipS, r.clutchSlipRuns, r.clutchMaxPct));
        if (c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        Intent open = new Intent(c, RideActivity.class).putExtra(RideActivity.EXTRA_ID, r.id);
        PendingIntent tap = PendingIntent.getActivity(c, ("clutch" + r.id).hashCode(), open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = String.format(java.util.Locale.ROOT, "In %d hard accelerations on this ride the engine "
                + "revved up to %.1f%% higher than the gear and the speed give: the clutch may be slipping. "
                + "Have it checked.", r.clutchSlipRuns, r.clutchMaxPct);
        Notification n = new Notification.Builder(c, CHANNEL_ALERTS)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Clutch may be slipping")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build();
        // an id of its own on the warnings channel: neither the ride's summary nor a battery warning replaces it
        c.getSystemService(NotificationManager.class).notify(BIKE_ALERT_NOTIFICATION_BASE + 2, n);
    }

    /**
     * A short note about something that happened. Opening it goes to the screen it is about, since that
     * is where the rider has to do something about it.
     */
    private void announceOn(String channel, int id, String title, Class<?> screen, String text) {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        PendingIntent open = PendingIntent.getActivity(this, id, new Intent(this, screen),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, channel)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(id, n);
    }

    private static final int REFUEL_NOTIFICATION_ID = 9_001;
    private final BikeAlerts.Tracker alertTracker = new BikeAlerts.Tracker();
    private final ColdEngine coldEngine = new ColdEngine();
    private final BikeAlerts.Monitor bikeAlertMonitor = new BikeAlerts.Monitor();
    private static final int BIKE_ALERT_NOTIFICATION_BASE = 10_000;

    /** The camera no longer ahead: the next one is announced again. */
    private void cameraGone() {
        lastCameraId = -1;
    }

    private long lastCameraId = -1;
    private long lastCameraAt;
    private static final long CAMERA_ALERT_MIN_GAP_MS = 20_000;

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
