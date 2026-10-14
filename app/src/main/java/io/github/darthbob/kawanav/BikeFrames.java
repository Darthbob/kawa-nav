package io.github.darthbob.kawanav;

import static io.github.darthbob.kawanav.FrameReader.be16;
import static io.github.darthbob.kawanav.FrameReader.be24;
import static io.github.darthbob.kawanav.FrameReader.be32;
import static io.github.darthbob.kawanav.FrameReader.bits;
import static io.github.darthbob.kawanav.FrameReader.blockPresent;
import static io.github.darthbob.kawanav.FrameReader.inPresentBlock;
import static io.github.darthbob.kawanav.FrameReader.u8;

/**
 * Frames the BLE5 meter sends: vehicle data, riding log, settings, answers. Byte layouts follow
 * Zen3515/homeassistant-kawasaki-rideology-ble (kawi_ble5_client.py, Apache-2.0), which mirrors
 * Rideology's Ble5Value classes; see PROTOCOL.md for what is verified on which bike. Unknown values are
 * NaN (decimals) or -1 (whole numbers). Pure Java (no Android) for JVM tests.
 */
public final class BikeFrames {
    private BikeFrames() {}

    public static final int MODEL_INFO = 0x03;
    public static final int METER_INIT = 0x08;
    public static final int PHONE_MODEL = 0x0B;
    public static final int GENERAL_CAPS = 0x1A;
    public static final int GENERAL_SETTINGS = 0x1B;
    public static final int SERVICE_CONFIG = 0x1D;
    public static final int SERVICE_INDICATOR = 0x1E;
    public static final int ACK = 0x20;
    public static final int STATUS = 0x30;
    public static final int INFO_CONFIG = 0x40;
    public static final int VEHICLE_INFO = 0x41;
    public static final int EMC_INFO = 0x42;
    public static final int RIDING_LOG_EXT = 0x45;
    public static final int TUNING_CONFIG = 0x47;
    public static final int VEHICLE_SETTINGS = 0x48;
    public static final int RIDING_LOG = 0x4A;
    public static final int RIDING_LOG_HIGH = 0x4B;

    /** Short name of a frame type, for logs and the debug card. */
    public static String name(int id) {
        switch (id) {
            case MODEL_INFO: return "model info";
            case METER_INIT: return "meter indication init";
            case PHONE_MODEL: return "phone model";
            case 0x13: return "meter indication";
            case 0x14: return "navigation";
            case GENERAL_CAPS: return "general setting capability";
            case GENERAL_SETTINGS: return "general settings";
            case SERVICE_CONFIG: return "maintenance config";
            case SERVICE_INDICATOR: return "maintenance";
            case ACK: return "ack";
            case STATUS: return "status";
            case INFO_CONFIG: return "vehicle info config";
            case VEHICLE_INFO: return "vehicle info";
            case EMC_INFO: return "EV/HEV info";
            case RIDING_LOG_EXT: return "riding log ext";
            case TUNING_CONFIG: return "tuning config";
            case VEHICLE_SETTINGS: return "vehicle settings";
            case RIDING_LOG: return "riding log";
            case RIDING_LOG_HIGH: return "riding log high";
            default: return String.format(java.util.Locale.ROOT, "0x%02X", id);
        }
    }

    /** Any frame from the meter; subclasses decode the types KawaNav knows. */
    public static class Frame {
        public final byte[] raw;
        public final int id;

        Frame(byte[] raw) {
            this.raw = raw;
            this.id = raw.length > 0 ? raw[0] & 0xFF : -1;
        }
    }

    // ------------------------------------------------------------------ answers

    /**
     * 0x20: the meter took (or refused) a request. Byte 3 is the request's frame type, byte 4 the result
     * (00 accepted, 01 rejected / not supported); longer ACKs echo the request's blocks after that.
     * (Zen3515 reads the result from byte 7 of long ACKs, which there is the echoed request value.)
     */
    public static final class Ack extends Frame {
        public final int cmd;
        public final int result;

        Ack(byte[] v) {
            super(v);
            cmd = u8(v, 3);
            result = u8(v, 4);
        }

        public boolean accepted() {
            return result == 0;
        }
    }

    /** 0x30: result of a settings block request ({@code 30 0C seq FF FF 05 <block> <result>}), or a text echo. */
    public static final class Status extends Frame {
        /** Block id the result is for, -1 for a text frame. */
        public final int block;
        public final int result;
        /** Echoed text (e.g. the phone name), else null. */
        public final String text;

        Status(byte[] v) {
            super(v);
            boolean blockResult = u8(v, 1) == 0x0C && blockPresent(v, 5);
            block = blockResult ? u8(v, 6) : -1;
            result = blockResult ? u8(v, 7) : -1;
            text = !blockResult && u8(v, 1) == 0x20 ? FrameReader.textChunks(v) : null;
        }
    }

    /** 0x03: VIN in ASCII chunks {@code 02 71 <8>}, {@code 02 72 <8>}, {@code 02 73 <1…>}. */
    public static final class ModelInfo extends Frame {
        /** Model part (first 8 characters of the VIN), null if missing. */
        public final String model;
        /** Full VIN (13 or 17 characters), null if not complete. */
        public final String vin;

        ModelInfo(byte[] v) {
            super(v);
            String[] parts = new String[3];
            for (int i = 5; i + 1 < v.length; i += 10) {
                int tag = u8(v, i + 1);
                if (u8(v, i) != 0x02 || tag < 0x71 || tag > 0x73) continue;
                StringBuilder sb = new StringBuilder();
                for (int k = i + 2; k < i + 10 && k < v.length; k++) {
                    int c = v[k] & 0xFF;
                    if (c == 0xFF || c == 0) break;
                    if (c >= 0x20 && c < 0x7F) sb.append((char) c);
                }
                parts[tag - 0x71] = sb.toString().trim();
            }
            model = parts[0] == null || parts[0].isEmpty() ? null : parts[0];
            String all = (parts[0] == null ? "" : parts[0]) + (parts[1] == null ? "" : parts[1])
                    + (parts[2] == null ? "" : parts[2]);
            vin = model != null && parts[1] != null && (all.length() == 17 || all.length() == 13) ? all : null;
        }
    }

    // ------------------------------------------------------------------ capabilities

