package io.github.darthbob.kawanav;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * The app's texts on the JVM: the string resources read straight from a {@code values} file, so classes
 * that say things through {@link Lang} work in the unit tests, in English unless a test asks for another
 * language. {@link Lang} installs it by itself the first time a text is asked for.
 */
public final class TestStrings {
    private TestStrings() {}

    /** The resource folder (the tests run in the app module). */
    static final File RES = new File("src/main/res");

    /** English, as on a phone without one of the app's other languages. */
    public static void install() {
        use("values");
    }

    /** The texts of one language folder, "values" (English) or "values-de" and so on. */
    public static void use(String folder) {
        Map<String, String> texts = new HashMap<>();
        Map<String, Map<String, String>> plurals = new HashMap<>();
        read(new File(RES, folder + "/strings.xml"), texts, plurals);
        Map<Integer, String> byId = new HashMap<>();
        Map<Integer, Map<String, String>> pluralsById = new HashMap<>();
        try {
            for (Field f : R.string.class.getFields()) {
                if (texts.containsKey(f.getName())) byId.put(f.getInt(null), texts.get(f.getName()));
            }
            for (Field f : R.plurals.class.getFields()) {
                if (plurals.containsKey(f.getName())) pluralsById.put(f.getInt(null), plurals.get(f.getName()));
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        String lang = folder.equals("values") ? "en" : folder.substring("values-".length());
        Lang.use(new Lang.Source() {
            @Override
            public String text(int id) {
                String s = byId.get(id);
                if (s == null) throw new IllegalArgumentException("no text " + id + " in " + folder);
                return s;
            }

            @Override
            public String plural(int id, int count) {
                Map<String, String> q = pluralsById.get(id);
                if (q == null) throw new IllegalArgumentException("no plural " + id + " in " + folder);
                String s = q.get(quantity(lang, count));
                return s != null ? s : q.get("other");
            }
        });
    }

    /** The plural form a count takes (CLDR, for whole numbers), in the app's languages. */
    static String quantity(String lang, int n) {
        switch (lang) {
            case "pl":
                if (n == 1) return "one";
                if (n % 10 >= 2 && n % 10 <= 4 && (n % 100 < 12 || n % 100 > 14)) return "few";
                return "many";
            case "fr":
                return n == 0 || n == 1 ? "one" : n != 0 && n % 1_000_000 == 0 ? "many" : "other";
            case "pt":
                return n == 0 || n == 1 ? "one" : n != 0 && n % 1_000_000 == 0 ? "many" : "other";
            case "it":
            case "es":
                return n == 1 ? "one" : n != 0 && n % 1_000_000 == 0 ? "many" : "other";
            default:
                return n == 1 ? "one" : "other";
        }
    }

    /** Reads a strings.xml: its texts, and its plurals by quantity, as Android would give them back. */
    static void read(File file, Map<String, String> texts, Map<String, Map<String, String>> plurals) {
        try {
            Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getDocumentElement();
            NodeList strings = root.getElementsByTagName("string");
            for (int i = 0; i < strings.getLength(); i++) {
                Element e = (Element) strings.item(i);
                texts.put(e.getAttribute("name"), unescape(e.getTextContent()));
            }
            NodeList ps = root.getElementsByTagName("plurals");
            for (int i = 0; i < ps.getLength(); i++) {
                Element p = (Element) ps.item(i);
                Map<String, String> q = new HashMap<>();
                NodeList items = p.getChildNodes();
                for (int j = 0; j < items.getLength(); j++) {
                    Node n = items.item(j);
                    if (n instanceof Element) q.put(((Element) n).getAttribute("quantity"), unescape(n.getTextContent()));
                }
                plurals.put(p.getAttribute("name"), q);
            }
        } catch (Exception e) {
            throw new IllegalStateException("cannot read " + file + ": " + e, e);
        }
    }

    /** Android's escapes in string resources: \' \" \n \@ \? \\. */
    static String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
