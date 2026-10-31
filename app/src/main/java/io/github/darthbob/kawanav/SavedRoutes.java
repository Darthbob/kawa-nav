package io.github.darthbob.kawanav;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Routes saved in the planner, one small text file each. A file keeps what was asked for (the points,
 * the route kind and what to avoid), not the line: opening it plans the route again on the downloaded
 * roads, which gives the same route as long as the regions are the same.
 */
public final class SavedRoutes {

    public static final class Saved {
        public String name = "";
        public long created;
        public boolean roundTrip;
        public RouteProfile.Kind kind = RouteProfile.Kind.CURVY;
        public boolean avoidMotorways, avoidTolls, avoidUnpaved = true;
        /** Length when it was saved, for the list. */
        public double km;
        /** Start, via points and destination; a round trip's start, corners and start again. */
        public final List<Places.Place> points = new ArrayList<>();
        /** Where it is kept (null until saved). */
        public File file;

        public RouteProfile profile() {
            return new RouteProfile(kind, avoidMotorways, avoidTolls, avoidUnpaved);
        }
    }

    private SavedRoutes() {}

    static String encode(Saved s) {
        StringBuilder sb = new StringBuilder();
        sb.append("name=").append(Places.escape(s.name)).append('\n');
        sb.append("created=").append(s.created).append('\n');
        sb.append("round_trip=").append(s.roundTrip).append('\n');
        sb.append("profile=").append(s.kind.name()).append('\n');
        sb.append("avoid_motorways=").append(s.avoidMotorways).append('\n');
        sb.append("avoid_tolls=").append(s.avoidTolls).append('\n');
        sb.append("avoid_unpaved=").append(s.avoidUnpaved).append('\n');
        sb.append("km=").append(String.format(Locale.ROOT, "%.1f", s.km)).append('\n');
        for (Places.Place p : s.points) sb.append("point=").append(Places.line(p)).append('\n');
        return sb.toString();
    }

    /** The saved route, or null when the text is not one (fewer than two points). */
    static Saved decode(String text) {
        Saved s = new Saved();
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq), v = line.substring(eq + 1).trim();
            try {
                switch (key) {
                    case "name": s.name = Places.unescape(v); break;
                    case "created": s.created = Long.parseLong(v); break;
                    case "round_trip": s.roundTrip = Boolean.parseBoolean(v); break;
                    case "profile": s.kind = RouteProfile.Kind.valueOf(v); break;
                    case "avoid_motorways": s.avoidMotorways = Boolean.parseBoolean(v); break;
                    case "avoid_tolls": s.avoidTolls = Boolean.parseBoolean(v); break;
                    case "avoid_unpaved": s.avoidUnpaved = Boolean.parseBoolean(v); break;
                    case "km": s.km = Double.parseDouble(v); break;
                    case "point": {
                        Places.Place p = Places.place(line.substring(eq + 1));
                        if (p != null) s.points.add(p);
                        break;
                    }
                    default: break; // written by a later version
                }
            } catch (IllegalArgumentException e) {
                // a broken value keeps the default
            }
        }
        return s.points.size() >= 2 ? s : null;
    }

    /** The saved routes in the folder, the newest first. */
    public static List<Saved> list(File dir) {
        List<Saved> l = new ArrayList<>();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".route"));
        if (files == null) return l;
        for (File f : files) {
            try {
                Saved s = decode(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                if (s == null) continue;
                s.file = f;
                l.add(s);
            } catch (IOException e) {
                Logger.log("Saved route unreadable: " + f.getName());
            }
        }
        l.sort((a, b) -> Long.compare(b.created, a.created));
        return l;
    }

    /** Writes the route (a new file the first time, else its own one). */
    public static void save(File dir, Saved s) throws IOException {
        if (s.file == null) {
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
            File f = new File(dir, s.created + ".route");
            for (int i = 1; f.exists(); i++) f = new File(dir, s.created + "-" + i + ".route");
            s.file = f;
        }
        Files.write(s.file.toPath(), encode(s).getBytes(StandardCharsets.UTF_8));
    }

    public static boolean delete(Saved s) {
        return s.file != null && s.file.delete();
    }
}
