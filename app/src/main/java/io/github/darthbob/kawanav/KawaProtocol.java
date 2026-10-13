package io.github.darthbob.kawanav;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Kawasaki BLE5 meter protocol, as used by Rideology The App Motorcycle v2.11.13.
 * See PROTOCOL.md next to this project for where each value comes from.
 */
public final class KawaProtocol {
    private KawaProtocol() {}

    public static final UUID SERVICE = UUID.fromString("92faec07-c075-4b7c-a6c2-bbd1d1a150f5");
    public static final UUID WRITE_CHAR = UUID.fromString("acf1b15c-10f9-4942-a32d-f9e019b95402");
    public static final UUID[] NOTIFY_CHARS = {
            UUID.fromString("3aabbb34-eac0-40f5-9d50-3a1ee6787136"),
            UUID.fromString("02fad1bd-358e-441c-b296-fe874af38a7e"),
            UUID.fromString("5e119eba-35a7-4463-a7af-7fa40a302350"),
    };
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /** Navigation status (Ble5Value.n). */
    public static final String[] FLAG_NAMES = {"Navigating", "Rerouting", "Arrived", "No navigation"};

    /** Distance unit (Ble5Value.p). */
    public static final String[] UNIT_NAMES = {"km", "m", "mile", "ft", "yd"};

    public static final int TURN_NONE = 0xFF;

    /** Turn type (Ble5Value.o), index == id on the wire. */
    public static final String[] TURN_NAMES = {
            "Straight", "Left", "Right", "Sharp left", "Sharp right",
            "Slight left", "Slight right", "U-turn left", "U-turn right",
            "Fork left", "Fork right", "Fork middle",
            "Ramp on left", "Ramp on right", "Exit left", "Exit right",
            "Merge left", "Merge right",
            "Rbt CW exit 1", "Rbt CW exit 2", "Rbt CW exit 3", "Rbt CW exit 4",
            "Rbt CW exit 5", "Rbt CW exit 6", "Rbt CW exit 7", "Rbt CW exit 8",
            "Rbt CW exit 9", "Rbt CW exit 10", "Rbt CW exit 11", "Rbt CW exit 12",
            "Rbt CCW exit 1", "Rbt CCW exit 2", "Rbt CCW exit 3", "Rbt CCW exit 4",
            "Rbt CCW exit 5", "Rbt CCW exit 6", "Rbt CCW exit 7", "Rbt CCW exit 8",
            "Rbt CCW exit 9", "Rbt CCW exit 10", "Rbt CCW exit 11", "Rbt CCW exit 12",
            "Departure", "Destination",
            "Waypoint 1", "Waypoint 2", "Waypoint 3", "Waypoint 4",
            "Waypoint 5", "Waypoint 6", "Waypoint 7", "Waypoint 8",
            "Waypoint 9", "Waypoint 10", "Waypoint 11", "Waypoint 12",
    };

    public static final int MAX_DISTANCE = 0xFFFFFF;

    /** Turn-by-turn "NaviControlPoint" frame, 35 bytes (oh/b.java, case NAVI_CONTROL_POINT). */
    public static byte[] naviFrame(int flag, int turn, int unit, int distance) {
        int d = Math.max(0, Math.min(MAX_DISTANCE, distance));
        byte[] b = new byte[35];
        Arrays.fill(b, (byte) 0xFF);
        b[0] = 20;
        b[1] = 8;
        b[2] = 0;
        b[5] = 5;
        b[6] = 0x70;
        b[7] = (byte) ((flag & 0x0F) << 4);
        b[13] = (byte) ((unit & 0x0F) << 4);
        b[15] = 5;
        b[16] = 0x71;
        b[17] = (byte) turn;
        b[18] = (byte) (d >>> 16);
        b[19] = (byte) (d >>> 8);
        b[20] = (byte) d;
        return b;
    }

    /** Frame that tells the meter navigation has ended. */
    public static byte[] clearFrame() {
        return naviFrame(3, TURN_NONE, 0x0F, 0);
    }

