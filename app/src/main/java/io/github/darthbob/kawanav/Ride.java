package io.github.darthbob.kawanav;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What is known about one recorded ride: the summary the list and the detail screen show. Kept in a
 * small JSON file next to the ride's samples ({@link RideStore}), so a ride can be listed without
 * reading its whole track.
 */
public final class Ride {
    public String id = "";
    public String name = "";
    /** recording, paused, done or recovered (a ride the app was still writing when it was killed). */
    public String state = "done";
    public long start, end;
    public long activeMs;
    public double distGpsM, distBikeM;
    public double maxKmh, avgKmh;
    public int maxRpm;
    public double maxLeanL, maxLeanR;
    public double fuelUsedL;
    public double odoStartKm = Double.NaN, odoEndKm = Double.NaN;
    public boolean hasGps, hasBike;
    public int samples;
    public String endReason = "";
    /** South-west and north-east corner of the track: minLat, minLon, maxLat, maxLon. */
    public double[] bbox;

    public boolean isRecording() {
        return "recording".equals(state) || "paused".equals(state);
    }

    public String distance() {
        double m = distBikeM > 0 ? distBikeM : distGpsM;
        return m >= 1000 ? String.format(Locale.ROOT, "%.1f km", m / 1000) : String.format(Locale.ROOT, "%.0f m", m);
    }

    /** "1 h 05 min", "12 min" or, for a short ride, "34 s". */
    public String duration() {
        long min = activeMs / 60_000;
        if (min >= 60) return String.format(Locale.ROOT, "%d h %02d min", min / 60, min % 60);
        if (min > 0) return min + " min";
        return Math.max(1, activeMs / 1000) + " s";
    }

    /** A ride named after where and when it started, e.g. "Ride 13 Oct 18:40". */
    public static String defaultName(long at) {
        return "Ride " + new java.text.SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(new java.util.Date(at));
    }

    /** Extends the bounding box with a position. */
    public void addPoint(double lat, double lon) {
        if (bbox == null) bbox = new double[]{lat, lon, lat, lon};
        bbox[0] = Math.min(bbox[0], lat);
        bbox[1] = Math.min(bbox[1], lon);
        bbox[2] = Math.max(bbox[2], lat);
        bbox[3] = Math.max(bbox[3], lon);
    }

    /** Recomputes the summary from the recorded samples (after a crash, or to check the file). */
    public void summarize(List<RideTsv.Sample> samples) {
        if (samples.isEmpty()) return;
        this.samples = samples.size();
        start = samples.get(0).epochMs;
        end = samples.get(samples.size() - 1).epochMs;
        activeMs = Math.max(activeMs, end - start);
        distGpsM = distBikeM = 0;
        maxKmh = 0;
        maxRpm = 0;
        maxLeanL = maxLeanR = 0;
        hasGps = hasBike = false;
        bbox = null;
        double speedSum = 0;
        int speedCount = 0;
        double prevLat = Double.NaN, prevLon = Double.NaN, prevOdo = Double.NaN, prevFuel = Double.NaN;
        for (RideTsv.Sample s : samples) {
            if ((s.flags & RideTsv.FLAG_BIKE) != 0) hasBike = true;
            if (s.hasGps()) {
                hasGps = true;
                addPoint(s.lat, s.lon);
                if (prevLat == prevLat) {
                    double d = distanceM(prevLat, prevLon, s.lat, s.lon);
                    if (d < 500 && (s.gpsAccM != s.gpsAccM || s.gpsAccM < 25)) distGpsM += d; // jumps and bad fixes
                }
                prevLat = s.lat;
                prevLon = s.lon;
            }
            if (s.kmh == s.kmh) {
                maxKmh = Math.max(maxKmh, s.kmh);
                speedSum += s.kmh;
                speedCount++;
            }
            if (s.rpm == s.rpm) maxRpm = Math.max(maxRpm, (int) s.rpm);
            if (s.lean == s.lean) {
                if (s.lean > maxLeanR) maxLeanR = s.lean;
                if (s.lean < maxLeanL) maxLeanL = s.lean;
            }
            if (s.odoKm == s.odoKm) {
                if (prevOdo == prevOdo && s.odoKm >= prevOdo) distBikeM += (s.odoKm - prevOdo) * 1000;
                prevOdo = s.odoKm;
            }
            if (s.fuelTotalL == s.fuelTotalL) {
                if (prevFuel == prevFuel && s.fuelTotalL >= prevFuel) fuelUsedL += s.fuelTotalL - prevFuel;
                prevFuel = s.fuelTotalL;
            }
        }
        avgKmh = speedCount == 0 ? 0 : speedSum / speedCount;
        if (samples.get(0).odoKm == samples.get(0).odoKm) odoStartKm = samples.get(0).odoKm;
        if (samples.get(samples.size() - 1).odoKm == samples.get(samples.size() - 1).odoKm) {
            odoEndKm = samples.get(samples.size() - 1).odoKm;
        }
    }

    /** Distance between two positions in metres (equirectangular, good enough for a ride). */
    public static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        return 6371000 * Math.sqrt(dLat * dLat + dLon * dLon);
    }

    /** The samples of a recorded ride, newest summary merged in. */
    public List<RideTsv.Sample> samples(List<RideTsv.Sample> fromFile) {
        return new ArrayList<>(fromFile);
    }
}
