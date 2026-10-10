package io.github.darthbob.kawanav;

import java.util.Arrays;
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

    /** Battery percentage to Ble5Value.j, same thresholds as Rideology (lj/a.b). */
    public static int batteryLevel(float percent) {
        if (percent <= 0f) return 15;
        if (percent <= 10f) return 0;
        if (percent <= 30f) return 1;
        if (percent <= 70f) return 2;
        return 3;
    }

    /**
     * Payload-less requests Rideology queues right after connecting (ne/c.java case 2).
     * They ask the bike for model / vehicle info; none of them carry settings.
     */
    public static final byte[][] INIT_REQUESTS = {
            {0x03, 0, 0},
            {0x40, 0, 0},
            {0x1A, 0, 0},
            {0x1D, 0, 0},
            {0x47, 0, 0},
            {0x41, 0, 0},
            {0x45, 0, 0},
    };

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
