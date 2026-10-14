package io.github.darthbob.kawanav;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Shared in-process state between the notification listener, the bridge service and the UI.
 * Everything is touched on the main thread only.
 */
public final class Hub {
    private Hub() {}

    /** What the bridge should show for Google Maps; null when Maps is not navigating. */
    public static final class MapsTurn {
        public final int turn;        // KawaProtocol turn id, or TURN_NONE when not recognised
        public final int distanceM;   // -1 when no distance was found
        public final String text;     // human readable, for UI/log
        public final long updatedAt;

        public MapsTurn(int turn, int distanceM, String text) {
            this.turn = turn;
            this.distanceM = distanceM;
            this.text = text;
            this.updatedAt = System.currentTimeMillis();
        }
    }

    /** A frame the bridge sends; distance in metres. */
    public static final class Output {
        public final int turn;
        public final int distanceM;
        public final String source;
        /** Speed camera warning: the OpenStreetMap node id, or -1 when this is not one. */
        public final long cameraId;
        /** "Speed camera in 350 m (50 km/h)"; only with a camera warning. */
        public final String alert;

        public Output(int turn, int distanceM, String source) {
            this(turn, distanceM, source, -1, null);
        }

        public Output(int turn, int distanceM, String source, long cameraId, String alert) {
            this.turn = turn;
            this.distanceM = distanceM;
            this.source = source;
            this.cameraId = cameraId;
            this.alert = alert;
        }

        public String describe() {
            String name = turn >= 0 && turn < KawaProtocol.TURN_NAMES.length ? KawaProtocol.TURN_NAMES[turn] : "?";
            return source + ": " + name + (distanceM >= 0 ? " in " + distanceM + " m" : "");
        }
    }

    // --- live state
    public static MapsTurn maps;
    public static boolean serviceRunning;
    public static String bikeStatus = "Not running";
    /** What the rider can do about the bike status, or "". */
    public static String bikeHint = "";
    public static boolean bikeConnected;
    /** What the meter shows right now (null: nothing). */
    public static Output output;
    public static String outputStatus = "–";
    public static String curveStatus = "–";
    public static boolean offlineRunning;
    public static String offlineStatus = "–";
    /** Progress of pairing the bike from KawaNav ("" = not pairing). */
    public static String pairStatus = "";
    /** What the connected meter says it can show (null: not known yet). */
    public static BikeFrames.GeneralCaps bikeCapabilities;
    /** Debug: the meter's Bluetooth services, read once per connect while the debug card is shown. */
    public static String gattDump = "";
    /** Status icons last sent to the meter, for the debug card. */
    public static String iconsStatus = "–";
    /** Vehicle data and settings from the meter (last values, also after the bike is switched off). */
    public static final BikeData bike = new BikeData();
    /** ATT MTU of the current connection, for the debug card. */
    public static int bikeMtu;
    /** Settings write waiting for the meter's answer, see {@link BridgeService#writeSettings}. */
    public static BridgeService.PendingSettings settingsWritePending;
    /** Warnings the bike's values show right now (tyres, ABS, battery), see {@link BikeAlerts}. */
    public static java.util.List<BikeAlerts.Alert> bikeAlerts = java.util.Collections.emptyList();
    /** Last GPS fix any part of KawaNav got (curves, ride recorder), or null. */
    public static android.location.Location lastFix;

