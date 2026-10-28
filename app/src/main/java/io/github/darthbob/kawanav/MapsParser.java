package io.github.darthbob.kawanav;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the text of Google Maps' navigation notification into a Kawasaki turn id and a distance.
 * The arrow mostly comes from Maps' icon (see {@link IconMemory}); the text decides roundabout
 * exit numbers, ramps / exits / merges, and is the fallback for unknown icons.
 *
 * Languages: German, English, French, Spanish, Italian, Dutch, Portuguese, Polish, Czech, Swedish,
 * Danish, Norwegian, Finnish, Greek, Russian. Words are matched as lower-case substrings, so stems
 * cover inflections ("prawo" in "w prawo", "oikeal" in "oikealle"). A "|" at the start / end of a
 * word anchors it to a word boundary, so street names don't match ("|right" not in "Wright Street",
 * "|ziel|" not in "Zielstraße"). Only German, English and Italian
 * wording is verified against real notifications; the others follow Google's usual phrasing.
 */
public final class MapsParser {
    private MapsParser() {}

    // "250 m", "1,2 km", "0.3 mi", "500 ft", "200 yd", "300 м", "1,5 км", "300 μ", "1,5 χλμ"
    private static final Pattern DISTANCE = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(km|mi|ft|yd|m|км|м|χλμ|μ)(?!\\p{L})", Pattern.CASE_INSENSITIVE);

    static final String[] LEFT = {"links", "|left", "gauche", "izquierda", "sinistra", "esquerda", "lewo",
            "lewej", "vlevo", "levém", "doleva", "vänster", "venstre", "vasemm", "αριστερ", "налево", "влево", "слева", "левее"};
    static final String[] RIGHT = {"rechts", "|right", "droite", "derecha", "destra", "direita", "prawo",
            "prawej", "vpravo", "pravém", "doprava", "höger", "højre", "høyre", "oikeal", "δεξι", "направо", "вправо", "справа", "правее"};
    static final String[] ROUNDABOUT = {"kreisverkehr", "kreisel", "roundabout", "traffic circle", "rotary", "rond-point",
            "rotonda", "rotatoria", "glorieta", "rotunda", "rotatória", "rotonde", "rondo", "rondzie", "kruhov", "rondell",
            "rundkørs", "rundkjøring", "liikenneympyr", "κυκλικ", "кругов"};
    static final String[] U_TURN = {"wenden", "u-turn", "kehrt", "umkehren", "demi-tour", "cambio de sentido",
            "media vuelta", "da la vuelta", "inversione", "inverti", "keer om", "omkeren", "retorno", "inversão",
            "zawróć", "zawracaj", "otočte", "otočit", "u-sväng", "vänd", "u-vending", "vend om", "u-sving",
            "snu", "u-käännös", "käänny ympäri", "αναστροφή", "разворот", "развернитесь"};
    static final String[] DESTINATION = {"|ziel|", "ankunft", "angekommen", "destination", "arriv", "tu destino",
            "has llegado", "destinazione", "bestemming", "aangekomen", "o destino", "seu destino", "chegou",
            "docelow", "dotarłeś", "cíl", "framme", "ankommet", "destinasjon", "fremme", "määränpää",
            "perillä", "προορισμ", "назначени", "прибыл"};
    static final String[] SHARP = {"scharf", "sharp", "fortement", "franchement", "bruscamente", "pronunciad",
            "cerrad", "nettamente", "|tutto a ", "scherp", "acentuad", "ostro", "ostře", "skarpt", "skarp", "jyrkästi",
            "απότομα", "резко"};
    static final String[] SLIGHT = {"leicht", "slight", "halb", "légèrement", "ligeramente", "levemente",
            "leggermente", "flauw", "licht ", "ligeiramente", "lekko", "mírně", "svagt", "lätt ", "let ",
            "svakt", "slakt", "loivasti", "ελαφρώς", "плавно", "слегка"};
    // "|merge" and "|ramp|"/"|rampa|" are anchored: "emergent" contains "merge", and "Rampart Street"
    // starts with "ramp", so neither may turn the arrow on its own. Inflected forms are listed in their
    // own right ("rampa di accesso", "insertion", …) instead of loosening the anchor.
    static final String[] MERGE = {"einfädeln", "|merge", "insérez", "insertion", "incorpór", "incorpor",
            "immett", "voeg in", "invoeg", "junte-se", "włącz", "připojte", "zařaďte", "anslut", "flet",
            "liity", "συγχων"};
    static final String[] ON_RAMP = {"auffahrt", "auffahren", "|ramp|", "|rampa|", "bretelle", "acceso",
            "entrata", "rampa di accesso", "oprit", "rampa de acesso", "wjazd", "nájezd", "påfart",
            "tilkørsel", "påkjørsel", "ράμπα", "въезд"};
    static final String[] EXIT = {"ausfahrt", "|exit|", "sortie", "salida", "uscita", "afrit",
            "afslag", "saída", "zjazd", "zjedź", "výjezd", "sjezd", "avfart", "frakørsel", "avkjørsel",
            "avkjøring", "poistu", "έξοδο", "съезд", "съезжайте"};
    static final String[] KEEP = {"halten", "keep", "gabelung", "fork", "serrez", "restez", "bifurcation",
            "mantente", "manténgase", "mantén", "bifurcación", "mantieni", "tieniti", "bivio", "houd ",
            "aanhouden", "splitsing", "mantenha", "bifurcação", "trzymaj", "rozwidl", "držte", "drž se",
            "rozcest", "håll ", "hold ", "pysy", "κρατηθείτε", "διακλάδωση", "держитесь", "развилк"};
    static final String[] MIDDLE = {"mitte", "middle", "center", "milieu", "centre", "centro", "medio",
            "centrale", "midden", "meio", "środk", "střed", "mitten", "midt", "keski", "μεσαί", "κέντρο",
            "средн", "центр"};
    static final String[] STRAIGHT = {"weiter", "geradeaus", "continue", "straight", "continuez", "tout droit",
            "continúa", "continúe", "recto", "continua", "prosegui", "dritto", "diritto", "ga verder",
            "rechtdoor", "em frente", "prosto", "kontynuuj", "jedź dalej", "rovně", "pokračujte", "rakt fram",
            "fortsätt", "ligeud", "fortsæt", "rett frem", "fortsett", "suoraan", "jatka", "ευθεία",
            "συνεχίστε", "прямо", "продолжайте"};
    static final String[] DEPART = {"richtung", "head ", "fahren sie", "dirigez-vous", "prenez la direction",
            "dirígete", "dirigete", "diríjase", "procedi", "dirigiti", "richting", "dirija-se", "siga ",
            "kieruj się", "jeďte", "směrem", "kör ", "kør ", "kjør ", "aja ", "κατευθυνθείτε", "κινηθείτε",
            "направляйтесь", "двигайтесь", "поезжайте"};

