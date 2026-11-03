package io.github.darthbob.kawanav;

import java.util.Locale;

/**
 * What the navigation, the curve and the camera warnings say, in KawaNav's language ({@link Lang#CODES}:
 * English, Italian, German, French, Spanish, Dutch, Portuguese, Polish, Swedish; any other language gets
 * English). The dash shows arrows and distances, so these phrases are for the voice and the app's screens.
 * They stay in code rather than in the string files: their word order, ordinals and plural forms differ
 * per language. Every phrase is a {@link #t} with all nine languages, so none can be missing.
 *
 * Plain Java.
 */
public final class NavText {

    /** The languages of {@link #t}, in its order. */
    private static final String[] LANGS = {"en", "it", "de", "fr", "es", "nl", "pt", "pl", "sv"};

    public final String lang;
    private final int index;

    NavText(String lang) {
        int i = java.util.Arrays.asList(LANGS).indexOf(lang);
        this.index = Math.max(0, i);
        this.lang = LANGS[index];
    }

    /** KawaNav's language ({@link Lang#code}): the phone's when the app has it, or the one chosen in the settings. */
    public static NavText current() {
        return new NavText(Lang.code());
    }

    public static NavText of(String language) {
        return new NavText(language);
    }

    /** The voice to speak these phrases with. */
    public Locale locale() {
        return new Locale(lang);
    }

    private String t(String en, String it, String de, String fr, String es, String nl, String pt, String pl, String sv) {
        switch (index) {
            case 1: return it;
            case 2: return de;
            case 3: return fr;
            case 4: return es;
            case 5: return nl;
            case 6: return pt;
            case 7: return pl;
            case 8: return sv;
            default: return en;
        }
    }

    // ------------------------------------------------------------------ maneuvers

