package io.github.darthbob.kawanav;

import android.content.Context;
import android.content.res.Configuration;

/**
 * The app's colours, spacing and type tokens in one place, so every screen — and the custom views —
 * look the same and both themes stay readable (body text at least 4.5:1 against its background).
 * Plain framework values: no AndroidX, no Material, no theme attributes to inherit from.
 *
 * <p>Kawasaki green is the single accent. As a fill it carries dark ink (white on it would be only
 * 2.3:1); as text it is darkened in the light theme and lightened in the dark one.
 */
public final class Ui {
    private Ui() {}

    /** True while the system is in dark mode. */
    public static boolean night(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ colours
    // Light / dark pairs. Names say what they are for, not what they look like.

    /** Page background. */
    public static int bg(boolean night) {
        return night ? 0xFF111214 : 0xFFF4F5F7;
    }

    /** Grouped list and card background. */
    public static int surface(boolean night) {
        return night ? 0xFF1E2024 : 0xFFFFFFFF;
    }

    /** Inset wells: stat tiles, chart and track area. */
    public static int surfaceSunken(boolean night) {
        return night ? 0xFF16181B : 0xFFEDEFF2;
    }

    /** Hairline between rows of a group. */
    public static int divider(boolean night) {
        return night ? 0xFF2A2D33 : 0xFFE3E6EA;
    }

    /** Outline of secondary buttons. */
    public static int outline(boolean night) {
        return night ? 0xFF3A3E45 : 0xFFC9CDD3;
    }

    /** Primary text and values. */
    public static int ink(boolean night) {
        return night ? 0xFFE9EBEE : 0xFF14171A;
    }

    /** Supporting text (13 sp). */
    public static int inkSecondary(boolean night) {
        return night ? 0xFFA9AFB8 : 0xFF5A6169;
    }

    /** Fine print and chevrons (12 sp). */
    public static int inkMuted(boolean night) {
        return night ? 0xFF8A9099 : 0xFF6B7280;
    }

    /** Kawasaki green, for fills only (chart line, buttons, start dot). */
    public static int accent() {
        return 0xFF6BBE45;
    }

    /** Green as text or a link: darker in the light theme, lighter in the dark one. */
    public static int accentText(boolean night) {
        return night ? 0xFF8AD46A : 0xFF357A1C;
    }

    /** Text on a green or neutral fill. */
    public static int inkOnAccent() {
        return 0xFF0E1B08;
    }

    /** Connected / ok. */
    public static int ok(boolean night) {
        return night ? 0xFF66BB6A : 0xFF2E7D32;
    }

    /** Warnings as text. */
    public static int warn(boolean night) {
        return night ? 0xFFFFB74D : 0xFF9A5A00;
    }

    /** The setup banner: its fill and the ink on it (unchanged look, dark text for sunlight). */
    public static int warnFill() {
        return 0xFFFB8C00;
    }

    public static int bannerInk() {
        return 0xFF1A1A1A;
    }

    /** Destructive actions. */
    public static int danger(boolean night) {
        return night ? 0xFFEF5350 : 0xFFC62828;
    }

    public static int dangerFill(boolean night) {
        return night ? 0xFFB3261E : 0xFFC62828;
    }

    /** Neutral fill for "Stop"/"Cancel" buttons. */
    public static int solidNeutral(boolean night) {
        return night ? 0xFF50555C : 0xFF5F6368;
    }

    /** Chart and track details. */
    public static int chartGrid(boolean night) {
        return night ? 0x33FFFFFF : 0x33888888;
    }

    public static int chartInk(boolean night) {
        return night ? 0x99FFFFFF : 0x99888888;
    }

    public static int rippleOnFill() {
        return 0x33FFFFFF;
    }

    public static int rippleOnPlain(boolean night) {
        return night ? 0x22FFFFFF : 0x22111111;
    }

    /** Marked point on the track, and the ride's start. */
    public static int markerOnTrack() {
        return 0xFFFFFFFF;
    }

    // ------------------------------------------------------------------ spacing (dp)

    public static final int SPACE1 = 4, SPACE2 = 8, SPACE3 = 12, SPACE4 = 16, SPACE5 = 20, SPACE6 = 24;
    public static final int CARD_PAD_H = 16, CARD_PAD_V = 14, CARD_GAP = 5, ROW_PAD_V = 12;
    public static final int RADIUS_GROUP = 16, RADIUS_TILE = 12;
    /** The floor for anything the rider can tap, and the bigger one for the main actions. */
    public static final int TOUCH_MIN = 48, TOUCH_PRIMARY = 56;
}