    /** 0x1A: what the meter can show and which general settings (clock, shift lamp) it takes. */
    public static final class GeneralCaps extends Frame {
        public final boolean valid;
        /** Byte 7 bits 7-6: 0 = the app may set the meter clock; anything else: leave the clock alone. */
        public final int clockGate;
        /** Byte 7 bits 1-0: shift-lamp mode (0 = off/tach/LED list, 3 = off/on, 1-2: not settable). */
        public final int shiftLampMode;
        public final int shiftLampMin, shiftLampMax;
        public final boolean shiftLampSettable;
        /** Clock fields the app may set: day, month, year, hour, minute, second. */
        public final boolean[] clockSettable = new boolean[6];
        public final int meterIndication, navi;
        public final int[] indicatorSubcaps = new int[4];
        /** Turn-by-turn supported unless the navi nibble is 15 (Rideology then sends no navigation frames). */
        public final boolean turnByTurn;
        /** Status icons supported unless every indicator field says "none". */
        public final boolean statusIcons;

        GeneralCaps(byte[] v) {
            super(v);
            valid = v.length < 15 || !retryRequired(v, 5, 6, 14);
            clockGate = bits(v, 7, 0xC0);
            shiftLampMode = bits(v, 7, 0x03);
            shiftLampMax = u8(v, 8);
            shiftLampMin = u8(v, 9);
            shiftLampSettable = valid && (shiftLampMode == 0 || shiftLampMode == 3)
                    && shiftLampMin >= 1 && shiftLampMax <= 61 && shiftLampMin <= shiftLampMax;
            int[][] clock = {{10, 0xC0}, {10, 0x30}, {10, 0x0C}, {10, 0x03}, {11, 0xC0}, {11, 0x30}};
            // the clock may be set only when the gate allows it and the field's own flag is 0
            for (int i = 0; i < 6; i++) {
                clockSettable[i] = clockGate == 0 && bits(v, clock[i][0], clock[i][1]) == 0;
            }
            meterIndication = bits(v, 11, 0x0F);
            navi = bits(v, 12, 0xF0);
            indicatorSubcaps[0] = bits(v, 12, 0x0C);
            indicatorSubcaps[1] = bits(v, 12, 0x03);
            indicatorSubcaps[2] = bits(v, 13, 0xC0);
            indicatorSubcaps[3] = bits(v, 13, 0x30);
            turnByTurn = navi != 15;
            boolean noIcons = meterIndication == 15;
            for (int s : indicatorSubcaps) noIcons &= s == 3;
            statusIcons = !noIcons;
        }

        public boolean clockSettable() {
            for (boolean b : clockSettable) if (!b) return false;
            return true;
        }
    }

    /**
     * Rideology's "retry" check on config frames: a block is there but its flag byte says the data is
     * not ready yet (bit 0 clear). The bridge then asks for the config again.
     */
    static boolean retryRequired(byte[] v, int a, int b, int flag) {
        return (u8(v, a) != 0xFF || u8(v, b) != 0xFF) && (u8(v, flag) & 1) == 0;
    }

    /** One 2-bit (or 4-bit) support flag of a config frame. */
    public static final class Flag {
        public final String name;
        final int index, mask;

        Flag(String name, int index, int mask) {
            this.name = name;
            this.index = index;
            this.mask = mask;
        }
    }

    /** 0x40: which vehicle-info and riding-log values this bike has (Ble5Value.i). 0 or 1: yes, 3: no. */
    public static final class InfoConfig extends Frame {
        public static final Flag TOTAL_DISTANCE = new Flag("total distance", 7, 0xC0);
        public static final Flag TOTAL_FUEL = new Flag("total fuel consumed", 7, 0x30);
        public static final Flag FUEL_RATE = new Flag("engine fuel rate", 7, 0x0C);
        public static final Flag ECU_BATTERY = new Flag("ECU battery", 7, 0x03);
        public static final Flag WATER_TEMP = new Flag("water temperature", 8, 0xC0);
        public static final Flag OIL_TEMP = new Flag("oil temperature", 8, 0x30);
        public static final Flag INTAKE_TEMP = new Flag("intake air temperature", 8, 0x0C);
        public static final Flag BOOST_TEMP = new Flag("boost temperature", 8, 0x03);
        public static final Flag BOOST_PRESSURE = new Flag("boost pressure", 9, 0xC0);
        public static final Flag FUEL_INJECTION = new Flag("fuel injection", 9, 0x30);
        public static final Flag WHEEL_SPEED = new Flag("wheel speed", 9, 0x0C);
        public static final Flag ENGINE_SPEED = new Flag("engine speed", 9, 0x03);
        public static final Flag GEAR = new Flag("gear", 10, 0xF0);
        public static final Flag THROTTLE = new Flag("throttle", 10, 0x0C);
        public static final Flag ACCELERATION = new Flag("acceleration", 10, 0x03);
        public static final Flag LEAN = new Flag("lean angle", 11, 0xC0);
        public static final Flag WHEELIE = new Flag("wheelie flag", 11, 0x30);
        public static final Flag WHEELIE_ANGLE = new Flag("wheelie angle", 11, 0x0C);
        public static final Flag TCS_HB = new Flag("TCS level high", 11, 0x03);
        public static final Flag TCS_LB = new Flag("TCS level low", 12, 0xC0);
        public static final Flag RIDER_TORQUE = new Flag("rider torque request", 12, 0x30);
        public static final Flag ENGINE_TORQUE_REQUEST = new Flag("engine torque request", 12, 0x0C);
        public static final Flag ENGINE_TORQUE = new Flag("engine torque", 12, 0x03);
        public static final Flag ODOMETER = new Flag("odometer", 27, 0xC0);
        public static final Flag FUEL_GAUGE = new Flag("fuel gauge", 27, 0x30);
        public static final Flag AVG_MILEAGE = new Flag("average mileage", 27, 0x0C);
        public static final Flag METER_BATTERY = new Flag("meter battery", 27, 0x03);
        public static final Flag TRIP_A = new Flag("trip A", 28, 0xC0);
        public static final Flag TRIP_B = new Flag("trip B", 28, 0x30);
        public static final Flag AVG_SPEED = new Flag("average speed", 28, 0x0C);
        public static final Flag OUTSIDE_TEMP = new Flag("outside temperature", 28, 0x03);
        public static final Flag RANGE_SYMBOL = new Flag("range symbol", 29, 0xC0);
        public static final Flag RANGE = new Flag("range", 29, 0x30);
        public static final Flag FUEL_CONSUMPTION = new Flag("fuel consumption", 29, 0x0C);
        public static final Flag TOTAL_TIME = new Flag("total time", 29, 0x03);
        public static final Flag INSTANT_FUEL = new Flag("instant fuel consumption", 31, 0x0C);
        /** The meter keeps the largest lean of the trip (0x41 bytes 53/54); 0 = yes (Ble5Value/i.java K, L). */
        public static final Flag MAX_LEAN_LEFT = new Flag("max lean left", 32, 0xC0);
        public static final Flag MAX_LEAN_RIGHT = new Flag("max lean right", 32, 0x30);
        /**
         * Whether the 0x41 frame carries a "this value is valid" bit for a value (bytes 33-34, one bit each,
         * 0 = it does; Ble5Value/i.java M-V). When it does, a value whose bit is set is not current.
         */
        public static final Flag ODOMETER_VALID = new Flag("odometer valid bit", 33, 0x80);
        public static final Flag METER_BATTERY_VALID = new Flag("meter battery valid bit", 33, 0x40);
        public static final Flag AVG_MILEAGE_VALID = new Flag("average mileage valid bit", 33, 0x20);
        public static final Flag INSTANT_FUEL_VALID = new Flag("instant fuel valid bit", 33, 0x10);
        public static final Flag TRIP_A_VALID = new Flag("trip A valid bit", 33, 0x08);
        public static final Flag TRIP_B_VALID = new Flag("trip B valid bit", 33, 0x04);
        public static final Flag AVG_SPEED_VALID = new Flag("average speed valid bit", 33, 0x02);
        public static final Flag RANGE_VALID = new Flag("range valid bit", 34, 0x40);
        public static final Flag TOTAL_TIME_VALID = new Flag("total time valid bit", 34, 0x20);
        public static final Flag[] ALL = {TOTAL_DISTANCE, TOTAL_FUEL, FUEL_RATE, ECU_BATTERY, WATER_TEMP, OIL_TEMP,
                INTAKE_TEMP, BOOST_TEMP, BOOST_PRESSURE, FUEL_INJECTION, WHEEL_SPEED, ENGINE_SPEED, GEAR, THROTTLE,
                ACCELERATION, LEAN, WHEELIE, WHEELIE_ANGLE, TCS_HB, TCS_LB, RIDER_TORQUE, ENGINE_TORQUE_REQUEST,
                ENGINE_TORQUE, ODOMETER, FUEL_GAUGE, AVG_MILEAGE, METER_BATTERY, TRIP_A, TRIP_B, AVG_SPEED,
                OUTSIDE_TEMP, RANGE_SYMBOL, RANGE, FUEL_CONSUMPTION, TOTAL_TIME, INSTANT_FUEL, MAX_LEAN_LEFT,
                MAX_LEAN_RIGHT};

