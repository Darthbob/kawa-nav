package io.github.darthbob.kawanav;

import java.util.Locale;

/**
 * Writing ride settings to the bike (riding mode, power, KTRC, KQS, profile name). Rideology uses frame
 * 0x48 with block 0x09 and byte 7 = 1 for the write (the read has 0 there), and reads the answer's byte 7
 * as the result (0x00 accepted, 0x10 "the meter is applying, wait", anything else rejected).
 *
 * <p>KawaNav is stricter than the app about when a write may be sent: the app only wants the bike
 * standing still (its own threshold is "under 5 km/h"), KawaNav also requires neutral and a closed
 * throttle, and stays in debug builds until this has been tried on a real bike.
 * Pure Java (no Android) so the frame and the result handling can be unit-tested.
 */
public final class SettingsWrite {
    private SettingsWrite() {}

    /** Values as the app labels them (ph/d.java); the wire encoding differs for KTRC, see {@link #ktrcWire}. */
    public static final String[] MODES = {"Sport", "Road", "Rain", "Rider"};
    public static final String[] POWER = {"Full", "Medium", "Low"};
    public static final String[] KTRC = {"OFF", "1", "2", "3", "4", "5"};
    public static final String[] ON_OFF = {"OFF", "ON"};

    /** Result codes of the answer's byte 7. */
    public static final int RESULT_OK = 0x00, RESULT_WAIT = 0x10;

    /**
     * KTRC as it goes on the wire: the app remaps its stored level (OFF = 1, levels 1-5 = 2-6) to
     * field values (oh/b.java:184-199). OFF becomes 10, level n becomes n; unknown becomes 0.
     */
    public static int ktrcWire(int level) {
        if (level == 0) return 10;              // "OFF" in the UI list
        if (level >= 1 && level <= 5) return level;
        return 0;
    }

    /** The KTRC field value back to a position in {@link #KTRC}. */
    public static int ktrcFromWire(int wire) {
        return wire == 10 ? 0 : wire >= 1 && wire <= 5 ? wire : -1;
    }

    /**
     * The 45-byte write frame. {@code ktrc} is the wire value (see {@link #ktrcWire}),
     * {@code kqs} 0/1, {@code kebc} 0/1 (0 when the bike has no KEBC), {@code power} 0..2 (Full..Low),
     * {@code mode} 0..3 (Sport/Road/Rain/Rider). {@code name} is the profile name (max 18 UTF-8 bytes).
     */
    public static byte[] frame(int mode, int power, int ktrc, int kqs, int kebc, String name) {
        byte[] b = new byte[45];
        java.util.Arrays.fill(b, (byte) 0xFF);
        b[0] = BikeFrames.VEHICLE_SETTINGS;
        b[1] = 0x2A;
        b[2] = 0x00;
        b[5] = 0x05;
        b[6] = 0x09;
        b[7] = 0x01; // write
        b[8] = (byte) (((ktrc & 0x0F) << 3) | (mode & 0x07));
        b[9] = (byte) (((kqs & 0x03) << 5) | ((kebc & 0x03) << 2) | (power & 0x03));
        byte[] text = nameBytes(name);
        for (int part = 0; part < 3; part++) {
            int at = 15 + part * 10;
            b[at] = 0x05;
            b[at + 1] = (byte) (4 + part);
            int len = part == 2 ? 2 : 8;
            System.arraycopy(text, part * 8, b, at + 2, Math.max(0, Math.min(len, text.length - part * 8)));
        }
        return b;
    }

    /** The profile name as at most 18 UTF-8 bytes, zero padded (the app cuts at 18, ae/j.java:362). */
    static byte[] nameBytes(String name) {
        byte[] text = name == null ? new byte[0] : name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] out = new byte[18];
        System.arraycopy(text, 0, out, 0, Math.min(18, text.length));
        return out;
    }

    /** The result of a write answer (byte 7 of the 0x48 reply), or -1 when the frame is not one. */
    public static int result(byte[] v) {
        if (v == null || v.length < 8 || (v[0] & 0xFF) != BikeFrames.VEHICLE_SETTINGS) return -1;
        return v[7] & 0xFF;
    }

    /** Why a write may not be sent now, or null when it may. {@code liveAge} is the age of the last riding data. */
    public static String blockedBecause(BikeFrames.Telemetry live, long liveAgeMs, BikeFrames.TuningConfig cfg) {
        if (live == null || liveAgeMs > 3000) return "no current data from the bike: keep it switched on";
        if (live.speedKmh != live.speedKmh || live.speedKmh > 0.5) return "the bike must stand still";
        if (live.gear != 0) return "the bike must be in neutral";
        if (live.throttlePct != live.throttlePct || live.throttlePct > 2) return "the throttle must be closed";
        if (cfg == null) return "the bike did not say which settings it takes";
        if (!cfg.supported(BikeFrames.TuningConfig.RIDING_MODE)) return "this bike takes no ride settings";
        return null;
    }

    /** Debug text for a result code. */
    public static String describeResult(int result) {
        switch (result) {
            case RESULT_OK: return "accepted";
            case RESULT_WAIT: return "the meter is applying it; waiting for it to finish";
            default: return String.format(Locale.ROOT, "rejected (result %02X)", result);
        }
    }
}
