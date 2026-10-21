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
    /** The ride's distance in metres, both of the above together ({@link Distance}); NaN in an older summary. */
    public double distM = Double.NaN;
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

    /**
     * The ride's distance in km, the one every screen and export shows. A summary saved before it kept
     * {@link #distM} falls back to the old rule (the odometer when it moved, else GPS) until it is redone.
     */
    public double km() {
        return (distM == distM ? distM : distBikeM > 0 ? distBikeM : distGpsM) / 1000;
    }

    public String distance() {
        double m = km() * 1000;
        return m >= 1000 ? String.format(Locale.ROOT, "%.1f km", m / 1000) : String.format(Locale.ROOT, "%.0f m", m);
    }

    /**
     * "82 h 39 min" or "39 min", from the number of minutes the dash reports as its total time
     * (0x41, bytes 51-52: the value is in minutes, not hours — see PROTOCOL.md).
     */
    public static String hoursMinutes(int minutes) {
        int m = Math.max(0, minutes);
        return m < 60 ? m + " min" : String.format(Locale.ROOT, "%d h %02d min", m / 60, m % 60);
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

    /**
     * How the saved summary was computed: a ride saved before this version has to be recomputed once.
     * 2: the distance ({@link #distM}), and no temperatures at the bottom of their scale.
     * 3: the torque, the fuel use, the suspension speeds and the raw brake values.
     * 4: the IMU accelerations, wheel and GPS speeds, lean, instant fuel economy, rotation rates, rear
     * wheel slip, the time in each gear and the gear changes.
     * 5: the bends by their most lean ({@link #bendLean}).
     */
    public static final int SUMMARY_VERSION = 5;
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
        /**
         * Only the lowest and the highest are worth showing: a value that swings both ways around zero
         * (a rotation rate, the wheel slip) averages out to nothing.
         */
        public final boolean extremes;
        /** Reads the value out of a sample; not saved (the key does that). */
        final java.util.function.ToDoubleFunction<RideTsv.Sample> of;
        public double min = Double.NaN, mean = Double.NaN, max = Double.NaN;
        /** Seconds that had this value. */
        public int n;
        private double sum;

        Metric(String key, String label, String section, String unit, int decimals,
               java.util.function.ToDoubleFunction<RideTsv.Sample> of) {
            this(key, label, section, unit, decimals, false, of);
        }

        Metric(String key, String label, String section, String unit, int decimals, boolean extremes,
               java.util.function.ToDoubleFunction<RideTsv.Sample> of) {
            this.key = key;
            this.label = label;
            this.section = section;
            this.unit = unit;
            this.decimals = decimals;
            this.extremes = extremes;
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
            // the bike's own units: the torque values compare with each other, the fuel use with itself
            new Metric("torque_rider", "Torque asked by the throttle", ENGINE, "bike units", 0, s -> s.torque_rider),
            new Metric("torque_req", "Torque the engine control asks for", ENGINE, "bike units", 0, s -> s.torque_req),
            new Metric("torque_actual", "Torque from the engine", ENGINE, "bike units", 0, s -> s.torque_actual),
            new Metric("fuel_rate", "Fuel use", ENGINE, "dash units", 2, s -> s.fuelRate),
            // km/L whatever the dash is set to (PROTOCOL.md, 0x45)
            new Metric("fuel_inst", "Fuel economy, instant", ENGINE, "km/L", 1, s -> s.fuelInst),
            new Metric("susp_f", "Front suspension", CHASSIS, "mm", 1, s -> s.susp_f_stroke_mm),
            new Metric("susp_r", "Rear suspension", CHASSIS, "mm", 1, s -> s.susp_r_stroke_mm),
            new Metric("susp_f_speed", "Front suspension speed (unverified)", CHASSIS, "bike units", 2, s -> s.susp_f_stroke_vp),
            new Metric("susp_r_speed", "Rear suspension speed (unverified)", CHASSIS, "bike units", 2, s -> s.susp_r_stroke_vp),
            new Metric("accel", "Acceleration", CHASSIS, "g", 2, s -> s.accG),
            // the IMU pair is mounted at 45°: turned as the chart turns it (BikeFrames.ImuSusp)
            new Metric("accel_fwd", "Acceleration, forward", CHASSIS, "g", 2,
                    s -> BikeFrames.ImuSusp.longitudinal(s.imu_ax_g, s.imu_ay_g)),
            new Metric("accel_side", "Acceleration, sideways", CHASSIS, "g", 2,
                    s -> BikeFrames.ImuSusp.lateral(s.imu_az_g)),
            new Metric("accel_up", "Acceleration, up", CHASSIS, "g", 2,
                    s -> BikeFrames.ImuSusp.vertical(s.imu_ax_g, s.imu_ay_g)),
            // either side: how far, and how far on average, the bike was leant over (the tiles have each side)
            new Metric("lean", "Lean angle, either side", CHASSIS, "°", 1, s -> Math.abs(s.lean)),
            // the rotation rates' scale is not established (PROTOCOL.md, 0x4B)
            new Metric("roll_rate", "Roll rate", CHASSIS, "bike units", 1, true, s -> s.imu_ax_psip3),
            new Metric("yaw_rate", "Yaw rate", CHASSIS, "bike units", 1, true, s -> s.imu_az_psip2),
            new Metric("imu_rate1", "IMU rate 1 (unverified)", CHASSIS, "bike units", 1, true, s -> s.imu_ay_psip1),
            new Metric("wheel_f", "Front wheel (filtered)", CHASSIS, "km/h", 0, s -> s.wheel_f_kmh),
            new Metric("wheel_r", "Rear wheel (filtered)", CHASSIS, "km/h", 0, s -> s.wheel_r_kmh),
            new Metric("wheelie", "Wheelie angle", CHASSIS, "°", 1, s -> s.wheelie_deg),
            new Metric("tyre_f", "Front tyre", CHASSIS, "bar", 2, s -> s.tyreFrontKpa / 100),
            new Metric("tyre_r", "Rear tyre", CHASSIS, "bar", 2, s -> s.tyreRearKpa / 100),
            new Metric("brake_f", "Front brake", BRAKES, "bar", 1, s -> s.brakeFrontBar),
            // kept raw, as Rideology keeps them
            new Metric("brake_f_master", "Front brake master (unverified)", BRAKES, "raw", 0, s -> s.brake_f_master),
            new Metric("brake_r_master", "Rear brake master (unverified)", BRAKES, "raw", 0, s -> s.brake_r_master),
            new Metric("brake_r_caliper", "Rear brake caliper (unverified)", BRAKES, "raw", 0, s -> s.brake_r_caliper),
            new Metric("rear_slip", "Rear wheel slip", BRAKES, "%", 0, true,
                    s -> rearSlip(s.wheel_f_kmh, s.wheel_r_kmh)),
            new Metric("battery", "Battery", BRAKES, "V", 2, s -> s.batteryV),
            new Metric("coolant", "Coolant", TEMPERATURES, "°C", 0, s -> s.waterC),
            new Metric("oil", "Oil", TEMPERATURES, "°C", 0, s -> s.oilC),
            new Metric("intake", "Intake air", TEMPERATURES, "°C", 0, s -> s.intakeC),
            new Metric("outside", "Outside", TEMPERATURES, "°C", 0, s -> s.outsideC),
            new Metric("gps_speed", "Speed (GPS)", QUALITY, "km/h", 0, s -> s.gpsKmh),
            new Metric("gps_acc", "GPS accuracy", QUALITY, "m", 1, s -> s.gpsAccM),
            new Metric("altitude", "Altitude", QUALITY, "m", 0, s -> s.altM),
    };

    /** This ride's copy of the table: summarize runs more than once on the same object. */
    public final Metric[] metrics = freshMetrics();

    private static Metric[] freshMetrics() {
        Metric[] out = new Metric[TEMPLATE.length];
        for (int i = 0; i < TEMPLATE.length; i++) {
            Metric t = TEMPLATE[i];
            out[i] = new Metric(t.key, t.label, t.section, t.unit, t.decimals, t.extremes, t.of);
        }
        return out;
    }

    /** Below this front-wheel speed the slip is not worked out: a ratio of two crawling wheels says nothing. */
    static final double SLIP_MIN_KMH = 10;

    /**
     * How much faster the rear wheel turns than the front, in % of the front: positive while the rear
     * spins up under power, negative while it is braked harder. NaN below {@link #SLIP_MIN_KMH} or
     * without both filtered wheel speeds.
     */
    static double rearSlip(double frontKmh, double rearKmh) {
        if (frontKmh != frontKmh || rearKmh != rearKmh || frontKmh < SLIP_MIN_KMH) return Double.NaN;
        return (rearKmh - frontKmh) / frontKmh * 100;
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
        // the gears are engine rows too, and a ride can have them without any other engine value
        if (gearS != null && !out.contains(ENGINE)) out.add(0, ENGINE);
        return out;
    }

    // ------------------------------------------------------------------ state, not measurements
    /**
     * Seconds the electronics were doing something. These are levels, not measurements: an average of a
     * traction-control level means nothing, the time it cut in does. NaN when the ride has no such column.
     */
    public double tcsOnS = Double.NaN, absOnS = Double.NaN, wheelieS = Double.NaN, cruiseOnS = Double.NaN;

    /** The highest gear counted; a reading above it is not a gear (an older decoder's cut-short frame). */
    static final int MAX_GEAR = 9;
    /** Seconds in each gear, the index is the gear (0 = neutral); null when the ride has no gear at all. */
    public int[] gearS;
    /** Gear changes read from the gear column, each step counted (3 → 5 in one second is two). */
    public int shiftsUp, shiftsDown;
    /**
     * Shifts the quick shifter made, from its operation flags: set when the rider shifted without the
     * clutch, not for every shift (PROTOCOL.md, 0x4B byte 51). NaN when the ride has no such column.
     */
    public double kqsUp = Double.NaN, kqsDown = Double.NaN;
    /**
     * The ride's bends by the most lean reached in each, in whole degrees: {@code bendLean[27]} bends
     * were leant over 27° at most. Null when the ride has no bend (or no lean at all). The lean a rider
     * usually takes a bend at comes from it ({@link RideStats#usualBendLean}).
     */
    public int[] bendLean;

    /**
     * Counts the bends of a ride, one recorded second after the other. A bend is a run of seconds leant
     * the same way by more than {@link #LEAN_IN} while riding (from {@link #MIN_KMH}); its most lean says
     * how hard it was taken. A bend that never reaches {@link #MIN_PEAK} is a sweep where nobody leans,
     * and a reading beyond {@link #MAX_PEAK} is not a lean anyone rides (a fall, a bad frame).
     */
    static final class Bends {
        static final double LEAN_IN = 10, MIN_KMH = 30;
        static final int MIN_PEAK = 15, MAX_PEAK = 60;
        private final int[] byPeak = new int[MAX_PEAK + 1];
        private boolean any;
        private int side;
        private double peak;

        void add(RideTsv.Sample s) {
            double kmh = s.kmh == s.kmh ? s.kmh : s.gpsKmh;
            int now = !(kmh >= MIN_KMH) || !(Math.abs(s.lean) > LEAN_IN) ? 0 : s.lean > 0 ? 1 : -1;
            if (now != side) {
                close();
                side = now;
            }
            if (now != 0) peak = Math.max(peak, Math.abs(s.lean));
        }

        private void close() {
            if (side != 0 && peak >= MIN_PEAK && peak <= MAX_PEAK) {
                byPeak[(int) Math.round(peak)]++;
                any = true;
            }
            peak = 0;
        }

        /** The bends by their most lean, or null without any. */
        int[] result() {
            close();
            side = 0;
            return any ? byPeak.clone() : null;
        }
    }

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
        double prevFuel = Double.NaN;
        int[] gears = new int[MAX_GEAR + 1];
        boolean gearSeen = false;
        int prevGear = -1;
        shiftsUp = shiftsDown = 0;
        double kqsU = 0, kqsD = 0, prevKqsU = Double.NaN, prevKqsD = Double.NaN;
        boolean kqsSeen = false;
        Distance dist = new Distance();
        Bends bends = new Bends();
        for (RideTsv.Sample s : samples) {
            if ((s.flags & RideTsv.FLAG_BIKE) != 0) hasBike = true;
            bends.add(s);
            if (s.hasGps()) {
                hasGps = true;
                addPoint(s.lat, s.lon);
            }
            dist.add(s);
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
            if (s.gear == s.gear) {
                int g = (int) Math.round(s.gear);
                if (g >= 0 && g <= MAX_GEAR) {
                    gears[g]++;
                    gearSeen = true;
                    // a change through neutral is not a shift up or down the box (N sits between 1 and 2)
                    if (prevGear > 0 && g > 0 && g != prevGear) {
                        if (g > prevGear) shiftsUp += g - prevGear;
                        else shiftsDown += prevGear - g;
                    }
                    prevGear = g;
                }
            }
            // a flag held over two seconds is one shift: count the seconds it comes on
            if (s.kqs_up == s.kqs_up || s.kqs_down == s.kqs_down) kqsSeen = true;
            if (s.kqs_up > 0 && !(prevKqsU > 0)) kqsU++;
            if (s.kqs_down > 0 && !(prevKqsD > 0)) kqsD++;
            prevKqsU = s.kqs_up;
            prevKqsD = s.kqs_down;
        }
        gearS = gearSeen ? gears : null;
        bendLean = bends.result();
        kqsUp = kqsSeen ? kqsU : Double.NaN;
        kqsDown = kqsSeen ? kqsD : Double.NaN;
        // seen but never on is 0 seconds ("never cut in"); a column the ride does not have stays unknown
        tcsOnS = tcsSeen ? tcs : Double.NaN;
        absOnS = absSeen ? abs : Double.NaN;
        wheelieS = wheelieSeen ? wheelie : Double.NaN;
        cruiseOnS = cruiseSeen ? cruise : Double.NaN;
        distGpsM = dist.gpsM;
        distBikeM = dist.bikeM;
        distM = dist.m();
        // average as the recorder counts it while riding: distance over recorded time (pauses left out),
        // so the list and the ride agree; the mean of the speed samples is only a fallback without a distance
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

    /**
     * The ride's distance as its samples come in, from the bike's odometer and the GPS together. The 0x41
     * odometer reads whole kilometres: on its own, a ride's distance moves in 1 km steps and can be nearly
     * a kilometre off either way, each end of the ride sitting anywhere in its kilometre. So the odometer
     * counts the kilometres and the GPS track fills in the rest: how far the ride had gone when the
     * odometer first stepped, and how far it has gone since its last step — at most the kilometre to the
     * next one, so the distance never runs back, and a GPS that loses the track for a while costs no more
     * than that kilometre. An odometer that reads finer than a kilometre is taken as it is; until the
     * odometer has moved (or without one), the GPS alone.
     */
    public static final class Distance {
        /** The GPS track's distance and the odometer's, in metres. */
        public double gpsM, bikeM;
        private double prevLat = Double.NaN, prevLon = Double.NaN, prevOdo = Double.NaN;
        /** An odometer reading with a fraction: this odometer is finer than whole kilometres. */
        private boolean fine;
        /** The odometer's and the GPS's distance at the odometer's last step. */
        private double stepBikeM, stepGpsM;
        /** How far the ride is ahead of the odometer's count at its steps; NaN until it has stepped. */
        private double offsetM = Double.NaN;

        /** Takes one recorded second: its position and its odometer, either of them possibly missing. */
        public void add(RideTsv.Sample s) {
            if (s.hasGps()) {
                if (prevLat == prevLat) {
                    double d = distanceM(prevLat, prevLon, s.lat, s.lon);
                    if (d < 500 && (s.gpsAccM != s.gpsAccM || s.gpsAccM < 25)) gpsM += d; // jumps and bad fixes
                }
                prevLat = s.lat;
                prevLon = s.lon;
            }
            if (s.odoKm == s.odoKm) {
                if (prevOdo == prevOdo && s.odoKm >= prevOdo) bikeM += (s.odoKm - prevOdo) * 1000;
                prevOdo = s.odoKm;
                if (Math.abs(s.odoKm - Math.rint(s.odoKm)) > 1e-6) fine = true;
            }
            if (bikeM > stepBikeM) {
                // the odometer has just turned a kilometre: at its first turn the GPS says how far the ride
                // really is (the odometer started counting somewhere inside a kilometre); without a track
                // yet, the odometer's count is all there is
                if (offsetM != offsetM) offsetM = gpsM > 0 ? gpsM - bikeM : 0;
                stepBikeM = bikeM;
                stepGpsM = gpsM;
            }
        }

        /** The ride's distance so far, in metres. */
        public double m() {
            if (offsetM != offsetM) return gpsM;
            if (fine) return bikeM;
            return bikeM + offsetM + Math.min(gpsM - stepGpsM, 1000);
        }
    }

    /** The samples of a recorded ride, newest summary merged in. */
    public List<RideTsv.Sample> samples(List<RideTsv.Sample> fromFile) {
        return new ArrayList<>(fromFile);
    }
}
