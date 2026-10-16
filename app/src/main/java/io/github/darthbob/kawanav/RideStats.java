package io.github.darthbob.kawanav;

import java.util.ArrayList;
import java.util.List;

/**
 * What the recorded rides add up to: the best of each, and the distance per year. Plain Java, so the
 * arithmetic is tested without a phone.
 */
public final class RideStats {
    /** The rider's best of something, and the ride it happened on. */
    public static final class Best {
        public final Ride ride;
        public final double value;

        Best(Ride ride, double value) {
            this.ride = ride;
            this.value = value;
        }

        public boolean known() {
            return ride != null && value > 0;
        }
    }

    /** One year's riding. */
    public static final class Year {
        public final int year, rides;
        public final double km, hours;

        Year(int year, int rides, double km, double hours) {
            this.year = year;
            this.rides = rides;
            this.km = km;
            this.hours = hours;
        }
    }

    /**
     * What the whole log came to, and the best of each. The records that need a value the ride only keeps
     * since 0.9 (hottest coolant, hardest braking, most suspension travel) are unknown for a ride that has
     * not computed them yet: a ride is summarised when it is opened, or by {@link #summariseOutdated}.
     */
    public final int rides;
    public final double totalKm, totalHours;
    public final Best fastest, maxLeanLeft, maxLeanRight, longest;
    /** Hottest coolant of any ride, strongest deceleration, most suspension travel used. */
    public final Best hottest, hardestBraking, mostSuspension;
    /** Fuel the log burned, and the seconds its electronics spent working. */
    public final double fuelL, tcsSeconds, absSeconds, wheelieSeconds;

    private RideStats(int rides, double totalKm, double totalHours,
                      Best fastest, Best maxLeanLeft, Best maxLeanRight, Best longest,
                      Best hottest, Best hardestBraking, Best mostSuspension,
                      double fuelL, double tcsSeconds, double absSeconds, double wheelieSeconds) {
        this.rides = rides;
        this.totalKm = totalKm;
        this.totalHours = totalHours;
        this.fastest = fastest;
        this.maxLeanLeft = maxLeanLeft;
        this.maxLeanRight = maxLeanRight;
        this.longest = longest;
        this.hottest = hottest;
        this.hardestBraking = hardestBraking;
        this.mostSuspension = mostSuspension;
        this.fuelL = fuelL;
        this.tcsSeconds = tcsSeconds;
        this.absSeconds = absSeconds;
        this.wheelieSeconds = wheelieSeconds;
    }

    /** The highest this channel reached on a ride, or NaN when the ride does not have it. */
    private static double peak(Ride r, String key) {
        Ride.Metric m = r.metric(key);
        return m != null && m.known() ? m.max : Double.NaN;
    }

    /** The distance of a ride the way the list shows it ({@link Ride#km()}). */
    public static double km(Ride r) {
        return r.km();
    }

    public static RideStats of(List<Ride> rides) {
        Ride fastest = null, left = null, right = null, longest = null;
        Ride hot = null, brake = null, susp = null;
        double fastestKmh = 0, leanL = 0, leanR = 0, longestKm = 0, km = 0, ms = 0;
        double hotC = 0, brakeG = 0, suspMm = 0, fuel = 0, tcsS = 0, absS = 0, wheelieS = 0;
        for (Ride r : rides) {
            double d = km(r);
            km += d;
            ms += r.activeMs;
            if (r.maxKmh > fastestKmh) {
                fastestKmh = r.maxKmh;
                fastest = r;
            }
            // a lean to the left is recorded as a negative number: compare magnitudes
            double l = Math.abs(r.maxLeanL);
            if (l > leanL) {
                leanL = l;
                left = r;
            }
            if (r.maxLeanR > leanR) {
                leanR = r.maxLeanR;
                right = r;
            }
            if (d > longestKm) {
                longestKm = d;
                longest = r;
            }
            // the values a ride only knows since 0.9; a ride that has not computed them contributes nothing
            double c = peak(r, "coolant");
            if (c == c && c > hotC) {
                hotC = c;
                hot = r;
            }
            Ride.Metric accel = r.metric("accel");
            double braking = accel != null && accel.known() ? -accel.min : Double.NaN;
            if (braking == braking && braking > brakeG) {
                brakeG = braking;
                brake = r;
            }
            double travel = Math.max(peak(r, "susp_f"), peak(r, "susp_r"));
            if (travel == travel && travel > suspMm) {
                suspMm = travel;
                susp = r;
            }
            if (r.fuelUsedL > 0) fuel += r.fuelUsedL;
            if (r.tcsOnS > 0) tcsS += r.tcsOnS;
            if (r.absOnS > 0) absS += r.absOnS;
            if (r.wheelieS > 0) wheelieS += r.wheelieS;
        }
        return new RideStats(rides.size(), km, ms / 3_600_000.0,
                new Best(fastest, fastestKmh), new Best(left, leanL), new Best(right, leanR),
                new Best(longest, longestKm), new Best(hot, hotC), new Best(brake, brakeG),
                new Best(susp, suspMm), fuel, tcsS, absS, wheelieS);
    }

    /** The rides per year, newest first. */
    public static List<Year> byYear(List<Ride> rides) {
        java.util.Map<Integer, double[]> sums = new java.util.TreeMap<>(java.util.Comparator.reverseOrder());
        java.util.Map<Integer, Integer> counts = new java.util.HashMap<>();
        for (Ride r : rides) {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTimeInMillis(r.start);
            int y = c.get(java.util.Calendar.YEAR);
            double[] s = sums.computeIfAbsent(y, k -> new double[2]);
            s[0] += km(r);
            s[1] += r.activeMs / 3_600_000.0;
            counts.merge(y, 1, Integer::sum);
        }
        List<Year> out = new ArrayList<>();
        for (java.util.Map.Entry<Integer, double[]> e : sums.entrySet()) {
            out.add(new Year(e.getKey(), counts.getOrDefault(e.getKey(), 0), e.getValue()[0], e.getValue()[1]));
        }
        return out;
    }

    /** The line a finished ride is announced with: distance, time, fuel and the best lean. */
    public static String summaryLine(Ride r) {
        StringBuilder sb = new StringBuilder();
        double km = km(r);
        if (km > 0) sb.append(String.format(java.util.Locale.ROOT, "%.1f km", km));
        long min = r.activeMs / 60_000;
        if (min > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(min >= 60 ? String.format(java.util.Locale.ROOT, "%d h %02d", min / 60, min % 60) : min + " min");
        }
        if (r.fuelUsedL > 0 && km > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(String.format(java.util.Locale.ROOT, "%.1f L/100 km", r.fuelUsedL / km * 100));
        }
        double lean = Math.max(Math.abs(r.maxLeanL), r.maxLeanR);
        if (lean > 1) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(String.format(java.util.Locale.ROOT, "%.0f° of lean", lean));
        }
        if (r.maxKmh > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(String.format(java.util.Locale.ROOT, "%.0f km/h at most", r.maxKmh));
        }
        return sb.toString();
    }
}