    /**
     * Status icon frame, 15 bytes (oh/b.java, case METER_INDICATION). Rideology sends it every 5 s.
     * battery: Ble5Value.j (0 low .. 3 full, 15 n/a), voice: Ble5Value.m (0 idle),
     * headset: Ble5Value.k (0 off, 1 on), ridingLog: Ble5Value.l (0 off).
     */
    public static byte[] meterIndicationFrame(int battery, int voice, int headset, int ridingLog) {
        byte[] b = new byte[15];
        Arrays.fill(b, (byte) 0xFF);
        b[0] = 19;
        b[1] = 12;
        b[2] = 0;
        b[5] = 5;
        b[6] = 0;
        b[7] = (byte) (((battery & 0x0F) << 4) | (voice & 0x0F));
        b[8] = (byte) (((headset & 0x0F) << 4) | (ridingLog & 0x0F));
        return b;
    }

    /** Longest phone name the meter takes, in UTF-8 bytes. */
    public static final int PHONE_NAME_BYTES = 24;

    /**
     * Phone name frame, 35 bytes (ne/c.java case 20; Rideology sends Build.MODEL while connecting).
     * The UTF-8 name in up to three 8-byte parts, cut at a character so it fits 24 bytes, zero-filled.
     */
    public static byte[] phoneNameFrame(String name) {
        byte[] text = phoneNameBytes(name);
        byte[] b = new byte[35];
        b[0] = 11;
        b[1] = 32;
        b[2] = 0;
        b[3] = (byte) 0xFF;
        b[4] = (byte) 0xFF;
        for (int part = 0; part < 3 && part * 8 < text.length; part++) {
            b[5 + part * 10] = 5;
            b[6 + part * 10] = (byte) (part + 1);
            System.arraycopy(text, part * 8, b, 7 + part * 10, Math.min(8, text.length - part * 8));
        }
        return b;
    }

