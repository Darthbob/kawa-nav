package io.github.darthbob.kawanav;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One recorded second of a ride, as a line of text: the columns are named in the header, so columns can
 * be added later without breaking old files, and a missing value is an empty cell. Pure Java (no
 * Android), so the format can be unit-tested; one file per ride, gzipped once the ride is finished.
 */
public final class RideTsv {
    private RideTsv() {}

    /** First line of the file: format version. */
    static final String MAGIC = "#kawanav-ride 1";

    /** Column names in the order they are written. */
    static final String[] COLUMNS = {
            "t_ms", "epoch_ms", "lat", "lon", "alt_m", "gps_kmh", "gps_acc_m", "flags",
            "kmh", "rpm", "gear", "thr_pct", "acc_g", "lean_deg", "water_c", "oil_c", "intake_c",
            "fuel_inst", "fuel_rate", "tcs", "abs", "odo_km", "dist_m", "fuel_total_l",
            // added in 0.7; older files simply lack them (columns are read by name)
            "tyre_f_kpa", "tyre_r_kpa", "battery_v", "outside_c", "brake_f_bar",
            // added in 0.8: the suspension/IMU frame (0x4B) and the rest of the live frame (0x4A)
            "susp_f_stroke_mm", "susp_r_stroke_mm", "susp_f_stroke_vp", "susp_r_stroke_vp", "imu_ax_g", "imu_ay_g", "imu_az_g", "imu_ax_psip3", "imu_ay_psip1", "imu_az_psip2", "wheel_f_kmh", "wheel_r_kmh", "tcs_hb", "tcs_lb", "kqs_down", "kqs_up", "wheelie_flag", "torque_rider", "torque_req", "torque_actual", "brake_f_master", "brake_r_master", "brake_r_caliper", "abs_target", "abs_ind", "abs_switching", "damping", "susp_cal", "susp_status", "susp_ecu", "cruise_on", "cruise_mode", "wheelie_deg", "wheel_speed_setting",
            // the other half of the 0x4A TCS byte: only the low nibble was recorded before 0.9
            "tcs_hi",
    };
    /** flags: the bike was connected while this second was recorded. */
    public static final int FLAG_BIKE = 1;
    /** flags: a fresh GPS fix was available. */
    public static final int FLAG_GPS = 2;

    /** One recorded second. Nothing is final: the recorder fills it in as the ride goes on. */
    public static final class Sample {
        public long tMs;          // since the ride started
        public long epochMs;
        public double lat = Double.NaN, lon = Double.NaN, altM = Double.NaN, gpsKmh = Double.NaN, gpsAccM = Double.NaN;
        public int flags;
        public double kmh = Double.NaN, rpm = Double.NaN, gear = Double.NaN, thrPct = Double.NaN, accG = Double.NaN,
                lean = Double.NaN, waterC = Double.NaN, oilC = Double.NaN, intakeC = Double.NaN,
                fuelInst = Double.NaN, fuelRate = Double.NaN, tcs = Double.NaN, abs = Double.NaN,
                odoKm = Double.NaN, distM = Double.NaN, fuelTotalL = Double.NaN,
                tyreFrontKpa = Double.NaN, tyreRearKpa = Double.NaN, batteryV = Double.NaN,
                outsideC = Double.NaN, brakeFrontBar = Double.NaN,
                susp_f_stroke_mm = Double.NaN, susp_r_stroke_mm = Double.NaN, susp_f_stroke_vp = Double.NaN, susp_r_stroke_vp = Double.NaN, imu_ax_g = Double.NaN, imu_ay_g = Double.NaN, imu_az_g = Double.NaN, imu_ax_psip3 = Double.NaN, imu_ay_psip1 = Double.NaN, imu_az_psip2 = Double.NaN, wheel_f_kmh = Double.NaN, wheel_r_kmh = Double.NaN, tcs_hb = Double.NaN, tcs_lb = Double.NaN, kqs_down = Double.NaN, kqs_up = Double.NaN, wheelie_flag = Double.NaN, torque_rider = Double.NaN, torque_req = Double.NaN, torque_actual = Double.NaN, brake_f_master = Double.NaN, brake_r_master = Double.NaN, brake_r_caliper = Double.NaN, abs_target = Double.NaN, abs_ind = Double.NaN, abs_switching = Double.NaN, damping = Double.NaN, susp_cal = Double.NaN, susp_status = Double.NaN, susp_ecu = Double.NaN, cruise_on = Double.NaN, cruise_mode = Double.NaN, wheelie_deg = Double.NaN, wheel_speed_setting = Double.NaN, tcs_hi = Double.NaN;

