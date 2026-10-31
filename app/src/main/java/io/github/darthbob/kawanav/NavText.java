package io.github.darthbob.kawanav;

import java.util.Locale;

/**
 * What the navigation, the curve and the camera warnings say, in the phone's language: English,
 * Italian or German (any other language gets English). The dash shows arrows and distances, so these
 * phrases are for the voice and the app's screens.
 *
 * Plain Java.
 */
public final class NavText {

    public final String lang;

    NavText(String lang) {
        this.lang = lang.equals("it") || lang.equals("de") ? lang : "en";
    }

    /** The phone's language, if it is one of ours. */
    public static NavText phone() {
        return new NavText(Locale.getDefault().getLanguage());
    }

    public static NavText of(String language) {
        return new NavText(language);
    }

    /** The voice to speak these phrases with. */
    public Locale locale() {
        return new Locale(lang);
    }

    private String t(String en, String it, String de) {
        return lang.equals("it") ? it : lang.equals("de") ? de : en;
    }

    // ------------------------------------------------------------------ maneuvers

    /** "Turn left onto Via Roma", for the screen and, after a distance, the voice. */
    public String action(Instructions.Maneuver m) {
        boolean left = m.side < 0;
        String s;
        switch (m.kind) {
            case DEPART:
                return m.road.isEmpty() ? t("Start the route", "Inizia il percorso", "Route starten")
                        : t("Follow ", "Segui ", "Folgen Sie ") + m.road;
            case CONTINUE: s = t("Continue straight", "Prosegui dritto", "Geradeaus weiterfahren"); break;
            case SLIGHT:
                s = left ? t("Bear left", "Svolta leggermente a sinistra", "Leicht links abbiegen")
                        : t("Bear right", "Svolta leggermente a destra", "Leicht rechts abbiegen");
                break;
            case TURN:
                s = left ? t("Turn left", "Svolta a sinistra", "Links abbiegen")
                        : t("Turn right", "Svolta a destra", "Rechts abbiegen");
                break;
            case SHARP:
                s = left ? t("Turn sharp left", "Svolta decisamente a sinistra", "Scharf links abbiegen")
                        : t("Turn sharp right", "Svolta decisamente a destra", "Scharf rechts abbiegen");
                break;
            case U_TURN: s = t("Make a U-turn", "Fai inversione a U", "Bitte wenden"); break;
            case KEEP:
                s = left ? t("Keep left", "Mantieni la sinistra", "Links halten")
                        : t("Keep right", "Mantieni la destra", "Rechts halten");
                break;
            case RAMP:
                s = left ? t("Take the ramp on the left", "Prendi la rampa a sinistra", "Auffahrt links nehmen")
                        : t("Take the ramp on the right", "Prendi la rampa a destra", "Auffahrt rechts nehmen");
                break;
            case EXIT:
                s = left ? t("Take the exit on the left", "Prendi l'uscita a sinistra", "Ausfahrt links nehmen")
                        : t("Take the exit on the right", "Prendi l'uscita a destra", "Ausfahrt rechts nehmen");
                break;
            case MERGE: s = t("Merge", "Immettiti", "Einfädeln"); break;
            case ROUNDABOUT:
                s = t("At the roundabout, take the " + ordinal(m.number) + " exit",
                        "Alla rotonda prendi la " + ordinal(m.number) + " uscita",
                        "Im Kreisverkehr die " + ordinal(m.number) + " Ausfahrt nehmen");
                break;
            case VIA:
                return t("You have reached waypoint ", "Hai raggiunto la tappa ", "Zwischenziel erreicht: ") + m.number;
            case ARRIVE:
                return t("You have arrived", "Sei arrivato a destinazione", "Sie haben Ihr Ziel erreicht");
            default:
                return "";
        }
        if (m.road.isEmpty()) return s;
        return s + t(" onto ", " in ", " auf ") + m.road;
    }

    /**
     * What the screen shows for a maneuver still ahead: {@link #action}, except that the destination and
     * the via points are named rather than reached ("Destination", not "You have arrived").
     */
    public String upcoming(Instructions.Maneuver m) {
        if (m.kind == Instructions.Kind.ARRIVE) return t("Destination", "Destinazione", "Ziel");
        if (m.kind == Instructions.Kind.VIA) return t("Waypoint ", "Tappa ", "Zwischenziel ") + m.number;
        return action(m);
    }

    /** "In 300 metres, turn left onto Via Roma"; just the action when it is close. */
    public String spoken(Instructions.Maneuver m, double distanceM, boolean imperial) {
        String a = action(m);
        if (distanceM < 40 || m.kind == Instructions.Kind.VIA || m.kind == Instructions.Kind.ARRIVE) return a;
        String head = t("In ", "Tra ", "In ") + distance(distanceM, imperial) + (lang.equals("de") ? " " : ", ");
        return head + afterWords(a);
    }