    /** The name as UTF-8, without characters beyond {@link #PHONE_NAME_BYTES}. */
    public static byte[] phoneNameBytes(String name) {
        String s = name;
        byte[] text = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        while (text.length > PHONE_NAME_BYTES) {
            s = s.substring(0, s.offsetByCodePoints(s.length(), -1));
            text = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        return text;
    }

    /** Battery level "n/a": the meter shows no phone battery icon. */
    public static final int BATTERY_NONE = 15;

    /** Battery percentage to Ble5Value.j, same thresholds as Rideology (lj/a.b). */
    public static int batteryLevel(float percent) {
        if (percent <= 0f) return BATTERY_NONE;
        if (percent <= 10f) return 0;
        if (percent <= 30f) return 1;
        if (percent <= 70f) return 2;
        return 3;
    }

    /**
     * Like {@link #batteryLevel(float)}, charging: Ble5Value.j CHARGING_SEGMENT_ON_0..3 (7..10).
     * Rideology itself only ever sends 7 (charging, no bars).
     */
    public static int batteryLevel(float percent, boolean charging) {
        int level = batteryLevel(percent);
        return charging && level != BATTERY_NONE ? 7 + level : level;
    }

    public static final int CAPABILITIES = 0x1A;
    /** Asks the meter what it can show; it answers with a {@link #CAPABILITIES} frame. */
    public static final byte[] CAPABILITIES_REQUEST = {CAPABILITIES, 0, 0};

    /** Payload-less read request {@code <id> 00 00}. */
    public static byte[] readRequest(int id) {
        return new byte[]{(byte) id, 0, 0};
    }

    /** A request frame of {@code length} bytes, FF-filled, with header {@code id, length - 3, counter, FF, FF}. */
    private static byte[] request(int id, int length, int counter) {
        byte[] b = new byte[length];
        Arrays.fill(b, (byte) 0xFF);
        b[0] = (byte) id;
        b[1] = (byte) (length - 3);
        b[2] = (byte) counter;
        return b;
    }

    /** Asks for one settings block: {@code 05 <block> 00} at {@code offset}. */
    private static void readBlock(byte[] b, int offset, int block) {
        b[offset] = 5;
        b[offset + 1] = (byte) block;
        b[offset + 2] = 0;
    }

    /** 0x1B: reads the general settings (meter clock, shift lamp), block 0x0A. 15 bytes. */
    public static byte[] generalSettingsRead() {
        byte[] b = request(BikeFrames.GENERAL_SETTINGS, 15, 0);
        readBlock(b, 5, 0x0A);
        return b;
    }

    /** 0x48: reads the vehicle settings (riding mode, power, KTRC, KQS…), block 0x09. 45 bytes. */
    public static byte[] vehicleSettingsRead() {
        byte[] b = request(BikeFrames.VEHICLE_SETTINGS, 45, 0);
        readBlock(b, 5, 0x09);
        return b;
    }

    /** 0x1E: reads the three maintenance reminders, blocks 0x0C, 0x0D, 0x0E. 45 bytes, counter 01 as captured. */
    public static byte[] serviceIndicatorRead() {
        byte[] b = request(BikeFrames.SERVICE_INDICATOR, 45, 1);
        readBlock(b, 5, 0x0C);
        readBlock(b, 15, 0x0D);
        readBlock(b, 25, 0x0E);
        return b;
    }

    /**
     * 0x08 "meter indication init" exactly as Rideology sends it: block 0A, command 08, enabled, then
     * 120, 1000, 200, 100 (meaning not known yet, possibly riding-log stream periods).
     */
    public static byte[] meterInitFrame() {
        return new byte[]{0x08, 0x0C, 0x00, (byte) 0xFF, (byte) 0xFF, 0x0A, 0x08, 0x01, 0x78, 0x03, (byte) 0xE8,
                0x00, (byte) 0xC8, 0x00, 0x64};
    }

    /** Connect sequence: off (KawaNav v0.3: only the capability request), read-only, or Rideology's full one. */
    public static final int INIT_NONE = 0, INIT_READ = 1, INIT_FULL = 2;

    /**
     * The requests sent after connecting, in Rideology's order (03, 40, 1A, 1D, 47, 0B, 41, 1B, 48, 1E,
     * 08, 45; 42 left out, it is for EV/HEV bikes). {@link #INIT_READ} only asks for information; the
     * full sequence also sends the phone name and the 0x08 init and starts the 0x45 riding-log frames.
     */
    public static List<BikeRequests.Request> startup(int level, String phoneModel, BikeRequests.Done done) {
        List<BikeRequests.Request> l = new java.util.ArrayList<>();
        int p = BikeRequests.STARTUP;
        if (level == INIT_NONE) {
            l.add(new BikeRequests.Request(CAPABILITIES_REQUEST, "capabilities request", BikeRequests.DATA_OR_ACK, p, done));
            return l;
        }
        boolean full = level >= INIT_FULL;
        l.add(new BikeRequests.Request(readRequest(BikeFrames.MODEL_INFO), "model info request", BikeRequests.DATA_OR_ACK, p, done));
        l.add(new BikeRequests.Request(readRequest(BikeFrames.INFO_CONFIG), "vehicle info config request", BikeRequests.DATA_OR_ACK, p, done));
        l.add(new BikeRequests.Request(CAPABILITIES_REQUEST, "capabilities request", BikeRequests.DATA_OR_ACK, p, done));
        l.add(new BikeRequests.Request(readRequest(BikeFrames.SERVICE_CONFIG), "maintenance config request", BikeRequests.DATA_OR_ACK, p, done));
        l.add(new BikeRequests.Request(readRequest(BikeFrames.TUNING_CONFIG), "tuning config request", BikeRequests.DATA_OR_ACK, p, done));
        if (full && phoneModel != null && !phoneModel.isEmpty()) {
            l.add(new BikeRequests.Request(phoneNameFrame(phoneModel), "phone name \"" + phoneModel + "\"", BikeRequests.ANY_ACK, p, done));
        }
        l.add(new BikeRequests.Request(readRequest(BikeFrames.VEHICLE_INFO), "vehicle info request", BikeRequests.DATA_OR_ACK, p, done));
        l.add(new BikeRequests.Request(generalSettingsRead(), "general settings read", BikeRequests.DATA_OR_ACK, p, done));
        l.add(new BikeRequests.Request(vehicleSettingsRead(), "vehicle settings read", BikeRequests.DATA_OR_ACK, p, done));
        l.add(new BikeRequests.Request(serviceIndicatorRead(), "maintenance read", BikeRequests.DATA_OR_ACK, p, done));
        if (full) {
            l.add(new BikeRequests.Request(meterInitFrame(), "meter indication init", BikeRequests.ACK_ONLY, p, done));
            l.add(new BikeRequests.Request(readRequest(BikeFrames.RIDING_LOG_EXT), "riding log ext request", BikeRequests.ACK_ONLY, p, done));
        }
        return l;
    }

    public static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (byte x : data) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("%02X", x & 0xFF));
        }
        return sb.toString();
    }

    /** Parses "14 08 00 ff" / "140800ff" into bytes, or returns null. */
    public static byte[] parseHex(String s) {
        String clean = s.replaceAll("[^0-9a-fA-F]", "");
        if (clean.isEmpty() || clean.length() % 2 != 0) return null;
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