        public final boolean valid;

        InfoConfig(byte[] v) {
            super(v);
            valid = v.length >= 35 && !(retryRequired(v, 5, 6, 14) || retryRequired(v, 15, 16, 24)
                    || retryRequired(v, 25, 26, 34));
        }

        /** The flag's value (0..3, 0..15 for the gear), -1 past the end. */
        public int flag(Flag f) {
            return bits(raw, f.index, f.mask);
        }

        /** The bike has this value. */
        public boolean supported(Flag f) {
            int x = flag(f);
            return x == 0 || x == 1;
        }
    }

    /** True unless the config is known and says the bike does not have the value. */
    static boolean has(InfoConfig cfg, Flag f) {
        return cfg == null || cfg.supported(f);
    }

    /** The config is known and its flag is exactly 0 (the strict reading Rideology uses for these). */
    static boolean zero(InfoConfig cfg, Flag f) {
        return cfg != null && cfg.flag(f) == 0;
    }

    /**
     * A value's "valid" bit: when the config says the bike sends one ({@code f} is 0), the bit must be 0
     * for the value to count. Bikes without the bit keep their value (the placeholders still catch it).
     */
    static boolean validBit(byte[] v, int i, int bit, InfoConfig cfg, Flag f) {
        if (!zero(cfg, f)) return true;
        return in(v, i) && (u8(v, i) & (1 << bit)) == 0;
    }

    /** 0x47: which vehicle settings this bike has (Ble5Value.y). */
    public static final class TuningConfig extends Frame {
        public static final Flag ECU = new Flag("ECU tuning", 7, 0x30);
        public static final Flag KTRC = new Flag("KTRC", 8, 0xC0);
        public static final Flag RIDING_MODE = new Flag("riding mode", 8, 0x30);
        public static final Flag KQS = new Flag("KQS", 8, 0x0C);
        public static final Flag KEBC = new Flag("KEBC", 8, 0x03);
        public static final Flag POWER = new Flag("power mode", 9, 0xC0);
        public static final Flag METER = new Flag("meter tuning", 17, 0xC0);
        public static final Flag KECS_PRELOAD = new Flag("KECS preload", 19, 0x30);
        public static final Flag KECS_MODE = new Flag("KECS mode", 19, 0x0C);
        public static final Flag KECS_LOAD = new Flag("KECS load", 19, 0x03);
        public static final Flag KECS_FR_COM = new Flag("KECS front compression", 20, 0xC0);
        public static final Flag KECS_FR_TEN = new Flag("KECS front rebound", 20, 0x30);
        public static final Flag KECS_RR_COM = new Flag("KECS rear compression", 20, 0x0C);
        public static final Flag KECS_RR_TEN = new Flag("KECS rear rebound", 20, 0x03);
        public static final Flag[] ALL = {ECU, KTRC, RIDING_MODE, KQS, KEBC, POWER, METER, KECS_PRELOAD, KECS_MODE,
                KECS_LOAD, KECS_FR_COM, KECS_FR_TEN, KECS_RR_COM, KECS_RR_TEN};

        TuningConfig(byte[] v) {
            super(v);
        }

        public int flag(Flag f) {
            return bits(raw, f.index, f.mask);
        }

        /** Supported values as Rideology reads them: KTRC 0-2, ECU/meter/riding mode/power 0-1, the rest 0. */
        public boolean supported(Flag f) {
            int x = flag(f);
            if (f == KTRC) return x >= 0 && x <= 2;
            if (f == ECU || f == METER || f == RIDING_MODE || f == POWER) return x == 0 || x == 1;
            return x == 0;
        }

