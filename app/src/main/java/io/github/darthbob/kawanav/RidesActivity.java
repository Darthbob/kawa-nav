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
 * The recorded rides: a summary on top (this week, this month, this year) and one row per ride with its
 * length, distance and when it was. Tapping a row opens the ride; exports write the ride as a CSV
 * (Rideology's column layout) or a GPX track into Download/KawaNav.
 */
public class RidesActivity extends KawaActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private RideStore store;
    private TextView totals;
    private LinearLayout totalsTiles;
    private LinearLayout list;
    private TextView empty;
    private boolean loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        Logger.init(this);
        prefs = Hub.prefs(this);
        store = new RideStore(this);
        night = isNight();

        LinearLayout root = column();
        root.setPadding(dp(Ui.SPACE4), dp(Ui.SPACE5), dp(Ui.SPACE4), dp(Ui.SPACE6));
        root.addView(display("Recorded rides"));
        totals = secondary("");
        totals.setPadding(0, dp(2), 0, dp(Ui.SPACE2));
        root.addView(totals);
        totalsTiles = column();
        root.addView(totalsTiles);
        empty = secondary("");
        root.addView(empty);
        list = column();
        root.addView(list);
        TextView note = note("Rides are stored on the phone only. Nothing is uploaded anywhere.");
        note.setPadding(dp(Ui.SPACE1), dp(Ui.SPACE5), dp(Ui.SPACE1), dp(Ui.SPACE2));
        root.addView(note);
        setContentView(page(root));
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh(); // a ride may have been exported, renamed or deleted in the ride screen
    }

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
                : String.format(Locale.ROOT, "%d rides · %s this week · %.0f km altogether",
                rides.size(), minutes(weekMs), all));
        empty.setVisibility(rides.isEmpty() ? View.VISIBLE : View.GONE);
        empty.setText("No rides recorded yet. Start one on the main screen before you ride; the values come "
                + "from the bike while it is connected" + (rides.isEmpty() ? "." : ""));
        LinearLayout group = null;
        if (!rides.isEmpty()) {
            group = group();
            list.addView(group);
        }
        for (Ride r : rides) addRow(group, row(r, today));
    }

    private static String minutes(long ms) {
        long min = ms / 60_000;
        return min >= 60 ? min / 60 + " h " + String.format(Locale.ROOT, "%02d", min % 60) : min + " min";
    }

    private View row(Ride r, long today) {
        LinearLayout row = settingRow();
        row.setMinimumHeight(dp(Ui.TOUCH_MIN));
        LinearLayout t = column();
        TextView name = itemTitle(r.name.isEmpty() ? Ride.defaultName(r.start) : r.name);
        t.addView(name);
        String when = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                .format(new java.util.Date(r.start));
        String state = "recovered".equals(r.state) ? " · recovered after a restart" : "";
        t.addView(secondary(String.format(Locale.ROOT, "%s · %s · %s%s", when, r.distance(), r.duration(), state)));
        String detail = String.format(Locale.ROOT, "%d samples%s", r.samples,
                r.maxKmh > 0 ? String.format(Locale.ROOT, " · max %.0f km/h", r.maxKmh) : "")
                + (r.hasGps ? "" : " · no GPS track");
        t.addView(secondary(detail));
        row.addView(t, weight());
        TextView chev = text(20, false);
        chev.setText("›");
        chev.setTextColor(Ui.inkMuted(night));
        row.addView(chev);
        row.setOnClickListener(v -> {
            android.content.Intent i = new android.content.Intent(this, RideActivity.class);
            i.putExtra(RideActivity.EXTRA_ID, r.id);
            startActivity(i);
        });
        return row;
    }
}
