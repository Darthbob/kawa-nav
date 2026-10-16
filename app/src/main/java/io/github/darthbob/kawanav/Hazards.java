package io.github.darthbob.kawanav;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Speed cameras from OpenStreetMap: the ones loaded for the tiles around the rider, and which of them
 * is ahead. Plain Java, so the selection can be tested without Android. Cameras come from the offline
 * region download ({@link PbfImporter} → {@link HazardStore}); nothing is fetched while riding.
 */
public final class Hazards {

    /** A camera on the map: position, what it is and the limit it watches (0: unknown). */
    public static final class Camera {
        public final long id;
        public final int lat7, lon7;
        public final String type;      // fixed, red_light, average_speed…
        public final int maxspeed;     // km/h, 0 when the data does not say

        public Camera(long id, int lat7, int lon7, String type, int maxspeed) {
            this.id = id;
            this.lat7 = lat7;
            this.lon7 = lon7;
            this.type = type;
            this.maxspeed = maxspeed;
        }

        public double lat() {
            return lat7 / 1e7;
        }

        public double lon() {
            return lon7 / 1e7;
        }
    }

    private Hazards() {}

    /** A camera ahead, with the distance to it. */
    public static final class Ahead {
        public final Camera camera;
        public final double distanceM;

        Ahead(Camera camera, double distanceM) {
            this.camera = camera;
            this.distanceM = distanceM;
        }
    }

    /** In front of the rider: within this many degrees of the heading (and not behind). */
    private static final double CONE_DEG = 50;
    /** A camera that is basically beside the rider now: no longer worth a warning. */
    public static final double PASSED_M = 20;

    /**
     * The nearest camera in front of the rider: within {@code withinM} metres and inside the cone
     * around {@code headingDeg}. Null when there is none. Recomputing this every second needs no
     * state: past a camera the bearing turns away and it drops out by itself.
     */
    public static Ahead next(List<Camera> cameras, double lat, double lon, double headingDeg, double withinM) {
        Ahead best = null;
        for (Camera c : cameras) {
            double d = distanceM(lat, lon, c.lat(), c.lon());
            if (d > withinM || d < PASSED_M) continue;
            if (!inFront(lat, lon, headingDeg, c.lat(), c.lon())) continue;
            if (best == null || d < best.distanceM) best = new Ahead(c, d);
        }
        return best;
    }

    /** True when the camera lies within {@link #CONE_DEG} of the heading, and roughly ahead. */
    static boolean inFront(double lat, double lon, double headingDeg, double camLat, double camLon) {
        double dLat = camLat - lat;
        double dLon = (camLon - lon) * Math.cos(Math.toRadians((lat + camLat) / 2));
        if (dLat == 0 && dLon == 0) return false;
        double bearing = Math.toDegrees(Math.atan2(dLon, dLat));                 // north-based, clockwise
        if (bearing < 0) bearing += 360;
        double diff = Math.abs(((bearing - headingDeg) % 360 + 540) % 360 - 180); // 0..180
        return diff <= CONE_DEG;
    }

    /** Distance in metres (equirectangular, like the ride distance). */
    public static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        return Ride.distanceM(lat1, lon1, lat2, lon2);
    }

    /** What the dash and the app call the camera: "Speed camera", "Red-light camera"… */
    public static String name(Camera c) {
        switch (c.type) {
            case "red_light": return "Red-light camera";
            case "average_speed": return "Average-speed section";
            default: return "Speed camera";
        }
    }

    /** "Speed camera in 350 m" or "Speed camera in 350 m (50 km/h)". */
    public static String describe(Camera c, double distanceM) {
        String s = name(c) + " in " + (distanceM >= 1000
                ? String.format(Locale.ROOT, "%.1f km", distanceM / 1000)
                : Math.round(distanceM / 10) * 10 + " m");
        return c.maxspeed > 0 ? s + " (" + c.maxspeed + " km/h)" : s;
    }

    /** Cameras of several tiles, each once (regions overlap, and border tiles are merged). */
    @SafeVarargs
    public static List<Camera> merge(List<Camera>... lists) {
        java.util.LinkedHashMap<Long, Camera> byId = new java.util.LinkedHashMap<>();
        for (List<Camera> list : lists) {
            if (list == null) continue;
            for (Camera c : list) byId.put(c.id, c);
        }
        return new ArrayList<>(byId.values());
    }
}