        public boolean any() {
            for (Flag f : ALL) if (f != ECU && f != METER && supported(f)) return true;
            return false;
        }
    }

    static boolean has(TuningConfig cfg, Flag f) {
        return cfg == null || cfg.supported(f);
    }

    // ------------------------------------------------------------------ vehicle data

    /** 0x41: snapshot of the meter (Ble5Value MC info), answer to {@code 41 00 00}. 85 bytes. */
    public static final class VehicleInfo extends Frame {
        public final double totalFuelL, ecuBatteryV, avgMileage, meterBatteryV, tripAKm, tripBKm, outsideC,
                fuelConsumption;
        /** Odometer in km (whole km). */
        public final int odometerKm;
        /** Fuel gauge bars as on the dash. */
        public final int fuelBars;
        public final int avgSpeedKmh, rangeSymbol, rangeKm, totalTime;
        /** The largest lean of the trip the meter shows, degrees each way; -1 when the bike has none. */
        public final int maxLeanLeft, maxLeanRight;

        /**
         * Values the meter fills with an all-ones placeholder while it has none (Rideology drops these:
         * qe/b.java), or marks with their "valid" bit, are left out rather than shown as numbers.
         */
        VehicleInfo(byte[] v, InfoConfig c) {
            super(v);
            totalFuelL = in(v, 10) && has(c, InfoConfig.TOTAL_FUEL) && be32(v, 7) != 0xFFFFFFFFL
                    ? be32(v, 7) * 0.01 : Double.NaN;
            ecuBatteryV = in(v, 14) && has(c, InfoConfig.ECU_BATTERY) && u8(v, 14) != 0xFF
                    ? u8(v, 14) * 20.0 / 256.0 : Double.NaN;
            int odo = in(v, 29) && has(c, InfoConfig.ODOMETER) ? (u8(v, 27) & 0x0F) << 16 | be16(v, 28) : -1;
            odometerKm = odo != 0xFFFFF && validBit(v, 27, 6, c, InfoConfig.ODOMETER_VALID) ? odo : -1;
            int bars = in(v, 30) && has(c, InfoConfig.FUEL_GAUGE) ? u8(v, 30) & 0x0F : -1;
            fuelBars = bars == 0x0F ? -1 : bars;
            int mileage = in(v, 33) && has(c, InfoConfig.AVG_MILEAGE) ? (u8(v, 32) & 0x03) << 8 | u8(v, 33) : -1;
            avgMileage = mileage >= 0 && mileage != 0x3FF && validBit(v, 27, 4, c, InfoConfig.AVG_MILEAGE_VALID)
                    ? mileage * 0.1 : Double.NaN;
            int meterBattery = in(v, 34) && has(c, InfoConfig.METER_BATTERY) ? u8(v, 34) : -1;
            meterBatteryV = meterBattery >= 0 && meterBattery != 0xFF
                    && validBit(v, 27, 5, c, InfoConfig.METER_BATTERY_VALID) ? meterBattery * 0.1 : Double.NaN;
            int tripA = in(v, 39) && has(c, InfoConfig.TRIP_A) ? (u8(v, 37) & 0x01) << 16 | be16(v, 38) : -1;
            tripAKm = tripA >= 0 && tripA != 0x1FFFF && validBit(v, 37, 7, c, InfoConfig.TRIP_A_VALID)
                    ? tripA * 0.1 : Double.NaN;
            int tripB = in(v, 42) && has(c, InfoConfig.TRIP_B) ? (u8(v, 40) & 0x01) << 16 | be16(v, 41) : -1;
            tripBKm = tripB >= 0 && tripB != 0x1FFFF && validBit(v, 37, 6, c, InfoConfig.TRIP_B_VALID)
                    ? tripB * 0.1 : Double.NaN;
            int avgSpeed = in(v, 43) && has(c, InfoConfig.AVG_SPEED) ? u8(v, 43) : -1;
            avgSpeedKmh = avgSpeed != 0xFF && validBit(v, 37, 5, c, InfoConfig.AVG_SPEED_VALID) ? avgSpeed : -1;
            outsideC = in(v, 44) && has(c, InfoConfig.OUTSIDE_TEMP) && u8(v, 44) != 0xFF ? u8(v, 44) - 60 : Double.NaN;
            rangeSymbol = in(v, 47) && has(c, InfoConfig.RANGE_SYMBOL) ? (u8(v, 47) & 0x30) >> 4 : -1;
            int range = in(v, 48) && has(c, InfoConfig.RANGE) ? (u8(v, 47) & 0x03) << 8 | u8(v, 48) : -1;
            rangeKm = range != 0x3FF && validBit(v, 47, 7, c, InfoConfig.RANGE_VALID) ? range : -1;
            int consumption = in(v, 50) && has(c, InfoConfig.FUEL_CONSUMPTION) ? (u8(v, 49) & 0x03) << 8 | u8(v, 50) : -1;
            fuelConsumption = consumption >= 0 && consumption != 0x3FF ? consumption * 0.1 : Double.NaN;
            int time = in(v, 52) && has(c, InfoConfig.TOTAL_TIME) ? (u8(v, 51) & 0x1F) << 8 | u8(v, 52) : -1;
            totalTime = time != 0x1FFF && validBit(v, 47, 6, c, InfoConfig.TOTAL_TIME_VALID) ? time : -1;
            // max lean: bytes 53/54 (7 bits, 127 = none), gated by 0x40 byte 32 and their bits in byte 47
            maxLeanLeft = lean(v, 53, 3, c, InfoConfig.MAX_LEAN_LEFT);
            maxLeanRight = lean(v, 54, 2, c, InfoConfig.MAX_LEAN_RIGHT);
        }

        private static int lean(byte[] v, int i, int validBitOf47, InfoConfig c, Flag f) {
            if (!in(v, i) || !zero(c, f) || !in(v, 47) || (u8(v, 47) & (1 << validBitOf47)) != 0) return -1;
            int deg = u8(v, i) & 0x7F;
            return deg == 127 ? -1 : deg;
        }
    }

    /** Byte {@code i} exists and its block is present. */
    private static boolean in(byte[] v, int i) {
        return inPresentBlock(v, i);
    }

    /** 0x45: riding log extension: temperatures, distance, TPMS. The meter sends it by itself once asked. 55 bytes. */
    public static final class LogExt extends Frame {
        public final double totalDistanceKm, fuelRate, waterC, oilC, intakeC, instantFuel, tyreFrontKpa, tyreRearKpa;
        /** TPMS warning bits: 0 front pressure drop, 1 rear pressure drop, 2 front sensor battery, 3 rear; -1 none. */
        public final int tpmsWarnings;

