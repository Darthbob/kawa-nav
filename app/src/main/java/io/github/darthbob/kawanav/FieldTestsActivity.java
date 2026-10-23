package io.github.darthbob.kawanav;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Debug builds: the tests still to be done at the bike ({@link FieldTests}), one card each with what it
 * settles, the steps and what counts as an answer, and a mark for the ones done. The tyre and clutch tests
 * also get their numbers, worked out from the latest recorded rides ({@link WheelChecks}). Opened from the
 * "Bike data" debug card in the settings.
 */
public class FieldTestsActivity extends KawaActivity {

    /** How many of the latest rides with bike data the tyre and clutch numbers are worked out for. */
    private static final int RIDES = 12;

    private RideStore store;
    private TextView summary;
    private LinearLayout tyreBox, clutchBox;
    private boolean loading;

    /** One ride and what its wheel speeds say. */
    private static final class Checked {
        final Ride ride;
        final WheelChecks.Tyres tyres;
        final WheelChecks.Clutch clutch;

        Checked(Ride ride, WheelChecks.Tyres tyres, WheelChecks.Clutch clutch) {
            this.ride = ride;
            this.tyres = tyres;
            this.clutch = clutch;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (!Hub.DEBUG_TOOLS) {
            finish();
            return;
        }
        Logger.init(this);
        prefs = Hub.prefs(this);
        store = new RideStore(this);
        night = isNight();

        LinearLayout root = column();
        root.setPadding(dp(Ui.SPACE4), 0, dp(Ui.SPACE4), dp(Ui.SPACE6));
        root.addView(headerBar("Field tests", v -> finish(), null));
        summary = secondary("");
        summary.setPadding(dp(Ui.SPACE1), dp(Ui.SPACE2), dp(Ui.SPACE1), dp(Ui.SPACE2));
        root.addView(summary);
        String group = null;
        for (FieldTests.Test t : FieldTests.ALL) {
            if (!t.group.equals(group)) {
                group = t.group;
                root.addView(sectionHeader(0, group));
            }
            root.addView(testCard(t));
        }
        TextView note = note("Tests still to do at the bike, from the open questions of docs/PROTOCOL.md. "
                + "Tap a test for its steps. The marks stay on this phone; the numbers are worked out from the "
                + RIDES + " latest rides with bike data.");
        note.setPadding(dp(Ui.SPACE1), dp(Ui.SPACE4), dp(Ui.SPACE1), dp(Ui.SPACE2));
        root.addView(note);
        setContentView(page(root));
        showSummary();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (store != null) load(); // a ride may have been recorded or deleted since
    }

    // ------------------------------------------------------------------ the tests

    private View testCard(FieldTests.Test t) {
        LinearLayout c = card();
        LinearLayout head = row();
        head.setMinimumHeight(dp(Ui.TOUCH_MIN));
        head.addView(cardTitle(t.title), weight());
        TextView status = text(12, true);
        head.addView(status);
        TextView arrow = chevron();
        head.addView(arrow);
        c.addView(head);

        LinearLayout body = column();
        body.addView(secondary(t.why));
        body.addView(label("How"));
        body.addView(secondary(t.steps));
        body.addView(label("Settled when"));
        body.addView(secondary(t.settled));
        if (t.results == FieldTests.RESULTS_TYRES) {
            body.addView(label("Recorded rides"));
            tyreBox = column();
            tyreBox.addView(secondary("Reading the latest rides…"));
            body.addView(tyreBox);
        } else if (t.results == FieldTests.RESULTS_CLUTCH) {
            body.addView(label("Recorded rides"));
            clutchBox = column();
            clutchBox.addView(secondary("Reading the latest rides…"));
            body.addView(clutchBox);
        }
        Button done = secondaryButton("", null);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(Ui.SPACE3);
        body.addView(done, lp);
        c.addView(body);

        Runnable show = () -> {
            long at = doneAt(t);
            status.setText(at > 0 ? "Done " + DateFormat.getDateInstance(DateFormat.SHORT).format(new Date(at)) : "To do");
            status.setTextColor(at > 0 ? Ui.accent(night) : Ui.inkSecondary(night));
            status.setPadding(dp(Ui.SPACE2), 0, 0, 0);
            done.setText(at > 0 ? "Mark as still to do" : "Mark done");
        };
        show.run();
        done.setOnClickListener(v -> {
            prefs.edit().putLong(Hub.KEY_DEBUG_FIELD_TEST + t.id, doneAt(t) > 0 ? 0 : System.currentTimeMillis()).apply();
            show.run();
            showSummary();
        });
        body.setVisibility(View.GONE);
        head.setOnClickListener(v -> {
            boolean open = body.getVisibility() != View.VISIBLE;
            body.setVisibility(open ? View.VISIBLE : View.GONE);
            arrow.setRotation(open ? 90 : 0);
        });
        return c;
    }

    private long doneAt(FieldTests.Test t) {
        return prefs.getLong(Hub.KEY_DEBUG_FIELD_TEST + t.id, 0);
    }

    private void showSummary() {
        int done = 0;
        for (FieldTests.Test t : FieldTests.ALL) if (doneAt(t) > 0) done++;
        summary.setText(String.format(Locale.ROOT, "%d of %d tests done", done, FieldTests.ALL.size()));
    }

    /** A small heading inside a test card. */
    private TextView label(String s) {
        TextView t = itemTitle(s);
        t.setPadding(0, dp(Ui.SPACE3), 0, dp(Ui.SPACE1));
        return t;
    }

