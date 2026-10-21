package io.github.darthbob.kawanav;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds curves on a polyline ahead of the rider and maps them to Kawasaki turn icons.
 * The path is resampled every {@link #STEP} metres; heading change is smoothed over a
 * {@link #WINDOW_M} window so sparse OSM geometry (a bend drawn with 2-3 nodes) is not
 * mistaken for a hairpin. Plain Java so it can be unit-tested off-device.
 */
public final class CurveDetector {
    private CurveDetector() {}

    static final double STEP = 5;
    static final double WINDOW_M = 30;

    /** Detection thresholds per sensitivity (0 low, 1 normal, 2 high). */
    public static final class Sensitivity {
        final double minAngle;        // smallest total heading change reported
        final double curvingPerWindow; // heading change per window that counts as curving
        /**
         * Second, lower threshold for long gentle bends (0: none). Only fills the stretches between
         * the bends found with {@link #curvingPerWindow}, so it never merges two bends into one.
         */
        final double gentlePerWindow;

        Sensitivity(double minAngle, double curvingPerWindow) {
            this(minAngle, curvingPerWindow, 0);
        }

        Sensitivity(double minAngle, double curvingPerWindow, double gentlePerWindow) {
            this.minAngle = minAngle;
            this.curvingPerWindow = curvingPerWindow;
            this.gentlePerWindow = gentlePerWindow;
        }

        public static Sensitivity of(int level) {
            switch (level) {
                case 0: return new Sensitivity(45, 10);  // radius < ~170 m, >= 45 deg
                case 2: return new Sensitivity(20, 5);   // radius < ~350 m, >= 20 deg
                case 3: return new Sensitivity(16, 7, 3); // > 15 deg, gentle bends up to radius ~570 m
                default: return new Sensitivity(30, 7);  // radius < ~250 m, >= 30 deg
            }
        }
    }

    /** A curve whose start is closer than this counts as entered; the meter then shows the next one. */
    public static final double ENTERED_M = 5;

    /** First curve the rider has not entered yet, or null. */
    public static Curve next(List<Curve> curves) {
        for (Curve c : curves) {
            if (c.startDist > ENTERED_M) return c;
        }
        return null;
    }

    public static final class Curve {
        public final int turn;           // Kawasaki turn id
        public final double startDist;   // metres from the rider to where the curve starts (<= 0: inside)
        public final double endDist;
        public final double angle;       // signed total heading change, + = right
        public final double minRadius;   // tightest part, metres
        public final double startX, startY; // where it starts, in the path's metres

        Curve(int turn, double startDist, double endDist, double angle, double minRadius, double startX, double startY) {
            this.turn = turn;
            this.startDist = startDist;
            this.endDist = endDist;
            this.angle = angle;
            this.minRadius = minRadius;
            this.startX = startX;
            this.startY = startY;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT, "%s %+.0f° r=%.0fm @%.0f-%.0fm",
                    KawaProtocol.TURN_NAMES[turn], angle, minRadius, startDist, endDist);
        }
    }

    /**
     * @param path x/y points in metres, starting at the rider
     */
    public static List<Curve> detect(List<double[]> path, Sensitivity sens) {
        List<Curve> out = new ArrayList<>();
        double[][] pts = resample(path);
        int n = pts.length;
        if (n < 3) return out;

        // d[i]: heading change at point i+1 (between segment i and i+1), + = right
        int m = n - 2;
        double[] d = new double[m];
        double prevH = RoadNetwork.bearing(pts[0][0], pts[0][1], pts[1][0], pts[1][1]);
        for (int i = 0; i < m; i++) {
            double h = RoadNetwork.bearing(pts[i + 1][0], pts[i + 1][1], pts[i + 2][0], pts[i + 2][1]);
            d[i] = RoadNetwork.angleDiff(prevH, h);
            prevH = h;
        }

        int w = (int) Math.round(WINDOW_M / STEP);
        int half = w / 2;
        double[] win = new double[m]; // heading change over the window centred on i
        for (int i = 0; i < m; i++) {
            double s = 0;
            for (int j = Math.max(0, i - half); j <= Math.min(m - 1, i + half); j++) s += d[j];
            win[i] = s;
        }

        scan(pts, d, win, half, sens.curvingPerWindow, sens.minAngle, out);
        if (sens.gentlePerWindow > 0) {
            List<Curve> gentle = new ArrayList<>();
            scan(pts, d, win, half, sens.gentlePerWindow, sens.minAngle, gentle);
            for (Curve g : gentle) {
                boolean overlaps = false;
                for (Curve c : out) {
                    if (g.startDist < c.endDist && c.startDist < g.endDist) overlaps = true;
                }
                if (!overlaps) out.add(g);
            }
            out.sort((a, b) -> Double.compare(a.startDist, b.startDist));
        }
        return out;
    }

    /** Finds the bends where the windowed heading change reaches {@code threshold}; adds those turning at least minAngle. */
    private static void scan(double[][] pts, double[] d, double[] win, int half, double threshold, double minAngle,
                             List<Curve> out) {
        int m = d.length;
        int i = 0;
        while (i < m) {
            int sign = (int) Math.signum(win[i]);
            if (Math.abs(win[i]) < threshold || sign == 0) {
                i++;
                continue;
            }
            int a = i;
            int b = i;
            int gap = 0;
            int j = i + 1;
            // extend while curving the same way; tolerate short straight bits inside one bend
            while (j < m) {
                boolean curving = Math.abs(win[j]) >= threshold && Math.signum(win[j]) == sign;
                boolean opposite = Math.abs(win[j]) >= threshold && Math.signum(win[j]) == -sign;
                if (curving) {
                    b = j;
                    gap = 0;
                } else if (opposite || ++gap > 3) {
                    break;
                }
                j++;
            }
            int from = Math.max(0, a - half);
            int to = Math.min(m - 1, b + half);
            double total = 0;
            double maxWin = 0;
            for (int k = from; k <= to; k++) {
                if (Math.signum(d[k]) == sign) total += d[k];
            }
            for (int k = a; k <= b; k++) maxWin = Math.max(maxWin, Math.abs(win[k]));
            // win[] spans 2*half+1 heading changes, i.e. that many STEPs of road
            double minRadius = maxWin > 0 ? (2 * half + 1) * STEP / Math.toRadians(maxWin) : Double.MAX_VALUE;
            if (Math.abs(total) >= minAngle) {
                double start = (from + 1) * STEP;
                double end = (to + 1) * STEP;
                double[] at = pts[from + 1];
                if (a == 0) { // already in the bend
                    start = 0;
                    at = pts[0];
                }
                out.add(new Curve(classify(total, minRadius), start, end, total, minRadius, at[0], at[1]));
            }
            i = Math.max(b + 1, j);
        }
    }

    /** Severity by total angle, upgraded when the bend is tight. */
    static int classify(double angle, double minRadius) {
        boolean right = angle > 0;
        double a = Math.abs(angle);
        if (a >= 150 && minRadius < 35) return right ? 8 : 7;   // hairpin -> U-turn icon
        if (a >= 100 || minRadius < 30) return right ? 4 : 3;   // sharp
        if (a >= 55 || minRadius < 60) return right ? 2 : 1;    // normal
        return right ? 6 : 5;                                   // slight
    }

    private static double[][] resample(List<double[]> path) {
        List<double[]> out = new ArrayList<>();
        if (path.isEmpty()) return new double[0][];
        out.add(path.get(0));
        double carry = 0;
        for (int i = 1; i < path.size(); i++) {
            double[] a = path.get(i - 1), b = path.get(i);
            double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
            double pos = STEP - carry;
            while (pos <= len) {
                double t = pos / len;
                out.add(new double[]{a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1])});
                pos += STEP;
            }
            carry = len - (pos - STEP);
        }
        return out.toArray(new double[0][]);
    }
}
