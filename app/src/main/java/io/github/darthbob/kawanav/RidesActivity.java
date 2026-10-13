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
        root.setPadding(dp(16), dp(20), dp(16), dp(24));
        TextView title = text(26, true);
        title.setText("Recorded rides");
        root.addView(title);
        totals = secondary("");
        totals.setPadding(0, 0, 0, dp(8));
        root.addView(totals);
        empty = secondary("");
        root.addView(empty);
        list = column();
        root.addView(list);
        TextView note = secondary("Rides are stored on the phone only. Nothing is uploaded anywhere.");
        note.setTextSize(12);
        note.setPadding(dp(4), dp(18), dp(4), dp(8));
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
        totals.setText(rides.isEmpty() ? ""
                : String.format(Locale.ROOT, "This week %.0f km in %s · month %.0f km · year %.0f km · all %.0f km",
                week, minutes(weekMs), month, year, all));
        empty.setVisibility(rides.isEmpty() ? View.VISIBLE : View.GONE);
        empty.setText("No rides recorded yet. Start one on the main screen before you ride; the values come "
                + "from the bike while it is connected" + (rides.isEmpty() ? "." : ""));
        for (Ride r : rides) list.addView(row(r, today));
    }

    private static String minutes(long ms) {
        long min = ms / 60_000;
        return min >= 60 ? min / 60 + " h " + String.format(Locale.ROOT, "%02d", min % 60) : min + " min";
    }

    private View row(Ride r, long today) {
        LinearLayout row = settingRow();
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
        row.setOnClickListener(v -> {
            android.content.Intent i = new android.content.Intent(this, RideActivity.class);
            i.putExtra(RideActivity.EXTRA_ID, r.id);
            startActivity(i);
        });
        return row;
    }
}