        LogExt(byte[] v, InfoConfig c) {
            super(v);
            long dist = in(v, 10) && has(c, InfoConfig.TOTAL_DISTANCE) ? be32(v, 7) : -1;
            totalDistanceKm = dist >= 0 && dist != 0xFFFFFFFFL ? dist * 0.1 : Double.NaN;
            fuelRate = in(v, 12) && has(c, InfoConfig.FUEL_RATE) && be16(v, 11) != 0xFFFF ? be16(v, 11) * 0.02 : Double.NaN;
            waterC = temp(v, 17, 40, c, InfoConfig.WATER_TEMP);
            oilC = temp(v, 19, 60, c, InfoConfig.OIL_TEMP);
            intakeC = temp(v, 20, 40, c, InfoConfig.INTAKE_TEMP);
            instantFuel = in(v, 28) && has(c, InfoConfig.INSTANT_FUEL) && u8(v, 27) != 0xFF
                    ? ((u8(v, 27) & 0x03) << 8 | u8(v, 28)) * 0.1 : Double.NaN;
            // TPMS bytes 45-49: present unless bytes 45 or 46 are FF, as Rideology reads them (qe/b.java);
            // an ordinary 05 block marker there must not hide the pressures. Untested on a bike.
            boolean tpms = v.length > 49 && u8(v, 45) != 0xFF && u8(v, 46) != 0xFF;
            tyreFrontKpa = tpms && u8(v, 47) != 0xFF ? u8(v, 47) * 1.373 + 100 : Double.NaN;
            tyreRearKpa = tpms && u8(v, 48) != 0xFF ? u8(v, 48) * 1.373 + 100 : Double.NaN;
            tpmsWarnings = tpms ? u8(v, 49) & 0x0F : -1;
        }

        private static double temp(byte[] v, int i, int offset, InfoConfig c, Flag f) {
            if (!in(v, i) || !has(c, f) || u8(v, i) == 0xFF) return Double.NaN;
            return u8(v, i) - offset;
        }
    }

    /**
     * 0x4A: live riding data (speed, rpm, gear, throttle, lean…), streamed by the meter without being
     * asked for. Which of the two scalings per value applies is decided by that value's 0x40 nibble
     * (Rideology reads the nibble and branches on it, qe/b.java:91-178) — so the values below are
     * already the right ones; the other reading of the same bytes is kept for the debug card.
     */
    public static final class Telemetry extends Frame {
        /** Wheel speed in km/h with the scaling the bike's 0x40 nibble selects, NaN when absent. */
        public final double speedKmh;
        /** Engine speed in rpm with the selected scaling, -1 when absent. */
        public final int rpm;
        /** Gear: 0 neutral, 1..6, -1 when absent. */
        public final int gear;
        /** Throttle in % (rider request or mechanical, per the nibble), NaN when absent. */
        public final double throttlePct;
        public final double fuelInjection;
        public final double accelG;
        /** Lean in degrees, + right / - left (sign to verify on the bike). */
        public final double leanDeg;
        public final int wheelie;
        public final double wheelieDeg;
        public final int tcsHigh, tcsLow;
        public final double brakeFrontBar;
        /** ABS bits (b37), -1 when absent: 0x10 inspection mode, 0x0C system error, 0x01 front, 0x02 rear active. */
        public final int abs;
        /** The other reading of the same bytes, for comparing with the dash (debug card). */
        public final double speedKmhAlt;
        public final int rpmAlt;
        /**
         * Torque the rider asks for, the engine control asks for, and the engine gives (bytes 27-32), in
         * the bike's own unit (Rideology does not name one), -1 when absent.
         */
        public final int riderTorque, engineTorqueRequest, engineTorque;
        /** Raw brake pressures (bytes 38, 40, 41; Rideology keeps them unscaled), -1 when absent. */
        public final int brakeFrontMaster, brakeRearMaster, brakeRearCaliper;
        /** ABS target (byte 47 bits 7-5), ABS indicator (bits 2-0), switching info (byte 48 bit 0), -1 absent. */
        public final int absTarget, absIndicator, absSwitching;
        /** Electronic suspension: damping value (byte 77) and status bits (byte 78), -1 when absent. */
        public final int damping, suspCalibration, suspStatus, suspEcu;
        /** Cruise control: working (byte 87 bit 1), mode (bit 0), set speed (byte 88, km/h), -1 when absent. */
        public final int cruiseOn, cruiseMode, cruiseKmh;