    // roundabout exit number next to the word for exit: "2. Ausfahrt", "Ausfahrt 2", "3rd exit",
    // "2e sortie", "2.ª salida", "2ª uscita", "2e afslag", "2. zjazdem", "2-й съезд", "2η έξοδο"
    private static final String EXIT_WORD = "(?:ausfahrt|exit|sortie|salida|uscita|afslag|afrit|saída|zjazd"
            + "|výjezd|avfart|frakørsel|avkjørsel|avkjøring|liittym|έξοδο|съезд|выезд)";
    private static final Pattern EXIT_NUMBER = Pattern.compile(
            "(\\d{1,2})[^\\d\\s]{0,4}\\s*" + EXIT_WORD + "|" + EXIT_WORD + "\\p{L}*\\s*(\\d{1,2})(?!\\d)");

    /** Ordinal words 1st..8th, per number all languages (only used inside a roundabout instruction). */
    private static final String[][] ORDINALS = {
            {"erste", "first", "première", "premier", "primera", "prima", "eerste", "primeira", "pierwsz",
                    "první", "första", "første", "ensimmäi", "πρώτη", "перв"},
            {"zweite", "second", "deuxième", "segunda", "seconda", "tweede", "drugi", "druh", "andra",
                    "anden", "andre", "toise", "δεύτερη", "втор"},
            {"dritte", "third", "troisième", "tercera", "terza", "derde", "terceira", "trzeci", "třetí",
                    "tredje", "kolmanne", "τρίτη", "трет"},
            {"vierte", "fourth", "quatrième", "cuarta", "quarta", "vierde", "czwart", "čtvrt", "fjärde",
                    "fjerde", "neljänne", "τέταρτη", "четв"},
            {"fünfte", "fifth", "cinquième", "quinta", "vijfde", "piąt", "pát", "femte", "viidenne",
                    "πέμπτη", "пят"},
            {"sechste", "sixth", "sixième", "sexta", "sesta", "zesde", "szóst", "šest", "sjätte", "sjette",
                    "kuudenne", "έκτη", "шест"},
            {"siebte", "seventh", "septième", "séptima", "settima", "zevende", "sétima", "siódm", "sedm",
                    "sjunde", "syvende", "sjuende", "seitsemänne", "έβδομη", "седьм"},
            {"achte", "eighth", "huitième", "octava", "ottava", "achtste", "oitava", "ósm", "osm", "åttonde",
                    "ottende", "åttende", "kahdeksanne", "όγδοη", "восьм"},
    };

