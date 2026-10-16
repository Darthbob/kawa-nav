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

    /** How the saved summary was computed: a ride saved before this version has to be recomputed once. */
    public static final int SUMMARY_VERSION = 1;
    /** Which summary the file holds, 0 for a ride saved by a version that did not know about metrics. */
    public int summaryVersion;

    /** The sections the screen shows, in order. */
    public static final String ENGINE = "Engine", CHASSIS = "Chassis and suspension",
            BRAKES = "Brakes and electronics", TEMPERATURES = "Temperatures", QUALITY = "Data quality";

    /**
     * One value over the whole ride: the lowest, the average and the highest of every recorded second, and
     * how many seconds had it at all. A value the bike never sent keeps n == 0 and is left out of the
     * screen, so a ride without, say, a suspension unit shows no suspension rather than a row of zeroes.
     */
    public static final class Metric {
        public final String key, label, section, unit;
        public final int decimals;
        /** Reads the value out of a sample; not saved (the key does that). */
        final java.util.function.ToDoubleFunction<RideTsv.Sample> of;
        public double min = Double.NaN, mean = Double.NaN, max = Double.NaN;
        /** Seconds that had this value. */
        public int n;
        private double sum;

        Metric(String key, String label, String section, String unit, int decimals,
               java.util.function.ToDoubleFunction<RideTsv.Sample> of) {
            this.key = key;
            this.label = label;
            this.section = section;
            this.unit = unit;
            this.decimals = decimals;
            this.of = of;
        }

        /** True when the ride has this value at all. */
        public boolean known() {
            return n > 0;
        }

        void reset() {
            min = mean = max = Double.NaN;
            n = 0;
            sum = 0;
        }

        /** Adds one second; a value the bike did not send is left out, but a zero is a value. */
        void add(double v) {
            if (v != v) return;
            if (n == 0) {
                min = max = v;
            } else {
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            n++;
            sum += v;
            mean = sum / n;
        }
    }

    /** The values the summary keeps, in the order the screen shows them. */
    private static final Metric[] TEMPLATE = {
            new Metric("rpm", "Engine speed", ENGINE, "rpm", 0, s -> s.rpm),
            new Metric("throttle", "Throttle", ENGINE, "%", 0, s -> s.thrPct),
            new Metric("susp_f", "Front suspension", CHASSIS, "mm", 1, s -> s.susp_f_stroke_mm),
            new Metric("susp_r", "Rear suspension", CHASSIS, "mm", 1, s -> s.susp_r_stroke_mm),
            new Metric("accel", "Acceleration", CHASSIS, "g", 2, s -> s.accG),
            new Metric("wheelie", "Wheelie angle", CHASSIS, "°", 1, s -> s.wheelie_deg),
            new Metric("tyre_f", "Front tyre", CHASSIS, "bar", 2, s -> s.tyreFrontKpa / 100),
            new Metric("tyre_r", "Rear tyre", CHASSIS, "bar", 2, s -> s.tyreRearKpa / 100),
            new Metric("brake_f", "Front brake", BRAKES, "bar", 1, s -> s.brakeFrontBar),
            new Metric("battery", "Battery", BRAKES, "V", 2, s -> s.batteryV),
            new Metric("coolant", "Coolant", TEMPERATURES, "°C", 0, s -> s.waterC),
            new Metric("oil", "Oil", TEMPERATURES, "°C", 0, s -> s.oilC),
            new Metric("intake", "Intake air", TEMPERATURES, "°C", 0, s -> s.intakeC),
            new Metric("outside", "Outside", TEMPERATURES, "°C", 0, s -> s.outsideC),
            new Metric("gps_acc", "GPS accuracy", QUALITY, "m", 1, s -> s.gpsAccM),
            new Metric("altitude", "Altitude", QUALITY, "m", 0, s -> s.altM),
    };

    /** This ride's copy of the table: summarize runs more than once on the same object. */
    public final Metric[] metrics = freshMetrics();

    private static Metric[] freshMetrics() {
        Metric[] out = new Metric[TEMPLATE.length];
        for (int i = 0; i < TEMPLATE.length; i++) {
            Metric t = TEMPLATE[i];
            out[i] = new Metric(t.key, t.label, t.section, t.unit, t.decimals, t.of);
        }
        return out;
    }

    /** The channel with this key, or null. */
    public Metric metric(String key) {
        for (Metric m : metrics) if (m.key.equals(key)) return m;
        return null;
    }

    /** The sections this ride has values in, in table order (a section with nothing recorded is left out). */
    public List<String> sections() {
        List<String> out = new java.util.ArrayList<>();
        for (Metric m : metrics) {
            if (m.known() && !out.contains(m.section)) out.add(m.section);
        }
        return out;
    }

    // ------------------------------------------------------------------ state, not measurements
    /**
     * Seconds the electronics were doing something. These are levels, not measurements: an average of a
     * traction-control level means nothing, the time it cut in does. NaN when the ride has no such column.
     */
    public double tcsOnS = Double.NaN, absOnS = Double.NaN, wheelieS = Double.NaN, cruiseOnS = Double.NaN;

    /** Recomputes the summary from the recorded samples (after a crash, or to check the file). */
    public void summarize(List<RideTsv.Sample> samples) {
        if (samples.isEmpty()) return;
        this.samples = samples.size();
        start = samples.get(0).epochMs;
        end = samples.get(samples.size() - 1).epochMs;
        // each sample carries the recorded time so far: pauses are not in it (end - start would count them)
        activeMs = Math.max(activeMs, samples.get(samples.size() - 1).tMs);
        distGpsM = distBikeM = 0;
        fuelUsedL = 0;
        maxKmh = 0;
        maxRpm = 0;
        maxLeanL = maxLeanR = 0;
        hasGps = hasBike = false;
        bbox = null;
        for (Metric m : metrics) m.reset();
        double tcs = 0, abs = 0, wheelie = 0, cruise = 0;
        boolean tcsSeen = false, absSeen = false, wheelieSeen = false, cruiseSeen = false;
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
            for (Metric m : metrics) m.add(m.of.applyAsDouble(s));
            // one sample is one second: the time a system was working is the number of samples it was on
            // the 0x4A nibbles and the 0x4B pair are different readings of different frames: either of
            // them moving counts as "the traction control worked this second" (see PROTOCOL.md)
            if (s.tcs == s.tcs || s.tcs_hi == s.tcs_hi || s.tcs_hb == s.tcs_hb || s.tcs_lb == s.tcs_lb) {
                tcsSeen = true;
                if (s.tcs > 0 || s.tcs_hi > 0 || s.tcs_hb > 0 || s.tcs_lb > 0) tcs++;
            }
            if (s.abs == s.abs && s.abs >= 0) {
                absSeen = true;
                if (((int) s.abs & 0x03) != 0) abs++;
            }
            if (s.wheelie_flag == s.wheelie_flag) {
                wheelieSeen = true;
                if (s.wheelie_flag > 0) wheelie++;
            }
            if (s.cruise_on == s.cruise_on) {
                cruiseSeen = true;
                if (s.cruise_on > 0) cruise++;
            }
        }
        // seen but never on is 0 seconds ("never cut in"); a column the ride does not have stays unknown
        tcsOnS = tcsSeen ? tcs : Double.NaN;
        absOnS = absSeen ? abs : Double.NaN;
        wheelieS = wheelieSeen ? wheelie : Double.NaN;
        cruiseOnS = cruiseSeen ? cruise : Double.NaN;
        // average as the recorder counts it while riding: distance over recorded time (pauses left out),
        // so the list and the ride agree; the mean of the speed samples is only a fallback without a distance
        double distM = distBikeM > 0 ? distBikeM : distGpsM;
        double hours = activeMs / 3_600_000.0;
        avgKmh = distM > 0 && hours > 0 ? distM / 1000 / hours : speedCount == 0 ? 0 : speedSum / speedCount;
        if (samples.get(0).odoKm == samples.get(0).odoKm) odoStartKm = samples.get(0).odoKm;
        if (samples.get(samples.size() - 1).odoKm == samples.get(samples.size() - 1).odoKm) {
            odoEndKm = samples.get(samples.size() - 1).odoKm;
        }
        summaryVersion = SUMMARY_VERSION;
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
