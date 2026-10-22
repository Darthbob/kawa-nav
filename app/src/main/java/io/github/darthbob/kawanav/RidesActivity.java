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
            // rides saved before 0.9 get their numbers added here, a few per visit, so the records above
            // fill in without opening every ride
            store.summariseOutdated(rides, 5);
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
            double km = r.km();
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
            totalsTiles.addView(records(rides), lp);
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

    /** The best of what has been recorded, and the distance per year. */
    private View records(List<Ride> rides) {
        RideStats stats = RideStats.of(rides);
        LinearLayout c = card();
        c.addView(iconTitle(R.drawable.ic_gauge, cardTitle("Records")));
        if (stats.fastest.known()) {
            c.addView(secondary("Fastest: " + String.format(Locale.ROOT, "%.0f km/h", stats.fastest.value)
                    + " on " + when(stats.fastest.ride)));
        }
        if (stats.maxLeanLeft.known() || stats.maxLeanRight.known()) {
            double l = stats.maxLeanLeft.value, r = stats.maxLeanRight.value;
            c.addView(secondary(String.format(Locale.ROOT, "Most lean: %.0f° left, %.0f° right", l, r)
                    + (l >= r ? " on " + when(stats.maxLeanLeft.ride) : " on " + when(stats.maxLeanRight.ride))));
        }
        if (stats.longest.known()) {
            c.addView(secondary(String.format(Locale.ROOT, "Longest ride: %.0f km on ", stats.longest.value)
                    + when(stats.longest.ride)));
        }
        // what only the rides opened since 0.9 know about themselves
        if (stats.hottest.known()) {
            c.addView(secondary(String.format(Locale.ROOT, "Hottest engine: %.0f °C on ",
                    stats.hottest.value) + when(stats.hottest.ride)));
        }
        if (stats.hardestBraking.known()) {
            c.addView(secondary(String.format(Locale.ROOT, "Hardest braking: %.2f g on ",
                    stats.hardestBraking.value) + when(stats.hardestBraking.ride)));
        }
        if (stats.mostSuspension.known()) {
            c.addView(secondary(String.format(Locale.ROOT, "Most suspension travel: %.1f mm on ",
                    stats.mostSuspension.value) + when(stats.mostSuspension.ride)));
        }
        // the same wording the totals use: "48 min", not "0 h", when the log is young
        StringBuilder total = new StringBuilder(String.format(Locale.ROOT, "Altogether %,.0f km in %s",
                stats.totalKm, minutes((long) (stats.totalHours * 3_600_000))));
        if (stats.fuelL > 0 && stats.fuelKm > 0) {
            total.append(String.format(Locale.ROOT, stats.fuelEstimated ? " · ≈ %,.1f L · %.1f L/100 km"
                    : " · %,.1f L · %.1f L/100 km", stats.fuelL, stats.fuelL / stats.fuelKm * 100));
        }
        c.addView(secondary(total.toString()));
        // how much the electronics worked over the whole log
        StringBuilder work = new StringBuilder();
        if (stats.tcsSeconds > 0) work.append(minutes((long) (stats.tcsSeconds * 1000))).append(" in traction control");
        if (stats.absSeconds > 0) {
            if (work.length() > 0) work.append(" · ");
            work.append(minutes((long) (stats.absSeconds * 1000))).append(" in ABS");
        }
        if (stats.wheelieSeconds > 0) {
            if (work.length() > 0) work.append(" · ");
            work.append(minutes((long) (stats.wheelieSeconds * 1000))).append(" with the front wheel up");
        }
        if (work.length() > 0) c.addView(secondary(work.toString()));
        List<RideStats.Year> years = RideStats.byYear(rides);
        if (years.size() > 1) {
            for (RideStats.Year y : years) {
                c.addView(secondary(String.format(Locale.ROOT, "%d: %,.0f km · %d %s", y.year, y.km, y.rides,
                        y.rides == 1 ? "ride" : "rides")));
            }
        }
        return c;
    }

    private static String when(Ride r) {
        return java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
                .format(new java.util.Date(r.start));
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
        String state = r.id.equals(Hub.rideId) ? " · recording now"
                : "recovered".equals(r.state) ? " · recovered after a restart"
                : "incomplete".equals(r.state) ? " · track lost"
                : "";
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