        public boolean hasGps() {
            return (flags & FLAG_GPS) != 0 && lat == lat && lon == lon;
        }

        /** The line for the file: values in column order, empty cells for what is missing. */
        public String line() {
            StringBuilder sb = new StringBuilder(320);
            double[] numbers = {tMs, epochMs, lat, lon, altM, gpsKmh, gpsAccM, flags, kmh, rpm, gear, thrPct, accG,
                    lean, waterC, oilC, intakeC, fuelInst, fuelRate, tcs, abs, odoKm, distM, fuelTotalL,
                    tyreFrontKpa, tyreRearKpa, batteryV, outsideC, brakeFrontBar,
                    susp_f_stroke_mm, susp_r_stroke_mm, susp_f_stroke_vp, susp_r_stroke_vp, imu_ax_g, imu_ay_g, imu_az_g, imu_ax_psip3, imu_ay_psip1, imu_az_psip2, wheel_f_kmh, wheel_r_kmh, tcs_hb, tcs_lb, kqs_down, kqs_up, wheelie_flag, torque_rider, torque_req, torque_actual, brake_f_master, brake_r_master, brake_r_caliper, abs_target, abs_ind, abs_switching, damping, susp_cal, susp_status, susp_ecu, cruise_on, cruise_mode, wheelie_deg, wheel_speed_setting, tcs_hi};
            for (int i = 0; i < numbers.length; i++) {
                if (i > 0) sb.append('\t');
                sb.append(cell(numbers[i], COLUMNS[i]));
            }
            return sb.toString();
        }
    }

    /** Numbers are written with just enough decimals; the bike's integers without any. */
    private static String cell(double v, String column) {
        if (v != v) return "";
        switch (column) {
            case "t_ms": case "epoch_ms": case "flags": case "rpm": case "gear": case "water_c": case "oil_c":
            case "intake_c": case "tcs": case "tcs_hi": case "abs":
            // the whole numbers of 0x4A/0x4B: counts and levels, not measurements
            case "abs_ind": case "abs_switching": case "abs_target": case "brake_f_master": case "brake_r_caliper": case "brake_r_master": case "cruise_mode": case "cruise_on": case "damping": case "kqs_down": case "kqs_up": case "susp_cal": case "susp_ecu": case "susp_status": case "tcs_hb": case "tcs_lb": case "torque_actual": case "torque_req": case "torque_rider": case "wheel_speed_setting": case "wheelie_flag":
                return String.valueOf(Math.round(v));
            case "lat": case "lon":
                return String.format(java.util.Locale.ROOT, "%.6f", v);
            default:
                return String.format(java.util.Locale.ROOT, "%.2f", v);
        }
    }

    public static String header() {
        return String.join("\t", COLUMNS);
    }

    /** The first line of a ride file. */
    public static String magic() {
        return MAGIC;
    }