        Telemetry(byte[] v, InfoConfig c) {
            super(v);
            // values sit in 10-byte blocks like the other frames; a block whose header is FF is absent
            boolean blk1 = blockOk(v, 5), blk2 = blockOk(v, 15);
            int speedMode = mode(c, InfoConfig.WHEEL_SPEED);
            boolean speedOn = blk1 && speedMode >= 0;
            double raw9 = ((u8(v, 9) & 0x01) << 8) | u8(v, 10);
            double scaled = be16(v, 9) * 0.01220703125;
            speedKmh = speedOn ? (speedMode == 1 ? raw9 : scaled) : Double.NaN;
            speedKmhAlt = speedOn ? (speedMode == 1 ? scaled : raw9) : Double.NaN;
            int rpmMode = mode(c, InfoConfig.ENGINE_SPEED);
            boolean rpmOn = blk1 && rpmMode >= 0;
            int raw15 = ((u8(v, 11) & 0x7F) << 8) | u8(v, 12);
            int quarter = be16(v, 11) / 4;
            rpm = rpmOn ? (rpmMode == 1 ? quarter : raw15) : -1;
            rpmAlt = rpmOn ? (rpmMode == 1 ? raw15 : quarter) : -1;
            gear = blk1 && v.length > 13 && has(c, InfoConfig.GEAR) ? u8(v, 13) & 0x0F : -1;
            int thrMode = mode(c, InfoConfig.THROTTLE);
            throttlePct = blk1 && thrMode >= 0 ? u8(v, 14) * (thrMode == 1 ? 0.3921568627 : 0.78125) : Double.NaN;
            int fiMode = mode(c, InfoConfig.FUEL_INJECTION);
            fuelInjection = blk1 && fiMode >= 0 ? be16(v, 7) * 1e-4 * (fiMode == 1 ? 100.0 / 140.0 : 1) : Double.NaN;
            int accMode = mode(c, InfoConfig.ACCELERATION);
            double rawAcc = (((u8(v, 17) & 0x01) << 8) | u8(v, 18)) * 0.01;
            accelG = blk2 && accMode >= 0 ? rawAcc - (accMode == 1 ? 0 : 2.0) : Double.NaN;
            int leanMode = mode(c, InfoConfig.LEAN);
            leanDeg = blk2 && leanMode >= 0
                    ? leanMode == 1 ? (((u8(v, 19) & 0x0F) << 8) | u8(v, 20)) * 0.1
                    : (((u8(v, 19) & 0x07) << 8) | u8(v, 20)) * 0.1953125 - 100.0
                    : Double.NaN;
            wheelie = blk2 && v.length > 21 && has(c, InfoConfig.WHEELIE) ? u8(v, 21) & 0x03 : -1;
            int whMode = mode(c, InfoConfig.WHEELIE_ANGLE);
            wheelieDeg = blk2 && whMode >= 0
                    ? (((u8(v, 22) & 0x07) << 8) | u8(v, 23)) * (whMode == 1 ? 0.1 : 0.09765625) : Double.NaN;
            tcsHigh = blk2 && v.length > 24 && has(c, InfoConfig.TCS_HB) ? (u8(v, 24) & 0xF0) >> 4 : -1;
            tcsLow = blk2 && v.length > 24 && has(c, InfoConfig.TCS_LB) ? u8(v, 24) & 0x0F : -1;
            boolean absPresent = v.length > 37 && blockOk(v, 35);
            abs = absPresent ? u8(v, 37) : -1;
            brakeFrontBar = absPresent && v.length > 39 && u8(v, 39) != 0xFF ? u8(v, 39) * 0.1953125 : Double.NaN;
            brakeFrontMaster = absPresent && v.length > 38 ? u8(v, 38) : -1;
            brakeRearMaster = absPresent && v.length > 40 ? u8(v, 40) : -1;
            brakeRearCaliper = absPresent && v.length > 41 ? u8(v, 41) : -1;
            // torque: 0x40 nibble 0 = 14 bits × 0.2, 1 = 12 bits raw (qe/b.java)
            boolean blk3 = v.length > 32 && blockOk(v, 25);
            riderTorque = torque(v, 27, blk3, mode(c, InfoConfig.RIDER_TORQUE));
            engineTorqueRequest = torque(v, 29, blk3, mode(c, InfoConfig.ENGINE_TORQUE_REQUEST));
            engineTorque = torque(v, 31, blk3, mode(c, InfoConfig.ENGINE_TORQUE));
            boolean blk5 = v.length > 48 && blockOk(v, 45);
            absTarget = blk5 ? (u8(v, 47) & 0xE0) >> 5 : -1;
            absIndicator = blk5 ? u8(v, 47) & 0x07 : -1;
            absSwitching = blk5 ? u8(v, 48) & 0x01 : -1;
            boolean blk8 = v.length > 78 && blockOk(v, 75);
            damping = blk8 ? u8(v, 77) : -1;
            suspCalibration = blk8 ? (u8(v, 78) & 0x04) >> 2 : -1;
            suspStatus = blk8 ? (u8(v, 78) & 0x02) >> 1 : -1;
            suspEcu = blk8 ? u8(v, 78) & 0x01 : -1;
            boolean blk9 = v.length > 87 && blockOk(v, 85);
            cruiseOn = blk9 ? (u8(v, 87) & 0x02) >> 1 : -1;
            cruiseMode = blk9 ? u8(v, 87) & 0x01 : -1;
            cruiseKmh = blk9 && v.length > 88 ? u8(v, 88) : -1;
        }

        private static int torque(byte[] v, int i, boolean block, int mode) {
            if (!block || mode < 0) return -1;
            return mode == 1 ? ((u8(v, i) & 0x0F) << 8) | u8(v, i + 1)
                    : (int) ((((u8(v, i) & 0x3F) << 8) | u8(v, i + 1)) * 0.2);
        }

        /** The block at {@code offset} has a header (marker and id not FF). */
        private static boolean blockOk(byte[] v, int offset) {
            return v.length > offset + 1 && u8(v, offset) != 0xFF && u8(v, offset + 1) != 0xFF;
        }

        /** The 0x40 nibble of a value: 0/1 = present and selects the scaling, 2/3 = absent, -1 unknown. */
        private static int mode(InfoConfig c, Flag f) {
            int x = c == null ? -1 : c.flag(f);
            return x >= 0 && x <= 1 ? x : -1;
        }
    }

    /** 0x4B: suspension stroke and IMU axes (all FF on bikes without them). */
    public static final class ImuSusp extends Frame {
        public final double rearStrokeMm, frontStrokeMm, rearStrokeSpeed, frontStrokeSpeed;
        public final double axG, ayG, azG;
        /**
         * The three rotation rates next to the axes (Rideology's "psi p1", "p3", "p2" in this order, ±163.84; which is roll,
         * pitch or yaw is not known yet), NaN when absent.
         */
        public final double rate1, rate2, rate3;
        /** Filtered front and rear wheel speed, km/h, NaN when absent. */
        public final double frontWheelKmh, rearWheelKmh;
        /** Traction control levels (byte 51 bits 7-5, 4-2), quick shifter down/up (bits 1, 0), wheelie (52 bit 0); -1 absent. */
        public final int tcsHigh, tcsLow, kqsDown, kqsUp, wheelie;

