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
 * "|ziel|" not in "Zielstraße"). Only German and English
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
            "rotonda", "glorieta", "rotunda", "rotatória", "rotonde", "rondo", "rondzie", "kruhov", "rondell",
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
            "cerrad", "nettamente", "scherp", "acentuad", "ostro", "ostře", "skarpt", "skarp", "jyrkästi",
            "απότομα", "резко"};
    static final String[] SLIGHT = {"leicht", "slight", "halb", "légèrement", "ligeramente", "levemente",
            "leggermente", "flauw", "licht ", "ligeiramente", "lekko", "mírně", "svagt", "lätt ", "let ",
            "svakt", "slakt", "loivasti", "ελαφρώς", "плавно", "слегка"};
    static final String[] MERGE = {"einfädeln", "merge", "insérez", "insertion", "incorpór", "incorpor",
            "immett", "voeg in", "invoeg", "junte-se", "włącz", "připojte", "zařaďte", "anslut", "flet",
            "liity", "συγχων"};
    static final String[] ON_RAMP = {"auffahrt", "auffahren", "ramp", "bretelle", "acceso", "entrata",
            "rampa di accesso", "oprit", "rampa de acesso", "wjazd", "nájezd", "påfart", "tilkørsel",
            "påkørsel", "påkjørsel", "ράμπα", "въезд"};
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
        return isRoundabout(text) && roundaboutExit(text.toLowerCase(Locale.ROOT).replaceAll("\\([^)]*\\)", " ")) > 0;
    }

    /** Kawasaki turn id for an instruction, or TURN_NONE. */
    public static int parseTurn(String text, boolean leftHandTraffic) {
        if (text == null) return KawaProtocol.TURN_NONE;
        // drop signpost text like "(Schilder nach München/A95/…)": place names are not instructions
        String t = text.toLowerCase(Locale.ROOT).replaceAll("\\([^)]*\\)", " ");
        boolean left = has(t, LEFT);
        boolean right = has(t, RIGHT);

        if (has(t, ROUNDABOUT)) {
            int exit = roundaboutExit(t);
            if (exit < 1) {
                // no number: "Im Kreisverkehr geradeaus/rechts/links…" (seen in Maps, Oct 2026)
                if (has(t, U_TURN)) exit = 4;
                else if (right) exit = leftHandTraffic ? 3 : 1;
                else if (left) exit = leftHandTraffic ? 1 : 3;
                else exit = 2; // geradeaus / straight / unknown
            }
            exit = Math.min(exit, 12);
            // right-hand traffic drives counter-clockwise (ids 30..41), left-hand clockwise (18..29)
            return leftHandTraffic ? 17 + exit : 29 + exit;
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

    private static int roundaboutExit(String t) {
        Matcher m = EXIT_NUMBER.matcher(t);
        if (m.find()) {
            String g = m.group(1) != null ? m.group(1) : m.group(2);
            try {
                return Integer.parseInt(g);
            } catch (NumberFormatException ignored) {
                // fall through to ordinal words
            }
        }
        for (int i = 0; i < ORDINALS.length; i++) {
            if (has(t, ORDINALS[i])) return i + 1;
        }
        return -1;
    }

    private static boolean has(String t, String... words) {
        for (String w : words) {
            if (w.indexOf('|') < 0 ? t.contains(w) : hasAnchored(t, w)) return true;
        }
        return false;
    }

    /** Match for a word with "|" anchors: no letter may touch an anchored side. */
    private static boolean hasAnchored(String t, String w) {
        boolean start = w.startsWith("|"), end = w.endsWith("|");
        String core = w.substring(start ? 1 : 0, w.length() - (end ? 1 : 0));
        for (int i = t.indexOf(core); i >= 0; i = t.indexOf(core, i + 1)) {
            int j = i + core.length();
            if ((!start || i == 0 || !Character.isLetter(t.charAt(i - 1)))
                    && (!end || j == t.length() || !Character.isLetter(t.charAt(j)))) return true;
        }
        return false;
    }
}
