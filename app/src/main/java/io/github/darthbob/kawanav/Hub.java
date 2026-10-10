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

        public Output(int turn, int distanceM, String source) {
            this.turn = turn;
            this.distanceM = distanceM;
            this.source = source;
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

    // --- settings
    public static final String PREFS = "settings";
    public static final String KEY_FREE_MODE = "free_mode";
    public static final String KEY_MAPS = "maps_directions";
    public static final String KEY_LEFT_HAND = "left_hand_traffic";
    public static final String KEY_IMPERIAL = "imperial";
    public static final String KEY_SENSITIVITY = "curve_sensitivity"; // 0 low, 1 normal, 2 high
    public static final String KEY_LOOKAHEAD = "curve_lookahead_m";
    public static final String KEY_DEVICE = "device";
    /** Address of the helmet whose Bluetooth connection starts the bridge (autostart fallback). */
    public static final String KEY_HELMET = "helmet";
    /** Curve warnings between Google Maps instructions: 0 off, 1 sharp curves only, 2 all curves. */
    public static final String KEY_NAV_CURVES = "nav_curves";

    /** Free mode (show upcoming curves) is on unless turned off; Google Maps navigation takes priority. */
    public static boolean freeMode(Context c) {
        return prefs(c).getBoolean(KEY_FREE_MODE, true);
    }

    /** Google Maps directions on the meter: on unless turned off; off, free mode runs during navigation too. */
    public static boolean mapsEnabled(Context c) {
        return prefs(c).getBoolean(KEY_MAPS, true);
    }

    public static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
