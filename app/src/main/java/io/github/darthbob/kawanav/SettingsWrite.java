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

    /**
     * The bike's numbers start at 1, as Rideology's own tables show (ph/d.java, entry 0 is "error"): riding
     * mode 1 Sport, 2 Road, 3 Rain, 4 Rider (5-7: Rider 2-4 on bikes with four rider modes); power 1 Full,
     * 2 Medium, 3 Low; KQS and KEBC 1 OFF, 2 ON, KEBC 3 Light. The arrays below are in that order, so the
     * name of value v is {@code names[v - 1]}; the app passes these numbers through unchanged both ways
     * (datamodels/b.java and oh/b.java). KTRC is mapped, see {@link #ktrcWire}.
     */
    public static final String[] MODES = {"Sport", "Road", "Rain", "Rider", "Rider 2", "Rider 3", "Rider 4"};
    /** The riding modes a bike without the four-rider-mode option has (the four every such bike has). */
    public static final int WRITABLE_MODES = 4;
    /** The riding modes a bike with four rider modes offers: 5, 6 and 7 are "Rider 2", "Rider 3", "Rider 4". */
    public static final int WRITABLE_MODES_7 = 7;

    /**
     * How many riding modes the bike has. Rideology reads the 0x47 tuning config's riding-mode field
     * (byte 8 bits 5-4, {@link BikeFrames.TuningConfig#RIDING_MODE}) and shows seven names when it is 1,
     * four when it is 0 (Ble5VehicleSettingViewModel.java:72; VehicleSettingEditViewModel.java:423). A config that has not arrived, or that
     * says the bike has no such setting, counts as four: the smaller list can never offer a mode the bike
     * does not have.
     */
    public static int modeCount(BikeFrames.TuningConfig tuning) {
        if (tuning == null || !tuning.valid) return WRITABLE_MODES;
        return tuning.flag(BikeFrames.TuningConfig.RIDING_MODE) == 1 ? WRITABLE_MODES_7 : WRITABLE_MODES;
    }
    public static final String[] POWER = {"Full", "Medium", "Low"};
    public static final String[] KTRC = {"OFF", "1", "2", "3", "4", "5"};
    public static final String[] ON_OFF = {"OFF", "ON"};
    public static final String[] KEBC = {"OFF", "ON", "Light"};

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

    /** The KTRC field value back to a position in {@link #KTRC}, or -1 when a write cannot set it. */
    public static int ktrcFromWire(int wire) {
        return wire == 10 ? 0 : wire >= 1 && wire <= 5 ? wire : -1;
    }

    /** KTRC as the bike reports it: OFF, or a level 1-9 (the write takes 1-5 only). */
    public static String ktrcName(int wire) {
        if (wire < 0) return null;
        String name = wire == 10 ? "OFF" : wire >= 1 && wire <= 9 ? String.valueOf(wire) : "?";
        return name + " (" + wire + ")";
    }

    /**
     * The 45-byte write frame, with the bike's own numbers: {@code mode} 1..4 (Sport/Road/Rain/Rider),
     * {@code power} 1..3 (Full..Low), {@code ktrc} the wire value (see {@link #ktrcWire}), {@code kqs} 1/2
     * (OFF/ON), {@code kebc} 1..3, or 0 on a bike without KEBC. {@code name} is the profile name
     * (max 18 UTF-8 bytes). Check the values with {@link #invalid} first.
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

    /** KECS modes and preload settings as Rideology names them (ph/d.java, values 1..3). */
    public static final String[] KECS_MODES = {"Hard", "Normal", "Soft"};
    public static final String[] KECS_PRELOAD = {"Rider", "Rider + L", "Rider + P + L"};

    /** A value with the number the bike sent in brackets: honest while the names are unverified. */
    public static String named(String[] names, int value) {
        if (value < 0) return null;
        String name = value >= 1 && value <= names.length ? names[value - 1] : "?";
        return name + " (" + value + ")";
    }

    /** The KECS load adjustment: the meter sends 1..11, Rideology shows it as -5..+5 (ph/d.java). */
    public static String adjustment(int value) {
        if (value < 0) return null;
        if (value < 1 || value > 11) return "? (" + value + ")";
        int shown = value - 6;
        return (shown > 0 ? "+" : "") + shown + " (" + value + ")";
    }

    /**
     * Why these values must not be written, or null when they are all in the ranges Rideology sends.
     * A 0 or a value off the end would be a setting the bike never offered.
     */
    public static String invalid(int modes, int mode, int power, int ktrcLevel, int kqs, int kebc) {
        if (mode < 1 || mode > modes) return "riding mode " + mode + " is not one the bike offers";
        if (power < 1 || power > POWER.length) return "power mode " + power + " is not one the bike offers";
        if (ktrcLevel < 0 || ktrcLevel >= KTRC.length) return "KTRC level " + ktrcLevel + " cannot be written";
        if (kqs < 1 || kqs > ON_OFF.length) return "quickshifter value " + kqs + " is not OFF or ON";
        if (kebc < 0 || kebc > KEBC.length) return "engine brake value " + kebc + " is not one the bike offers";
        return null;
    }

    /** Shift-lamp levels the meter takes: code 1..61, each step a 250 rpm step from 3000 rpm up. */
    public static final int LAMP_MIN_RPM = 3000, LAMP_RPM_STEP = 250, LAMP_LEVELS = 61;

    /** Rotations per minute the meter lights the shift lamp at, from its level code. */
    public static int lampRpm(int level) {
        return (Math.max(1, Math.min(LAMP_LEVELS, level)) - 1) * LAMP_RPM_STEP + LAMP_MIN_RPM;
    }

    /** The level code for a wanted rpm, clamped to what the meter takes. */
    public static int lampLevel(int rpm) {
        return Math.max(1, Math.min(LAMP_LEVELS, (rpm - LAMP_MIN_RPM) / LAMP_RPM_STEP + 1));
    }

    /** The shift-lamp kinds the meter offers: four when it has a tachometer and an LED, two otherwise. */
    public static String[] lampStatusNames(int shiftLampMode) {
        return shiftLampMode == 3 ? new String[]{"Off", "On"}
                : new String[]{"Off", "Tachometer", "LED", "LED + tachometer"};
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
