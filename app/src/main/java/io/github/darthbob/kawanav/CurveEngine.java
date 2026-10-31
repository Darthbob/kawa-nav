package io.github.darthbob.kawanav;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Curves of the road you are on: GPS fix + heading -> match to an OpenStreetMap road -> follow it
 * ahead -> {@link CurveDetector} (and roundabouts with the exit that continues the road). While
 * KawaNav navigates ({@link NavService}) the route ahead is the planned one.
 *
 * Used for free mode (shows the next curve), for curve warnings between Google Maps
 * instructions and for the spoken curve warnings. Roads come from the downloaded regions
 * ({@link RoadStore} tiles): the 3x3 tiles around the rider. Nothing is fetched while riding.
 */
@SuppressLint("MissingPermission") // location permission is checked before start()
public final class CurveEngine implements LocationListener {

    private static final long STALE_FIX_MS = 5_000;
    private static final double MIN_SPEED_FOR_BEARING = 2.0; // m/s
    /** During navigation, a curve is shown this close at most (the Maps arrow otherwise). */
    private static final double NAV_CURVE_SHOW_M = 300;
    /** ... and only if it comes clearly before the next Maps maneuver. */
    private static final double NAV_CURVE_BEFORE_MANEUVER_M = 50;
    /** Free mode on a straight: how far to search for the next curve to show "straight for X m". */
    private static final double STRAIGHT_SEARCH_M = 3000;

    private final Context context;
    private final RoadStore store;
    private final HazardStore hazards;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private boolean running;
    private RoadNetwork network;
    private int builtTy = Integer.MIN_VALUE;
    private int builtTx = Integer.MIN_VALUE;
    private int builtTiles;
    private boolean busy;

    private Location lastBearingFix;
    private double heading = Double.NaN;
    private RoadNetwork.Match lastMatch;
    /** Last road before a roundabout; decides which exit continues it. */
    private RoadNetwork.Approach approach;
    private Hub.Output output;
    /** Keeps {@link #output} from flipping between two curves from one fix to the next. */
    private final CurveSmoother smoother = new CurveSmoother();
    private List<CurveDetector.Curve> curves = Collections.emptyList();
    /** The network the coordinates of {@link #curves} are in, and the speed of their fix (m/s). */
    private RoadNetwork curvesNet;
    private double curvesSpeed;
    private long outputAt;

    /** Speed cameras of the tiles around the rider, and the one ahead. */
    private List<Hazards.Camera> cameras = Collections.emptyList();
    private Hazards.Ahead camera;
    private long cameraAt;
    private String cameraTiles = "";
    /** A region download finished since the last fix: the tiles on disk changed. */
    private boolean offlineWasRunning;

    public CurveEngine(Context context) {
        this.context = context.getApplicationContext();
        this.store = new RoadStore(this.context);
        this.hazards = new HazardStore(this.context);
    }

