package io.github.darthbob.kawanav;

import java.util.ArrayList;
import java.util.List;

/**
 * Refuels: worked out from the dash's own numbers while the bike stands at the pump, and edited by hand.
 * The meter never says how much was put in — it reports the gauge, the range and the fuel it has burned in
 * total — so the litres are estimated from the jump of the gauge and the tank size set in the app, and the
 * rider can correct them. Pure Java, so the detection is tested without a bike.
 */
public final class FuelLog {
    /** Litres the tank holds when the rider has not said otherwise (see {@link #KEY_TANK_L}). */
    public static final double DEFAULT_TANK_L = 15.0;
    /** Bars a full gauge is drawn with, for turning a bar jump into litres. */
    public static final int FULL_BARS = 6;
    /** Refuelling happens standing still, below this speed. */
    public static final double STILL_KMH = 5;
    /** A jump of less than a bar is the gauge settling, not a fill. */
    static final int MIN_BARS = 1;
    /**
     * The gauge has to hold still this long before what it says is taken as the level after filling: at
     * the pump it climbs a bar at a time over a few seconds, and only where it stops is the full tank.
     */
    static final long DWELL_MS = 20_000;
    /**
     * Two fills closer together than this, with the bike not having moved, are one fill the gauge climbed
     * through in steps rather than two.
     */
    static final long SAME_FILL_MS = 15 * 60_000;
    /** Below this the gauge reading is noise (the bike may still be on the side stand). */
    static final int MIN_RANGE_KM = 20;

    /** Settings key: litres the tank holds. */
    public static final String KEY_TANK_L = "fuel_tank_l";

    /** One refuel at the pump. */
    public static final class Entry implements Comparable<Entry> {
        public long at;
        /** Odometer when the tank was filled, NaN when the bike does not report it. */
        public double odoKm = Double.NaN;
        /** Litres added, NaN until the rider fills it in. */
        public double litres = Double.NaN;
        /** True when {@link #litres} came from the gauge rather than from the rider. */
        public boolean estimated;
        public double barsBefore = Double.NaN, barsAfter = Double.NaN;
        public double rangeBeforeKm = Double.NaN, rangeAfterKm = Double.NaN;
        public String note = "";

