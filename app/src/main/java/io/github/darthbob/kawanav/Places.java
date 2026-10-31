package io.github.darthbob.kawanav;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The rider's favourite places and recent destinations, offered by the planner's search. Kept in the
 * settings as lines of text: latitude, longitude and name, separated by tabs.
 */
public final class Places {

    public static final int MAX_RECENT = 10;
    /** Places closer than this are the same place. */
    static final double SAME_M = 150;

    public static final class Place {
        public final String name;
        public final double lat, lon;

        public Place(String name, double lat, double lon) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
        }
    }

    private Places() {}

    public static List<Place> recent(SharedPreferences p) {
        return decode(p.getString(Hub.KEY_PLACES_RECENT, ""));
    }

    public static List<Place> favourites(SharedPreferences p) {
        return decode(p.getString(Hub.KEY_PLACES_FAVOURITE, ""));
    }

    /** A destination the rider went for: first in the recent ones. */
    public static void addRecent(SharedPreferences p, Place place) {
        p.edit().putString(Hub.KEY_PLACES_RECENT, encode(addFirst(recent(p), place, MAX_RECENT))).apply();
    }

    public static void addFavourite(SharedPreferences p, Place place) {
        p.edit().putString(Hub.KEY_PLACES_FAVOURITE, encode(addFirst(favourites(p), place, Integer.MAX_VALUE))).apply();
    }

    public static void saveFavourites(SharedPreferences p, List<Place> l) {
        p.edit().putString(Hub.KEY_PLACES_FAVOURITE, encode(l)).apply();
    }

    /** The list with {@code place} first and no other place near it, at most {@code max} long. */
    static List<Place> addFirst(List<Place> l, Place place, int max) {
        List<Place> out = new ArrayList<>();
        out.add(place);
        for (Place q : l) {
            if (out.size() >= max) break;
            if (Ride.distanceM(q.lat, q.lon, place.lat, place.lon) >= SAME_M) out.add(q);
        }
        return out;
    }

    static String encode(List<Place> l) {
        StringBuilder sb = new StringBuilder();
        for (Place p : l) sb.append(line(p)).append('\n');
        return sb.toString();
    }

    static List<Place> decode(String s) {
        List<Place> l = new ArrayList<>();
        if (s == null) return l;
        for (String line : s.split("\n")) {
            Place p = place(line);
            if (p != null) l.add(p);
        }
        return l;
    }

    /** One "lat TAB lon TAB name" line; null when it is not one. */
    static Place place(String line) {
        String[] f = line.split("\t", 3);
        if (f.length < 3) return null;
        try {
            return new Place(unescape(f[2]), Double.parseDouble(f[0]), Double.parseDouble(f[1]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String line(Place p) {
        return String.format(Locale.ROOT, "%.6f\t%.6f\t", p.lat, p.lon) + escape(p.name);
    }

    /** A name on one line without tabs: backslash, tab and line breaks written as \\, \t, \n. */
    static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') sb.append("\\\\");
            else if (c == '\t') sb.append("\\t");
            else if (c == '\n') sb.append("\\n");
            else if (c != '\r') sb.append(c);
        }
        return sb.toString();
    }

    static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 't' ? '\t' : n == 'n' ? '\n' : n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