    /** "Turn left onto Via Roma", for the screen and, after a distance, the voice. */
    public String action(Instructions.Maneuver m) {
        boolean left = m.side < 0;
        String s;
        switch (m.kind) {
            case DEPART:
                return m.road.isEmpty()
                        ? t("Start the route", "Inizia il percorso", "Route starten", "Démarrez l'itinéraire",
                        "Inicia la ruta", "Start de route", "Inicie a rota", "Rozpocznij trasę", "Starta rutten")
                        : t("Follow ", "Segui ", "Folgen Sie ", "Suivez ", "Sigue por ", "Volg ", "Siga pela ",
                        "Jedź drogą ", "Följ ") + m.road;
            case CONTINUE:
                s = t("Continue straight", "Prosegui dritto", "Geradeaus weiterfahren", "Continuez tout droit",
                        "Continúa recto", "Ga rechtdoor", "Siga em frente", "Jedź prosto", "Fortsätt rakt fram");
                break;
            case SLIGHT:
                s = left ? t("Bear left", "Svolta leggermente a sinistra", "Leicht links abbiegen",
                        "Tournez légèrement à gauche", "Gira ligeramente a la izquierda", "Sla flauw linksaf",
                        "Vire levemente à esquerda", "Skręć lekko w lewo", "Sväng svagt vänster")
                        : t("Bear right", "Svolta leggermente a destra", "Leicht rechts abbiegen",
                        "Tournez légèrement à droite", "Gira ligeramente a la derecha", "Sla flauw rechtsaf",
                        "Vire levemente à direita", "Skręć lekko w prawo", "Sväng svagt höger");
                break;
            case TURN:
                s = left ? t("Turn left", "Svolta a sinistra", "Links abbiegen", "Tournez à gauche",
                        "Gira a la izquierda", "Sla linksaf", "Vire à esquerda", "Skręć w lewo", "Sväng vänster")
                        : t("Turn right", "Svolta a destra", "Rechts abbiegen", "Tournez à droite",
                        "Gira a la derecha", "Sla rechtsaf", "Vire à direita", "Skręć w prawo", "Sväng höger");
                break;
            case SHARP:
                s = left ? t("Turn sharp left", "Svolta decisamente a sinistra", "Scharf links abbiegen",
                        "Tournez fortement à gauche", "Gira bruscamente a la izquierda", "Sla scherp linksaf",
                        "Vire acentuadamente à esquerda", "Skręć ostro w lewo", "Sväng skarpt vänster")
                        : t("Turn sharp right", "Svolta decisamente a destra", "Scharf rechts abbiegen",
                        "Tournez fortement à droite", "Gira bruscamente a la derecha", "Sla scherp rechtsaf",
                        "Vire acentuadamente à direita", "Skręć ostro w prawo", "Sväng skarpt höger");
                break;
            case U_TURN:
                s = t("Make a U-turn", "Fai inversione a U", "Bitte wenden", "Faites demi-tour", "Cambia de sentido",
                        "Keer om", "Faça o retorno", "Zawróć", "Gör en U-sväng");
                break;
            case KEEP:
                s = left ? t("Keep left", "Mantieni la sinistra", "Links halten", "Restez à gauche",
                        "Mantente a la izquierda", "Houd links aan", "Mantenha-se à esquerda",
                        "Trzymaj się lewej strony", "Håll vänster")
                        : t("Keep right", "Mantieni la destra", "Rechts halten", "Restez à droite",
                        "Mantente a la derecha", "Houd rechts aan", "Mantenha-se à direita",
                        "Trzymaj się prawej strony", "Håll höger");
                break;
            case RAMP:
                s = left ? t("Take the ramp on the left", "Prendi la rampa a sinistra", "Auffahrt links nehmen",
                        "Prenez la bretelle à gauche", "Toma la rampa de la izquierda", "Neem de oprit links",
                        "Pegue a rampa à esquerda", "Skorzystaj z wjazdu po lewej", "Ta påfarten till vänster")
                        : t("Take the ramp on the right", "Prendi la rampa a destra", "Auffahrt rechts nehmen",
                        "Prenez la bretelle à droite", "Toma la rampa de la derecha", "Neem de oprit rechts",
                        "Pegue a rampa à direita", "Skorzystaj z wjazdu po prawej", "Ta påfarten till höger");
                break;
            case EXIT:
                s = left ? t("Take the exit on the left", "Prendi l'uscita a sinistra", "Ausfahrt links nehmen",
                        "Prenez la sortie à gauche", "Toma la salida de la izquierda", "Neem de afrit links",
                        "Pegue a saída à esquerda", "Zjedź zjazdem po lewej", "Ta avfarten till vänster")
                        : t("Take the exit on the right", "Prendi l'uscita a destra", "Ausfahrt rechts nehmen",
                        "Prenez la sortie à droite", "Toma la salida de la derecha", "Neem de afrit rechts",
                        "Pegue a saída à direita", "Zjedź zjazdem po prawej", "Ta avfarten till höger");
                break;
            case MERGE:
                s = t("Merge", "Immettiti", "Einfädeln", "Insérez-vous", "Incorpórate", "Voeg in", "Incorpore-se",
                        "Włącz się do ruchu", "Anslut till trafiken");
                break;
            case ROUNDABOUT: {
                String n = ordinal(m.number);
                s = t("At the roundabout, take the " + n + " exit", "Alla rotonda prendi la " + n + " uscita",
                        "Im Kreisverkehr die " + n + " Ausfahrt nehmen", "Au rond-point, prenez la " + n + " sortie",
                        "En la rotonda, toma la " + n + " salida", "Neem op de rotonde de " + n + " afslag",
                        "Na rotatória, pegue a " + n + " saída", "Na rondzie zjedź " + n + " zjazdem",
                        "I rondellen, ta " + n + " avfarten");
                break;
            }
            case VIA:
                return String.format(Locale.ROOT, t("You have reached waypoint %d", "Hai raggiunto la tappa %d",
                        "Zwischenziel erreicht: %d", "Vous avez atteint l'étape %d", "Has llegado a la parada %d",
                        "Je hebt tussenstop %d bereikt", "Você chegou à parada %d", "Dotarłeś do punktu pośredniego %d",
                        "Du har nått delmål %d"), m.number);
            case ARRIVE:
                return t("You have arrived", "Sei arrivato a destinazione", "Sie haben Ihr Ziel erreicht",
                        "Vous êtes arrivé", "Has llegado a tu destino", "Je bent op je bestemming",
                        "Você chegou ao destino", "Dotarłeś do celu", "Du har kommit fram");
            default:
                return "";
        }
        if (m.road.isEmpty()) return s;
        return s + t(" onto ", " in ", " auf ", " sur ", " hacia ", " naar ", " na ", " na ", " in på ") + m.road;
    }

