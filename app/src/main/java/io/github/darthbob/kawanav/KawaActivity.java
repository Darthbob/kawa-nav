package io.github.darthbob.kawanav;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;

/**
 * Base of KawaNav's screens: the shared look. Calm surfaces, one accent (Kawasaki green), grouped rows
 * with hairline dividers, big enough targets for gloves, and readable in sunlight — all built with
 * framework views only (no AndroidX, no Material, no XML layouts). Colours and sizes come from
 * {@link Ui}, so both themes stay consistent and readable.
 */
public abstract class KawaActivity extends Activity {

    /** Kawasaki green as a fill (buttons, chart line): dark ink goes on it, see {@link Ui#inkOnAccent()}. */
    protected static final int KAWASAKI_GREEN = 0xFF6BBE45;
    protected static final int OK_GREEN = 0xFF43A047;

    protected SharedPreferences prefs;
    /** Dark theme; set by {@link #page} (and each screen's onCreate) from the current configuration. */
    protected boolean night;

    /** True while the system is in dark mode. */
    protected boolean isNight() {
        return Ui.night(this);
    }

    /**
     * The scrolling page around a screen's content: page background, and the content kept out of the
     * status and navigation bar (clipped at the padding, so nothing scrolls underneath the status bar),
     * also above the keyboard.
     */
    protected ScrollView page(View content) {
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
        TextView t = text(26, true);
        t.setText(s);
        t.setLetterSpacing(-0.02f);
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

    /** A small icon, tinted for the current theme (quiet by default, accent on request). */
    protected android.widget.ImageView icon(int drawable, boolean accent) {
        android.widget.ImageView v = new android.widget.ImageView(this);
        v.setImageResource(drawable);
        int size = dp(20);
        v.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        v.setColorFilter(accent ? Ui.accentText(night) : Ui.inkSecondary(night));
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return v;
    }

    /** An icon followed by a gap, to put in front of a title. */
    protected LinearLayout iconTitle(int drawable, TextView title) {
        LinearLayout r = row();
        r.addView(icon(drawable, false));
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
        TextView t = text(17, true);
        t.setText(s);
        t.setPadding(0, dp(2), 0, dp(4));
        return t;
    }

    /**
     * A header above a group of rows, on the page background (not inside a card): the "airy grouped
     * list" look. Upper case and quiet, so it organises without shouting.
     */
    protected TextView sectionHeader(String s) {
        TextView t = text(13, true);
        t.setText(s.toUpperCase(Locale.ROOT));
        t.setTextColor(Ui.inkSecondary(night));
        t.setLetterSpacing(0.08f);
        return t;
    }

    /** A section header with its icon: icon, then the label, with the section's air around it. */
    protected LinearLayout sectionHeader(int drawable, String s) {
        LinearLayout r = row();
        r.setPadding(dp(Ui.SPACE1), dp(Ui.SPACE4), dp(Ui.SPACE1), dp(Ui.SPACE1));
        if (drawable != 0) r.addView(icon(drawable, false), iconGap());
        r.addView(sectionHeader(s), weight());
        return r;
    }

    /** The room a small icon takes in front of a label (18 dp + a small gap). */
    protected LinearLayout.LayoutParams iconGap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(18), dp(18));
        lp.rightMargin = dp(Ui.SPACE1);
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

