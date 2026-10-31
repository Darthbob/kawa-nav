package io.github.darthbob.kawanav;

/**
 * How winding a road is, as the sum of its direction changes per kilometre (degrees per km).
 *
 * The way is resampled every {@link #STEP_M} metres (so the density of OSM points does not matter),
 * the turn at each sample is added up, and every sample gets the average over a window of
 * {@link #WINDOW_M} around it: one hairpin in a long straight stays a hairpin, a whole valley road of
 * bends counts as winding; a way shorter than the window is measured over at least {@link #MIN_SPAN_M}.
 * A stretch of the way (a routing edge) gets the mean of its samples and one
 * of four {@link #level levels}: 0 straight, 1 sweeping, 2 winding, 3 very winding.
 *
 * Plain Java, coordinates in a local metric plane (x east, y north, metres).
 */
public final class Curviness {
    private Curviness() {}

    static final double STEP_M = 10;
    static final double WINDOW_M = 1000;
    /** A way shorter than the window counts as at least this long: one street corner is not a winding road. */
    static final double MIN_SPAN_M = 500;
    /**
     * Most a single sample may turn: a corner (all its angle in one point) is no bend, while even a tight
     * hairpin spreads its turn over several samples.
     */
    static final double MAX_TURN_PER_STEP = 30;
    /**
     * Level thresholds in degrees per km. Bends of radius R give 57296 / R: these mark sustained bends
     * of about 440, 220 and 125 m. OSM geometry adds some wiggle of its own, which these allow for.
     */
    static final double LEVEL1 = 130, LEVEL2 = 260, LEVEL3 = 455;

    /**
     * Degrees per km at every vertex of the polyline (x, y in metres): the windowed value of the
     * resampled road, read back at the vertex positions. Ways shorter than the window use their whole
     * length.
     */
    public static double[] atVertices(double[] x, double[] y) {
        int n = x.length;
        double[] out = new double[n];
        if (n < 3) return out;
        double[] along = new double[n];
        for (int i = 1; i < n; i++) along[i] = along[i - 1] + Math.hypot(x[i] - x[i - 1], y[i] - y[i - 1]);
        double total = along[n - 1];
        if (total < 2 * STEP_M) return out;

        // resample: the turn at each sample, in degrees
        int k = (int) Math.floor(total / STEP_M) + 1;
        double[] sx = new double[k], sy = new double[k];
        int seg = 0;
        for (int s = 0; s < k; s++) {
            double d = Math.min(total, s * STEP_M);
            while (seg < n - 2 && along[seg + 1] < d) seg++;
            double len = along[seg + 1] - along[seg];
            double t = len <= 0 ? 0 : (d - along[seg]) / len;
            sx[s] = x[seg] + (x[seg + 1] - x[seg]) * t;
            sy[s] = y[seg] + (y[seg + 1] - y[seg]) * t;
        }
        double[] turn = new double[k];
        for (int s = 1; s < k - 1; s++) {
            double h1 = RoadNetwork.bearing(sx[s - 1], sy[s - 1], sx[s], sy[s]);
            double h2 = RoadNetwork.bearing(sx[s], sy[s], sx[s + 1], sy[s + 1]);
            turn[s] = Math.min(MAX_TURN_PER_STEP, Math.abs(RoadNetwork.angleDiff(h1, h2)));
        }
        // prefix sums for the window average
        double[] pre = new double[k + 1];
        for (int s = 0; s < k; s++) pre[s + 1] = pre[s] + turn[s];
        double[] perSample = new double[k];
        int half = (int) Math.round(WINDOW_M / 2 / STEP_M);
        for (int s = 0; s < k; s++) {
            int a, b;
            if (total <= WINDOW_M) {
                a = 0;
                b = k - 1;
            } else {
                a = Math.max(0, s - half);
                b = Math.min(k - 1, s + half);
                // keep the full window length near the ends of the way
                if (b - a < 2 * half) {
                    if (a == 0) b = Math.min(k - 1, 2 * half);
                    else a = Math.max(0, k - 1 - 2 * half);
                }
            }
            double km = Math.max((b - a) * STEP_M, Math.min(MIN_SPAN_M, WINDOW_M)) / 1000.0;
            perSample[s] = (pre[b + 1] - pre[a]) / km;
        }
        for (int i = 0; i < n; i++) {
            int s = (int) Math.min(k - 1, Math.round(along[i] / STEP_M));
            out[i] = perSample[s];
        }
        return out;
    }

    /** Mean degrees per km of the vertices from..to (inclusive), weighted by segment length. */
    public static double between(double[] perVertex, double[] x, double[] y, int from, int to) {
        double sum = 0, len = 0;
        for (int i = from; i < to; i++) {
            double l = Math.hypot(x[i + 1] - x[i], y[i + 1] - y[i]);
            sum += l * (perVertex[i] + perVertex[i + 1]) / 2;
            len += l;
        }
        return len <= 0 ? perVertex[from] : sum / len;
    }

    /** 0 straight, 1 sweeping, 2 winding, 3 very winding. */
    public static int level(double degPerKm) {
        return degPerKm >= LEVEL3 ? 3 : degPerKm >= LEVEL2 ? 2 : degPerKm >= LEVEL1 ? 1 : 0;
    }
}
