package io.github.darthbob.kawanav;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * How one recorded ride's bends were ridden, from its seconds (one a second). Each bend has its side,
 * its most lean (the apex), the speed there and the radius that speed and lean give, the speeds in and
 * out, the throttle at the apex, and three things worth knowing: braking at the apex, the throttle
 * snapped open while leant over, a downshift while leant over. And the grip the ride used: each riding
 * second's sideways acceleration, from the lean, against its change of speed. Plain Java, so it is
 * tested without a phone.
 *
 * <p>The longitudinal acceleration is the change of speed over the seconds either side, not the IMU's:
 * the IMU reads a slope as an acceleration (0.01 g per 1 % of grade), the speed does not. The sideways
 * one is tan(lean), what a bike leant over in a steady turn needs; the IMU's own sideways axis turns
 * with the bike and reads next to nothing in a bend.
 */
final class RideAnalysis {
    /** A bend: {@link #MIN_S} seconds or more leant the same way by more than {@link #LEAN_IN}°. */
    static final double LEAN_IN = 10;
    static final int MIN_S = 2;
    /** A bend is ridden at this speed somewhere, and leans no further than {@link #MAX_LEAN} (a fall, a bad frame). */
    static final double MIN_KMH = 15, MAX_LEAN = 60;
    /** The grip diagram leaves out the seconds slower than this: crawling, the lean says nothing. */
    static final double GRIP_MIN_KMH = 20;
    /** A speed change beyond this in g is a dropped second or a bad frame, not riding. */
    static final double MAX_G = 1.5;
    /** Two seconds with more than this between them are not next to each other (a gap in the recording). */
    static final long GAP_MS = 2_500;
    /** Braking at the apex: the throttle under this %, and slowing by more than {@link #BRAKING_G}. */
    static final double CLOSED_PCT = 2, BRAKING_G = 0.1;
    /** The throttle snapped open: from under {@link #SNAP_FROM}% to over {@link #SNAP_TO}% in one second, leant over {@link #SNAP_LEAN}°. */
    static final double SNAP_FROM = 5, SNAP_TO = 20, SNAP_STEP = 15, SNAP_LEAN = 15;
    static final double G = 9.81;

    static final class Bend {
        /** First and last second of the bend and its apex, as indexes into the ride's samples. */
        final int start, end, apex;
        /** -1 left, +1 right. */
        final int side;
        /** The most lean, degrees (either side). */
        final double lean;
        final double apexKmh, entryKmh, exitKmh, minKmh;
        /** v² / (g · tan lean) at the apex, metres: the radius of a steady turn at that speed and lean. */
        final double radiusM;
        /** The throttle at the apex, %, NaN when the ride has none. */
        final double apexThrottle;
        final boolean brakingAtApex, snapThrottle;
        final int downshifts;

        Bend(int start, int end, int apex, int side, double lean, double apexKmh, double entryKmh, double exitKmh,
             double minKmh, double apexThrottle, boolean brakingAtApex, boolean snapThrottle, int downshifts) {
            this.start = start;
            this.end = end;
            this.apex = apex;
            this.side = side;
            this.lean = lean;
            this.apexKmh = apexKmh;
            this.entryKmh = entryKmh;
            this.exitKmh = exitKmh;
            this.minKmh = minKmh;
            this.apexThrottle = apexThrottle;
            this.brakingAtApex = brakingAtApex;
            this.snapThrottle = snapThrottle;
            this.downshifts = downshifts;
            radiusM = apexKmh == apexKmh ? radius(apexKmh, lean) : Double.NaN;
        }

        /** How much faster it was left than entered, km/h. */
        double gain() {
            return exitKmh - entryKmh;
        }
    }

    final List<Bend> bends;
    /** One point per riding second: sideways (+ right) and forward (+ faster) acceleration in g. */
    final float[] sideG, forwardG;
    /** The ride's hardest braking and acceleration and most sideways g, all positive; 0 without any. */
    final double maxBrakeG, maxAccelG, maxSideG;