    /** A group of rows on a rounded surface: white (dark grey at night), no border, no shadow. */
    protected LinearLayout group() {
        LinearLayout c = column();
        c.setPadding(dp(Ui.CARD_PAD_H), dp(2), dp(Ui.CARD_PAD_H), dp(2));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.surface(night));
        bg.setCornerRadius(dp(Ui.RADIUS_GROUP));
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.setMargins(0, dp(0), 0, dp(Ui.CARD_GAP));
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
            r.addView(icon(drawable, false), lp);
        }
        LinearLayout t = column();
        t.addView(itemTitle(title));
        if (line1 != null && !line1.isEmpty()) t.addView(secondary(line1));
        if (line2 != null && !line2.isEmpty()) t.addView(secondary(line2));
        r.addView(t, weight());
        if (chevron) {
            TextView c = text(20, false);
            c.setText("›"); // ›
            c.setTextColor(Ui.inkMuted(night));
            r.addView(c);
        }
        if (l != null) r.setOnClickListener(l);
        return r;
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

    // ------------------------------------------------------------------ numbers

    /** One number a rider glances at: the value big, what it is under it. */
    protected View statTile(String label, String value) {
        LinearLayout c = column();
        c.setPadding(dp(Ui.SPACE3), dp(10), dp(Ui.SPACE3), dp(10));
        c.setMinimumHeight(dp(Ui.TOUCH_MIN));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.surfaceSunken(night));
        bg.setCornerRadius(dp(Ui.RADIUS_TILE));
        c.setBackground(bg);
        TextView v = text(20, true);
        v.setText(value);
        TextView l = text(12, false);
        l.setText(label);
        l.setTextColor(Ui.inkSecondary(night));
        c.addView(v);
        c.addView(l);
        return c;
    }

    /** Rows of equal tiles with 8 dp gaps; a null tile pads the last row. */
    protected LinearLayout statGrid(int cols, View... tiles) {
        LinearLayout grid = column();
        for (int i = 0; i < tiles.length; i += cols) {
            LinearLayout r = row();
            if (i > 0) r.setPadding(0, dp(Ui.SPACE2), 0, 0);
            for (int j = 0; j < cols; j++) {
                int k = i + j;
                if (j > 0) {
                    View gap = new View(this);
                    gap.setLayoutParams(new LinearLayout.LayoutParams(dp(Ui.SPACE2), 1));
                    r.addView(gap);
                }
                if (k < tiles.length && tiles[k] != null) r.addView(tiles[k], weight());
                else r.addView(new View(this), weight());
            }
            grid.addView(r);
        }
        return grid;
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

    /** The main action of a screen or card: green fill, dark ink on it, 56 dp tall. */
    protected Button primaryButton(String text, View.OnClickListener l) {
        Button b = button(text, l);
        b.setTextColor(Ui.inkOnAccent());
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setMinHeight(dp(Ui.TOUCH_PRIMARY));
        tint(b, KAWASAKI_GREEN);
        return b;
    }

    /** The other choice: outline, green text, quieter than the primary action. */
    protected Button secondaryButton(String text, View.OnClickListener l) {
        Button b = button(text, l);
        b.setTextColor(Ui.accentText(night));
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
        bg.setCornerRadius(dp(28));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.rippleOnFill()), bg, null));
    }

    /** A filled button with a quiet grey ink: used when it is not the main action of the screen. */
    protected void quiet(Button b) {
        if (Integer.valueOf(Ui.solidNeutral(night)).equals(b.getTag())) return; // already quiet
        b.setTag(null); // invalidates the tint cache
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.surfaceSunken(night));
        bg.setCornerRadius(dp(28));
        b.setTextColor(Ui.ink(night));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.rippleOnPlain(night)), bg, null));
    }

    /** The outlined look of {@link #secondaryButton}. */
    protected void outline(Button b) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x00000000);
        bg.setStroke(Math.max(1, dp(1)), Ui.outline(night));
        bg.setCornerRadius(dp(28));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.rippleOnPlain(night)), bg, null));
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
        Switch s = new Switch(this);
        s.setChecked(prefs.getBoolean(key, byDefault));
        s.setMinimumHeight(dp(Ui.TOUCH_MIN));
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(states,
                new int[]{KAWASAKI_GREEN, Ui.outline(night)}));
        s.setTrackTintList(new ColorStateList(states,
                new int[]{0x666BBE45, night ? 0x332A2D33 : 0x33C9CDD3}));
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
            more.setTextColor(Ui.accentText(night));
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
                t.setTextColor(Ui.ink(night));
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
