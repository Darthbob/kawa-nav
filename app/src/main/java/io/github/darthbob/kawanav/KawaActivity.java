package io.github.darthbob.kawanav;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Base of KawaNav's screens: the shared look and the bottom tab bar. The look follows Rideology's
 * (see {@link Ui}): calm surfaces, values in big condensed figures in the accent colour, grouped rows
 * with hairline dividers, Kawasaki-green icons, and a tab bar with a round record button in the
 * middle. Everything is built with framework views only (no AndroidX, no Material, no XML layouts),
 * with targets big enough for gloves and text readable in sunlight.
 */
public abstract class KawaActivity extends Activity {

    /** The tabs of the bottom bar. */
    protected static final int TAB_HOME = 0, TAB_BIKE = 1, TAB_RIDES = 2, TAB_SETTINGS = 3;
    /** Extra for a tab: the section to scroll to (see {@link SettingsActivity}). */
    public static final String EXTRA_SECTION = "section";

    protected static final int REQ_PERMS = 1;
    protected static final int REQ_BG_LOCATION = 3;

    protected SharedPreferences prefs;
    /** Dark theme; set by each screen's onCreate from the current configuration. */
    protected boolean night;
    /** The scrolling content of a tab page (for scrolling to a section). */
    protected ScrollView pageScroll;

    private FrameLayout recordButton;
    private ImageView recordIcon;
    private boolean backRegistered;

    /** True while the system is in dark mode. */
    protected boolean isNight() {
        return Ui.night(this);
    }

