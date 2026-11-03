package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every language has every text of the English file, no more, and with the same values in it ({@code %1$s},
 * {@code %2$d}…), so no screen shows a missing text or crashes on a format. Run after any change to a
 * strings.xml; the message lists what is missing where. (Lint's MissingTranslation checks the keys too, but
 * not the values in them.)
 */
public class StringsTest {
    private static final Pattern FORMAT = Pattern.compile("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[sdfxXc%]");
    /** A quote or apostrophe Android would drop: not escaped with a backslash. */
    private static final Pattern BARE_QUOTE = Pattern.compile("(?<!\\\\)['\"]");

    @After
    public void english() {
        TestStrings.install();
    }

    private static Map<String, String> texts(String folder, Map<String, Map<String, String>> plurals) {
        Map<String, String> texts = new HashMap<>();
        TestStrings.read(new File(TestStrings.RES, folder + "/strings.xml"), texts, plurals);
        return texts;
    }

    @Test
    public void everyLanguageHasEveryTextWithTheSameValues() {
        Map<String, Map<String, String>> enPlurals = new HashMap<>();
        Map<String, String> en = texts("values", enPlurals);
        List<String> problems = new ArrayList<>();
        for (String code : Lang.CODES) {
            if (code.equals("en")) continue;
            String folder = "values-" + code;
            Map<String, Map<String, String>> plurals = new HashMap<>();
            Map<String, String> t = texts(folder, plurals);
            for (String k : new TreeSet<>(en.keySet())) {
                if (!t.containsKey(k)) problems.add(folder + ": missing " + k);
                else if (!formats(t.get(k)).equals(formats(en.get(k)))) {
                    problems.add(folder + ": " + k + " has " + formats(t.get(k)) + ", English " + formats(en.get(k)));
                }
            }
            for (String k : new TreeSet<>(t.keySet())) if (!en.containsKey(k)) problems.add(folder + ": extra " + k);
            for (String k : new TreeSet<>(enPlurals.keySet())) {
                Map<String, String> q = plurals.get(k);
                if (q == null) {
                    problems.add(folder + ": missing plural " + k);
                    continue;
                }
                for (String quantity : quantities(code)) {
                    String s = q.get(quantity);
                    if (s == null) problems.add(folder + ": plural " + k + " has no " + quantity);
                    else if (!formats(s).equals(formats(enPlurals.get(k).get("other")))) {
                        problems.add(folder + ": plural " + k + " " + quantity + " has " + formats(s));
                    }
                }
            }
            for (String k : plurals.keySet()) if (!enPlurals.containsKey(k)) problems.add(folder + ": extra plural " + k);
            if (!code.equals(t.get("lang_code"))) problems.add(folder + ": lang_code is " + t.get("lang_code"));
        }
        if (!problems.isEmpty()) fail(problems.size() + " problems:\n" + String.join("\n", problems));
    }

    /** The plural forms each language needs on Android (CLDR). */
    static List<String> quantities(String lang) {
        switch (lang) {
            case "pl": return Arrays.asList("one", "few", "many", "other");
            case "fr": case "it": case "es": case "pt": return Arrays.asList("one", "many", "other");
            default: return Arrays.asList("one", "other");
        }
    }

    private static List<String> formats(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = FORMAT.matcher(s);
        while (m.find()) if (!m.group().equals("%%")) out.add(m.group());
        Collections.sort(out);
        return out;
    }

    @Test
    public void quotesAreEscaped() throws Exception {
        for (String code : Lang.CODES) {
            String folder = code.equals("en") ? "values" : "values-" + code;
            String xml = new String(Files.readAllBytes(new File(TestStrings.RES, folder + "/strings.xml").toPath()),
                    StandardCharsets.UTF_8);
            // the text between the tags only: attributes and comments have their own quotes
            Matcher m = Pattern.compile(">([^<]*)</(string|item)>").matcher(xml);
            while (m.find()) {
                assertTrue(folder + ": unescaped quote in \"" + m.group(1) + "\"", !BARE_QUOTE.matcher(m.group(1)).find());
            }
        }
    }

    @Test
    public void theLanguagesAreTheOnesAndroidIsToldAbout() throws Exception {
        String config = new String(Files.readAllBytes(new File(TestStrings.RES, "xml/locales_config.xml").toPath()),
                StandardCharsets.UTF_8);
        List<String> listed = new ArrayList<>();
        Matcher m = Pattern.compile("android:name=\"([a-z]+)\"").matcher(config);
        while (m.find()) listed.add(m.group(1));
        assertEquals(Arrays.asList(Lang.CODES), listed);
        assertEquals(Lang.CODES.length, Lang.NAMES.length);
        for (String code : Lang.CODES) {
            if (!code.equals("en")) assertTrue(code, new File(TestStrings.RES, "values-" + code + "/strings.xml").isFile());
        }
    }
}