        ImuSusp(byte[] v) {
            super(v);
            boolean susp = present(v, 5, 6, 14);
            rearStrokeMm = susp ? be16(v, 7) * 0.015625 : Double.NaN;
            frontStrokeMm = susp ? be16(v, 9) * 0.015625 : Double.NaN;
            rearStrokeSpeed = susp ? be16(v, 11) * 0.001 : Double.NaN;
            frontStrokeSpeed = susp ? be16(v, 13) * 0.001 : Double.NaN;
            ayG = axis(v, 15, 16, 22, 21);
            axG = axis(v, 25, 26, 32, 31);
            azG = axis(v, 35, 36, 42, 41);
            rate1 = rate(v, 15, 16, 18, 17);
            rate2 = rate(v, 25, 26, 28, 27);
            rate3 = rate(v, 35, 36, 38, 37);
            // block 45: Rideology treats it as absent only when both header bytes are FF (qe/a.java)
            boolean blk5 = v.length > 46 && !(u8(v, 45) == 0xFF && u8(v, 46) == 0xFF);
            frontWheelKmh = blk5 && v.length > 48 ? be16(v, 47) * 400.0 / 32768.0 : Double.NaN;
            rearWheelKmh = blk5 && v.length > 50 ? be16(v, 49) * 400.0 / 32768.0 : Double.NaN;
            boolean b51 = blk5 && v.length > 51;
            tcsHigh = b51 ? (u8(v, 51) & 0xE0) >> 5 : -1;
            tcsLow = b51 ? (u8(v, 51) & 0x1C) >> 2 : -1;
            kqsDown = b51 ? (u8(v, 51) & 0x02) >> 1 : -1;
            kqsUp = b51 ? u8(v, 51) & 0x01 : -1;
            wheelie = blk5 && v.length > 52 ? u8(v, 52) & 0x01 : -1;
        }

        /** Centered rotation rate (hi byte after lo byte), span 163.84. */
        private static double rate(byte[] v, int a, int b, int hi, int lo) {
            if (!present(v, a, b, Math.max(hi, lo))) return Double.NaN;
            int x = u8(v, hi) << 8 | u8(v, lo);
            return x == 0 ? Double.NaN : x * 163.84 / 32768.0 - 163.84;
        }

        private static boolean present(byte[] v, int a, int b, int last) {
            return v.length > last && u8(v, a) != 0xFF && u8(v, b) != 0xFF;
        }

        /** Centered axis (hi byte after lo byte), span 4.1768 g. */
        private static double axis(byte[] v, int a, int b, int hi, int lo) {
            if (!present(v, a, b, Math.max(hi, lo))) return Double.NaN;
            int x = u8(v, hi) << 8 | u8(v, lo);
            return x == 0 ? Double.NaN : x * 4.1768 / 32768.0 - 4.1768;
        }
    }

    // ------------------------------------------------------------------ settings

    /** 0x1B: meter clock and shift lamp, answer to the general settings read (block 0x0A). */
    public static final class GeneralSettings extends Frame {
        public final int result;
        /** Shift lamp: status (bits 7-6) and level 1..61 (= (level - 1) × 250 + 3000 rpm), -1 unknown. */
        public final int shiftLampStatus, shiftLampLevel;
        public final int day, month, year, hour, minute, second;

        GeneralSettings(byte[] v, GeneralCaps caps) {
            super(v);
            boolean b = blockPresent(v, 5) && v.length >= 15;
            result = b ? u8(v, 7) : -1;
            int shift = b ? u8(v, 8) : 0xFF;
            boolean lamp = shift != 0xFF && (caps == null || caps.shiftLampSettable);
            shiftLampStatus = lamp ? shift >> 6 & 3 : -1;
            int level = shift & 0x3F;
            shiftLampLevel = lamp && level >= 1 && level <= 61 ? level : -1;
            day = field(b, v, 9, 0x3F, 1, 31, caps, 0);
            month = field(b, v, 10, 0x0F, 1, 12, caps, 1);
            int y = b ? u8(v, 11) : 0xFF;
            year = y != 0xFF && (caps == null || caps.clockSettable[2]) ? 2000 + y : -1;
            hour = field(b, v, 12, 0x1F, 0, 23, caps, 3);
            minute = field(b, v, 13, 0x3F, 0, 59, caps, 4);
            second = field(b, v, 14, 0x3F, 0, 59, caps, 5);
        }

        private static int field(boolean b, byte[] v, int i, int mask, int min, int max, GeneralCaps caps, int clockField) {
            if (!b || (caps != null && !caps.clockSettable[clockField])) return -1;
            int x = u8(v, i) & mask;
            return x >= min && x <= max ? x : -1;
        }

        public int shiftLampRpm() {
            return shiftLampLevel < 0 ? -1 : (shiftLampLevel - 1) * 250 + 3000;
        }
    }

    /**
     * 0x48: the bike's ride settings (block 0x09). Numbers as the bike sends them; their names (which
     * riding mode is Sport/Road/Rain/Rider) are still to be confirmed. {@link #raw} is kept so a change
     * can be written back with every other byte exactly as read.
     */
    public static final class VehicleSettings extends Frame {
        public final int meterCapability, ecuCapability;
        public final int ktrc, ridingMode, kqs, kebc, power;
        public final int kecsPreload, kecsMode, kecsLoad, kecsFrontCom, kecsFrontTen, kecsRearCom, kecsRearTen;

        VehicleSettings(byte[] v, TuningConfig t) {
            super(v);
            boolean ok = v.length >= 20;
            meterCapability = ok && has(t, TuningConfig.METER) && u8(v, 7) != 0xFF ? u8(v, 7) : -1;
            ecuCapability = ok && has(t, TuningConfig.ECU) && u8(v, 17) != 0xFF ? u8(v, 17) : -1;
            boolean b18 = ok && u8(v, 18) != 0xFF;
            ktrc = b18 && has(t, TuningConfig.KTRC) ? (u8(v, 18) & 0x78) >> 3 : -1;
            ridingMode = b18 && has(t, TuningConfig.RIDING_MODE) ? u8(v, 18) & 0x07 : -1;
            boolean b19 = ok && u8(v, 19) != 0xFF;
            kqs = b19 && has(t, TuningConfig.KQS) ? (u8(v, 19) & 0x60) >> 5 : -1;
            kebc = b19 && has(t, TuningConfig.KEBC) ? (u8(v, 19) & 0x0C) >> 2 : -1;
            power = b19 && has(t, TuningConfig.POWER) ? u8(v, 19) & 0x03 : -1;
            boolean b10 = ok && u8(v, 10) != 0xFF;
            kecsPreload = b10 && has(t, TuningConfig.KECS_PRELOAD) ? (u8(v, 10) & 0x38) >> 3 : -1;
            kecsMode = b10 && has(t, TuningConfig.KECS_MODE) ? u8(v, 10) & 0x07 : -1;
            kecsLoad = ok && u8(v, 11) != 0xFF && has(t, TuningConfig.KECS_LOAD) ? u8(v, 11) & 0x0F : -1;
            boolean b12 = ok && u8(v, 12) != 0xFF;
            kecsFrontCom = b12 && has(t, TuningConfig.KECS_FR_COM) ? (u8(v, 12) & 0xF0) >> 4 : -1;
            kecsFrontTen = b12 && has(t, TuningConfig.KECS_FR_TEN) ? u8(v, 12) & 0x0F : -1;
            boolean b13 = ok && u8(v, 13) != 0xFF;
            kecsRearCom = b13 && has(t, TuningConfig.KECS_RR_COM) ? (u8(v, 13) & 0xF0) >> 4 : -1;
            kecsRearTen = b13 && has(t, TuningConfig.KECS_RR_TEN) ? u8(v, 13) & 0x0F : -1;
        }
    }

