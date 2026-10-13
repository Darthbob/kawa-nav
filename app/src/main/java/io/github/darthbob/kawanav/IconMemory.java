package io.github.darthbob.kawanav;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps Google Maps notification icons to Kawasaki turn ids.
 *
 * Maps draws the notification arrow from its maneuver drawables, so the icon identifies the
 * maneuver independent of language and wording. Sources: icons taught by hand (corrections) first,
 * then the library of all Maps maneuver icons ({@link MapsIcons}).
 *
 * Icons are compared as a 32x32 mask of the drawn area (cropped to its bounding box, so padding and
 * scale do not matter) with a small tolerance for antialiasing.
 */
public final class IconMemory {
    private static final int SIZE = 32;
    private static final int MAX_DIFF_BITS = 40; // ~4 % of the mask
    private static final int MAX_COLLECTED = 500;

    public static final String SOURCE_TAUGHT = "taught icon";
    public static final String SOURCE_LIBRARY = "Maps icon";
    /** Library value for icons without direction information: ignore, use the text. */
    public static final int NOT_A_MANEUVER = -1;

    /** Lookup result. */
    public static final class Hit {
        public final int turn;
        public final String source;

        Hit(int turn, String source) {
            this.turn = turn;
            this.source = source;
        }
    }

    private final Context context;
    private final SharedPreferences taught;

    public IconMemory(Context context) {
        this.context = context.getApplicationContext();
        taught = this.context.getSharedPreferences("icons", Context.MODE_PRIVATE);
        // automatic learning was replaced by the Maps icon library; drop its old data
        this.context.getSharedPreferences("icons_learned", Context.MODE_PRIVATE).edit().clear().apply();
    }

    // ------------------------------------------------------------------ hashing

    public static Bitmap render(Drawable d) {
        Bitmap b = Bitmap.createBitmap(SIZE * 4, SIZE * 4, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, c.getWidth(), c.getHeight());
        d.draw(c);
        return b;
    }

    /** Hex string of the 32x32 mask of the icon's drawn (opaque) area. */
    public static String hash(Bitmap src) {
        // bounding box of opaque pixels
        int w = src.getWidth(), h = src.getHeight();
        int minX = w, minY = h, maxX = -1, maxY = -1;
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((px[y * w + x] >>> 24) > 128) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        byte[] bits = new byte[SIZE * SIZE / 8];
        if (maxX >= 0) {
            // centre the box in a square so the aspect ratio is kept
            int side = Math.max(maxX - minX + 1, maxY - minY + 1);
            int ox = minX - (side - (maxX - minX + 1)) / 2;
            int oy = minY - (side - (maxY - minY + 1)) / 2;
            Bitmap square = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
            new Canvas(square).drawBitmap(src, -ox, -oy, null);
            Bitmap small = Bitmap.createScaledBitmap(square, SIZE, SIZE, true);
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    if ((small.getPixel(x, y) >>> 24) > 128) {
                        int i = y * SIZE + x;
                        bits[i >> 3] |= (byte) (1 << (i & 7));
                    }
                }
            }
        }
        StringBuilder sb = new StringBuilder(bits.length * 2);
        for (byte v : bits) sb.append(String.format("%02x", v & 0xFF));
        return sb.toString();
    }

    /** Short, stable id for logs and file names. */
    public static String shortId(String hash) {
        return Integer.toHexString(hash.hashCode());
    }

    // ------------------------------------------------------------------ lookup

    /** Turn for this icon, or null when unknown or without direction information. */
    public Hit lookup(String hash) {
        if (hash == null) return null;
        Integer t = nearest(hash, taughtMap());
        if (t != null) return new Hit(t, SOURCE_TAUGHT);
        t = nearest(hash, MapsIcons.library(context));
        if (t != null && t != NOT_A_MANEUVER) return new Hit(t, SOURCE_LIBRARY);
        return null;
    }

    /** Value of the closest entry within tolerance, or null. */
    private static Integer nearest(String hash, Map<String, Integer> map) {
        Integer best = null;
        int bestDiff = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            int d = diff(hash, e.getKey());
            if (d < bestDiff) {
                bestDiff = d;
                best = e.getValue();
            }
        }
        return bestDiff <= MAX_DIFF_BITS ? best : null;
    }

    private Map<String, Integer> taughtMap() {
        Map<String, Integer> m = new HashMap<>();
        for (Map.Entry<String, ?> e : taught.getAll().entrySet()) m.put(e.getKey(), (Integer) e.getValue());
        return m;
    }

    // ------------------------------------------------------------------ teaching

    // ------------------------------------------------------------------ collecting

    /**
     * Saves each notification icon not seen before as files/icons/&lt;id&gt;.png plus a line in
     * index.tsv (id, hash, parsed turn, instruction), for checking the library against real
     * notifications: adb shell run-as io.github.jackyes.kawanav tar c files/icons | tar x
     */
    public void collect(String hash, Bitmap icon, int parsedTurn, String instruction) {
        if (hash == null || icon == null) return;
        File dir = new File(context.getFilesDir(), "icons");
        File png = new File(dir, shortId(hash) + ".png");
        if (png.exists()) return;
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        String[] existing = dir.list();
        if (existing != null && existing.length > MAX_COLLECTED) return;
        try (FileOutputStream os = new FileOutputStream(png)) {
            icon.compress(Bitmap.CompressFormat.PNG, 100, os);
        } catch (Exception e) {
            Logger.log("Icon save failed: " + e);
            return;
        }
        try (FileWriter w = new FileWriter(new File(dir, "index.tsv"), true)) {
            w.write(shortId(hash) + "\t" + hash + "\t" + parsedTurn + "\t" + instruction.replace('\t', ' ') + "\n");
        } catch (Exception e) {
            Logger.log("Icon index write failed: " + e);
        }
    }

    static int diff(String a, String b) {
        if (a.length() != b.length()) return Integer.MAX_VALUE;
        int d = 0;
        for (int i = 0; i < a.length(); i += 2) {
            int x = Integer.parseInt(a.substring(i, i + 2), 16) ^ Integer.parseInt(b.substring(i, i + 2), 16);
            d += Integer.bitCount(x);
        }
        return d;
    }
}
