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
    /** The km of the rides that know their fuel: the consumption is {@link #fuelL} over these, not over all. */
    public final double fuelKm;
    /** True when some of {@link #fuelL} is worked out from the instant fuel economy ({@link Ride#fuelEstimated}). */
    public final boolean fuelEstimated;

    private RideStats(int rides, double totalKm, double totalHours,
                      Best fastest, Best maxLeanLeft, Best maxLeanRight, Best longest,
                      Best hottest, Best hardestBraking, Best mostSuspension,
                      double fuelL, double fuelKm, boolean fuelEstimated,
                      double tcsSeconds, double absSeconds, double wheelieSeconds) {
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
        this.fuelKm = fuelKm;
        this.fuelEstimated = fuelEstimated;
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
        double hotC = 0, brakeG = 0, suspMm = 0, fuel = 0, fuelKm = 0, tcsS = 0, absS = 0, wheelieS = 0;
        boolean fuelEstimated = false;
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
            // a ride recorded without the bike burned fuel too, but nobody knows how much
            if (r.fuelL() > 0) {
                fuel += r.fuelL();
                fuelKm += d;
                fuelEstimated |= r.fuelEstimated();
            }
            if (r.tcsOnS > 0) tcsS += r.tcsOnS;
            if (r.absOnS > 0) absS += r.absOnS;
            if (r.wheelieS > 0) wheelieS += r.wheelieS;
        }
        return new RideStats(rides.size(), km, ms / 3_600_000.0,
                new Best(fastest, fastestKmh), new Best(left, leanL), new Best(right, leanR),
                new Best(longest, longestKm), new Best(hot, hotC), new Best(brake, brakeG),
                new Best(susp, suspMm), fuel, fuelKm, fuelEstimated, tcsS, absS, wheelieS);
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
        if (r.fuelL() > 0 && km > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(String.format(java.util.Locale.ROOT, r.fuelEstimated() ? "≈ %.1f L/100 km" : "%.1f L/100 km",
                    r.fuelL() / km * 100));
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

    /** The bends of this many of the latest rides that have any make the rider's usual lean ... */
    public static final int BEND_RIDES = 20;
    /** ... once there are at least this many of them. */
    public static final int MIN_BENDS = 30;

    /** The lean the rider usually takes a bend at, and what it comes from. */
    public static final class BendLean {
        /** Degrees, or NaN while there are fewer than {@link #MIN_BENDS} bends. */
        public final double degrees;
        public final int bends, rides;

        BendLean(double degrees, int bends, int rides) {
            this.degrees = degrees;
            this.bends = bends;
            this.rides = rides;
        }

        public boolean known() {
            return degrees == degrees;
        }
    }

    /**
     * The lean the rider usually takes a bend at: of the bends in the latest {@link #BEND_RIDES} rides
     * that have any ({@link Ride#bendLean}), the most lean of the middle one, half of the bends taken
     * with less and half with more. Unknown below {@link #MIN_BENDS} bends.
     *
     * @param rides newest first, as {@link RideStore#list} gives them
     */
    public static BendLean usualBendLean(List<Ride> rides) {
        int[] all = new int[Ride.Bends.MAX_PEAK + 1];
        int bends = 0, used = 0;
        for (Ride r : rides) {
            if (used >= BEND_RIDES) break;
            if (r.bendLean == null || r.isRecording()) continue;
            used++;
            for (int d = 0; d < Math.min(all.length, r.bendLean.length); d++) {
                all[d] += r.bendLean[d];
                bends += r.bendLean[d];
            }
        }
        if (bends < MIN_BENDS) return new BendLean(Double.NaN, bends, used);
        int seen = 0;
        for (int d = 0; d < all.length; d++) {
            seen += all[d];
            if (seen * 2 >= bends) return new BendLean(d, bends, used);
        }
        return new BendLean(Double.NaN, bends, used); // not reached: seen ends at bends
    }

    /** What the app says while a ride runs without a GPS track (a background start without GPS). */
    public static final String NO_TRACK = "no GPS track";

    /**
     * The ride status line the UI and the ride notification show: "12.3 km · 0:41:12", with
     * {@link #NO_TRACK} added while the recorder has no fix, so the rider learns it during the ride
     * and not from the ride list afterwards.
     */
    public static String rideStatusLine(double metres, long activeMs, boolean partialGps) {
        String line = String.format(java.util.Locale.ROOT, "%.1f km · %s", metres / 1000, duration(activeMs));
        return partialGps ? line + " · " + NO_TRACK : line;
    }

    /** Milliseconds as h:mm:ss (or m:ss under an hour). */
    public static String duration(long ms) {
        long s = Math.max(0, ms) / 1000;
        long h = s / 3600;
        return h > 0 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, (s % 3600) / 60, s % 60)
                : String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }
}
