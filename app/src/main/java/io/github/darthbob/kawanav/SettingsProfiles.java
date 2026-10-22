package io.github.darthbob.kawanav;

import java.util.ArrayList;
import java.util.List;

/**
 * Ride settings kept on the phone under a name, as Rideology keeps its tuning profiles: riding mode,
 * power, KTRC level and KQS, picked on the bike screen, filled into the pickers and sent like any other
 * write (the name goes along as the profile name). KEBC is not kept: a write sends it back as the bike
 * has it. Stored as text in the settings, one profile per line, the values and the name separated by
 * tabs. Plain Java so it can be unit-tested off-device.
 */
final class SettingsProfiles {
    private SettingsProfiles() {}

    /** More profiles than this are not kept: the picker would be a list to scroll through on the road. */
    static final int MAX = 20;

    /** One named set of ride settings, in the numbers of a write ({@link SettingsWrite.Choice}). */
    static final class Profile {
        final String name;
        final int mode, power, ktrcLevel, kqs;

        Profile(String name, int mode, int power, int ktrcLevel, int kqs) {
            this.name = name;
            this.mode = mode;
            this.power = power;
            this.ktrcLevel = ktrcLevel;
            this.kqs = kqs;
        }

        SettingsWrite.Choice choice() {
            return new SettingsWrite.Choice(mode, power, ktrcLevel, kqs);
        }
    }

    /**
     * A name as a profile keeps it: the profile name the write can carry ({@link SettingsWrite#fitName}),
     * without tabs or line breaks, which the stored text uses.
     */
    static String clean(String name) {
        return SettingsWrite.fitName(name == null ? "" : name.replaceAll("[\\t\\r\\n]+", " "));
    }

    /** The profiles in the stored text, in their order; a line that is not a whole, sendable profile is left out. */
    static List<Profile> parse(String text) {
        List<Profile> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            String[] f = line.split("\t", 5);
            if (f.length != 5) continue;
            try {
                int mode = Integer.parseInt(f[0]), power = Integer.parseInt(f[1]);
                int ktrc = Integer.parseInt(f[2]), kqs = Integer.parseInt(f[3]);
                String name = clean(f[4]);
                if (name.isEmpty() || indexOf(out, name) >= 0) continue;
                // the most modes a bike has: whether this one has them is checked when the profile is filled in
                if (SettingsWrite.invalid(SettingsWrite.WRITABLE_MODES_7, mode, power, ktrc, kqs, 0) != null) continue;
                out.add(new Profile(name, mode, power, ktrc, kqs));
            } catch (NumberFormatException e) {
                // not a profile: skipped
            }
            if (out.size() >= MAX) break;
        }
        return out;
    }

    static String format(List<Profile> profiles) {
        StringBuilder sb = new StringBuilder();
        for (Profile p : profiles) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(p.mode).append('\t').append(p.power).append('\t').append(p.ktrcLevel).append('\t')
                    .append(p.kqs).append('\t').append(clean(p.name));
        }
        return sb.toString();
    }

    /** Where the profile of this name is (names are compared without regard to case), or -1. */
    static int indexOf(List<Profile> profiles, String name) {
        String n = clean(name);
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).name.equalsIgnoreCase(n)) return i;
        }
        return -1;
    }

    /**
     * The list with {@code p} saved: in the place of the profile of the same name, else at the end.
     * Null when it is a new one and there are {@link #MAX} already.
     */
    static List<Profile> put(List<Profile> profiles, Profile p) {
        List<Profile> out = new ArrayList<>(profiles);
        int at = indexOf(out, p.name);
        if (at >= 0) {
            out.set(at, p);
        } else {
            if (out.size() >= MAX) return null;
            out.add(p);
        }
        return out;
    }

    /** The list without the profile of this name. */
    static List<Profile> remove(List<Profile> profiles, String name) {
        int at = indexOf(profiles, name);
        if (at < 0) return profiles;
        List<Profile> out = new ArrayList<>(profiles);
        out.remove(at);
        return out;
    }
}