        @Override
        public int compareTo(Entry o) {
            return Long.compare(at, o.at);
        }

        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append(litres == litres ? String.format(java.util.Locale.ROOT, "%.1f L", litres) : "? L");
            if (estimated) sb.append(" (estimated)");
            if (odoKm == odoKm) sb.append(String.format(java.util.Locale.ROOT, " · %,.0f km", odoKm));
            return sb.toString();
        }
    }

    /**
     * Watches the gauge while the bike is standing still. A refuel shows up as the gauge and the range
     * jumping up between two readings at a standstill; the odometer then says where it happened.
     */
    public static final class Detector {
        /** The newest reading, the settled one it is compared with, and the largest range ever seen. */
        private double bars = Double.NaN, rangeKm = Double.NaN, odoKm = Double.NaN;
        private double baseBars = Double.NaN, baseRangeKm = Double.NaN, rangeFullKm = Double.NaN;
        /** When the reading last changed: a fill is only read once the gauge has stopped moving. */
        private long changedAt, lastEntryAt;
        private boolean have;

        /** Clears what is remembered: a new connection, or a ride that ended. */
        public void reset() {
            have = false;
            baseBars = baseRangeKm = Double.NaN;
            changedAt = 0;
        }

        /**
         * One look at the dash while standing still. Returns the refuel when the tank went up, else null.
         * Readings the meter does not have (NaN, -1) are ignored rather than counted as zero.
         */
        public Entry offer(double barsNow, int rangeNow, int odoNow, long now, double tankL) {
            if (barsNow != barsNow || barsNow < 0) barsNow = Double.NaN;
            double range = rangeNow >= 0 ? rangeNow : Double.NaN;
            double odo = odoNow >= 0 ? odoNow : Double.NaN;
            // the largest range ever seen is what a full tank gives, for turning a range jump into litres
            if (range == range && (rangeFullKm != rangeFullKm || range > rangeFullKm)) rangeFullKm = range;
            if (!have) {
                bars = barsNow;
                rangeKm = range;
                odoKm = odo;
                baseBars = barsNow;
                baseRangeKm = range;
                changedAt = now;
                have = true;
                return null;
            }
            if (!same(bars, barsNow) || !same(rangeKm, range)) changedAt = now;
            // only where the gauge has stopped moving: at the pump it climbs a bar at a time, and logging
            // each step would give one refuel per step, each with a fraction of the fuel that went in
            boolean settled = now - changedAt >= DWELL_MS;
            boolean barsUp = jumped(baseBars, barsNow, MIN_BARS);
            boolean rangeUp = jumped(baseRangeKm, range, MIN_RANGE_KM);
            Entry e = null;
            if (settled && (barsUp || rangeUp) && !sameFill(now, odo)) {
                e = new Entry();
                e.at = now;
                e.odoKm = odo == odo ? odo : odoKm;
                e.barsBefore = baseBars;
                e.barsAfter = barsNow;
                e.rangeBeforeKm = baseRangeKm;
                e.rangeAfterKm = range;
                e.litres = litres(barsUp, baseBars, barsNow, baseRangeKm, range, tankL, rangeFullKm);
                e.estimated = e.litres == e.litres;
                lastEntryAt = now;
            }
            if (settled) {
                // the settled reading becomes what the next jump is measured from
                baseBars = barsNow;
                baseRangeKm = range;
            }
            // the newest reading is what the next one is compared with, settled or not
            if (barsNow == barsNow) bars = barsNow;
            if (range == range) rangeKm = range;
            if (odo == odo) odoKm = odo;
            return e;
        }

        /** True when the newest reading is the one seen before: the gauge has stopped moving. */
        private static boolean same(double before, double after) {
            if (before != before || after != after) return false;
            return Math.abs(before - after) < 1e-9;
        }

        /** A jump right after the last entry, with the bike not having moved, is the same fill. */
        private boolean sameFill(long now, double odo) {
            if (lastEntryAt == 0) return false;
            if (now - lastEntryAt >= SAME_FILL_MS) return false;
            return !(odo == odo && odoKm == odoKm && odo > odoKm);
        }

        /** A reading that went up by at least {@code min}; an empty one is the dash waking up, not a fill. */
        private static boolean jumped(double before, double after, int min) {
            if (before != before || after != after) return false;
            if (before <= 0) return false;
            return after - before >= min;
        }

        /**
         * How much went in. From the gauge when it jumped (a bar of a full gauge is tank / bars litres);
         * from the range that came back otherwise: that many kilometres represent that fraction of the
         * full range, so that fraction of the tank. NaN when the tank size is not known either way.
         */
        private static double litres(boolean barsUp, double barsBefore, double barsAfter,
                                     double rangeBefore, double rangeAfter, double tankL, double rangeFullKm) {
            if (tankL <= 0) return Double.NaN;
            if (barsUp) return round1((barsAfter - barsBefore) / FULL_BARS * tankL);
            if (rangeBefore == rangeBefore && rangeAfter == rangeAfter && rangeAfter > rangeBefore) {
                // measured against the range a full tank gives: the range in the tank before the fill says
                // nothing about how much the tank holds, so a part top-up must not be read as a full one
                double full = Math.max(rangeFullKm == rangeFullKm ? rangeFullKm : 0, rangeAfter);
                return round1((rangeAfter - rangeBefore) / full * tankL);
            }
            return Double.NaN;
        }

        private static double round1(double v) {
            return Math.round(v * 10) / 10.0;
        }
    }

    /** Fuel used between two refuels: distance over litres, or NaN when either is missing. */
    public static double consumptionLPer100(Entry from, Entry to) {
        if (from == null || to == null) return Double.NaN;
        if (from.odoKm != from.odoKm || to.odoKm != to.odoKm) return Double.NaN;
        if (to.litres != to.litres || to.litres <= 0) return Double.NaN;
        double km = to.odoKm - from.odoKm;
        return km > 0 ? to.litres / km * 100 : Double.NaN;
    }

    /** The entries of a log, oldest first. */
    public static List<Entry> sorted(List<Entry> entries) {
        List<Entry> out = new ArrayList<>(entries);
        java.util.Collections.sort(out);
        return out;
    }
}
