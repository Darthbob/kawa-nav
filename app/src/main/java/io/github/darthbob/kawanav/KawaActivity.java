package io.github.darthbob.kawanav;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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

/**
 * Base of KawaNav's screens. The UI is built in code (no XML layouts, no AndroidX): cards on a
 * scrolling page, made from the view helpers here so every screen looks the same.
 */
public abstract class KawaActivity extends Activity {

    protected static final int KAWASAKI_GREEN = 0xFF6BBE45;
    protected static final int OK_GREEN = 0xFF43A047;
    protected static final int WARN_ORANGE = 0xFFFB8C00;
    /** Dark text on the orange banner (white on orange is hard to read in sunlight). */
    protected static final int BANNER_TEXT = 0xFF1A1A1A;
    protected static final int ERROR_RED = 0xFFE53935;

    protected SharedPreferences prefs;
    /** Dark theme; set by {@link #page} (and MainActivity's buildUi) from the current configuration. */
    protected boolean night;

    /** True while the system is in dark mode. */
    protected boolean isNight() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * The scrolling page around a screen's content: page background, and the content kept out of the
     * status and navigation bar (clipped at the padding, so nothing scrolls underneath the status bar),
     * also above the keyboard.
     */
    protected ScrollView page(View content) {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(night ? 0xFF111214 : 0xFFF4F5F7);
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

    // ------------------------------------------------------------------ view helpers

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

    protected int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    protected LinearLayout card() {
        LinearLayout c = column();
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(night ? 0xFF1E2024 : 0xFFFFFFFF);
        bg.setCornerRadius(dp(16));
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.setMargins(0, dp(6), 0, dp(6));
        c.setLayoutParams(lp);
        return c;
    }

    protected TextView cardTitle(String s) {
        TextView t = text(17, true);
        t.setText(s);
        t.setPadding(0, dp(2), 0, dp(4));
        return t;
    }

    protected TextView itemTitle(String s) {
        TextView t = text(15, false);
        t.setText(s);
        return t;
    }

    protected TextView secondary(String s) {
        TextView t = text(13, false);
        t.setText(s);
        t.setAlpha(0.65f);
        return t;
    }

    /** A row of a card: text on the left, a control on the right. */
    protected LinearLayout settingRow() {
        LinearLayout r = row();
        r.setPadding(0, dp(10), 0, dp(4));
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
            more.setTextColor(KAWASAKI_GREEN);
            more.setPadding(0, dp(4), 0, dp(4));
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
        s.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(key, on).apply();
            BridgeService.settingsChanged();
            if (after != null) after.accept(on);
        });
        r.addView(s);
        return r;
    }

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

    protected LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    /** Drop-downs next to a setting's text: fixed width, so the text keeps its room. */
    protected LinearLayout.LayoutParams controlWidth() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(8), 0, 0, 0);
        return lp;
    }

    protected LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    protected Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    /** Big filled button for the main action of a card. */
    protected Button primaryButton(String text, View.OnClickListener l) {
        Button b = button(text, l);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setMinHeight(dp(52));
        b.setStateListAnimator(null);
        tint(b, KAWASAKI_GREEN);
        return b;
    }

    protected void tint(Button b, int color) {
        if (Integer.valueOf(color).equals(b.getTag())) return; // called on every refresh
        b.setTag(color);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(26));
        b.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF), bg, null));
    }

    protected Spinner spinner(String[] items, int selected) {
        Spinner s = new Spinner(this);
        // compact closed view (wraps its text), full-width rows only in the open list
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setSelection(selected);
        return s;
    }

    protected TextView text(int size, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(size);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }
}
