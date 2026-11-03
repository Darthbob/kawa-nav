package io.github.darthbob.kawanav;

import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;

/**
 * The app's colours, spacing and type tokens in one place, so every screen — and the custom views —
 * look the same and both themes stay readable (body text at least 4.5:1 against its background).
 * Plain framework values: no AndroidX, no Material, no theme attributes to inherit from.
 *
 * <p>The look follows Rideology's two themes, without any of Kawasaki's artwork or fonts: in the dark
 * theme black surfaces with lime-green values and cyan highlights (Rideology "Black"), in the light
 * theme white surfaces with the same lime on what is filled and dark green values (Rideology "White",
 * in green instead of its violet). Kawasaki green marks the icons and the record button in both.
 */
public final class Ui {
    private Ui() {}

    /** True while the app is dark: the phone's mode, or the one chosen under Settings → Appearance. */
    public static boolean night(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    // ------------------------------------------------------------------ theme
    // The choice is the app's own night mode, kept by Android: every screen, dialog and system bar
    // follows it, and the open screens are recreated when it changes.

    /** Settings → Appearance → Theme, {@link Hub#KEY_THEME}: an index into {@link #themeLabels}. */
    public static final int THEME_SYSTEM = 0, THEME_LIGHT = 1, THEME_DARK = 2;

    public static String[] themeLabels() {
        return new String[]{Lang.t(R.string.theme_system), Lang.t(R.string.theme_light), Lang.t(R.string.theme_dark)};
    }

    /** Makes the app light or dark, or lets it follow the phone again. */
    public static void applyTheme(Context c, int theme) {
        UiModeManager m = c.getSystemService(UiModeManager.class);
        if (m == null) return;
        m.setApplicationNightMode(theme == THEME_DARK ? UiModeManager.MODE_NIGHT_YES
                : theme == THEME_LIGHT ? UiModeManager.MODE_NIGHT_NO : UiModeManager.MODE_NIGHT_AUTO);
    }

    /**
     * At app start: Android keeps the chosen mode itself, but not after the settings came back from a
     * backup onto a new phone. Only a light or dark choice that is not in force is applied again.
     */
    public static void keepTheme(Context c) {
        int theme = Hub.prefs(c).getInt(Hub.KEY_THEME, THEME_SYSTEM);
        if (theme != THEME_SYSTEM && night(c) != (theme == THEME_DARK)) applyTheme(c, theme);
    }

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ colours
    // Light / dark pairs. Names say what they are for, not what they look like.

    /** Page background. */
    public static int bg(boolean night) {
        return night ? 0xFF111111 : 0xFFF2F2F2;
    }

    /** Grouped list and card background. */
    public static int surface(boolean night) {
        return night ? 0xFF1C1C1C : 0xFFFFFFFF;
    }

    /** Inset wells: the dash panel, stat tiles, chart and track area. */
    public static int surfaceSunken(boolean night) {
        return night ? 0xFF000000 : 0xFFEFEFEF;
    }

    /** Hairline between rows of a group. */
    public static int divider(boolean night) {
        return night ? 0xFF3C3C3C : 0xFFE3E3E3;
    }

    /** Outline of secondary buttons and of the dash panel. */
    public static int outline(boolean night) {
        return night ? 0xFF545454 : 0xFFD9D9D9;
    }

    /** Primary text. */
    public static int ink(boolean night) {
        return night ? 0xFFFFFFFF : 0xFF171717;
    }

    /** Supporting text (13 sp). */
    public static int inkSecondary(boolean night) {
        return night ? 0xFFC2C2C2 : 0xFF646464;
    }

    /** Fine print, units and chevrons (12 sp). */
    public static int inkMuted(boolean night) {
        return night ? 0xFF9A9A9A : 0xFF6F6F6F;
    }

    /**
     * Lime green in both themes, for what is filled: the main buttons, switches, sliders, the turn
     * arrows, progress. On white it is too pale for text: there use {@link #accentText}.
     */
    public static int accent(boolean night) {
        return 0xFF80EE2D;
    }

    /**
     * The values and the selection as text, an icon or a thin line: the lime in the dark theme, a dark
     * green in the light one (lime on white is 1.4:1, this one 5.4:1).
     */
    public static int accentText(boolean night) {
        return night ? accent(true) : 0xFF2F7A12;
    }

    /** Text and icons on an accent fill: dark, the lime is light in both themes. */
    public static int onAccent(boolean night) {
        return 0xFF111111;
    }

    /** The second value colour: temperatures, where a value comes from (cyan / deep green). */
    public static int highlight(boolean night) {
        return night ? 0xFFABFEFE : 0xFF1B5E20;
    }

    /** Kawasaki green for icons (darker in the light theme, so they keep 3:1 on white). */
    public static int brand(boolean night) {
        return night ? 0xFF80EE2D : 0xFF4E9A1E;
    }

    /** The record button's fill while nothing is recorded. */
    public static int recordIdle() {
        return 0xFF69BE28;
    }

    /** The record button while recording, and while paused. */
    public static int recording() {
        return 0xFFE53935;
    }

    public static int recordPaused() {
        return 0xFFFB8C00;
    }

    /** Connected / ok. */
    public static int ok(boolean night) {
        return night ? 0xFF80EE2D : 0xFF2E7D32;
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
        return night ? 0xFFFF6B6B : 0xFFC62828;
    }

    public static int dangerFill(boolean night) {
        return night ? 0xFFB3261E : 0xFFC62828;
    }

    /** Neutral fill for "Stop"/"Cancel" buttons. */
    public static int solidNeutral(boolean night) {
        return night ? 0xFF4A4A4A : 0xFF5F6368;
    }

    /** The bottom tab bar. */
    public static int tabBar(boolean night) {
        return night ? 0xFF000000 : 0xFFFFFFFF;
    }

    /** An empty segment of a gauge (fuel). */
    public static int gaugeEmpty(boolean night) {
        return night ? 0xFF4E4E4E : 0xFFD9D9D9;
    }

    /** A filled gauge segment: the lime in both themes, every segment alike. */
    public static int gaugeFill(boolean night, int index, int count) {
        return accent(night);
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

    /** Marked point on the track. */
    public static int markerOnTrack() {
        return 0xFFFFFFFF;
    }

    /** Mixes two colours; {@code f} 0 gives {@code a}, 1 gives {@code b}. */
    public static int blend(int a, int b, float f) {
        f = Math.max(0f, Math.min(1f, f));
        int r = Math.round(((a >> 16) & 0xFF) * (1 - f) + ((b >> 16) & 0xFF) * f);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - f) + ((b >> 8) & 0xFF) * f);
        int bl = Math.round((a & 0xFF) * (1 - f) + (b & 0xFF) * f);
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }

    // ------------------------------------------------------------------ type

    /** Big numbers (range, odometer, distances): condensed and bold, like the figures on an instrument. */
    public static Typeface numbers() {
        return Typeface.create("sans-serif-condensed", Typeface.BOLD);
    }

    /** Screen and card titles. */
    public static Typeface titles() {
        return Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    // ------------------------------------------------------------------ spacing (dp)

    public static final int SPACE1 = 4, SPACE2 = 8, SPACE3 = 12, SPACE4 = 16, SPACE5 = 20, SPACE6 = 24;
    public static final int CARD_PAD_H = 16, CARD_PAD_V = 14, CARD_GAP = 6, ROW_PAD_V = 12;
    public static final int RADIUS_GROUP = 12, RADIUS_TILE = 10, RADIUS_BUTTON = 12;
    /** The floor for anything the rider can tap, and the bigger one for the main actions. */
    public static final int TOUCH_MIN = 48, TOUCH_PRIMARY = 56;
    /** The bottom tab bar and its round record button. */
    public static final int TAB_BAR = 64, RECORD_BUTTON = 60;
}