    /**
     * The scrolling page around a screen's content: page background, and the content kept out of the
     * status and navigation bar (clipped at the padding, so nothing scrolls underneath the status bar),
     * also above the keyboard. For screens without the tab bar (one ride, the first-start help).
     */
    protected ScrollView page(View content) {
        systemBarsFollowTheme();
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.bg(night));
        scroll.addView(content);
        scroll.setClipToPadding(true);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                    | android.view.WindowInsets.Type.displayCutout() | android.view.WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return android.view.WindowInsets.CONSUMED;
        });
        return scroll;
    }

    /** Dark status and navigation bar icons on the light theme, light ones on the dark theme. */
    private void systemBarsFollowTheme() {
        int light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        android.view.WindowInsetsController c = getWindow().getDecorView().getWindowInsetsController();
        if (c != null) c.setSystemBarsAppearance(night ? 0 : light, light);
    }

    // ------------------------------------------------------------------ tab bar

    /**
     * A page of one of the tabs: the scrolling content and the tab bar under it. The status bar's room
     * goes to the content, the navigation bar's to the tab bar; with the keyboard open the bar hides.
     */
    protected View tabPage(int tab, View content) {
        systemBarsFollowTheme();
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        scroll.setClipToPadding(true);
        pageScroll = scroll;
        LinearLayout root = column();
        root.setBackgroundColor(Ui.bg(night));
        root.setClipChildren(false);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        View bar = tabBar(tab);
        root.addView(bar, matchWrap());
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                    | android.view.WindowInsets.Type.displayCutout());
            android.graphics.Insets ime = insets.getInsets(android.view.WindowInsets.Type.ime());
            boolean keyboard = ime.bottom > bars.bottom;
            // the record button stands out above the bar: keep the last row clear of it
            scroll.setPadding(bars.left, bars.top, bars.right, dp(Ui.SPACE4));
            bar.setVisibility(keyboard ? View.GONE : View.VISIBLE);
            bar.setPadding(bars.left, 0, bars.right, bars.bottom);
            v.setPadding(0, 0, 0, keyboard ? ime.bottom : 0);
            return android.view.WindowInsets.CONSUMED;
        });
        registerTabBack(tab);
        updateTabBar();
        return root;
    }

    /** Home, Bike, the record button, Rides, Settings. */
    private View tabBar(int current) {
        LinearLayout bar = row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setClipChildren(false);
        bar.setClipToPadding(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.tabBar(night));
        bar.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                bg, hairlineTop()}));
        bar.addView(tabItem(TAB_HOME, R.drawable.ic_home, "Home", current), tabWeight());
        bar.addView(tabItem(TAB_BIKE, R.drawable.ic_moto, "Bike", current), tabWeight());
        bar.addView(recordSlot(), tabWeight());
        bar.addView(tabItem(TAB_RIDES, R.drawable.ic_list, "Rides", current), tabWeight());
        bar.addView(tabItem(TAB_SETTINGS, R.drawable.ic_settings, "Settings", current), tabWeight());
        return bar;
    }

    private android.graphics.drawable.Drawable hairlineTop() {
        android.graphics.drawable.ShapeDrawable line = new android.graphics.drawable.ShapeDrawable();
        line.getPaint().setColor(Ui.divider(night));
        android.graphics.drawable.InsetDrawable d = new android.graphics.drawable.InsetDrawable(line, 0);
        android.graphics.drawable.LayerDrawable layer = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{d});
        layer.setLayerHeight(0, Math.max(1, dp(1)));
        layer.setLayerGravity(0, Gravity.TOP | Gravity.FILL_HORIZONTAL);
        return layer;
    }

    private LinearLayout.LayoutParams tabWeight() {
        return new LinearLayout.LayoutParams(0, dp(Ui.TAB_BAR), 1f);
    }

    private View tabItem(int tab, int drawable, String label, int current) {
        boolean on = tab == current;
        int color = on ? Ui.accent(night) : Ui.inkSecondary(night);
        LinearLayout item = column();
        item.setGravity(Gravity.CENTER);
        ImageView icon = new ImageView(this);
        icon.setImageResource(drawable);
        icon.setColorFilter(color);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        item.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView t = text(11, on);
        t.setText(label);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(1);
        t.setPadding(0, dp(2), 0, 0);
        item.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        item.setContentDescription(label + (on ? ", selected" : ""));
        item.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.rippleOnPlain(night)), null, null));
        item.setOnClickListener(v -> {
            if (tab == current) {
                if (pageScroll != null) pageScroll.smoothScrollTo(0, 0);
            } else {
                openTab(tab, null);
            }
        });
        return item;
    }

    /** The round record button in the middle of the bar, standing out above it. */
    private View recordSlot() {
        FrameLayout slot = new FrameLayout(this);
        slot.setClipChildren(false);
        recordButton = new FrameLayout(this);
        recordIcon = new ImageView(this);
        recordIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER);
        recordButton.addView(recordIcon, ilp);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(Ui.RECORD_BUTTON), dp(Ui.RECORD_BUTTON),
                Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        lp.topMargin = -dp(18);
        slot.addView(recordButton, lp);
        recordButton.setOnClickListener(v -> onRecordTapped());
        return slot;
    }

    /** Colours the record button for the recorder's state: green idle, red recording, amber paused. */
    protected void updateTabBar() {
        if (recordButton == null) return;
        int state = Hub.rideState;
        int fill = state == RideRecorder.RECORDING ? Ui.recording()
                : state == RideRecorder.PAUSED ? Ui.recordPaused() : Ui.recordIdle();
        if (Integer.valueOf(fill).equals(recordButton.getTag())) return;
        recordButton.setTag(fill);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(fill);
        circle.setStroke(dp(4), Ui.tabBar(night));
        recordButton.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.rippleOnFill()), circle, null));
        recordIcon.setImageResource(state == RideRecorder.RECORDING ? R.drawable.ic_stop
                : state == RideRecorder.PAUSED ? R.drawable.ic_pause : R.drawable.ic_record);
        recordIcon.setColorFilter(state == RideRecorder.PAUSED ? Ui.bannerInk() : 0xFFFFFFFF);
        recordButton.setContentDescription(state == RideRecorder.IDLE ? "Record a ride"
                : state == RideRecorder.PAUSED ? "Ride paused: continue or stop" : "Recording: pause or stop");
    }

    /** Idle: starts recording (and KawaNav, if needed). Recording or paused: pause, continue or stop. */
    protected void onRecordTapped() {
        if (Hub.rideState == RideRecorder.IDLE) {
            boolean running = Hub.serviceRunning;
            String problem = BridgeService.startRide(this);
            android.widget.Toast.makeText(this, problem != null ? "Not started: " + problem
                    : running ? "Recording the ride" : "Starting KawaNav and recording the ride",
                    problem != null ? android.widget.Toast.LENGTH_LONG : android.widget.Toast.LENGTH_SHORT).show();
        } else {
            boolean paused = Hub.rideState == RideRecorder.PAUSED;
            new AlertDialog.Builder(this)
                    .setTitle(paused ? "Ride paused" : "Recording the ride")
                    .setMessage(Hub.rideText)
                    .setPositiveButton("Stop ride", (d, w) -> {
                        BridgeService.stopRide();
                        recordButton.postDelayed(this::updateTabBar, 300);
                    })
                    .setNeutralButton(paused ? "Continue" : "Pause", (d, w) -> {
                        BridgeService.toggleRidePause();
                        recordButton.postDelayed(this::updateTabBar, 300);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }
        recordButton.postDelayed(this::updateTabBar, 300);
    }

    /** Opens a tab: the screen already open is brought back as it was, without an animation. */
    protected void openTab(int tab, String section) {
        Class<?> target = tab == TAB_BIKE ? BikeActivity.class : tab == TAB_RIDES ? RidesActivity.class
                : tab == TAB_SETTINGS ? SettingsActivity.class : MainActivity.class;
        Intent i = new Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        if (section != null) i.putExtra(EXTRA_SECTION, section);
        startActivity(i);
    }

    /** Back on a tab goes to Home; on Home it leaves the app (the tabs stay as they are). */
    private void registerTabBack(int tab) {
        if (backRegistered) return;
        backRegistered = true;
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {
                    if (tab == TAB_HOME) moveTaskToBack(true);
                    else openTab(TAB_HOME, null);
                });
    }

    /**
     * The bar on top of a tab or screen: the title in the middle, a back arrow on the left when
     * {@code back} is given, and room for an action on the right.
     */
    protected LinearLayout headerBar(String title, View.OnClickListener back, View action) {
        LinearLayout r = row();
        r.setPadding(0, dp(Ui.SPACE2), 0, dp(Ui.SPACE2));
        r.setMinimumHeight(dp(56));
        View left;
        if (back != null) {
            ImageView arrow = new ImageView(this);
            arrow.setImageResource(R.drawable.ic_back);
            arrow.setColorFilter(Ui.ink(night));
            arrow.setScaleType(ImageView.ScaleType.CENTER);
            arrow.setContentDescription("Back");
            arrow.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.rippleOnPlain(night)), null, null));
            arrow.setOnClickListener(back);
            left = arrow;
        } else {
            left = new View(this);
        }
        r.addView(left, new LinearLayout.LayoutParams(dp(Ui.TOUCH_MIN), dp(Ui.TOUCH_MIN)));
        TextView t = text(18, false);
        t.setTypeface(Ui.titles());
        t.setText(title);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setTag("title");
        r.addView(t, weight());
        View right = action != null ? action : new View(this);
        r.addView(right, new LinearLayout.LayoutParams(dp(Ui.TOUCH_MIN), dp(Ui.TOUCH_MIN)));
        return r;
    }

    /** A small round icon button (refresh, edit) for the right of a header. */
    protected ImageView headerAction(int drawable, String description, View.OnClickListener l) {
        ImageView v = new ImageView(this);
        v.setImageResource(drawable);
        v.setColorFilter(Ui.accent(night));
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setContentDescription(description);
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.rippleOnPlain(night)), null, null));
        v.setOnClickListener(l);
        return v;
    }

    // ------------------------------------------------------------------ text

    protected TextView text(int size, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(size);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Ui.ink(night));
        return t;
    }

    /** The screen's name, at the top of a page. */
    protected TextView display(String s) {
        TextView t = text(24, false);
        t.setTypeface(Ui.titles());
        t.setText(s);
        return t;
    }

    /** The small line under the title, and the one under a section header. */
    protected TextView secondary(String s) {
        TextView t = text(13, false);
        t.setText(s);
        t.setTextColor(Ui.inkSecondary(night));
        return t;
    }

    /** Fine print: legal notes, captions under charts. */
    protected TextView note(String s) {
        TextView t = text(12, false);
        t.setText(s);
        t.setTextColor(Ui.inkMuted(night));
        return t;
    }

    /** A figure in the instrument face and the accent colour. */
    protected TextView number(int size) {
        TextView t = text(size, false);
        t.setTypeface(Ui.numbers());
        t.setTextColor(Ui.accent(night));
        t.setFontFeatureSettings("tnum");
        t.setIncludeFontPadding(false);
        return t;
    }

    /** A small icon: quiet grey, or Kawasaki green with {@code accent}. */
    protected ImageView icon(int drawable, boolean accent) {
        ImageView v = new ImageView(this);
        v.setImageResource(drawable);
        int size = dp(20);
        v.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        v.setColorFilter(accent ? Ui.brand(night) : Ui.inkSecondary(night));
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return v;
    }

    /** An icon followed by a gap, to put in front of a title. */
    protected LinearLayout iconTitle(int drawable, TextView title) {
        LinearLayout r = row();
        r.addView(icon(drawable, true));
        LinearLayout.LayoutParams lp = weight();
        lp.leftMargin = dp(Ui.SPACE2);
        r.addView(title, lp);
        return r;
    }

    /** The name of a row or of a value's label. */
    protected TextView itemTitle(String s) {
        TextView t = text(15, false);
        t.setText(s);
        return t;
    }

    /** A value on the right of a row. */
    protected TextView value(String s) {
        TextView t = text(15, true);
        t.setText(s);
        t.setGravity(Gravity.END);
        return t;
    }

    /** The header of a card: the title of a group of rows. */
    protected TextView cardTitle(String s) {
        TextView t = text(16, false);
        t.setTypeface(Ui.titles());
        t.setText(s);
        t.setPadding(0, dp(2), 0, dp(4));
        return t;
    }

    /** A header above a group of rows, on the page background: short, in sentence case, like Rideology's. */
    protected TextView sectionHeader(String s) {
        TextView t = text(15, false);
        t.setTypeface(Ui.titles());
        t.setText(s);
        return t;
    }

    /** A section header with its green icon. */
    protected LinearLayout sectionHeader(int drawable, String s) {
        LinearLayout r = row();
        r.setPadding(dp(Ui.SPACE1), dp(Ui.SPACE5), dp(Ui.SPACE1), dp(Ui.SPACE2));
        if (drawable != 0) r.addView(icon(drawable, true), iconGap());
        r.addView(sectionHeader(s), weight());
        return r;
    }

    /** The room a small icon takes in front of a label (20 dp + a gap). */
    protected LinearLayout.LayoutParams iconGap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(20), dp(20));
        lp.rightMargin = dp(Ui.SPACE2);
        return lp;
    }

    // ------------------------------------------------------------------ containers

    protected LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    protected LinearLayout column() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }

    /** A group of rows on a rounded surface, no border, no shadow. */
    protected LinearLayout group() {
        LinearLayout c = column();
        c.setPadding(dp(Ui.CARD_PAD_H), dp(2), dp(Ui.CARD_PAD_H), dp(2));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.surface(night));
        bg.setCornerRadius(dp(Ui.RADIUS_GROUP));
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.setMargins(0, 0, 0, dp(Ui.CARD_GAP));
        c.setLayoutParams(lp);
        return c;
    }

    /** A card with room for content (hero blocks, charts, debug text). */
    protected LinearLayout card() {
        LinearLayout c = column();
        c.setPadding(dp(Ui.CARD_PAD_H), dp(Ui.CARD_PAD_V), dp(Ui.CARD_PAD_H), dp(Ui.CARD_PAD_V));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.surface(night));
        bg.setCornerRadius(dp(Ui.RADIUS_GROUP));
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.setMargins(0, dp(Ui.CARD_GAP), 0, dp(Ui.CARD_GAP));
        c.setLayoutParams(lp);
        return c;
    }

    /** The dark well of a "panel": the dash preview, black in the dark theme with a thin outline. */
    protected LinearLayout panel() {
        LinearLayout c = column();
        c.setPadding(dp(Ui.CARD_PAD_H), dp(Ui.CARD_PAD_V), dp(Ui.CARD_PAD_H), dp(Ui.CARD_PAD_V));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(night ? Ui.surfaceSunken(true) : Ui.surface(false));
        bg.setStroke(Math.max(1, dp(1)), night ? Ui.divider(true) : Ui.outline(false));
        bg.setCornerRadius(dp(Ui.RADIUS_GROUP));
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.setMargins(0, dp(Ui.CARD_GAP), 0, dp(Ui.CARD_GAP));
        c.setLayoutParams(lp);
        return c;
    }

    /** A row of a group: text on the left, a control on the right. */
    protected LinearLayout settingRow() {
        LinearLayout r = row();
        r.setPadding(0, dp(Ui.ROW_PAD_V), 0, dp(Ui.ROW_PAD_V));
        r.setMinimumHeight(dp(Ui.TOUCH_MIN));
        return r;
    }

    /** A tappable row: title, up to two lines under it, and a chevron when it opens something. */
    protected View listRow(String title, String line1, String line2, boolean chevron, View.OnClickListener l) {
        return listRow(0, title, line1, line2, chevron, l);
    }

    /** @param drawable a leading icon, or 0 for none */
    protected View listRow(int drawable, String title, String line1, String line2, boolean chevron, View.OnClickListener l) {
        LinearLayout r = row();
        r.setPadding(0, dp(Ui.ROW_PAD_V), 0, dp(Ui.ROW_PAD_V));
        r.setMinimumHeight(dp(Ui.TOUCH_MIN));
        if (drawable != 0) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(20), dp(20));
            lp.rightMargin = dp(Ui.SPACE3);
            r.addView(icon(drawable, true), lp);
        }
        LinearLayout t = column();
        t.addView(itemTitle(title));
        if (line1 != null && !line1.isEmpty()) t.addView(secondary(line1));
        if (line2 != null && !line2.isEmpty()) t.addView(secondary(line2));
        r.addView(t, weight());
        if (chevron) r.addView(chevron());
        if (l != null) r.setOnClickListener(l);
        return r;
    }

    protected TextView chevron() {
        TextView c = text(22, false);
        c.setText("›");
        c.setTextColor(Ui.inkMuted(night));
        c.setPadding(dp(Ui.SPACE2), 0, 0, 0);
        return c;
    }

    /** Adds a row to a group with a hairline under it (except after the last one). */
    protected void addRow(LinearLayout group, View row) {
        boolean first = group.getChildCount() == 0;
        if (!first) {
            View d = new View(this);
            d.setBackgroundColor(Ui.divider(night));
            LinearLayout.LayoutParams lp = matchWrap();
            lp.height = Math.max(1, dp(1));
            group.addView(d, lp);
        }
        group.addView(row);
    }

    /** A small rounded tag: "DEBUG", "Connected", "Paused". */
    protected TextView pill(String text, int fill, int inkColor) {
        TextView t = text(12, true);
        t.setText(text);
        t.setTextColor(inkColor);
        t.setPadding(dp(8), dp(3), dp(8), dp(3));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(9));
        t.setBackground(bg);
        return t;
    }

    /** The "DEBUG" tag of the test tools. */
    protected TextView debugPill() {
        return pill("DEBUG", (Ui.accent(night) & 0x00FFFFFF) | 0x33000000, Ui.accent(night));
    }

    // ------------------------------------------------------------------ numbers

    /** A figure with its unit, as two views: the figure in the instrument face, the unit small and quiet. */
    protected final class Figure {
        public final LinearLayout view;
        public final TextView number;
        public final TextView unit;
        private final int bigSize;

        Figure(int size, int gravity) {
            bigSize = size;
            view = row();
            view.setGravity(gravity | Gravity.BOTTOM);
            number = number(size);
            unit = text(Math.max(12, size / 3), false);
            unit.setTextColor(Ui.inkSecondary(night));
            unit.setPadding(dp(4), 0, 0, Math.max(0, dp(size / 12)));
            view.addView(number);
            view.addView(unit);
        }

        /**
         * "12,345 km" becomes the figure 12,345 and the unit km; "1 h 05 min" or "35° / 31°" stay one
         * figure; anything that does not start with a number is shown as plain text.
         */
        public void set(String value) {
            String[] p = splitFigure(value);
            if (p != null && p[1].matches(".*[0-9].*")) {
                if (!value.contentEquals(number.getText())) number.setText(value);
                number.setTypeface(Ui.numbers());
                number.setTextSize(bigSize);
                unit.setVisibility(View.GONE);
            } else if (p != null) {
                if (!p[0].contentEquals(number.getText())) number.setText(p[0]);
                if (!p[1].contentEquals(unit.getText())) unit.setText(p[1]);
                number.setTypeface(Ui.numbers());
                number.setTextSize(bigSize);
                unit.setVisibility(p[1].isEmpty() ? View.GONE : View.VISIBLE);
            } else {
                if (!value.contentEquals(number.getText())) number.setText(value);
                number.setTypeface(Typeface.DEFAULT_BOLD);
                number.setTextSize(15);
                unit.setVisibility(View.GONE);
            }
        }

        public void color(int c) {
            number.setTextColor(c);
        }
    }

    private static final Pattern FIGURE = Pattern.compile("^([-+−]?[0-9][0-9,.]*)\\s*(.*)$");

    /** {figure, unit} of a value like "12,345 km" or "85 °C", null if it does not start with a number. */
    static String[] splitFigure(String value) {
        if (value == null) return null;
        Matcher m = FIGURE.matcher(value.trim());
        if (!m.matches()) return null;
        return new String[]{m.group(1), m.group(2) == null ? "" : m.group(2).trim()};
    }

    protected Figure figure(int size, int gravity) {
        return new Figure(size, gravity);
    }

    /** The big value of a screen: figure and unit in the middle, what it is under it (Rideology's "Remaining range"). */
    protected final class Hero {
        public final LinearLayout view;
        public final Figure figure;
        public final TextView caption;

        Hero(int size) {
            view = column();
            view.setGravity(Gravity.CENTER_HORIZONTAL);
            figure = new Figure(size, Gravity.CENTER_HORIZONTAL);
            view.addView(figure.view);
            caption = secondary("");
            caption.setGravity(Gravity.CENTER);
            caption.setPadding(0, dp(Ui.SPACE1), 0, 0);
            view.addView(caption);
        }
    }

    protected Hero hero(int size) {
        return new Hero(size);
    }

    /** A row of a value list: icon and label on the left, the figure on the right. */
    protected final class ValueRow {
        public final LinearLayout view;
        public final TextView label;
        public final Figure figure;

        ValueRow(int drawable, String labelText) {
            view = row();
            view.setPadding(0, dp(10), 0, dp(10));
            view.setMinimumHeight(dp(Ui.TOUCH_MIN));
            if (drawable != 0) view.addView(icon(drawable, false), iconGap());
            label = itemTitle(labelText);
            view.addView(label, weight());
            figure = new Figure(22, Gravity.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(Ui.SPACE2);
            view.addView(figure.view, lp);
        }
    }

    protected ValueRow valueRow(int drawable, String label, String value) {
        ValueRow r = new ValueRow(drawable, label);
        r.figure.set(value);
        return r;
    }

    /** One number a rider glances at: the figure big in the accent colour, what it is under it. */
    protected View statTile(String label, String value) {
        LinearLayout c = column();
        c.setPadding(dp(Ui.SPACE3), dp(10), dp(Ui.SPACE3), dp(10));
        c.setMinimumHeight(dp(Ui.TOUCH_MIN));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.surfaceSunken(night));
        bg.setCornerRadius(dp(Ui.RADIUS_TILE));
        c.setBackground(bg);
        Figure f = new Figure(22, Gravity.START);
        f.set(value);
        TextView l = text(12, false);
        l.setText(label);
        l.setTextColor(Ui.inkSecondary(night));
        l.setPadding(0, dp(2), 0, 0);
        c.addView(f.view);
        c.addView(l);
        return c;
    }

    /** Rows of equal tiles with 8 dp gaps; null tiles (no value) are left out, the last row is padded. */
    protected LinearLayout statGrid(int cols, View... all) {
        List<View> shown = new ArrayList<>();
        for (View t : all) if (t != null) shown.add(t);
        View[] tiles = shown.toArray(new View[0]);
        LinearLayout grid = column();
        for (int i = 0; i < tiles.length; i += cols) {
            LinearLayout r = row();
            r.setGravity(Gravity.TOP);
            if (i > 0) r.setPadding(0, dp(Ui.SPACE2), 0, 0);
            for (int j = 0; j < cols; j++) {
                int k = i + j;
                if (j > 0) {
                    View gap = new View(this);
                    gap.setLayoutParams(new LinearLayout.LayoutParams(dp(Ui.SPACE2), 1));
                    r.addView(gap);
                }
                if (k < tiles.length) r.addView(tiles[k], weight());
                else r.addView(new View(this), weight());
            }
            grid.addView(r);
        }
        return grid;
    }

    /** A menu tile, like Rideology's "Vehicle menu": a green icon over a short label. */
    protected View menuTile(int drawable, String label, View.OnClickListener l) {
        LinearLayout c = column();
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(Ui.SPACE2), dp(14), dp(Ui.SPACE2), dp(12));
        c.setMinimumHeight(dp(104)); // room for a two-line label: all tiles of a row the same height
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.surface(night));
        bg.setCornerRadius(dp(Ui.RADIUS_TILE));
        c.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.rippleOnPlain(night)), bg, null));
        ImageView i = new ImageView(this);
        i.setImageResource(drawable);
        i.setColorFilter(Ui.brand(night));
        i.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        c.addView(i, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView t = text(13, false);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(2);
        t.setPadding(0, dp(Ui.SPACE2), 0, 0);
        c.addView(t);
        c.setContentDescription(label);
        c.setOnClickListener(l);
        return c;
    }

    // ------------------------------------------------------------------ controls

    protected interface OnPos {
        void on(int pos);
    }

    protected AdapterView.OnItemSelectedListener onSelect(OnPos f) {
        return new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                f.on(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        };
    }

    /** A plain button: the caller gives it a look (see {@link #tint}). */
    protected Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setMinimumHeight(dp(Ui.TOUCH_MIN));
        b.setStateListAnimator(null);
        b.setOnClickListener(l);
        return b;
    }

    /** The main action of a screen or card: accent fill (lime / violet), 56 dp tall. */
    protected Button primaryButton(String text, View.OnClickListener l) {
        Button b = button(text, l);
        b.setTextColor(Ui.onAccent(night));
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setMinHeight(dp(Ui.TOUCH_PRIMARY));
        tint(b, Ui.accent(night));
        return b;
    }

    /** Puts a button back to the primary look (after it was made neutral). */
    protected void primary(Button b) {
        b.setTextColor(Ui.onAccent(night));
        tint(b, Ui.accent(night));
    }

    /** The other choice: outline, accent text, quieter than the primary action. */
    protected Button secondaryButton(String text, View.OnClickListener l) {
        Button b = button(text, l);
        b.setTextColor(Ui.accent(night));
        outline(b);
        return b;
    }

    /** "Stop", "Cancel": filled neutral grey, white text. */
    protected Button neutralButton(String text, View.OnClickListener l) {
        Button b = button(text, l);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setMinHeight(dp(Ui.TOUCH_PRIMARY));
        tint(b, Ui.solidNeutral(night));
        return b;
    }

    /** Puts a button to the neutral look. */
    protected void neutral(Button b) {
        b.setTextColor(0xFFFFFFFF);
        tint(b, Ui.solidNeutral(night));
    }

    /** Something that throws data away: red, and never the first thing in a row. */
    protected Button destructiveButton(String text, View.OnClickListener l) {
        Button b = button(text, l);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setMinHeight(dp(Ui.TOUCH_PRIMARY));
        tint(b, Ui.dangerFill(night));
        return b;
    }

    /** A button with a small icon in front of its label. */
    protected Button iconButton(Button b, int drawable) {
        android.graphics.drawable.Drawable icon = getDrawable(drawable);
        if (icon != null) {
            int size = dp(18);
            icon.setBounds(0, 0, size, size);
            icon.setTint(b.getCurrentTextColor());
            b.setCompoundDrawablesRelative(icon, null, null, null);
            b.setCompoundDrawablePadding(dp(Ui.SPACE2));
        }
        return b;
    }

    /** Gives a button the rounded filled look; cached in the tag because refresh runs often. */
    protected void tint(Button b, int color) {
        if (Integer.valueOf(color).equals(b.getTag())) return; // called on every refresh
        b.setTag(color);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(Ui.RADIUS_BUTTON));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.rippleOnFill()), bg, null));
    }

    /** A filled button with a quiet grey ink: used when it is not the main action of the screen. */
    protected void quiet(Button b) {
        if (Integer.valueOf(Ui.surfaceSunken(night)).equals(b.getTag())) return; // already quiet
        b.setTag(Ui.surfaceSunken(night));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.surfaceSunken(night));
        bg.setCornerRadius(dp(Ui.RADIUS_BUTTON));
        b.setTextColor(Ui.ink(night));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.rippleOnPlain(night)), bg, null));
    }

    /** The outlined look of {@link #secondaryButton}. */
    protected void outline(Button b) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x00000000);
        bg.setStroke(Math.max(1, dp(1)), Ui.outline(night));
        bg.setCornerRadius(dp(Ui.RADIUS_BUTTON));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.rippleOnPlain(night)), bg, null));
    }

    /** A switch in the accent colour. */
    protected Switch accentSwitch(boolean checked) {
        Switch s = new Switch(this);
        s.setChecked(checked);
        s.setMinimumHeight(dp(Ui.TOUCH_MIN));
        int[][] states = {{android.R.attr.state_checked}, {}};
        int accent = Ui.accent(night);
        s.setThumbTintList(new ColorStateList(states, new int[]{accent, Ui.outline(night)}));
        s.setTrackTintList(new ColorStateList(states,
                new int[]{(accent & 0x00FFFFFF) | 0x66000000, night ? 0x33545454 : 0x33D9D8E3}));
        return s;
    }

    /** A row with a switch: any change is stored and the bridge is told. */
    protected View switchRow(String title, String sub, String key, boolean byDefault) {
        return switchRow(title, sub, key, byDefault, null);
    }

    /** @param after run after the setting was stored (e.g. to tell the listener about a dash-icon change) */
    protected View switchRow(String title, String sub, String key, boolean byDefault, java.util.function.Consumer<Boolean> after) {
        LinearLayout r = settingRow();
        LinearLayout t = column();
        t.addView(itemTitle(title));
        t.addView(secondary(sub));
        r.addView(t, weight());
        Switch s = accentSwitch(prefs.getBoolean(key, byDefault));
        s.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(key, on).apply();
            BridgeService.settingsChanged();
            if (after != null) after.accept(on);
        });
        r.addView(s);
        return r;
    }

    protected View choiceRow(String title, String sub, String[] items, int selected, OnPos f) {
        return choiceRow(title, sub, null, items, selected, f);
    }

    /** With info: a link under the text opens it as explanation of the options. */
    protected View choiceRow(String title, String sub, String info, String[] items, int selected, OnPos f) {
        LinearLayout r = settingRow();
        LinearLayout t = column();
        t.addView(itemTitle(title));
        t.addView(secondary(sub));
        if (info != null) {
            TextView more = text(13, false);
            more.setText("What do the options mean?");
            more.setTextColor(Ui.accent(night));
            more.setPadding(0, dp(Ui.SPACE1), 0, dp(Ui.SPACE1));
            more.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(info)
                    .setPositiveButton("OK", null)
                    .show());
            t.addView(more);
        }
        r.addView(t, weight());
        Spinner s = spinner(items, selected);
        s.setOnItemSelectedListener(onSelect(f));
        r.addView(s, controlWidth());
        return r;
    }

    /** A drop-down: the closed view is a quiet value, the open list has full-height rows. */
    protected Spinner spinner(String[] items, int selected) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView t = (TextView) super.getView(position, convertView, parent);
                t.setTextColor(Ui.accent(night));
                t.setTextSize(15);
                t.setPadding(0, dp(Ui.SPACE3), 0, dp(Ui.SPACE3));
                t.setMinimumHeight(dp(Ui.TOUCH_MIN));
                return t;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView t = (TextView) super.getDropDownView(position, convertView, parent);
                t.setTextColor(Ui.ink(night));
                t.setTextSize(16);
                t.setPadding(dp(Ui.SPACE4), dp(14), dp(Ui.SPACE4), dp(14));
                t.setMinimumHeight(dp(Ui.TOUCH_MIN));
                return t;
            }
        };
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setSelection(selected);
        return s;
    }

    /** A card that only says that there is nothing (yet). */
    protected LinearLayout emptyState(String title, String body) {
        LinearLayout c = card();
        c.addView(cardTitle(title));
        c.addView(secondary(body));
        return c;
    }

    // ------------------------------------------------------------------ permissions

    protected List<String> missingPermissions() {
        List<String> need = new ArrayList<>();
        for (String p : new String[]{Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS}) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        }
        return need;
    }

    protected void requestMissingPermissions() {
        List<String> need = missingPermissions();
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    protected void openAppSettings() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + getPackageName())));
    }

    protected boolean hasBackgroundLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Autostart begins in the background, where GPS needs location "Allow all the time". Android only
     * offers that on its settings page, so explain first what to pick there. No-op when granted.
     */
    protected void askBackgroundLocation() {
        askBackgroundLocation(null, null);
    }

    /**
     * @param onContinue run before Android's request opens (null: nothing)
     * @param onSkip     run when the user picks "Not now" (null: nothing)
     */
    protected void askBackgroundLocation(Runnable onContinue, Runnable onSkip) {
        if (hasBackgroundLocation()) return;
        new AlertDialog.Builder(this)
                .setTitle("Location: \"Allow all the time\"")
                .setMessage("Autostart starts KawaNav while your phone is locked in your pocket. "
                        + "To show curve warnings then, KawaNav needs GPS in the background.\n\n"
                        + "On the next screen, select \"Allow all the time\".\n\n"
                        + "Without it, Google Maps arrows still work, but curve warnings only start "
                        + "after you open KawaNav.")
                .setPositiveButton("Continue", (d, w) -> {
                    if (onContinue != null) onContinue.run();
                    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        requestMissingPermissions(); // "all the time" can only follow "while using"
                    } else {
                        requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQ_BG_LOCATION);
                    }
                })
                .setNegativeButton("Not now", (d, w) -> {
                    if (onSkip != null) onSkip.run();
                })
                .setCancelable(false)
                .show();
    }

    // ------------------------------------------------------------------ layout params

    protected int dp(int v) {
        return Ui.dp(this, v);
    }

    protected LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    /** Drop-downs next to a setting's text: fixed width, so the text keeps its room. */
    protected LinearLayout.LayoutParams controlWidth() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(Ui.SPACE2), 0, 0, 0);
        return lp;
    }

    protected LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
