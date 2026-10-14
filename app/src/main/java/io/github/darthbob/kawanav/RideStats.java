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

    public final int rides;
    public final double totalKm, totalHours;
    public final Best fastest, maxLeanLeft, maxLeanRight, longest;

    private RideStats(int rides, double totalKm, double totalHours,
                      Best fastest, Best maxLeanLeft, Best maxLeanRight, Best longest) {
        this.rides = rides;
        this.totalKm = totalKm;
        this.totalHours = totalHours;
        this.fastest = fastest;
        this.maxLeanLeft = maxLeanLeft;
        this.maxLeanRight = maxLeanRight;
        this.longest = longest;
    }

    /** The distance of a ride the way the list shows it: the bike's odometer when it has one. */
    public static double km(Ride r) {
        return (r.distBikeM > 0 ? r.distBikeM : r.distGpsM) / 1000;
    }

    public static RideStats of(List<Ride> rides) {
        Ride fastest = null, left = null, right = null, longest = null;
        double fastestKmh = 0, leanL = 0, leanR = 0, longestKm = 0, km = 0, ms = 0;
        for (Ride r : rides) {
            double d = km(r);
            km += d;
            ms += r.activeMs;
            if (r.maxKmh > fastestKmh) {
                fastestKmh = r.maxKmh;
                fastest = r;
            }
            if (r.maxLeanL > leanL) {
                leanL = r.maxLeanL;
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
        }
        return new RideStats(rides.size(), km, ms / 3_600_000.0,
                new Best(fastest, fastestKmh), new Best(left, leanL), new Best(right, leanR),
                new Best(longest, longestKm));
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
        double lean = Math.max(r.maxLeanL, r.maxLeanR);
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