    /** Distance in metres, or -1. */
    public static int parseDistance(String text) {
        if (text == null) return -1;
        Matcher m = DISTANCE.matcher(text);
        if (!m.find()) return -1;
        double v;
        try {
            v = Double.parseDouble(m.group(1).replace(',', '.'));
        } catch (NumberFormatException e) {
            return -1;
        }
        switch (m.group(2).toLowerCase(Locale.ROOT)) {
            case "km":
            case "км":
            case "χλμ": return (int) Math.round(v * 1000);
            case "mi": return (int) Math.round(v * 1609.344);
            case "ft": return (int) Math.round(v * 0.3048);
            case "yd": return (int) Math.round(v * 0.9144);
            default: return (int) Math.round(v);
        }
    }

    public static boolean isRoundabout(String text) {
        return text != null && has(text.toLowerCase(Locale.ROOT), ROUNDABOUT);
    }

    /** True when the text names the roundabout exit by number ("die 2. Ausfahrt", "3rd exit"). */
    public static boolean hasRoundaboutExitNumber(String text) {
        if (text == null || !isRoundabout(text)) return false;
        return exitIn(text.toLowerCase(Locale.ROOT).replaceAll("\\([^)]*\\)", " ")) > 0;
    }

    /** The roundabout exit the text names, or -1: see {@link #exitIn}. */
    public static int roundaboutExit(String text) {
        return text == null ? -1 : exitIn(text.toLowerCase(Locale.ROOT).replaceAll("\\([^)]*\\)", " "));
    }

    /**
     * Kawasaki turn id of a roundabout exit (1..12), in the rotation of the traffic side: right-hand
     * traffic drives counter-clockwise (ids 30..41), left-hand traffic clockwise (18..29).
     */
    public static int roundaboutTurn(int exit, boolean leftHandTraffic) {
        exit = Math.max(1, Math.min(exit, 12));
        return leftHandTraffic ? 17 + exit : 29 + exit;
    }

    /** The exit number a roundabout turn id shows, or -1 when the id is not a roundabout exit. */
    public static int roundaboutExitOf(int turn) {
        if (turn >= 18 && turn <= 29) return turn - 17;
        if (turn >= 30 && turn <= 41) return turn - 29;
        return -1;
    }

    /** True when the text names a side at all (left / right in any of the supported languages). */
    public static boolean sideKnown(String text) {
        if (text == null) return false;
        String t = text.toLowerCase(Locale.ROOT).replaceAll("\\([^)]*\\)", " ");
        return has(t, LEFT) || has(t, RIGHT);
    }