    // ------------------------------------------------------------------ maintenance

    /** 0x1D: which maintenance reminders the meter keeps (Ble5Value.u). */
    public static final class ServiceConfig extends Frame {
        /** Per reminder ({@link ServiceIndicator#KAWASAKI}, RIDER, OIL): capability and the date/distance flags. */
        private static final int[][] LAYOUT = {
                // capability, notify, month, day, year, distance: {index, mask}
                {7, 0xC0, 8, 0xC0, 8, 0x30, 8, 0x0C, 8, 0x03, 9, 0x30},
                {7, 0x30, 9, 0x0C, 9, 0x03, 10, 0xC0, 10, 0x30, 10, 0x03},
                {7, 0x0C, 11, 0xC0, 11, 0x30, 11, 0x0C, 11, 0x03, 12, 0x30},
        };
        public final boolean valid;

        ServiceConfig(byte[] v) {
            super(v);
            // unlike 0x1A/0x40/0x47 the maintenance config has no "not ready" bit: it counts when its
            // block is there (Ble5Value.u.java:100-101)
            valid = v.length >= 15 && blockPresent(v, 5);
        }

        /** Fields of a reminder in {@link #has}: capability, notify, month, day, year, distance. */
        public static final int CAPABILITY = 0, NOTIFY = 1, MONTH = 2, DAY = 3, YEAR = 4, DISTANCE = 5;

        /** The meter keeps this field of reminder {@code which} (capability: 0 or 1; the others: 0). */
        public boolean has(int which, int field) {
            int[] l = LAYOUT[which];
            int x = bits(raw, l[field * 2], l[field * 2 + 1]);
            return field == CAPABILITY ? x == 0 || x == 1 : x == 0;
        }

        /** The meter keeps reminder {@code which} with all its fields. */
        public boolean supported(int which) {
            for (int f = CAPABILITY; f <= DISTANCE; f++) if (!has(which, f)) return false;
            return true;
        }
    }

    /** 0x1E: the maintenance reminders: Kawasaki service, the rider's own, oil change. */
    public static final class ServiceIndicator extends Frame {
        public static final int KAWASAKI = 0, RIDER = 1, OIL = 2;
        public static final String[] NAMES = {"Kawasaki service", "Rider setting", "Oil change"};

        /** One reminder: due date and/or distance; -1 where not set. */
        public static final class Reminder {
            public final boolean supported;
            public final int notify, day, month, year, distanceKm;

            /** @param c the maintenance config; only a valid one hides fields (each by its own flag) */
            Reminder(byte[] v, int base, ServiceConfig c, int which) {
                ServiceConfig cfg = c != null && c.valid ? c : null;
                boolean b = blockPresent(v, base - 2) && v.length > base + 7;
                supported = b && (cfg == null || cfg.has(which, ServiceConfig.CAPABILITY));
                int n = b ? u8(v, base + 1) : 0xFF;
                notify = n != 0xFF && field(cfg, which, ServiceConfig.NOTIFY) ? n >> 4 : -1;
                int m = n & 0x0F;
                month = b && m >= 1 && m <= 12 && field(cfg, which, ServiceConfig.MONTH) ? m : -1;
                int d = b ? u8(v, base + 2) & 0x3F : 0;
                day = d >= 1 && d <= 31 && field(cfg, which, ServiceConfig.DAY) ? d : -1;
                int y = b ? u8(v, base + 3) : 0xFF;
                year = y != 0xFF && field(cfg, which, ServiceConfig.YEAR) ? 2000 + (y & 0x7F) : -1;
                distanceKm = b && u8(v, base + 5) != 0xFF && field(cfg, which, ServiceConfig.DISTANCE)
                        ? (u8(v, base + 5) & 0x0F) << 16 | be16(v, base + 6) : -1;
            }

            private static boolean field(ServiceConfig cfg, int which, int field) {
                return cfg == null || cfg.has(which, field);
            }

            public boolean hasDate() {
                return year > 0 && month > 0 && day > 0;
            }
        }

        public final Reminder[] reminders = new Reminder[3];

        ServiceIndicator(byte[] v, ServiceConfig c) {
            super(v);
            for (int i = 0; i < 3; i++) reminders[i] = new Reminder(v, 7 + i * 10, c, i);
        }
    }

    // ------------------------------------------------------------------ decoding

    /**
     * Decodes the frames of one connection. Remembers the config frames (0x40, 0x47, 0x1D, 0x1A) since
     * they decide which values of the later frames are real.
     */
    public static final class Decoder {
        public InfoConfig info;
        public TuningConfig tuning;
        public ServiceConfig service;
        public GeneralCaps caps;

        /** The decoded frame, or a plain {@link Frame} for types KawaNav does not decode; null for an empty one. */
        public Frame decode(byte[] v) {
            if (v == null || v.length == 0) return null;
            switch (v[0] & 0xFF) {
                case ACK: return new Ack(v);
                case STATUS: return new Status(v);
                case MODEL_INFO: return new ModelInfo(v);
                case GENERAL_CAPS: return caps = new GeneralCaps(v);
                case GENERAL_SETTINGS: return new GeneralSettings(v, caps);
                case INFO_CONFIG: return info = new InfoConfig(v);
                case TUNING_CONFIG: return tuning = new TuningConfig(v);
                case SERVICE_CONFIG: return service = new ServiceConfig(v);
                case VEHICLE_INFO: return new VehicleInfo(v, info);
                case RIDING_LOG_EXT: return new LogExt(v, info);
                case RIDING_LOG: return new Telemetry(v, info);
                case RIDING_LOG_HIGH: return new ImuSusp(v);
                case VEHICLE_SETTINGS: return new VehicleSettings(v, tuning);
                case SERVICE_INDICATOR: return new ServiceIndicator(v, service);
                default: return new Frame(v);
            }
        }
    }
}