    // ------------------------------------------------------------------ the numbers

    private void load() {
        if (loading) return;
        loading = true;
        new Thread(() -> {
            List<Checked> rides = new ArrayList<>();
            for (Ride r : store.list()) {
                if (rides.size() >= RIDES) break;
                if (!r.hasBike || r.isRecording()) continue;
                List<RideTsv.Sample> s = store.samples(r.id);
                if (s.isEmpty()) continue;
                rides.add(new Checked(r, WheelChecks.tyres(s), WheelChecks.clutch(s)));
            }
            runOnUiThread(() -> show(rides));
        }, "field-tests").start();
    }

    private void show(List<Checked> rides) {
        loading = false;
        if (isFinishing()) return;
        showTyres(rides);
        showClutch(rides);
    }

    private void showTyres(List<Checked> rides) {
        tyreBox.removeAllViews();
        if (rides.isEmpty()) {
            tyreBox.addView(secondary("No recorded ride with bike data yet."));
            return;
        }
        int bands = WheelChecks.BANDS.length - 1;
        double[] usual = new double[bands];
        StringBuilder head = new StringBuilder("Usual rear/front ratio:");
        for (int b = 0; b < bands; b++) {
            List<Double> values = new ArrayList<>();
            for (Checked c : rides) values.add(c.tyres.ratio[b]);
            usual[b] = WheelChecks.usual(values);
            head.append(b == 0 ? " " : " · ").append(band(b)).append(' ').append(ratio(usual[b]));
        }
        tyreBox.addView(secondary(head + ". Normal rides differ by about 0.06%."));
        for (Checked c : rides) {
            StringBuilder line = new StringBuilder();
            for (int b = 0; b < bands; b++) {
                if (b > 0) line.append(" · ");
                line.append(band(b)).append(' ').append(ratio(c.tyres.ratio[b]));
                if (c.tyres.ratio[b] == c.tyres.ratio[b]) line.append(" (").append(c.tyres.n[b]).append(" s)");
            }
            // the lowest band with a value both here and usually: the ratio rises with speed
            String verdict = "Too little steady riding to compare";
            for (int b = 0; b < bands; b++) {
                if (c.tyres.ratio[b] == c.tyres.ratio[b] && usual[b] == usual[b]) {
                    verdict = String.format(Locale.ROOT, "%+.2f%% against the usual at %s",
                            (c.tyres.ratio[b] / usual[b] - 1) * 100, band(b));
                    break;
                }
            }
            tyreBox.addView(rideRow(c.ride, line.toString(), verdict));
        }
    }

    private void showClutch(List<Checked> rides) {
        clutchBox.removeAllViews();
        if (rides.isEmpty()) {
            clutchBox.addView(secondary("No recorded ride with bike data yet."));
            return;
        }
        StringBuilder gearing = new StringBuilder();
        for (int g = 1; g <= Ride.MAX_GEAR; g++) {
            List<Double> values = new ArrayList<>();
            for (Checked c : rides) values.add(c.clutch.rpmPerKmh[g]);
            double usual = WheelChecks.usual(values);
            if (usual != usual) continue;
            gearing.append(gearing.length() == 0 ? "" : " · ").append(ordinal(g))
                    .append(String.format(Locale.ROOT, " %.1f", usual));
        }
        clutchBox.addView(secondary(gearing.length() == 0 ? "Not enough riding in gear yet for the gearing."
                : "Usual rpm per km/h of the rear wheel at light throttle: " + gearing + "."));
        for (Checked c : rides) {
            WheelChecks.Clutch k = c.clutch;
            String line = k.loadN == 0
                    ? String.format(Locale.ROOT, "No second at %.0f%% throttle or more in 3rd gear or higher",
                    WheelChecks.LOAD_THR)
                    : String.format(Locale.ROOT, "Under load %d s: median %+.1f%%, highest %+.1f%% against the gear's "
                    + "light-throttle value", k.loadN, k.loadMedianPct, k.loadMaxPct);
            String verdict = k.loadN == 0 ? null
                    : k.slipN > 0 ? String.format(Locale.ROOT, "%d s more than %.0f%% above: look at this ride",
                    k.slipN, WheelChecks.SLIP_PCT)
                    : "No sign of slip";
            clutchBox.addView(rideRow(c.ride, line, verdict));
        }
    }

    /** A ride in a test's results: its name and when, then what was found; tapping it opens the ride. */
    private View rideRow(Ride r, String line, String verdict) {
        String when = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(r.start));
        return listRow(r.name.isEmpty() ? Ride.defaultName(r.start) : r.name,
                when + String.format(Locale.ROOT, " · %.0f km", r.km()) + "\n" + line, verdict, true, v -> {
                    Intent i = new Intent(this, RideActivity.class);
                    i.putExtra(RideActivity.EXTRA_ID, r.id);
                    startActivity(i);
                });
    }

    private static String band(int b) {
        double lo = WheelChecks.BANDS[b], hi = WheelChecks.BANDS[b + 1];
        return String.format(Locale.ROOT, "%.0f–%.0f km/h", lo, hi);
    }

    private static String ratio(double v) {
        return v == v ? String.format(Locale.ROOT, "%.4f", v) : "–";
    }

    private static String ordinal(int g) {
        return g + (g == 1 ? "st" : g == 2 ? "nd" : g == 3 ? "rd" : "th");
    }
}
