package io.github.darthbob.kawanav;

import java.util.Locale;

/** A planned route as GPX 1.1: its line as a track, the start, via points and destination as waypoints. */
public final class RouteGpx {
    private RouteGpx() {}

    public static String of(Route r, String name) {
        StringBuilder sb = new StringBuilder(64 + r.lat.length * 48);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<gpx version=\"1.1\" creator=\"KawaNav\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n");
        sb.append("  <metadata><name>").append(escape(name)).append("</name></metadata>\n");
        for (int i = 0; i < r.asked.size(); i++) {
            double[] p = r.asked.get(i);
            String what = i == 0 ? "Start" : i == r.asked.size() - 1 ? "Destination" : "Via " + i;
            if (r.roundTrip && i > 0 && i < r.asked.size() - 1) continue;
            sb.append(String.format(Locale.ROOT, "  <wpt lat=\"%.6f\" lon=\"%.6f\"><name>%s</name></wpt>\n", p[0], p[1], what));
        }
        sb.append("  <trk><name>").append(escape(name)).append("</name><trkseg>\n");
        for (int i = 0; i < r.lat.length; i++) {
            sb.append(String.format(Locale.ROOT, "    <trkpt lat=\"%.6f\" lon=\"%.6f\"/>\n", r.lat[i], r.lon[i]));
        }
        sb.append("  </trkseg></trk>\n</gpx>\n");
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