    private RideAnalysis(List<Bend> bends, float[] sideG, float[] forwardG) {
        this.bends = bends;
        this.sideG = sideG;
        this.forwardG = forwardG;
        double brake = 0, accel = 0, side = 0;
        for (int i = 0; i < sideG.length; i++) {
            brake = Math.max(brake, -forwardG[i]);
            accel = Math.max(accel, forwardG[i]);
            side = Math.max(side, Math.abs(sideG[i]));
        }
        maxBrakeG = brake;
        maxAccelG = accel;
        maxSideG = side;
    }

    /** The radius of a steady turn at this speed and lean; a lean under 5° counts as 5° (straight on). */
    static double radius(double kmh, double leanDeg) {
        double v = kmh / 3.6;
        return v * v / (G * Math.tan(Math.toRadians(Math.max(Math.abs(leanDeg), 5))));
    }

    static RideAnalysis of(List<RideTsv.Sample> samples) {
        int n = samples.size();
        double[] kmh = new double[n], accel = new double[n];
        for (int i = 0; i < n; i++) kmh[i] = speed(samples.get(i));
        for (int i = 0; i < n; i++) accel[i] = accelG(samples, kmh, i);

        List<Bend> bends = new ArrayList<>();
        int start = -1, side = 0;
        for (int i = 0; i < n; i++) {
            double lean = samples.get(i).lean;
            int now = lean == lean && Math.abs(lean) > LEAN_IN ? (lean > 0 ? 1 : -1) : 0;
            if (start >= 0 && (now != side || gap(samples, i - 1, i))) {
                Bend b = bend(samples, kmh, accel, start, i - 1, side);
                if (b != null) bends.add(b);
                start = -1;
            }
            if (start < 0 && now != 0) {
                start = i;
                side = now;
            }
        }
        if (start >= 0) {
            Bend b = bend(samples, kmh, accel, start, n - 1, side);
            if (b != null) bends.add(b);
        }

        List<float[]> points = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double lean = samples.get(i).lean;
            if (!(kmh[i] >= GRIP_MIN_KMH) || lean != lean || Math.abs(lean) > MAX_LEAN || accel[i] != accel[i]) continue;
            points.add(new float[]{(float) (Math.signum(lean) * Math.tan(Math.toRadians(Math.abs(lean)))), (float) accel[i]});
        }
        float[] sideG = new float[points.size()], forwardG = new float[points.size()];
        for (int k = 0; k < points.size(); k++) {
            sideG[k] = points.get(k)[0];
            forwardG[k] = points.get(k)[1];
        }
        return new RideAnalysis(bends, sideG, forwardG);
    }

    /** The bend from {@code a} to {@code b}, or null when it is too short, too slow or leans too far to be one. */
    private static Bend bend(List<RideTsv.Sample> s, double[] kmh, double[] accel, int a, int b, int side) {
        if (b - a + 1 < MIN_S) return null;
        int apex = a;
        double fastest = Double.NaN, slowest = Double.NaN;
        for (int i = a; i <= b; i++) {
            if (Math.abs(s.get(i).lean) > Math.abs(s.get(apex).lean)) apex = i;
            if (kmh[i] == kmh[i]) {
                fastest = fastest == fastest ? Math.max(fastest, kmh[i]) : kmh[i];
                slowest = slowest == slowest ? Math.min(slowest, kmh[i]) : kmh[i];
            }
        }
        double lean = Math.abs(s.get(apex).lean);
        if (lean > MAX_LEAN || !(fastest >= MIN_KMH)) return null;
        double thr = s.get(apex).thrPct;
        boolean braking = thr == thr && thr < CLOSED_PCT && accel[apex] < -BRAKING_G;
        boolean snap = false;
        int downs = 0;
        for (int i = a + 1; i <= b; i++) {
            double t0 = s.get(i - 1).thrPct, t1 = s.get(i).thrPct;
            if (t0 < SNAP_FROM && t1 > SNAP_TO && t1 - t0 > SNAP_STEP && Math.abs(s.get(i).lean) > SNAP_LEAN) snap = true;
            double g0 = s.get(i - 1).gear, g1 = s.get(i).gear;
            // a gear down the box, not into or out of neutral
            if (g0 >= 1 && g1 >= 1 && g1 < g0) downs += (int) Math.round(g0 - g1);
        }
        return new Bend(a, b, apex, side, lean, kmh[apex], kmh[a], kmh[b], slowest, thr, braking, snap, downs);
    }

    /** The bike's speed, the GPS's when the bike has none. */
    private static double speed(RideTsv.Sample s) {
        return s.kmh == s.kmh ? s.kmh : s.gpsKmh;
    }

    /** The change of speed over the seconds either side of {@code i}, in g; NaN at a gap or the ends. */
    private static double accelG(List<RideTsv.Sample> s, double[] kmh, int i) {
        if (i == 0 || i == s.size() - 1 || gap(s, i - 1, i) || gap(s, i, i + 1)) return Double.NaN;
        double dt = (s.get(i + 1).tMs - s.get(i - 1).tMs) / 1000.0;
        if (!(dt > 0) || kmh[i - 1] != kmh[i - 1] || kmh[i + 1] != kmh[i + 1]) return Double.NaN;
        double g = (kmh[i + 1] - kmh[i - 1]) / 3.6 / dt / G;
        return Math.abs(g) <= MAX_G ? g : Double.NaN;
    }

    private static boolean gap(List<RideTsv.Sample> s, int a, int b) {
        return s.get(b).tMs - s.get(a).tMs > GAP_MS;
    }

    // ------------------------------------------------------------------ what the ride's bends add up to

    int count(int side) {
        int c = 0;
        for (Bend b : bends) if (b.side == side) c++;
        return c;
    }

    int brakingAtApex() {
        int c = 0;
        for (Bend b : bends) if (b.brakingAtApex) c++;
        return c;
    }

    int snapThrottle() {
        int c = 0;
        for (Bend b : bends) if (b.snapThrottle) c++;
        return c;
    }

    /** Bends with a downshift in them. */
    int downshifting() {
        int c = 0;
        for (Bend b : bends) if (b.downshifts > 0) c++;
        return c;
    }

    /** The middle value of each bend's {@code what}, leaving out a bend without it; NaN for none. */
    double median(java.util.function.ToDoubleFunction<Bend> what) {
        double[] v = bends.stream().mapToDouble(what).filter(x -> x == x).toArray();
        if (v.length == 0) return Double.NaN;
        Arrays.sort(v);
        return v.length % 2 == 1 ? v[v.length / 2] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2;
    }

    /** The ways the list of bends can be ordered: its labels, and the order each stands for. */
    static final String[] ORDERS = {"Most lean", "Fastest", "Best exit", "Most speed lost", "In ride order"};

    /** The bends in the order {@code order} picks ({@link #ORDERS}), the first {@code max} of them. */
    List<Bend> top(int order, int max) {
        List<Bend> out = new ArrayList<>(bends);
        java.util.Comparator<Bend> by;
        switch (order) {
            case 1:
                by = java.util.Comparator.comparingDouble((Bend b) -> b.apexKmh == b.apexKmh ? -b.apexKmh : 0);
                break;
            case 2:
                by = java.util.Comparator.comparingDouble((Bend b) -> b.gain() == b.gain() ? -b.gain() : 0);
                break;
            case 3:
                by = java.util.Comparator.comparingDouble((Bend b) -> b.gain() == b.gain() ? b.gain() : 0);
                break;
            case 4:
                by = java.util.Comparator.comparingInt((Bend b) -> b.start);
                break;
            default:
                by = java.util.Comparator.comparingDouble((Bend b) -> -b.lean);
                break;
        }
        out.sort(by);
        return out.subList(0, Math.min(max, out.size()));
    }
}