    // --- settings
    public static final String PREFS = "settings";
    public static final String KEY_FREE_MODE = "free_mode";
    public static final String KEY_MAPS = "maps_directions";
    public static final String KEY_LEFT_HAND = "left_hand_traffic";
    public static final String KEY_IMPERIAL = "imperial";
    public static final String KEY_SENSITIVITY = "curve_sensitivity"; // 0 low, 1 normal, 2 high
    public static final String KEY_LOOKAHEAD = "curve_lookahead_m";
    public static final String KEY_DEVICE = "device";
    /** Bluetooth name of the bike last connected (e.g. "Kawasaki-ZR900S"), for the bike screens. */
    public static final String KEY_BIKE_NAME = "bike_name";
    /** Address of the helmet whose Bluetooth connection starts the bridge (autostart fallback). */
    public static final String KEY_HELMET = "helmet";
    /** Phone battery icon on the bike's display (on unless turned off). */
    public static final String KEY_PHONE_BATTERY = "phone_battery";
    /** When the user stopped the bridge by hand (0: not), see {@link #autostartPaused}. */
    public static final String KEY_STOPPED_BY_USER = "stopped_by_user_at";
    /** A stop by hand pauses autostart at most this long (normally until the bike is switched off). */
    public static final long AUTOSTART_PAUSE_MS = 60 * 60_000;
    /** When the bike was last reported out of range (0: not since the last manual stop). */
    public static final String KEY_BIKE_GONE_AT = "bike_gone_at";
    /**
     * The bike must be gone this long before it counts as switched off. Right after Stop, Android
     * reports it out of range and back within ~2 s (the link closes, the bike advertises again).
     */
    public static final long BIKE_OFF_MS = 20_000;
    /**
     * Debug builds only: the hidden debug card and everything behind it (icon values, phone name,
     * Bluetooth service list). Release builds ignore those settings even if a debug build left them set.
     */
    public static final boolean DEBUG_TOOLS = BuildConfig.DEBUG;
    /** Hidden debug card for the meter's status icons is shown (tap the title 7 times). */
    public static final String KEY_DEBUG_ICONS = "debug_icons";
    /** Debug: fixed values (0..15) for the status icon fields, -1 = automatic. */
    public static final String KEY_ICON_BATTERY = "icon_battery";
    public static final String KEY_ICON_VOICE = "icon_voice";
    public static final String KEY_ICON_HEADSET = "icon_headset";
    public static final String KEY_ICON_RIDING_LOG = "icon_riding_log";
    /** Debug: text sent to the meter as the phone's name on every connect ("" = none). */
    public static final String KEY_DEBUG_PHONE_NAME = "debug_phone_name";
    /** Debug: requests sent after connecting, {@link KawaProtocol#INIT_NONE} / INIT_READ / INIT_FULL. */
    public static final String KEY_DEBUG_INIT_LEVEL = "debug_init_level";
    /** Debug: every frame to and from the meter is written to a capture file, see {@link RawCapture}. */
    public static final String KEY_DEBUG_CAPTURE = "debug_capture";
    /** Debug: notifications are written down, to see what an app like Waze puts in them. */
    public static final String KEY_DEBUG_NOTIF_CAPTURE = "debug_notif_capture";
    /** Debug: capture every app's notifications, not only Waze and the shell. */
    public static final String KEY_DEBUG_NOTIF_CAPTURE_ALL = "debug_notif_capture_all";
    /** The meter clock is set from the phone once per connection (off until tried on the bike). */
    public static final String KEY_CLOCK_SYNC = "clock_sync";
    /** Call icon on the dash (Rideology's notification icons). */
    public static final String KEY_DASH_CALLS = "dash_calls";
    /** Mail/message icon on the dash. */
    public static final String KEY_DASH_MAIL = "dash_mail";
    /**
     * Speed camera warnings from the offline OpenStreetMap data, on the phone and on the dash.
     * Off by default: announcing cameras is restricted in some countries (see README).
     */
    public static final String KEY_CAMERAS = "speed_cameras";
    /** Warn about a camera from this many metres on (the value of {@link #CAMERA_WARN_CHOICES}). */
    public static final String KEY_CAMERA_WARN = "camera_warn_m";
    public static final int CAMERA_WARN_DEFAULT = 500;
    /** Start recording by itself when the bike is moving (off by default). */
    public static final String KEY_RIDE_AUTO = "ride_auto";
    /** Set while the bridge is being started only because a ride was asked for. */
    public static final String KEY_RIDE_WANTED = "ride_wanted";
    /** Ride state ({@link RideRecorder#IDLE}, RECORDING, PAUSED) and its "3.4 km · 12 min" text, for the UI. */
    public static int rideState;
    public static String rideText = "";
    /** Curve warnings between Google Maps instructions: 0 off, 1 sharp curves only, 2 all curves. */
    public static final String KEY_NAV_CURVES = "nav_curves";

    /** Free mode (show upcoming curves) is on unless turned off; Google Maps navigation takes priority. */
    /** Speed camera warnings are on. */
    public static boolean camerasEnabled(Context c) {
        return prefs(c).getBoolean(KEY_CAMERAS, false);
    }

    /** Distance from which a camera is announced, metres. */
    public static int cameraWarnM(Context c) {
        return prefs(c).getInt(KEY_CAMERA_WARN, CAMERA_WARN_DEFAULT);
    }

    public static boolean freeMode(Context c) {
        return prefs(c).getBoolean(KEY_FREE_MODE, true);
    }

    /** Google Maps directions on the meter: on unless turned off; off, free mode runs during navigation too. */
    public static boolean mapsEnabled(Context c) {
        return prefs(c).getBoolean(KEY_MAPS, true);
    }

    /** Debug value of a status icon field (-1 = automatic, always in release builds). */
    public static int iconSetting(Context c, String key) {
        if (!DEBUG_TOOLS) return -1;
        return prefs(c).getInt(key, -1);
    }

    /** Requests sent to the bike after connecting (release builds: read-only). */
    public static int initLevel(Context c) {
        if (!DEBUG_TOOLS) return KawaProtocol.INIT_READ;
        int level = prefs(c).getInt(KEY_DEBUG_INIT_LEVEL, KawaProtocol.INIT_READ);
        return Math.max(KawaProtocol.INIT_NONE, Math.min(KawaProtocol.INIT_FULL, level));
    }

    /**
     * The user stopped the bridge: autostart (bike or helmet) must not start it again right away. After
     * Stop the bike advertises again and would be "seen" within seconds. Ends when the bike is switched
     * off (gone for {@link #BIKE_OFF_MS}), on a manual start, or after {@link #AUTOSTART_PAUSE_MS}.
     */
    public static boolean autostartPaused(Context c) {
        long at = prefs(c).getLong(KEY_STOPPED_BY_USER, 0);
        return at > 0 && System.currentTimeMillis() - at < AUTOSTART_PAUSE_MS;
    }

    public static void setStoppedByUser(Context c, boolean stopped) {
        prefs(c).edit().putLong(KEY_STOPPED_BY_USER, stopped ? System.currentTimeMillis() : 0)
                .putLong(KEY_BIKE_GONE_AT, 0).apply();
    }

    public static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