    /** Kawasaki turn id for an instruction, or TURN_NONE. */
    public static int parseTurn(String text, boolean leftHandTraffic) {
        if (text == null) return KawaProtocol.TURN_NONE;
        // drop signpost text like "(Schilder nach München/A95/…)": place names are not instructions
        String t = text.toLowerCase(Locale.ROOT).replaceAll("\\([^)]*\\)", " ");
        boolean left = has(t, LEFT);
        boolean right = has(t, RIGHT);

        if (has(t, ROUNDABOUT)) {
            int exit = exitIn(t);
            if (exit < 1) {
                // no number: "Im Kreisverkehr geradeaus/rechts/links…" (seen in Maps, Oct 2026)
                if (has(t, U_TURN)) exit = 4;
                else if (right) exit = leftHandTraffic ? 3 : 1;
                else if (left) exit = leftHandTraffic ? 1 : 3;
                else exit = 2; // geradeaus / straight / unknown
            }
            return roundaboutTurn(exit, leftHandTraffic);
        }
        if (has(t, U_TURN)) {
            if (right) return 8;
            if (left) return 7;
            return leftHandTraffic ? 8 : 7;
        }
        if (has(t, DESTINATION)) return 43;
        if (has(t, SHARP)) {
            if (right) return 4;
            if (left) return 3;
        }
        if (has(t, SLIGHT)) {
            if (right) return 6;
            if (left) return 5;
        }
        if (has(t, MERGE)) return right ? 17 : 16;
        // an exit word wins: "bretelle de sortie", "rampa de salida" are exits
        if (has(t, EXIT)) return left ? 14 : 15;
        if (has(t, ON_RAMP)) return left ? 12 : 13;
        if (has(t, KEEP)) {
            if (has(t, MIDDLE)) return 11;
            if (right) return 10;
            if (left) return 9;
        }
        if (right) return 2;
        if (left) return 1;
        if (has(t, STRAIGHT)) return 0;
        if (has(t, DEPART)) return 42;
        return KawaProtocol.TURN_NONE;
    }

    /**
     * The exit number of a roundabout instruction, or -1. The digits must sit next to the word for
     * exit; an ordinal word only counts inside the clause the roundabout (or exit) word is in — a
     * place name ("Prima Straße, dann Kreisverkehr") must not decide which exit to take.
     */
    private static int exitIn(String t) {
        Matcher m = EXIT_NUMBER.matcher(t);
        if (m.find()) {
            String g = m.group(1) != null ? m.group(1) : m.group(2);
            try {
                return Integer.parseInt(g);
            } catch (NumberFormatException ignored) {
                // fall through to ordinal words
            }
        }
        // The ordinal counts from the word it belongs to: "Kreisverkehr, zweite Ausfahrt" names the
        // exit in the second clause, and "die zweite Ausfahrt" does so without naming a roundabout.
        // The roundabout word is the fallback ("At the roundabout, take the second exit" has both).
        int at = indexOf(t, EXIT);
        if (at < 0) at = indexOf(t, ROUNDABOUT);
        String clause = clauseAt(t, at);
        for (int i = 0; i < ORDINALS.length; i++) {
            if (has(clause, ORDINALS[i])) return i + 1;
        }
        return -1;
    }

    /** Clause separators: the exit number is not taken from a different part of the instruction. */
    private static final Pattern CLAUSE = Pattern.compile("[.,;:!?·]|\\bdann\\b|\\bthen\\b");

    /**
     * The clause of {@code t} that names the maneuver, and the one after it. The number and the word it
     * counts do not always share a clause: "Kreisverkehr, zweite Ausfahrt" puts the roundabout in the
     * first and the exit in the second. A clause in front of it is never looked at, which is what keeps
     * "Prima Straße, dann Kreisverkehr" from choosing an exit.
     */
    private static String clauseAt(String t, int at) {
        if (at < 0) return "";
        Matcher m = CLAUSE.matcher(t);
        int start = 0, seps = 0;
        while (m.find()) {
            if (m.end() <= at) {
                start = m.end();
            } else if (++seps == 1) {
                continue;                       // the clause's own end: the next one is included
            } else {
                return t.substring(start, m.start());
            }
        }
        return t.substring(start);
    }