    /**
     * What the screen shows for a maneuver still ahead: {@link #action}, except that the destination and
     * the via points are named rather than reached ("Destination", not "You have arrived").
     */
    public String upcoming(Instructions.Maneuver m) {
        if (m.kind == Instructions.Kind.ARRIVE) {
            return t("Destination", "Destinazione", "Ziel", "Destination", "Destino", "Bestemming", "Destino", "Cel", "Mål");
        }
        if (m.kind == Instructions.Kind.VIA) {
            return t("Waypoint ", "Tappa ", "Zwischenziel ", "Étape ", "Parada ", "Tussenstop ", "Parada ",
                    "Punkt pośredni ", "Delmål ") + m.number;
        }
        return action(m);
    }

    /** "In 300 metres, turn left onto Via Roma"; just the action when it is close. */
    public String spoken(Instructions.Maneuver m, double distanceM, boolean imperial) {
        String a = action(m);
        if (distanceM < 40 || m.kind == Instructions.Kind.VIA || m.kind == Instructions.Kind.ARRIVE) return a;
        String head = t("In ", "Tra ", "In ", "Dans ", "En ", "Over ", "Em ", "Za ", "Om ") + distance(distanceM, imperial)
                + (lang.equals("de") ? " " : ", ");
        return head + afterWords(a);
    }

    /**
     * The next maneuver when the rider asks for it: {@link #spoken}, and the destination or a via point
     * with its distance ("Destination in 2 kilometres").
     */
    public String ahead(Instructions.Maneuver m, double distanceM, boolean imperial) {
        if (m.kind != Instructions.Kind.ARRIVE && m.kind != Instructions.Kind.VIA) return spoken(m, distanceM, imperial);
        if (distanceM < 40) return upcoming(m);
        return upcoming(m) + t(" in ", " tra ", " in ", " dans ", " en ", " over ", " em ", " za ", " om ")
                + distance(distanceM, imperial);
    }

    /** A distance on the screen: "850 m", "4.2 km", "37 km"; "300 ft", "0.8 mi", "23 mi". */
    public static String shown(double m, boolean imperial) {
        if (imperial) {
            if (m < 300) return Math.round(m * 3.28084 / 10) * 10 + " ft";
            double miles = m / 1609.344;
            return String.format(Locale.ROOT, miles < 9.95 ? "%.1f mi" : "%.0f mi", miles);
        }
        if (m < 995) return CurveEngine.roundDistance(m) + " m";
        return String.format(Locale.ROOT, m < 9950 ? "%.1f km" : "%.0f km", m / 1000);
    }

    /** A time on the screen: "48 min", "1 h 05 min". */
    public static String duration(double seconds) {
        long min = Math.max(1, Math.round(seconds / 60));
        return min < 60 ? min + " min" : String.format(Locale.ROOT, "%d h %02d min", min / 60, min % 60);
    }

    /** The phrase continuing a sentence: lower case first letter, except German nouns. */
    private String afterWords(String a) {
        if (lang.equals("de") && (a.startsWith("Auffahrt") || a.startsWith("Ausfahrt") || a.startsWith("Kurve")
                || a.startsWith("Kehre") || a.startsWith("Ziel") || a.startsWith("Zwischenziel"))) {
            return a;
        }
        return Character.toLowerCase(a.charAt(0)) + a.substring(1);
    }

    /** The maneuver after the next one, said with it when it follows straight after: "then turn right". */
    public String then(Instructions.Maneuver m) {
        return thenWord() + afterWords(action(m));
    }

    private String thenWord() {
        return t(", then ", ", poi ", ", dann ", ", puis ", ", luego ", ", daarna ", ", depois ", ", potem ", ", sedan ");
    }

    /** The maneuver after the next one, on the screen under it: "Then turn right onto Via Roma". */
    public String thenShown(Instructions.Maneuver m) {
        return t("Then ", "Poi ", "Dann ", "Puis ", "Luego ", "Daarna ", "Depois ", "Potem ", "Sedan ") + afterWords(upcoming(m));
    }

    public String recalculating() {
        return t("Recalculating the route", "Ricalcolo del percorso", "Route wird neu berechnet", "Recalcul de l'itinéraire",
                "Recalculando la ruta", "Route wordt opnieuw berekend", "Recalculando a rota", "Ponowne wyznaczanie trasy",
                "Beräknar rutten på nytt");
    }

