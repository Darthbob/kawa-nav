package io.github.darthbob.kawanav;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a ride's wheel speeds say about the tyres and the clutch. The tyres are a debug field test
 * ({@link FieldTests}); the clutch is part of every ride's summary ({@link Ride#clutchSlipping}), which
 * warns after a ride. Pure Java, so the rules can be unit-tested.
 *
 * <ul>
 * <li><b>Tyres:</b> on steady, upright riding the rear wheel turns a fixed amount slower than the front
 * (0.988 on the Z900 of 2026-10, the same to 0.06% from ride to ride). The ratio follows the rolling
 * radius of the two tyres, so a soft or worn rear should pull it down. How far it moves for a given
 * pressure is what the test is for.</li>
 * <li><b>Clutch:</b> in a gear, rpm per km/h of the rear wheel is fixed by the gearing while the clutch
 * holds (spread about 1%). A slipping clutch lets the engine run ahead under load, so the ratio rises
 * above the same gear's light-throttle value. On the Z900 it reads 1–4% <i>below</i> it at full throttle
 * (the two values come from different frames in the once-a-second file), so only a rise counts; a ride
 * with roll-ons in 3rd to 6th showed no slip (2026-10). One noisy second is no slipping clutch: it takes
 * {@link #MIN_SLIP_RUNS} roll-ons that each rise above {@link #SLIP_PCT}.</li>
 * </ul>
 */
final class WheelChecks {
    private WheelChecks() {}

    /** Speed bands the rear/front ratio is given in: it rises a little with speed (0.9876 → 0.990). */
    static final double[] BANDS = {40, 70, 100, 140};
    /** Fewer steady seconds than this in a band (or a gear) and no ratio is given for it. */
    static final int MIN_SAMPLES = 30;
    /** Steady riding for the tyre ratio: nearly upright, no hard acceleration or braking, light throttle. */
    static final double UPRIGHT_DEG = 4, STEADY_G = 0.05, THR_MIN = 3, THR_MAX = 40;
    /** The front wheel changes less than this from one second to the next on steady riding. */
    static final double STEADY_KMH = 2;
    /** Clutch: rear wheel speeds the gearing is read at; below, the clutch may be slipping on purpose. */
    static final double GEAR_MIN_KMH = 15, GEAR_MAX_KMH = 160;
    /** Clutch: light throttle (the reference) and load (where a worn clutch slips). */
    static final double LIGHT_THR = 30, LOAD_THR = 50;
    /** Clutch: rpm changes less than this from one second to the next in a reference second. */
    static final double STEADY_RPM = 150;
    /** Clutch: a loaded second this far above its gear's reference counts as slipping. */
    static final double SLIP_PCT = 3;
    /** Clutch: this many roll-ons with a slipping second make a slipping clutch. */
    static final int MIN_SLIP_RUNS = 2;
    /** Two samples further apart than this are not neighbours (a gap in the recording). */
    static final long NEIGHBOUR_MS = 1500;

    /** The tyre side: rear/front wheel speed per speed band, NaN where the ride had too little of it. */
    static final class Tyres {
        final double[] ratio = new double[BANDS.length - 1];
        final int[] n = new int[BANDS.length - 1];

        /** The ratio over every band together, NaN without any steady riding. */
        double all = Double.NaN;
        int allN;
    }

    /** The clutch side: the gearing per gear and how the loaded seconds compare with it. */
    static final class Clutch {
        /** rpm per km/h of the rear wheel at light throttle, by gear (index = gear); NaN when not ridden. */
        final double[] rpmPerKmh = new double[Ride.MAX_GEAR + 1];
        /** Loaded seconds (throttle at {@link #LOAD_THR} or more, gear 3 and up) that had a reference. */
        int loadN;
        /** Their distance above the gear's reference in %: the median and the largest; NaN when none. */
        double loadMedianPct = Double.NaN, loadMaxPct = Double.NaN;
        /** Loaded seconds more than {@link #SLIP_PCT} above the reference. */
        int slipN;
        /** Roll-ons (loaded seconds one after the other) with at least one of those seconds. */
        int slipRuns;

        Clutch() {
            java.util.Arrays.fill(rpmPerKmh, Double.NaN);
        }

        /** Slipped in enough roll-ons to warn about. */
        boolean slipping() {
            return slipRuns >= MIN_SLIP_RUNS;
        }
    }

    static Tyres tyres(List<RideTsv.Sample> samples) {
        List<List<Double>> bands = new ArrayList<>();
        for (int b = 0; b < BANDS.length - 1; b++) bands.add(new ArrayList<>());
        List<Double> all = new ArrayList<>();
        for (int i = 1; i < samples.size(); i++) {
            RideTsv.Sample s = samples.get(i), prev = samples.get(i - 1);
            double f = s.wheel_f_kmh, r = s.wheel_r_kmh;
            if (!(f >= BANDS[0] && f < BANDS[BANDS.length - 1]) || !(r > 0)) continue;
            if (!(Math.abs(s.lean) < UPRIGHT_DEG) || !(Math.abs(s.accG) < STEADY_G)) continue;
            if (!(s.thrPct >= THR_MIN && s.thrPct <= THR_MAX)) continue;
            if (s.tMs - prev.tMs > NEIGHBOUR_MS || !(Math.abs(f - prev.wheel_f_kmh) < STEADY_KMH)) continue;
            double ratio = r / f;
            all.add(ratio);
            for (int b = 0; b < BANDS.length - 1; b++) {
                if (f >= BANDS[b] && f < BANDS[b + 1]) bands.get(b).add(ratio);
            }
        }
        Tyres t = new Tyres();
        for (int b = 0; b < BANDS.length - 1; b++) {
            t.n[b] = bands.get(b).size();
            t.ratio[b] = t.n[b] >= MIN_SAMPLES ? median(bands.get(b)) : Double.NaN;
        }
        t.allN = all.size();
        t.all = t.allN >= MIN_SAMPLES ? median(all) : Double.NaN;
        return t;
    }

    static Clutch clutch(List<RideTsv.Sample> samples) {
        List<List<Double>> light = new ArrayList<>();
        for (int g = 0; g <= Ride.MAX_GEAR; g++) light.add(new ArrayList<>());
        for (int i = 1; i < samples.size() - 1; i++) {
            RideTsv.Sample s = samples.get(i);
            int g = gearHeld(samples, i);
            if (g < 1 || !(s.thrPct < LIGHT_THR)) continue;
            if (!(Math.abs(s.rpm - samples.get(i - 1).rpm) < STEADY_RPM)) continue;
            light.get(g).add(s.rpm / s.wheel_r_kmh);
        }
        Clutch c = new Clutch();
        for (int g = 1; g <= Ride.MAX_GEAR; g++) {
            if (light.get(g).size() >= MIN_SAMPLES) c.rpmPerKmh[g] = median(light.get(g));
        }
        List<Double> load = new ArrayList<>();
        int lastLoaded = -2;
        boolean runSlipped = false;
        for (int i = 1; i < samples.size() - 1; i++) {
            RideTsv.Sample s = samples.get(i);
            int g = gearHeld(samples, i);
            if (g < 3 || !(s.thrPct >= LOAD_THR) || !(c.rpmPerKmh[g] > 0)) continue;
            double pct = (s.rpm / s.wheel_r_kmh / c.rpmPerKmh[g] - 1) * 100;
            load.add(pct);
            // a roll-on is a run of loaded seconds one after the other (gearHeld already wants both
            // neighbours recorded); a second that is not right after the last loaded one starts a new one
            if (lastLoaded != i - 1) runSlipped = false;
            lastLoaded = i;
            if (pct > SLIP_PCT) {
                c.slipN++;
                if (!runSlipped) c.slipRuns++;
                runSlipped = true;
            }
        }
        c.loadN = load.size();
        if (!load.isEmpty()) {
            c.loadMedianPct = median(load);
            c.loadMaxPct = Collections.max(load);
        }
        return c;
    }

    /**
     * The gear of sample {@code i} when the gearing can be read there, else 0: in gear, the same gear a
     * second before and after (a shift in between has the clutch pulled), no quick-shifter cut, the
     * rear wheel in {@link #GEAR_MIN_KMH}..{@link #GEAR_MAX_KMH}, and both neighbours recorded.
     */
    private static int gearHeld(List<RideTsv.Sample> samples, int i) {
        RideTsv.Sample s = samples.get(i), prev = samples.get(i - 1), next = samples.get(i + 1);
        if (!(s.gear >= 1 && s.gear <= Ride.MAX_GEAR) || s.gear != prev.gear || s.gear != next.gear) return 0;
        if (s.tMs - prev.tMs > NEIGHBOUR_MS || next.tMs - s.tMs > NEIGHBOUR_MS) return 0;
        if (s.kqs_up > 0 || s.kqs_down > 0) return 0;
        if (!(s.wheel_r_kmh >= GEAR_MIN_KMH && s.wheel_r_kmh <= GEAR_MAX_KMH) || !(s.rpm > 0)) return 0;
        return (int) s.gear;
    }

    /**
     * The usual value of a ratio over several rides (their median, ignoring the NaN ones), to compare
     * one ride with; NaN with fewer than two.
     */
    static double usual(List<Double> values) {
        List<Double> known = new ArrayList<>();
        for (Double v : values) if (v != null && !v.isNaN()) known.add(v);
        return known.size() < 2 ? Double.NaN : median(known);
    }

    static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        if (n == 0) return Double.NaN;
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }
}