    public void start() {
        if (running) return;
        running = true;
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this, Looper.getMainLooper());
        Hub.curveStatus = store.keys().isEmpty() ? "No offline roads: download a region under Offline maps"
                : "Waiting for GPS";
        Logger.log("Curve detection on");
    }

    public void stop() {
        if (!running) return;
        running = false;
        ((LocationManager) context.getSystemService(Context.LOCATION_SERVICE)).removeUpdates(this);
        output = null;
        smoother.clear();
        curves = Collections.emptyList();
        curvesNet = null;
        lastMatch = null;
        camera = null;
        cameras = Collections.emptyList();
        cameraTiles = "";
        Hub.curveStatus = "Off";
        Logger.log("Curve detection off");
    }

    private boolean fresh() {
        return System.currentTimeMillis() - outputAt <= STALE_FIX_MS;
    }

    /** Free mode: what to show now (next curve, roundabout or straight), or null. */
    public Hub.Output current() {
        return output != null && fresh() ? output : null;
    }

    /**
     * The speed camera ahead within the warning distance, or null. Independent of the offline roads:
     * it also works where no region is downloaded, as long as one holds the cameras.
     */
    public Hazards.Ahead cameraAhead() {
        return camera != null && System.currentTimeMillis() - cameraAt <= STALE_FIX_MS ? camera : null;
    }

    /**
     * Curve warning during navigation: the next curve within {@link #NAV_CURVE_SHOW_M} that comes
     * before the next Maps maneuver, or null.
     *
     * @param maneuverDist distance to the Maps maneuver (m); unknown (negative) means it is
     *                     imminent ("Ankunft", "Links abbiegen" without distance): no curve then
     * @param sharpOnly    only sharp curves and hairpins
     */
    public Hub.Output curveBeforeManeuver(int maneuverDist, boolean sharpOnly) {
        if (!fresh() || maneuverDist < 0) return null;
        for (CurveDetector.Curve c : curves) {
            if (c.startDist <= CurveDetector.ENTERED_M) continue;
            if (c.startDist > NAV_CURVE_SHOW_M) return null;
            if (c.startDist > maneuverDist - NAV_CURVE_BEFORE_MANEUVER_M) return null;
            boolean sharp = c.turn == 3 || c.turn == 4 || c.turn == 7 || c.turn == 8;
            if (sharpOnly && !sharp) continue;
            return new Hub.Output(c.turn, roundDistance(c.startDist), "Curve");
        }
        return null;
    }

    /** The curves ahead for the spoken warnings, each with where it starts; empty when the fix is old. */
    public List<CurveAlerts.Ahead> curvesAhead() {
        RoadNetwork net = curvesNet;
        if (!fresh() || net == null) return Collections.emptyList();
        List<CurveAlerts.Ahead> out = new ArrayList<>(curves.size());
        for (CurveDetector.Curve c : curves) {
            double[] at = net.unproject(c.startX, c.startY);
            out.add(new CurveAlerts.Ahead(c.turn, c.startDist, c.endDist, c.minRadius, at[0], at[1]));
        }
        return out;
    }

    /** Speed of the fix the curves are from, m/s (0 when it had none). */
    public double speed() {
        return curvesSpeed;
    }

    @Override
    public void onLocationChanged(Location loc) {
        if (!running) return;
        Hub.lastFix = loc;
        updateHeading(loc);
        ensureRoads(loc);
        ensureHazards(loc);
        compute(loc);
    }

    private void updateHeading(Location loc) {
        if (loc.hasBearing() && loc.hasSpeed() && loc.getSpeed() >= MIN_SPEED_FOR_BEARING) {
            heading = loc.getBearing();
            lastBearingFix = loc;
            return;
        }
        // slow: derive from movement, keep the old heading when standing
        if (lastBearingFix != null && lastBearingFix.distanceTo(loc) > 8) {
            heading = lastBearingFix.bearingTo(loc);
            if (heading < 0) heading += 360;
            lastBearingFix = loc;
        } else if (lastBearingFix == null) {
            lastBearingFix = loc;
        }
    }

    private void compute(Location loc) {
        Context c = context;
        int sensitivity = Hub.prefs(c).getInt(Hub.KEY_SENSITIVITY, 1);
        int lookahead = Hub.prefs(c).getInt(Hub.KEY_LOOKAHEAD, 1000);
        // cameras first: they are not a road feature, so a missing/mismatched road must not hide them
        camera = Hub.camerasEnabled(c) && !Double.isNaN(heading)
                ? Hazards.next(cameras, loc.getLatitude(), loc.getLongitude(), heading, Hub.cameraWarnM(c)) : null;
        cameraAt = System.currentTimeMillis();
        RoadNetwork net = network;
        if (net == null) {
            Hub.curveStatus = busy ? "Loading roads…" : "No offline roads here: download this region under Offline maps";
            output = smoother.next(null, 0, 0);
            return;
        }
        if (Double.isNaN(heading)) {
            Hub.curveStatus = "Waiting for movement to know the heading";
            output = smoother.next(null, 0, 0);
            return;
        }
        double[] p = net.project(loc.getLatitude(), loc.getLongitude());
        RoadNetwork.Match m = net.match(p[0], p[1], heading, lastMatch);
        lastMatch = m;
        if (m != null && !net.onRoundabout(m)) approach = net.approach(m, heading);
        if (m == null) {
            Hub.curveStatus = "Not on a known road";
            output = smoother.next(null, 0, 0);
            return;
        }
        List<RoadNetwork.RoundaboutPass> passes = new ArrayList<>();
        List<double[]> path;
        List<double[]> navAhead = Hub.navActive() != null ? Hub.navAhead : null;
        if (navAhead != null && navAhead.size() >= 2) {
            // KawaNav is navigating: the bends of the planned route, not of the road straight on
            path = new ArrayList<>(navAhead.size());
            for (double[] ll : navAhead) path.add(net.project(ll[0], ll[1]));
            for (double[] rb : Hub.navRoundabouts) {
                RoadNetwork.RoundaboutPass pass = new RoadNetwork.RoundaboutPass(rb[0], 1, false);
                pass.exitDist = rb[1];
                passes.add(pass);
            }
        } else {
            path = net.pathAhead(m, Math.max(lookahead, STRAIGHT_SEARCH_M), approach, passes);
        }
        List<CurveDetector.Curve> found = new ArrayList<>();
        // the bends of a roundabout are not curves to warn about: the roundabout is shown instead
        for (CurveDetector.Curve cv : CurveDetector.detect(path, CurveDetector.Sensitivity.of(sensitivity))) {
            boolean inRoundabout = false;
            for (RoadNetwork.RoundaboutPass rb : passes) {
                if (cv.endDist > rb.entryDist - 25 && cv.startDist < rb.exitDist + 25) inRoundabout = true;
            }
            if (!inRoundabout) found.add(cv);
        }
        curves = found;
        curvesNet = net;
        curvesSpeed = loc.hasSpeed() ? loc.getSpeed() : 0;
        double ahead = pathLength(path);
        // inside a bend the rider is already handling it: show the one after
        CurveDetector.Curve next = CurveDetector.next(found);
        RoadNetwork.RoundaboutPass nextRb = null;
        for (RoadNetwork.RoundaboutPass rb : passes) {
            if (rb.entryDist > CurveDetector.ENTERED_M) {
                nextRb = rb;
                break;
            }
        }
        double nextDist = Math.min(next != null ? next.startDist : Double.MAX_VALUE,
                nextRb != null ? nextRb.entryDist : Double.MAX_VALUE);
        Hub.Output raw;
        if (nextDist > lookahead) {
            // straight until the next curve beyond the look-ahead, or to the end of the known road
            raw = new Hub.Output(0, roundDistance(Math.min(nextDist, ahead)), "Curve");
        } else if (nextRb != null && (next == null || nextRb.entryDist <= next.startDist)) {
            int exit = Math.max(1, Math.min(12, nextRb.exitNumber));
            int turn = nextRb.ccw ? 29 + exit : 17 + exit; // RUNABOUT_CCWn / RUNABOUT_CWn
            raw = new Hub.Output(turn, roundDistance(nextRb.entryDist), "Curve");
        } else {
            raw = new Hub.Output(next.turn, roundDistance(next.startDist), "Curve");
        }
        outputAt = System.currentTimeMillis();
        output = smoother.next(raw, outputAt, curvesSpeed);
        String road = net.roads.get(m.road).name;
        Hub.curveStatus = String.format(Locale.ROOT, "%s · %d curves, %d roundabouts in %.0f m · next %s",
                road.isEmpty() ? "unnamed road" : road, found.size(), passes.size(), ahead, output.describe());
    }

    private static double pathLength(List<double[]> path) {
        double len = 0;
        for (int i = 1; i < path.size(); i++) {
            len += Math.hypot(path.get(i)[0] - path.get(i - 1)[0], path.get(i)[1] - path.get(i - 1)[1]);
        }
        return len;
    }

    /** Same rounding Rideology applies: 10 m steps from 10 m on. */
    static int roundDistance(double m) {
        return m >= 10 ? (int) (Math.round(m / 10.0) * 10) : (int) Math.round(m);
    }

    // ------------------------------------------------------------------ road data

    /** The tile of the position first, then its 8 neighbours. */
    private static List<int[]> neighbourhood(int ty, int tx) {
        List<int[]> l = new ArrayList<>(9);
        l.add(new int[]{ty, tx});
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dy != 0 || dx != 0) l.add(new int[]{ty + dy, tx + dx});
            }
        }
        return l;
    }

    /**
     * Keeps {@link #cameras} from the stored camera tiles around the rider. They are few and small:
     * reloaded when the set of tiles with cameras changes (a region was downloaded or deleted).
     */
    private void ensureHazards(Location loc) {
        int ty = RoadStore.tileY(loc.getLatitude()), tx = RoadStore.tileX(loc.getLongitude());
        List<int[]> tiles = new ArrayList<>();
        StringBuilder sig = new StringBuilder();
        for (int[] t : neighbourhood(ty, tx)) {
            if (!hazards.hasAny(t[0], t[1])) continue;
            tiles.add(t);
            sig.append(t[0]).append('_').append(t[1]).append(' ');
        }
        // a finished region download replaced the tiles under us: load them again
        if (offlineWasRunning && !Hub.offlineRunning) {
            cameraTiles = "";
            builtTiles = Integer.MIN_VALUE;   // and the roads, which changed with them
        }
        offlineWasRunning = Hub.offlineRunning;
        if (sig.toString().equals(cameraTiles)) return;
        cameraTiles = sig.toString();
        List<Hazards.Camera> loaded = hazards.loadAll(tiles);
        cameras = loaded;
        if (!loaded.isEmpty()) Logger.log("Cameras: " + loaded.size() + " around " + ty + "_" + tx);
    }

    /**
     * Keeps {@link #network} built from the stored tiles around the rider: rebuilds when entering
     * another tile or when tiles appear or disappear (a region was downloaded or deleted).
     */
    private void ensureRoads(Location loc) {
        if (busy) return;
        int ty = RoadStore.tileY(loc.getLatitude()), tx = RoadStore.tileX(loc.getLongitude());
        List<int[]> needed = neighbourhood(ty, tx);
        // complete counts twice, partial (region border) once: a complete tile replacing partial
        // data also triggers a rebuild
        int present = 0;
        for (int[] t : needed) present += store.has(t[0], t[1]) ? 2 : store.hasAny(t[0], t[1]) ? 1 : 0;
        if (builtTy == ty && builtTx == tx && builtTiles == present) return;
        builtTy = ty;
        builtTx = tx;
        builtTiles = present;
        if (!store.hasAny(ty, tx)) {
            if (network != null) Logger.log("No offline roads at " + ty + "_" + tx);
            network = null;
            lastMatch = null;
            return;
        }
        busy = true;
        io.execute(() -> {
            List<int[]> tiles = new ArrayList<>();
            for (int[] t : needed) if (store.hasAny(t[0], t[1])) tiles.add(t);
            RoadNetwork net = new RoadNetwork((ty + 0.5) * RoadStore.TILE_DEG, (tx + 0.5) * RoadStore.TILE_DEG,
                    store.loadAll(tiles));
            main.post(() -> {
                busy = false;
                network = net;
                lastMatch = null; // indices refer to the old network
                Logger.log("Roads ready: " + net.size() + " roads from " + tiles.size() + "/9 tiles");
            });
        });
    }

    // LocationListener defaults for older callbacks
    @Override
    public void onProviderEnabled(String provider) {}

    @Override
    public void onProviderDisabled(String provider) {
        Hub.curveStatus = "GPS is off";
    }
}
