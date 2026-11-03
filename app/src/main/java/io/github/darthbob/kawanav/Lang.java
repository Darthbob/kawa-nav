package io.github.darthbob.kawanav;

import android.app.LocaleManager;
import android.content.Context;
import android.os.LocaleList;

import java.util.Locale;

/**
 * The app's language and its texts. The texts are Android string resources, one file per language
 * ({@code res/values/strings.xml} in English, {@code res/values-de/strings.xml} and so on); Android picks
 * the phone's language when the app has it, else English. Settings → Appearance → Language overrides it
 * with Android's per-app language, which Android keeps; "System default" goes back to the phone's.
 *
 * <p>Texts are looked up here rather than through a {@link Context}, so plain Java classes (the alerts,
 * the labels of the settings) can say things too. On the JVM, in the unit tests, the English file is read
 * directly ({@code TestStrings}), so their texts stay what the tests expect. Nothing may keep a looked-up
 * text in a static field: the language can change while the app runs.
 */
public final class Lang {
    private Lang() {}

    /** The languages the app has, in the order of the Settings choice; English first, the fallback. */
    public static final String[] CODES = {"en", "de", "it", "fr", "es", "nl", "pt", "pl", "sv"};
    /** Each language's own name, as the choice lists it. */
    public static final String[] NAMES = {"English", "Deutsch", "Italiano", "Français", "Español", "Nederlands",
            "Português", "Polski", "Svenska"};

    /** Where the texts come from: the app's resources, or the English file in the unit tests. */
    interface Source {
        String text(int id);

        String plural(int id, int count);
    }

    private static Source source;

    /** From {@link KawaApp}: the texts are the app's resources, in the language Android chose. */
    static void init(Context c) {
        Context app = c.getApplicationContext();
        source = new Source() {
            @Override
            public String text(int id) {
                return app.getString(id);
            }

            @Override
            public String plural(int id, int count) {
                return app.getResources().getQuantityString(id, count);
            }
        };
    }

    /** For the unit tests. */
    static void use(Source s) {
        source = s;
    }

    private static Source source() {
        if (source == null) {
            // not on a phone: the unit tests put the English file in place
            try {
                Class.forName("io.github.darthbob.kawanav.TestStrings").getMethod("install").invoke(null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Lang used before KawaApp set it up", e);
            }
        }
        return source;
    }

    /** A text ({@code R.string}). */
    public static String t(int id) {
        return source().text(id);
    }

    /**
     * A text with values in it ({@code %1$s}, {@code %1$d}…). Numbers are written as everywhere else in the
     * app (Locale.ROOT: "4.2 km"), whatever the language.
     */
    public static String t(int id, Object... args) {
        return String.format(Locale.ROOT, source().text(id), args);
    }

    /** A text that depends on a count ({@code R.plurals}): "1 bar", "4 bars"; the count is its first value. */
    public static String plural(int id, int count, Object... args) {
        String text = source().plural(id, count);
        return args.length == 0 ? String.format(Locale.ROOT, text, count) : String.format(Locale.ROOT, text, args);
    }

    /** The language the texts are in right now, one of {@link #CODES}: the voice speaks it too. */
    public static String code() {
        return t(R.string.lang_code);
    }

    /** The choice in the settings: 0 for the phone's language, else 1 + the index into {@link #CODES}. */
    public static int chosen(Context c) {
        LocaleList l = c.getSystemService(LocaleManager.class).getApplicationLocales();
        if (l.isEmpty()) return 0;
        String lang = l.get(0).getLanguage();
        for (int i = 0; i < CODES.length; i++) if (CODES[i].equals(lang)) return i + 1;
        return 0;
    }

    /** Sets the app's language (0: the phone's again); Android recreates the open screens in it. */
    public static void choose(Context c, int choice) {
        LocaleList l = choice <= 0 || choice > CODES.length ? LocaleList.getEmptyLocaleList()
                : LocaleList.forLanguageTags(CODES[choice - 1]);
        c.getSystemService(LocaleManager.class).setApplicationLocales(l);
    }
}
