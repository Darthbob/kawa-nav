package io.github.darthbob.kawanav;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * The Rides tab, Rideology's riding log: the distance of this week, month and year on top, then one
 * card per ride with its distance, length and when it was. Tapping a ride opens it; exports write the
 * ride as a CSV (Rideology's column layout) or a GPX track into Download/KawaNav.
 */
public class RidesActivity extends KawaActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private RideStore store;
    private TextView totals;
    private LinearLayout totalsTiles;
    private LinearLayout list;
    private LinearLayout empty;
    private boolean loading;
    private int lastRideState = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        Logger.init(this);
        prefs = Hub.prefs(this);
        store = new RideStore(this);
        night = isNight();

        LinearLayout root = column();
        root.setPadding(dp(Ui.SPACE4), 0, dp(Ui.SPACE4), dp(Ui.SPACE6));
        root.addView(headerBar("Riding log", null, null));
        totals = secondary("");
        totals.setGravity(android.view.Gravity.CENTER);
        totals.setPadding(0, 0, 0, dp(Ui.SPACE2));
        root.addView(totals);
        totalsTiles = column();
        root.addView(totalsTiles);
        empty = column();
        root.addView(empty);
        list = column();
        root.addView(list);
        TextView note = note("Rides are stored on the phone only. Nothing is uploaded anywhere.");
        note.setPadding(dp(Ui.SPACE1), dp(Ui.SPACE5), dp(Ui.SPACE1), dp(Ui.SPACE2));
        root.addView(note);
        setContentView(tabPage(TAB_RIDES, root));
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh(); // a ride may have been exported, renamed or deleted in the ride screen
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    /** Keeps the record button current, and lists a ride as soon as its recording ends. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateTabBar();
            int state = Hub.rideState;
            if (lastRideState != -1 && state != lastRideState) refresh();
            lastRideState = state;
            handler.postDelayed(this, 1000);
        }
    };

    private void refresh() {
        if (loading) return;
        loading = true;
        new Thread(() -> {
            List<Ride> rides = store.list();
            runOnUiThread(() -> show(rides));
        }, "rides-list").start();
    }

    private void show(List<Ride> rides) {
        loading = false;
        if (isFinishing()) return;
        list.removeAllViews();
        long now = System.currentTimeMillis();
        double week = 0, month = 0, year = 0, all = 0;
        long weekMs = 0, monthMs = 0;
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long today = c.getTimeInMillis();
        c.add(Calendar.DAY_OF_MONTH, -6);
        long weekFrom = c.getTimeInMillis();
        c.add(Calendar.DAY_OF_MONTH, 6 - c.get(Calendar.DAY_OF_MONTH) + 1);
        long monthFrom = c.getTimeInMillis();
        c.add(Calendar.MONTH, -c.get(Calendar.MONTH));
        c.set(Calendar.DAY_OF_MONTH, 1);
        long yearFrom = c.getTimeInMillis();

        for (Ride r : rides) {
            double km = (r.distBikeM > 0 ? r.distBikeM : r.distGpsM) / 1000;
            all += km;
            if (r.start >= yearFrom) year += km;
            if (r.start >= monthFrom) {
                month += km;
                monthMs += r.activeMs;
            }
            if (r.start >= weekFrom) {
                week += km;
                weekMs += r.activeMs;
            }
        }
        totalsTiles.removeAllViews();
        if (!rides.isEmpty()) {
            LinearLayout tiles = statGrid(3,
                    statTile("This week", String.format(Locale.ROOT, "%.0f km", week)),
                    statTile("This month", String.format(Locale.ROOT, "%.0f km", month)),
                    statTile("This year", String.format(Locale.ROOT, "%.0f km", year)));
            LinearLayout.LayoutParams lp = matchWrap();
            lp.setMargins(0, dp(Ui.SPACE3), 0, dp(Ui.SPACE2));
            totalsTiles.addView(tiles, lp);
        }
        totals.setText(rides.isEmpty() ? ""
                : String.format(Locale.ROOT, "%d %s · %s this week · %.0f km altogether",
                rides.size(), rides.size() == 1 ? "ride" : "rides", minutes(weekMs), all));
        empty.setVisibility(rides.isEmpty() ? View.VISIBLE : View.GONE);
        empty.removeAllViews();
        if (rides.isEmpty()) {
            LinearLayout card = emptyState("Record your first ride",
                    "Tap the round button below before you ride. The bike's values (speed, engine, gear, "
                            + "throttle, lean, temperatures) and the phone's track are written to the phone once a "
                            + "second while it is connected.");
            empty.addView(card);
        }
        for (Ride r : rides) list.addView(row(r, today));
    }

    private static String minutes(long ms) {
        long min = ms / 60_000;
        return min >= 60 ? min / 60 + " h " + String.format(Locale.ROOT, "%02d", min % 60) : min + " min";
    }

    /** One ride as a card: a route icon, its name and when, and its distance big. */
    private View row(Ride r, long today) {
        LinearLayout card = card();
        card.setPadding(dp(Ui.SPACE3), dp(Ui.SPACE3), dp(Ui.SPACE3), dp(Ui.SPACE3));
        LinearLayout line = row();
        android.widget.FrameLayout badge = new android.widget.FrameLayout(this);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Ui.surfaceSunken(night));
        bg.setCornerRadius(dp(Ui.RADIUS_TILE));
        badge.setBackground(bg);
        android.widget.ImageView route = icon(R.drawable.ic_route, true);
        badge.addView(route, new android.widget.FrameLayout.LayoutParams(dp(26), dp(26), android.view.Gravity.CENTER));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(52), dp(52));
        blp.rightMargin = dp(Ui.SPACE3);
        line.addView(badge, blp);

        LinearLayout t = column();
        TextView name = itemTitle(r.name.isEmpty() ? Ride.defaultName(r.start) : r.name);
        name.setTypeface(Ui.titles());
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.addView(name);
        String when = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                .format(new java.util.Date(r.start));
        String state = "recovered".equals(r.state) ? " · recovered after a restart" : "";
        t.addView(secondary(when + state));
        Figure distance = figure(24, android.view.Gravity.START);
        distance.set(r.distance());
        distance.view.setPadding(0, dp(Ui.SPACE1), 0, 0);
        t.addView(distance.view);
        String detail = r.duration()
                + (r.maxKmh > 0 ? String.format(Locale.ROOT, " · max %.0f km/h", r.maxKmh) : "")
                + (r.hasGps ? "" : " · no GPS track");
        t.addView(note(detail));
        line.addView(t, weight());
        line.addView(chevron());
        card.addView(line);
        card.setOnClickListener(v -> {
            android.content.Intent i = new android.content.Intent(this, RideActivity.class);
            i.putExtra(RideActivity.EXTRA_ID, r.id);
            startActivity(i);
        });
        return card;
    }
}
