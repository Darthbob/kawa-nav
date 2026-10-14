package io.github.darthbob.kawanav;

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
 * theme white surfaces with violet values (Rideology "White"). Kawasaki green marks the icons and the
 * record button in both.
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
        return night ? 0xFF111111 : 0xFFF2F2F5;
    }

    /** Grouped list and card background. */
    public static int surface(boolean night) {
        return night ? 0xFF1C1C1C : 0xFFFFFFFF;
    }

    /** Inset wells: the dash panel, stat tiles, chart and track area. */
    public static int surfaceSunken(boolean night) {
        return night ? 0xFF000000 : 0xFFEFEFF3;
    }

    /** Hairline between rows of a group. */
    public static int divider(boolean night) {
        return night ? 0xFF3C3C3C : 0xFFE3E3EA;
    }

    /** Outline of secondary buttons and of the dash panel. */
    public static int outline(boolean night) {
        return night ? 0xFF545454 : 0xFFD9D8E3;
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

    /** The values and the selection: lime green in the dark theme, violet in the light one. Also the main buttons. */
    public static int accent(boolean night) {
        return night ? 0xFF80EE2D : 0xFF3E2CC3;
    }

    /** Accent as text or a link (the same colours: both read well on their background). */
    public static int accentText(boolean night) {
        return accent(night);
    }

    /** Text and icons on an accent fill. */
    public static int onAccent(boolean night) {
        return night ? 0xFF111111 : 0xFFFFFFFF;
    }

    /** The second value colour: temperatures, where a value comes from (cyan / blue-violet). */
    public static int highlight(boolean night) {
        return night ? 0xFFABFEFE : 0xFF4F47CF;
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
        return night ? 0xFF4E4E4E : 0xFFD9D8E3;
    }

    /**
     * A filled gauge segment: lime in the dark theme; in the light theme from violet (first) to green
     * (last), like Rideology's fuel gauge.
     */
    public static int gaugeFill(boolean night, int index, int count) {
        if (night) return 0xFF80EE2D;
        float f = count <= 1 ? 1f : index / (float) (count - 1);
        return blend(0xFF4F47CF, 0xFF4FB36B, f);
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