    /**
     * The arrow to show, from the instruction text and the arrow Maps drew in the notification
     * (null: no icon). The icon wins: it shows the side Maps committed to, while the text often
     * names no side at all. The text still decides the maneuver kind it is sure about, and an
     * explicit roundabout exit number.
     */
    public static int chooseTurn(String text, Integer iconTurn, boolean leftHandTraffic) {
        int parsed = parseTurn(text, leftHandTraffic);
        if (iconTurn == null) return parsed;
        if (text != null && isRoundabout(text) && hasRoundaboutExitNumber(text)) {
            // the icon shows the exit's direction, not its number: the text knows the number, the
            // icon only the rotation, so take the text's number in the icon's direction
            int exit = roundaboutExit(text);
            int exits = roundaboutExitOf(iconTurn);
            boolean clockwise = exits < 0 || iconTurn < 30;
            return roundaboutTurn(exit, clockwise);
        }
        // A roundabout id carries the exit and its rotation: the text's "rechts" beside the word for
        // roundabout means the same thing, and Maps' arrow is the one that counted the exits.
        if (iconTurn >= 18 && iconTurn <= 41) return iconTurn;
        // A roundabout text names no kind the icon could be missing: the arrow Maps drew is the one it
        // counted the exits with, so the text's vague "rechts" does not turn it into an exit id here.
        if (text != null && isRoundabout(text)) return iconTurn;
        int kind = kindOf(text);
        if (kind == KawaProtocol.TURN_NONE) {
            // no kind to take from the text: the icon stands, unless the text names a side of its own
            return sideKnown(text) ? parsed : iconTurn;
        }
        // The text is sure of the kind, the icon of the side: a plain right arrow with "Take the ramp"
        // is the ramp on the right (13), not the plain turn (2). A side in the text wins over the icon.
        Boolean left = sideOf(text);
        if (left == null) left = sideOfIcon(iconTurn);
        return left == null ? parsed : kindTurn(kind, left);
    }

    /** The side the text names, or null when it names none. */
    private static Boolean sideOf(String text) {
        String t = text.toLowerCase(Locale.ROOT).replaceAll("\\([^)]*\\)", " ");
        int l = indexOf(t, LEFT), r = indexOf(t, RIGHT);
        if (l < 0 && r < 0) return null;
        if (l < 0) return Boolean.FALSE;
        return r < 0 || l < r;
    }

    /** The side a turn id points to, or null when it has none (straight, fork middle, destinations). */
    static Boolean sideOfIcon(int turn) {
        switch (turn) {
            case 1: case 3: case 5: case 7: case 9: case 12: case 14: case 16: return Boolean.TRUE;
            case 2: case 4: case 6: case 8: case 10: case 13: case 15: case 17: return Boolean.FALSE;
            default: return null;
        }
    }

    /** The turn id of a maneuver kind in a side: ramp, exit or merge, left or right. */
    private static int kindTurn(int kind, boolean left) {
        if (kind == KawaProtocol.TURN_MERGE) return left ? 16 : 17;
        if (kind == KawaProtocol.TURN_EXIT) return left ? 14 : 15;
        return left ? 12 : 13;
    }

    /**
     * The maneuver kind of an instruction, or {@link KawaProtocol#TURN_NONE} when it names none: a
     * kind is what the text can override on an icon (a ramp and a turn share one arrow), and what an
     * exit id requires.
     */
    public static int kindOf(String text) {
        if (text == null) return KawaProtocol.TURN_NONE;
        String t = text.toLowerCase(Locale.ROOT).replaceAll("\\([^)]*\\)", " ");
        if (has(t, MERGE)) return KawaProtocol.TURN_MERGE;
        if (has(t, EXIT)) return KawaProtocol.TURN_EXIT;    // "bretelle de sortie" is an exit, not a ramp
        if (has(t, ON_RAMP)) return KawaProtocol.TURN_RAMP;
        return KawaProtocol.TURN_NONE;
    }

    private static boolean has(String t, String... words) {
        return indexOf(t, words) >= 0;
    }

    /** Position of the first match of any of these words, or -1. */
    private static int indexOf(String t, String... words) {
        int best = -1;
        for (String w : words) {
            int at = w.indexOf('|') < 0 ? t.indexOf(w) : indexOfAnchored(t, w);
            if (at >= 0 && (best < 0 || at < best)) best = at;
        }
        return best;
    }

    /** Match for a word with "|" anchors: no letter may touch an anchored side. */
    private static int indexOfAnchored(String t, String w) {
        boolean start = w.startsWith("|"), end = w.endsWith("|");
        String core = w.substring(start ? 1 : 0, w.length() - (end ? 1 : 0));
        for (int i = t.indexOf(core); i >= 0; i = t.indexOf(core, i + 1)) {
            int j = i + core.length();
            if ((!start || i == 0 || !Character.isLetter(t.charAt(i - 1)))
                    && (!end || j == t.length() || !Character.isLetter(t.charAt(j)))) return i;
        }
        return -1;
    }
}
