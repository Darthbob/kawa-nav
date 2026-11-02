package io.github.darthbob.kawanav;

import java.util.Collections;
import java.util.List;

/**
 * Once a second: what goes on the dash. KawaNav's own navigation before Google Maps' notification,
 * a speed camera before both, a curve on the way to the next maneuver when asked for, free ride when
 * nothing navigates, else nothing. Plain Java: {@link BridgeService} gathers the {@link Inputs} from
 * the settings, the curve engine and {@link Hub}, this class only decides, so the rules can be tested
 * without a bike, a GPS or Android.
 */
public final class DashArbiter {
    private DashArbiter() {}

    /** While navigating, a camera takes the arrow over only this close (the maneuver matters more). */
    public static final double CAMERA_NAV_M = 250;
    /** During navigation, a curve is shown this close at most (the maneuver's arrow otherwise). */
    public static final double NAV_CURVE_SHOW_M = 300;
    /** ... and only if it starts this clearly before the next maneuver. */
    public static final double NAV_CURVE_BEFORE_MANEUVER_M = 50;

    /** "Curve warnings during navigation": the values of {@link Hub#KEY_NAV_CURVES}. */
    public static final int NAV_CURVES_OFF = 0, NAV_CURVES_SHARP = 1, NAV_CURVES_ALL = 2;

    /** Everything the decision looks at, this second. */
    public static final class Inputs {
        /** KawaNav's own navigation while it runs ({@link Hub#navActive()}), else null. */
        public Hub.Nav nav;
        /** Google Maps' last notification, null when Maps is not navigating. */
        public Hub.MapsTurn maps;
        /** "Google Maps directions" is on. */
        public boolean mapsEnabled = true;
        /** "Show upcoming curves" is on. */
        public boolean freeMode = true;
        /** {@link #NAV_CURVES_OFF}, {@link #NAV_CURVES_SHARP} or {@link #NAV_CURVES_ALL}. */
        public int navCurves = NAV_CURVES_OFF;
        /** What free ride would show now ({@link CurveEngine#current()}), null when nothing. */
        public Hub.Output freeRide;
        /** The curves ahead from a recent fix (empty when the fix is old), nearest first. */
        public List<CurveDetector.Curve> curves = Collections.emptyList();
        /** The speed camera ahead within the warning distance, or null. */
        public Hazards.Ahead camera;
        /** The speed camera warnings are on (and the phone may use its position). */
        public boolean camerasEnabled;
        /** The distance the camera warnings are set to, metres. */
        public double cameraWarnM = Hub.CAMERA_WARN_DEFAULT;
    }

    /** The frame for the dash this second, or null for nothing (the dash is then cleared). */
    public static Hub.Output decide(Inputs in) {
        // KawaNav's own navigation comes before Google Maps' notifications
        Hub.Nav nav = in.nav;
        Hub.MapsTurn m = nav == null && in.mapsEnabled ? in.maps : null;
        // a camera warning comes first: it is short, and it is the one thing the rider cannot see coming
        Hub.Output cam = cameraWarning(in.camera, m != null || nav != null, in.camerasEnabled, in.cameraWarnM);
        if (cam != null) return cam;
        if (nav != null) {
            if (nav.turn == KawaProtocol.TURN_NONE) return null;
            if (in.navCurves > NAV_CURVES_OFF && nav.state == Navigator.State.NAVIGATING) {
                Hub.Output c = curveBeforeManeuver(in.curves, nav.distanceM, in.navCurves == NAV_CURVES_SHARP);
                if (c != null) return c;
            }
            return new Hub.Output(nav.turn, CurveEngine.roundDistance(nav.distanceM), "Nav");
        }
        if (m != null) {
            // e.g. "Navigation wird gestartet…": nothing useful to show yet
            if (m.turn == KawaProtocol.TURN_NONE && m.distanceM < 0) return null;
            // between Maps instructions: warn about curves on the way to the next maneuver
            if (in.navCurves > NAV_CURVES_OFF) {
                Hub.Output c = curveBeforeManeuver(in.curves, m.distanceM, in.navCurves == NAV_CURVES_SHARP);
                if (c != null) return c;
            }
            return new Hub.Output(m.turn, m.distanceM, "Maps");
        }
        if (in.freeMode) return in.freeRide;
        return null;
    }

    /**
     * The camera ahead, when the warnings are on and it is close enough: the whole warning distance
     * in free ride, {@link #CAMERA_NAV_M} at most while navigating (the arrow matters more there).
     */
    public static Hub.Output cameraWarning(Hazards.Ahead a, boolean navigating, boolean enabled, double warnM) {
        if (!enabled || a == null) return null;
        double window = navigating ? Math.min(warnM, CAMERA_NAV_M) : warnM;
        if (a.distanceM > window) return null;
        return new Hub.Output(KawaProtocol.CAMERA_TURN, CurveEngine.roundDistance(a.distanceM), "Camera",
                a.camera.id, Hazards.describe(a.camera, a.distanceM));
    }

    /**
     * Curve warning during navigation: the next curve within {@link #NAV_CURVE_SHOW_M} that starts at
     * least {@link #NAV_CURVE_BEFORE_MANEUVER_M} before the next maneuver, or null. A curve already
     * entered is the rider's business; the first one not yet entered decides, so a gentle bend right
     * ahead hides nothing behind it when only sharp ones are asked for: the sharp one after it is found.
     *
     * @param maneuverDist distance to the maneuver (m); unknown (negative) means it is imminent
     *                     ("Ankunft", "Links abbiegen" without a distance): no curve then
     * @param sharpOnly    only sharp curves and hairpins
     */
    public static Hub.Output curveBeforeManeuver(List<CurveDetector.Curve> curves, int maneuverDist, boolean sharpOnly) {
        if (maneuverDist < 0) return null;
        for (CurveDetector.Curve c : curves) {
            if (c.startDist <= CurveDetector.ENTERED_M) continue;
            if (c.startDist > NAV_CURVE_SHOW_M) return null;
            if (c.startDist > maneuverDist - NAV_CURVE_BEFORE_MANEUVER_M) return null;
            if (sharpOnly && !sharp(c.turn)) continue;
            return new Hub.Output(c.turn, CurveEngine.roundDistance(c.startDist), "Curve");
        }
        return null;
    }

    /** A sharp bend or a hairpin, either side ({@link KawaProtocol} turn ids). */
    static boolean sharp(int turn) {
        return turn == 3 || turn == 4 || turn == 7 || turn == 8;
    }
}