    /** Reads a file's lines back into samples (used for the summary, the charts and the recovery). */
    public static List<Sample> parse(List<String> lines) {
        List<Sample> out = new ArrayList<>();
        if (lines.isEmpty() || !MAGIC.equals(lines.get(0).trim())) return out;
        Map<String, Integer> index = new LinkedHashMap<>();
        int start = 1;
        if (lines.size() > 1 && lines.get(1).startsWith("t_ms")) {
            String[] names = lines.get(1).split("\t", -1);
            for (int i = 0; i < names.length; i++) index.put(names[i], i);
            start = 2;
        } else {
            for (int i = 0; i < COLUMNS.length; i++) index.put(COLUMNS[i], i);
        }
        for (int l = start; l < lines.size(); l++) {
            String line = lines.get(l);
            if (line.isEmpty()) continue;
            String[] cells = line.split("\t", -1);
            Sample s = new Sample();
            s.tMs = (long) num(cells, index, "t_ms");
            s.epochMs = (long) num(cells, index, "epoch_ms");
            s.lat = num(cells, index, "lat");
            s.lon = num(cells, index, "lon");
            s.altM = num(cells, index, "alt_m");
            s.gpsKmh = num(cells, index, "gps_kmh");
            s.gpsAccM = num(cells, index, "gps_acc_m");
            s.flags = (int) num(cells, index, "flags");
            s.kmh = num(cells, index, "kmh");
            s.rpm = num(cells, index, "rpm");
            s.gear = num(cells, index, "gear");
            s.thrPct = num(cells, index, "thr_pct");
            s.accG = num(cells, index, "acc_g");
            s.lean = num(cells, index, "lean_deg");
            s.waterC = temp(num(cells, index, "water_c"), -40);
            s.oilC = temp(num(cells, index, "oil_c"), -60);
            s.intakeC = temp(num(cells, index, "intake_c"), -40);
            s.fuelInst = num(cells, index, "fuel_inst");
            s.fuelRate = num(cells, index, "fuel_rate");
            s.tcs = num(cells, index, "tcs");
            s.abs = num(cells, index, "abs");
            s.odoKm = num(cells, index, "odo_km");
            s.distM = num(cells, index, "dist_m");
            s.fuelTotalL = num(cells, index, "fuel_total_l");
            s.tyreFrontKpa = num(cells, index, "tyre_f_kpa");
            s.tyreRearKpa = num(cells, index, "tyre_r_kpa");
            s.batteryV = num(cells, index, "battery_v");
            s.outsideC = num(cells, index, "outside_c");
            s.brakeFrontBar = num(cells, index, "brake_f_bar");
            s.susp_f_stroke_mm = num(cells, index, "susp_f_stroke_mm");
            s.susp_r_stroke_mm = num(cells, index, "susp_r_stroke_mm");
            s.susp_f_stroke_vp = num(cells, index, "susp_f_stroke_vp");
            s.susp_r_stroke_vp = num(cells, index, "susp_r_stroke_vp");
            s.imu_ax_g = num(cells, index, "imu_ax_g");
            s.imu_ay_g = num(cells, index, "imu_ay_g");
            s.imu_az_g = num(cells, index, "imu_az_g");
            s.imu_ax_psip3 = num(cells, index, "imu_ax_psip3");
            s.imu_ay_psip1 = num(cells, index, "imu_ay_psip1");
            s.imu_az_psip2 = num(cells, index, "imu_az_psip2");
            s.wheel_f_kmh = num(cells, index, "wheel_f_kmh");
            s.wheel_r_kmh = num(cells, index, "wheel_r_kmh");
            s.tcs_hb = num(cells, index, "tcs_hb");
            s.tcs_lb = num(cells, index, "tcs_lb");
            s.kqs_down = num(cells, index, "kqs_down");
            s.kqs_up = num(cells, index, "kqs_up");
            s.wheelie_flag = num(cells, index, "wheelie_flag");
            s.torque_rider = num(cells, index, "torque_rider");
            s.torque_req = num(cells, index, "torque_req");
            s.torque_actual = num(cells, index, "torque_actual");
            s.brake_f_master = num(cells, index, "brake_f_master");
            s.brake_r_master = num(cells, index, "brake_r_master");
            s.brake_r_caliper = num(cells, index, "brake_r_caliper");
            s.abs_target = num(cells, index, "abs_target");
            s.abs_ind = num(cells, index, "abs_ind");
            s.abs_switching = num(cells, index, "abs_switching");
            s.damping = num(cells, index, "damping");
            s.susp_cal = num(cells, index, "susp_cal");
            s.susp_status = num(cells, index, "susp_status");
            s.susp_ecu = num(cells, index, "susp_ecu");
            s.cruise_on = num(cells, index, "cruise_on");
            s.tcs_hi = num(cells, index, "tcs_hi");
            s.cruise_mode = num(cells, index, "cruise_mode");
            s.wheelie_deg = num(cells, index, "wheelie_deg");
            s.wheel_speed_setting = num(cells, index, "wheel_speed_setting");
            out.add(s);
        }
        return out;
    }

    /**
     * A 0x45 temperature, NaN when it is the bottom of its scale: older versions decoded a raw 00 (a sensor
     * that reads nothing, see {@code BikeFrames.LogExt}) as −40 °C or −60 °C and wrote it down.
     */
    private static double temp(double c, double floor) {
        return c == floor ? Double.NaN : c;
    }

    private static double num(String[] cells, Map<String, Integer> index, String column) {
        Integer i = index.get(column);
        if (i == null || i >= cells.length) return Double.NaN;
        String cell = cells[i].trim();
        if (cell.isEmpty()) return Double.NaN;
        try {
            return Double.parseDouble(cell);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
