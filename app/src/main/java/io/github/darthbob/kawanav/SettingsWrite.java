package io.github.darthbob.kawanav;

import java.util.Locale;

/**
 * Writing ride settings to the bike (riding mode, power, KTRC, KQS, profile name). Rideology uses frame
 * 0x48 with block 0x09 and byte 7 = 1 for the write (the read has 0 there), and reads the answer's byte 7
 * as the result (0x00 accepted, 0x10 "the meter is applying, wait", anything else rejected).
 *
 * <p>KawaNav is stricter than the app about when a write may be sent: the app only wants the bike
 * standing still (its own threshold is "under 5 km/h"), KawaNav also requires neutral and a closed
 * throttle, because this has not been tried on a real bike yet.
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

    /** Room for the profile name in the write: 18 UTF-8 bytes (bytes 17-24, 27-34, 37-38). */
    public static final int NAME_BYTES = 18;

    /**
     * The profile name as it fits: trimmed, and cut after the last whole character that fits in
     * {@link #NAME_BYTES} UTF-8 bytes. Rideology cuts at 18 bytes even inside a character
     * (ae/j.java:362); KawaNav never sends half an "è".
     */
    public static String fitName(String name) {
        if (name == null) return "";
        String s = name.trim();
        int bytes = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int n = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (bytes + n > NAME_BYTES) return s.substring(0, i).trim();
            bytes += n;
            i += Character.charCount(cp);
        }
        return s;
    }

    /** The profile name as at most 18 UTF-8 bytes, zero padded, cut at a whole character ({@link #fitName}). */
    static byte[] nameBytes(String name) {
        byte[] text = fitName(name).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] out = new byte[NAME_BYTES];
        System.arraycopy(text, 0, out, 0, Math.min(NAME_BYTES, text.length));
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

    /**
     * The power and KTRC level Sport, Road and Rain come with, as {power, KTRC level}, or null for the
     * Rider modes, which keep the rider's own. Rideology's preset buttons set them (Sport: power 1, KTRC 2;
     * Road: 1, 3; Rain: 3, 4 in its stored KTRC numbers, that is levels 1, 2 and 3;
     * Ble5VehicleSettingViewModel.java:123-183). It sets KECS too, but never writes KECS.
     */
    public static int[] preset(int mode) {
        switch (mode) {
            case 1: return new int[]{1, 1}; // Sport: Full, KTRC 1
            case 2: return new int[]{1, 2}; // Road: Full, KTRC 2
            case 3: return new int[]{3, 3}; // Rain: Low, KTRC 3
            default: return null;
        }
    }

    /** The riding mode that takes the rider's own power and KTRC. */
    public static final int RIDER = 4;

    /**
     * Sport, Road or Rain with a power or KTRC level other than its own ({@link #preset}): that is not the
     * mode any more but the rider's own setting, so KawaNav sends it as {@link #RIDER}. KQS is no part of
     * a preset.
     */
    public static boolean leavesPreset(int mode, int power, int ktrcLevel) {
        int[] p = preset(mode);
        return p != null && (power != p[0] || ktrcLevel != p[1]);
    }

    /**
     * Where power and KTRC come from, on the line above them: "Sport: Full power, KTRC 1" for a preset,
     * "Your own: Rider" for a Rider mode. A preset whose values are not its own (only the bike's can be:
     * a change by the rider turns it into Rider) says so.
     */
    public static String presetLine(int mode, int power, int ktrcLevel) {
        if (mode < 1 || mode > MODES.length) return "";
        int[] p = preset(mode);
        if (p == null) return "Your own: " + MODES[mode - 1];
        String own = MODES[mode - 1] + ": " + POWER[p[0] - 1] + " power, KTRC " + KTRC[p[1]];
        return leavesPreset(mode, power, ktrcLevel) ? own + " (the bike has other values)" : own;
    }

    /** Why the riding mode became Rider, after {@code fromMode}'s power or KTRC was changed. */
    public static String riderNote(int fromMode) {
        int[] p = preset(fromMode);
        if (p == null) return "";
        return MODES[fromMode - 1] + " comes with " + POWER[p[0] - 1] + " power and KTRC " + KTRC[p[1]]
                + ", so with other values the riding mode is now Rider, which keeps your own.";
    }

    /**
     * Riding mode, power, KTRC level and KQS in the numbers of a write (mode 1.., power 1..3, KTRC level
     * 0 OFF .. 5, KQS 1 OFF / 2 ON); -1 where it is not known. What the bike has, or what is picked.
     */
    public static final class Choice {
        public final int mode, power, ktrcLevel, kqs;

        public Choice(int mode, int power, int ktrcLevel, int kqs) {
            this.mode = mode;
            this.power = power;
            this.ktrcLevel = ktrcLevel;
            this.kqs = kqs;
        }

        /** What the bike reported, or null before it did. */
        public static Choice of(BikeFrames.VehicleSettings s) {
            return s == null ? null : new Choice(s.ridingMode, s.power, ktrcFromWire(s.ktrc), s.kqs);
        }

        /** Something here is not what the bike has (or the bike's value is not known). */
        public boolean differsFrom(Choice bike) {
            return bike == null || mode != bike.mode || power != bike.power || ktrcLevel != bike.ktrcLevel
                    || kqs != bike.kqs;
        }
    }

    /**
     * What a write sends, one line per value next to what the bike has: "Power: Full → Low" for a change,
     * "KQS: ON" for one that stays, "?" where the bike did not say. All of them go in the one write (the
     * frame has no way to send a single value), so the ones that stay are listed too. KEBC goes back as
     * the bike has it (0: the bike has none, no line); the name is sent as given.
     */
    public static java.util.List<String> summary(Choice bike, Choice picked, int kebc, String name) {
        java.util.List<String> out = new java.util.ArrayList<>();
        out.add(line("Riding mode", MODES, bike == null ? -1 : bike.mode - 1, picked.mode - 1));
        out.add(line("Power", POWER, bike == null ? -1 : bike.power - 1, picked.power - 1));
        out.add(line("KTRC", KTRC, bike == null ? -1 : bike.ktrcLevel, picked.ktrcLevel));
        out.add(line("KQS", ON_OFF, bike == null ? -1 : bike.kqs - 1, picked.kqs - 1));
        if (kebc > 0) out.add("Engine brake: " + nameAt(KEBC, kebc - 1) + " (as the bike has it)");
        out.add(name == null || name.isEmpty() ? "Name: none" : "Name: \"" + name + "\"");
        return out;
    }

    private static String line(String label, String[] names, int onBike, int sent) {
        String to = nameAt(names, sent);
        return onBike == sent ? label + ": " + to : label + ": " + nameAt(names, onBike) + " → " + to;
    }

    private static String nameAt(String[] names, int i) {
        return i >= 0 && i < names.length ? names[i] : "?";
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

    /** How long what came of the last write (or why it was not sent) stays on its card. */
    public static final long OUTCOME_MS = 30_000;

    /**
     * The status line of a write card: the write that is running, else what came of the last one while it
     * is recent ({@code outcomeAgeMs} under {@link #OUTCOME_MS}), else {@code idle}. A refused or answered
     * write must not be overwritten by "Ready" on the next refresh before the rider has read it.
     */
    public static String status(String job, String outcome, long outcomeAgeMs, String idle) {
        if (job != null && !job.isEmpty()) return job;
        if (outcome != null && !outcome.isEmpty() && outcomeAgeMs >= 0 && outcomeAgeMs < OUTCOME_MS) return outcome;
        return idle;
    }

    /** "Ready", or why a ride-settings write would not be sent now ({@code blocked} from {@link #blockedBecause}). */
    public static String readiness(String blocked) {
        return blocked == null ? "Ready" : "Not ready: " + blocked;
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
