package io.github.darthbob.kawanav;

import java.util.ArrayList;
import java.util.List;

/**
 * What the 12 V battery says at rest, kept over time: the voltage before the engine starts is the one
 * that tells whether it is on its way out (a battery that is being charged reads 13-14 V and hides it).
 * Plain Java, so the trend is tested without a bike.
 */
public final class BatteryLog {
    /** Readings kept: one per connection is plenty for a battery's life. */
    public static final int MAX = 500;
    /** Two readings closer than this are the same stop, not a new one. */
    static final long SAME_STOP_MS = 60 * 60_000;
    /** A battery has to lose this much to count as declining. */
    static final double DECLINE_V = 0.4;
    /** Over at least this long, and with this many readings, before that means anything. */
    static final long SPAN_MS = 3L * 24 * 60 * 60_000;
    static final int MIN_SAMPLES = 4;

    /** One reading at rest. */
    public static final class Reading {
        public long at;
        public double volts;

        public Reading() {}

        public Reading(long at, double volts) {
            this.at = at;
            this.volts = volts;
        }
    }

    /**
     * The readings with a new one added, oldest first. Nothing is added when the voltage is unknown, when
     * it is a charging voltage (engine on), or when the last reading is from the same stop.
     */
    public static List<Reading> record(List<Reading> readings, long now, double volts, boolean engineRunning) {
        List<Reading> out = new ArrayList<>(readings);
        if (volts != volts || volts <= 0 || engineRunning) return out;
        if (!out.isEmpty() && now - out.get(out.size() - 1).at < SAME_STOP_MS) return out;
        out.add(new Reading(now, volts));
        while (out.size() > MAX) out.remove(0);
        return out;
    }

    /** How much the resting voltage has fallen over the whole log, or NaN when there is not enough of it. */
    public static double decline(List<Reading> readings) {
        if (readings.size() < MIN_SAMPLES) return Double.NaN;
        Reading first = readings.get(0), last = readings.get(readings.size() - 1);
        if (last.at - first.at < SPAN_MS) return Double.NaN;
        return first.volts - last.volts;
    }

    /** True when the resting voltage has fallen steadily enough to be worth telling the rider about. */
    public static boolean declining(List<Reading> readings) {
        double d = decline(readings);
        return d == d && d >= DECLINE_V;
    }

    /** The lowest and highest reading, for the summary line. */
    public static double[] range(List<Reading> readings) {
        double lo = Double.NaN, hi = Double.NaN;
        for (Reading r : readings) {
            if (r.volts != r.volts) continue;
            if (lo != lo || r.volts < lo) lo = r.volts;
            if (hi != hi || r.volts > hi) hi = r.volts;
        }
        return new double[]{lo, hi};
    }
}
