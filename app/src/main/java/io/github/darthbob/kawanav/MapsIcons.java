package io.github.darthbob.kawanav;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The icon library: Google Maps' own maneuver drawables (res/drawable/maneuver_*), rendered from
 * the installed Maps app and mapped to Kawasaki turn ids. Maps draws its navigation notification
 * arrow from these drawables, so a notification icon matches exactly one of them.
 */
public final class MapsIcons {
    private MapsIcons() {}

    /** Library value: generic icon without direction information, let the text decide. */
    public static final int NO_INFO = IconMemory.NOT_A_MANEUVER;

    /**
     * Maps drawable name -> Kawasaki turn id (see KawaProtocol.TURN_NAMES).
     * Several drawables are pixel-identical (e.g. on_ramp_normal_left = turn_normal_left,
     * keep_left = fork_left, merge_left = merge); the first entry in this order wins, so generic
     * meanings come first and the instruction text refines ramps/exits/merges (MapsListener).
     */
    static final Map<String, Integer> TABLE = new LinkedHashMap<>();

    static {
        TABLE.put("maneuver_depart", 42);
        TABLE.put("maneuver_destination", 43);
        TABLE.put("maneuver_destination_left", 43);
        TABLE.put("maneuver_destination_right", 43);
        TABLE.put("maneuver_destination_straight", 43);
        TABLE.put("maneuver_straight", 0);
        TABLE.put("maneuver_name_change", 0);

        TABLE.put("maneuver_turn_normal_left", 1);
        TABLE.put("maneuver_turn_normal_right", 2);
        TABLE.put("maneuver_turn_sharp_left", 3);
        TABLE.put("maneuver_turn_sharp_right", 4);
        TABLE.put("maneuver_turn_slight_left", 5);
        TABLE.put("maneuver_turn_slight_right", 6);
        TABLE.put("maneuver_u_turn_left", 7);
        TABLE.put("maneuver_u_turn_right", 8);

        TABLE.put("maneuver_fork_left", 9);
        TABLE.put("maneuver_fork_right", 10);
        TABLE.put("maneuver_keep_left", 9);
        TABLE.put("maneuver_keep_right", 10);

        TABLE.put("maneuver_merge", 16);
        TABLE.put("maneuver_merge_left", 16);
        TABLE.put("maneuver_merge_right", 17);

        for (String v : new String[]{"keep", "normal", "sharp", "slight", "u_turn"}) {
            TABLE.put("maneuver_on_ramp_" + v + "_left", 12);
            TABLE.put("maneuver_on_ramp_" + v + "_right", 13);
            TABLE.put("maneuver_off_ramp_" + v + "_left", 14);
            TABLE.put("maneuver_off_ramp_" + v + "_right", 15);
        }

        // Roundabouts: Maps gives the exit's direction, Kawasaki the exit number. Use the usual
        // 4-arm layout (right = 1st, straight = 2nd, left = 3rd, back = 4th exit); a number in the
        // instruction text overrides this (see MapsListener).
        // ccw = counter-clockwise (right-hand traffic) -> ids 30..41, cw = clockwise -> 18..29
        for (String dir : new String[]{"ccw", "cw"}) {
            int base = dir.equals("ccw") ? 29 : 17; // + exit number
            String p = "maneuver_roundabout_enter_and_exit_" + dir;
            String near = dir.equals("ccw") ? "right" : "left";  // first exit side
            String far = dir.equals("ccw") ? "left" : "right";
            TABLE.put(p + "_sharp_" + near, base + 1);
            TABLE.put(p + "_normal_" + near, base + 1);
            TABLE.put(p + "_slight_" + near, base + 1);
            TABLE.put(p + "_straight", base + 2);
            TABLE.put(p + "_slight_" + far, base + 3);
            TABLE.put(p + "_normal_" + far, base + 3);
            TABLE.put(p + "_sharp_" + far, base + 3);
            TABLE.put(p + "_u_turn", base + 4);
            TABLE.put(p, NO_INFO);
            TABLE.put("maneuver_roundabout_enter_" + dir, NO_INFO);
            TABLE.put("maneuver_roundabout_exit_" + dir, NO_INFO);
        }
    }

    private static Map<String, Integer> library;
    private static long libraryVersion = -1;

    /**
     * hash -> turn for all Maps maneuver icons of the installed Maps version (rebuilt after a Maps
     * update). Also writes the rendered icons to files/maps_icons/ for review.
     */
    static synchronized Map<String, Integer> library(Context context) {
        long version = mapsVersion(context);
        if (library != null && version == libraryVersion) return library;
        Map<String, Integer> lib = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        int missing = 0;
        try {
            Context maps = context.createPackageContext(MapsListener.MAPS, 0);
            File dir = new File(context.getFilesDir(), "maps_icons");
            if (!dir.isDirectory()) dir.mkdirs();
            for (Map.Entry<String, Integer> e : TABLE.entrySet()) {
                int id = maps.getResources().getIdentifier(e.getKey(), "drawable", MapsListener.MAPS);
                if (id == 0) {
                    missing++;
                    continue;
                }
                Drawable d = maps.getResources().getDrawable(id, null).mutate();
                d.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
                Bitmap b = Bitmap.createBitmap(126, 126, Bitmap.Config.ARGB_8888);
                d.setBounds(0, 0, 126, 126);
                d.draw(new Canvas(b));
                String hash = IconMemory.hash(b);
                if (!lib.containsKey(hash)) { // identical image already mapped by a more generic name
                    lib.put(hash, e.getValue());
                    names.put(hash, e.getKey());
                }
                try (FileOutputStream os = new FileOutputStream(new File(dir, e.getKey() + ".png"))) {
                    b.compress(Bitmap.CompressFormat.PNG, 100, os);
                }
            }
            Logger.log("Icon library: " + lib.size() + " Google Maps maneuver icons (Maps " + version + ")"
                    + (missing > 0 ? ", " + missing + " not found in this Maps version" : ""));
        } catch (Exception e) {
            Logger.log("Icon library unavailable: " + e);
        }
        library = lib;
        libraryVersion = version;
        verifyCollected(context, names);
        return lib;
    }

    /** Logs which library icon each collected real notification icon matches (files/icons). */
    private static void verifyCollected(Context context, Map<String, String> names) {
        File dir = new File(context.getFilesDir(), "icons");
        File[] pngs = dir.listFiles((d, n) -> n.endsWith(".png"));
        if (pngs == null) return;
        for (File f : pngs) {
            Bitmap b = BitmapFactory.decodeFile(f.getPath());
            if (b == null) continue;
            String h = IconMemory.hash(b);
            String best = "-";
            int bestDiff = Integer.MAX_VALUE;
            for (Map.Entry<String, String> e : names.entrySet()) {
                int d = IconMemory.diff(h, e.getKey());
                if (d < bestDiff) {
                    bestDiff = d;
                    best = e.getValue();
                }
            }
            Logger.log("Icon check " + f.getName() + " -> " + best + " (" + bestDiff + " bits differ)");
        }
    }

    private static long mapsVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(MapsListener.MAPS, 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }
}