    /** The phrase continuing a sentence: lower case first letter, except German nouns. */
    private String afterWords(String a) {
        if (lang.equals("de") && (a.startsWith("Auffahrt") || a.startsWith("Ausfahrt") || a.startsWith("Kurve")
                || a.startsWith("Kehre"))) {
            return a;
        }
        return Character.toLowerCase(a.charAt(0)) + a.substring(1);
    }

    /** The maneuver after the next one, said with it when it follows straight after: "then turn right". */
    public String then(Instructions.Maneuver m) {
        return t(", then ", ", poi ", ", dann ") + afterWords(action(m));
    }

    public String recalculating() {
        return t("Recalculating the route", "Ricalcolo del percorso", "Route wird neu berechnet");
    }

    public String noRoute() {
        return t("No route found", "Nessun percorso trovato", "Keine Route gefunden");
    }

    /** "1st" / "prima" / "erste". */
    String ordinal(int n) {
        if (lang.equals("it")) {
            String[] w = {"prima", "seconda", "terza", "quarta", "quinta", "sesta", "settima", "ottava", "nona",
                    "decima", "undicesima", "dodicesima"};
            return n >= 1 && n <= 12 ? w[n - 1] : n + "ª";
        }
        if (lang.equals("de")) {
            String[] w = {"erste", "zweite", "dritte", "vierte", "fünfte", "sechste", "siebte", "achte", "neunte",
                    "zehnte", "elfte", "zwölfte"};
            return n >= 1 && n <= 12 ? w[n - 1] : n + ".";
        }
        int m100 = n % 100;
        String suffix = m100 >= 11 && m100 <= 13 ? "th" : n % 10 == 1 ? "st" : n % 10 == 2 ? "nd" : n % 10 == 3 ? "rd" : "th";
        return n + suffix;
    }

    /** A distance as spoken: "300 metres", "1.5 kilometres", "0.3 miles"; rounded the way a voice says it. */
    public String distance(double m, boolean imperial) {
        if (imperial) {
            double miles = m / 1609.344;
            if (miles < 0.1) {
                long ft = Math.round(m * 3.28084 / 50) * 50;
                return ft + t(" feet", " piedi", " Fuß");
            }
            String v = number(miles < 10 ? Math.round(miles * 10) / 10.0 : Math.round(miles));
            boolean one = v.equals("1");
            return v + (one ? t(" mile", " miglio", " Meile") : t(" miles", " miglia", " Meilen"));
        }
        if (m < 1000) {
            long r = m < 100 ? Math.round(m / 10) * 10 : m < 300 ? Math.round(m / 50) * 50 : Math.round(m / 100) * 100;
            if (r >= 1000) return distance(1000, false);
            return r + t(" metres", " metri", " Metern");
        }
        String v = number(m < 10_000 ? Math.round(m / 100) / 10.0 : Math.round(m / 1000));
        boolean one = v.equals("1");
        return v + (one ? t(" kilometre", " chilometro", " Kilometer") : t(" kilometres", " chilometri", " Kilometern"));
    }

    private String number(double v) {
        String s = v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
        return lang.equals("en") ? s : s.replace('.', ',');
    }

    // ------------------------------------------------------------------ curve and camera warnings

    /** "Sharp bend left": the kind of curve as on the dash's arrow (see {@link CurveAlerts#severity}). */
    public String curve(int turn) {
        boolean right = turn == 2 || turn == 4 || turn == 6 || turn == 8;
        String side = right ? t("right", "a destra", "rechts") : t("left", "a sinistra", "links");
        switch (CurveAlerts.severity(turn)) {
            case 3: return t("Hairpin ", "Tornante ", "Kehre ") + side;
            case 2: return t("Sharp bend ", "Curva stretta ", "Scharfe Kurve ") + side;
            case 1: return t("Bend ", "Curva ", "Kurve ") + side;
            default: return t("Slight bend ", "Curva leggera ", "Leichte Kurve ") + side;
        }
    }

    /** "Sharp bend left, slow down, then bend right". {@code then} is a turn id, or -1. */
    public String curveWarning(int turn, boolean slowDown, int then) {
        String s = curve(turn) + (slowDown ? t(", slow down", ", rallenta", ", langsamer") : "");
        if (then < 0) return s;
        return s + t(", then ", ", poi ", ", dann ") + afterWords(curve(then));
    }

    /** "Speed camera ahead, limit 50". */
    public String camera(String type, int maxspeed) {
        String name;
        switch (type) {
            case "red_light": name = t("Red-light camera ahead", "Telecamera semaforica più avanti", "Rotlichtblitzer voraus"); break;
            case "average_speed": name = t("Average-speed section ahead", "Tutor più avanti", "Abschnittskontrolle voraus"); break;
            default: name = t("Speed camera ahead", "Autovelox più avanti", "Blitzer voraus");
        }
        return maxspeed > 0 ? name + t(", limit ", ", limite ", ", Limit ") + maxspeed : name;
    }
}