    /** The cold engine warning. */
    public String coldEngine() {
        return t("Engine still cold", "Motore ancora freddo", "Motor noch kalt", "Moteur encore froid",
                "Motor todavía frío", "Motor nog koud", "Motor ainda frio", "Silnik jest jeszcze zimny",
                "Motorn är fortfarande kall");
    }

    public String noRoute() {
        return t("No route found", "Nessun percorso trovato", "Keine Route gefunden", "Aucun itinéraire trouvé",
                "No se encontró ninguna ruta", "Geen route gevonden", "Nenhuma rota encontrada", "Nie znaleziono trasy",
                "Ingen rutt hittades");
    }

    /** "1st" / "prima" / "erste" / "première"…: the exit's number, in the form the roundabout phrase needs. */
    String ordinal(int n) {
        String[] w;
        switch (lang) {
            case "it": w = new String[]{"prima", "seconda", "terza", "quarta", "quinta", "sesta", "settima", "ottava",
                    "nona", "decima", "undicesima", "dodicesima"}; break;
            case "de": w = new String[]{"erste", "zweite", "dritte", "vierte", "fünfte", "sechste", "siebte", "achte",
                    "neunte", "zehnte", "elfte", "zwölfte"}; break;
            case "fr": w = new String[]{"première", "deuxième", "troisième", "quatrième", "cinquième", "sixième",
                    "septième", "huitième", "neuvième", "dixième", "onzième", "douzième"}; break;
            case "es": w = new String[]{"primera", "segunda", "tercera", "cuarta", "quinta", "sexta", "séptima",
                    "octava", "novena", "décima", "undécima", "duodécima"}; break;
            case "nl": w = new String[]{"eerste", "tweede", "derde", "vierde", "vijfde", "zesde", "zevende", "achtste",
                    "negende", "tiende", "elfde", "twaalfde"}; break;
            case "pt": w = new String[]{"primeira", "segunda", "terceira", "quarta", "quinta", "sexta", "sétima",
                    "oitava", "nona", "décima", "décima primeira", "décima segunda"}; break;
            // instrumental: "zjedź trzecim zjazdem"
            case "pl": w = new String[]{"pierwszym", "drugim", "trzecim", "czwartym", "piątym", "szóstym", "siódmym",
                    "ósmym", "dziewiątym", "dziesiątym", "jedenastym", "dwunastym"}; break;
            case "sv": w = new String[]{"första", "andra", "tredje", "fjärde", "femte", "sjätte", "sjunde", "åttonde",
                    "nionde", "tionde", "elfte", "tolfte"}; break;
            default: w = null;
        }
        if (w != null) return n >= 1 && n <= 12 ? w[n - 1] : n + (lang.equals("de") || lang.equals("pl") ? "." : "");
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
                return ft + t(" feet", " piedi", " Fuß", " pieds", " pies", " voet", " pés", " stóp", " fot");
            }
            String v = number(miles < 10 ? Math.round(miles * 10) / 10.0 : Math.round(miles));
            if (lang.equals("pl")) return v + polish(v, " mila", " mile", " mil", " mili");
            boolean one = one(v);
            return v + (one ? t(" mile", " miglio", " Meile", " mile", " milla", " mijl", " milha", "", " engelsk mil")
                    : t(" miles", " miglia", " Meilen", " miles", " millas", " mijl", " milhas", "", " engelska mil"));
        }
        if (m < 1000) {
            long r = m < 100 ? Math.round(m / 10) * 10 : m < 300 ? Math.round(m / 50) * 50 : Math.round(m / 100) * 100;
            if (r >= 1000) return distance(1000, false);
            return r + t(" metres", " metri", " Metern", " mètres", " metros", " meter", " metros", " metrów", " meter");
        }
        String v = number(m < 10_000 ? Math.round(m / 100) / 10.0 : Math.round(m / 1000));
        if (lang.equals("pl")) return v + polish(v, " kilometr", " kilometry", " kilometrów", " kilometra");
        boolean one = one(v);
        return v + (one ? t(" kilometre", " chilometro", " Kilometer", " kilomètre", " kilómetro", " kilometer",
                " quilômetro", "", " kilometer")
                : t(" kilometres", " chilometri", " Kilometern", " kilomètres", " kilómetros", " kilometer",
                " quilômetros", "", " kilometer"));
    }

    /** Singular: 1, and in French anything under 2 ("1,5 kilomètre"). */
    private boolean one(String v) {
        if (v.equals("1")) return true;
        return lang.equals("fr") && Double.parseDouble(v.replace(',', '.')) < 2;
    }

    /** Polish has three plural forms, and a fourth for a fraction: 1 kilometr, 2 kilometry, 5 kilometrów, 1,5 kilometra. */
    private static String polish(String v, String one, String few, String many, String fraction) {
        if (v.contains(",")) return fraction;
        long n = Long.parseLong(v);
        if (n == 1) return one;
        if (n % 10 >= 2 && n % 10 <= 4 && (n % 100 < 12 || n % 100 > 14)) return few;
        return many;
    }

    private String number(double v) {
        String s = v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
        return lang.equals("en") ? s : s.replace('.', ',');
    }

    // ------------------------------------------------------------------ curve and camera warnings

    /** "Sharp bend left": the kind of curve as on the dash's arrow (see {@link CurveAlerts#severity}). */
    public String curve(int turn) {
        boolean right = turn == 2 || turn == 4 || turn == 6 || turn == 8;
        String side = right ? t("right", "a destra", "rechts", "à droite", "a la derecha", "rechts", "à direita", "w prawo", "höger")
                : t("left", "a sinistra", "links", "à gauche", "a la izquierda", "links", "à esquerda", "w lewo", "vänster");
        switch (CurveAlerts.severity(turn)) {
            case 3: return t("Hairpin ", "Tornante ", "Kehre ", "Épingle ", "Curva de herradura ", "Haarspeldbocht ",
                    "Curva em U ", "Agrafka ", "Hårnålskurva ") + side;
            case 2: return t("Sharp bend ", "Curva stretta ", "Scharfe Kurve ", "Virage serré ", "Curva cerrada ",
                    "Scherpe bocht ", "Curva fechada ", "Ostry zakręt ", "Skarp kurva ") + side;
            case 1: return t("Bend ", "Curva ", "Kurve ", "Virage ", "Curva ", "Bocht ", "Curva ", "Zakręt ", "Kurva ") + side;
            default: return t("Slight bend ", "Curva leggera ", "Leichte Kurve ", "Virage léger ", "Curva suave ",
                    "Flauwe bocht ", "Curva suave ", "Łagodny zakręt ", "Svag kurva ") + side;
        }
    }

    /** "Sharp bend left, slow down, then bend right". {@code then} is a turn id, or -1. */
    public String curveWarning(int turn, boolean slowDown, int then) {
        String s = curve(turn) + (slowDown ? t(", slow down", ", rallenta", ", langsamer", ", ralentissez",
                ", reduce la velocidad", ", rem af", ", reduza a velocidade", ", zwolnij", ", sakta ner") : "");
        if (then < 0) return s;
        return s + thenWord() + afterWords(curve(then));
    }

    /** "Speed camera ahead, limit 50". */
    public String camera(String type, int maxspeed) {
        String name;
        switch (type) {
            case "red_light":
                name = t("Red-light camera ahead", "Telecamera semaforica più avanti", "Rotlichtblitzer voraus",
                        "Radar de feu rouge en vue", "Cámara de semáforo más adelante", "Roodlichtcamera verderop",
                        "Radar de semáforo à frente", "Przed tobą kamera na czerwone światło", "Rödljuskamera framöver");
                break;
            case "average_speed":
                name = t("Average-speed section ahead", "Tutor più avanti", "Abschnittskontrolle voraus",
                        "Radar tronçon en vue", "Tramo de velocidad media más adelante", "Trajectcontrole verderop",
                        "Trecho de velocidade média à frente", "Przed tobą odcinkowy pomiar prędkości",
                        "Medelhastighetskontroll framöver");
                break;
            default:
                name = t("Speed camera ahead", "Autovelox più avanti", "Blitzer voraus", "Radar en vue",
                        "Radar más adelante", "Flitser verderop", "Radar à frente", "Przed tobą fotoradar",
                        "Fartkamera framöver");
        }
        return maxspeed > 0 ? name + t(", limit ", ", limite ", ", Limit ", ", limite ", ", límite ", ", limiet ",
                ", limite ", ", ograniczenie ", ", gräns ") + maxspeed : name;
    }
}
