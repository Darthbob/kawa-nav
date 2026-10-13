package io.github.darthbob.kawanav;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/**
 * One recorded ride: the summary, the track, the values over time (pick one, touch it to see where it
 * was) and what can be done with it — rename, delete, export as Rideology's CSV or as a GPX track.
 */
public class RideActivity extends KawaActivity {

    public static final String EXTRA_ID = "ride";

    private static final ChartView.Series[] SERIES = {ChartView.Series.SPEED, ChartView.Series.RPM,
            ChartView.Series.GEAR, ChartView.Series.THROTTLE, ChartView.Series.LEAN, ChartView.Series.WATER,
            ChartView.Series.ACCEL};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private RideStore store;
    private String id;
    private Ride ride;
    private List<RideTsv.Sample> samples;
    private TextView title;
    private LinearLayout summary;
    private LinearLayout chartCard;
    private TrackView track;
    private ChartView chart;
    private TextView cursor;
    private android.widget.Spinner seriesSpinner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        Logger.init(this);
        prefs = Hub.prefs(this);
        store = new RideStore(this);
        night = isNight();
        id = getIntent().getStringExtra(EXTRA_ID);

        LinearLayout root = column();
        root.setPadding(dp(16), dp(20), dp(16), dp(24));
        title = text(26, true);
        root.addView(title);
        TextView when = secondary("");
        when.setId(View.generateViewId());
        when.setPadding(0, 0, 0, dp(8));
        root.addView(when);
        summary = column();
        root.addView(summary);
        root.addView(trackCard());
        root.addView(chartCard());
        root.addView(actionsCard());
        setContentView(page(root));
        load(when);
    }

    private void load(TextView when) {
        new Thread(() -> {
            Ride r = store.get(id);
            List<RideTsv.Sample> s = r == null ? List.of() : store.samples(id);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                ride = r;
                samples = s;
                if (r == null) {
                    title.setText("Ride not found");
                    return;
                }
                title.setText(r.name.isEmpty() ? Ride.defaultName(r.start) : r.name);
                when.setText(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.FULL,
                        java.text.DateFormat.SHORT).format(new java.util.Date(r.start))
                        + (r.endReason.isEmpty() ? "" : " · ended: " + r.endReason));
                showSummary(r);
                boolean gps = r.hasGps && samples.stream().anyMatch(RideTsv.Sample::hasGps);
                track.set(gps ? samples : null, -1);
                ((View) track.getParent()).setVisibility(gps ? View.VISIBLE : View.GONE);
                chartCard.setVisibility(hasSeriesData() ? View.VISIBLE : View.GONE);
                showChart(0);
            });
        }, "ride-load").start();
    }

    private boolean hasSeriesData() {
        for (RideTsv.Sample s : samples) {
            if (ChartView.value(s, ChartView.Series.SPEED) == ChartView.value(s, ChartView.Series.SPEED)) return true;
            if (ChartView.value(s, ChartView.Series.RPM) == ChartView.value(s, ChartView.Series.RPM)) return true;
        }
        return false;
    }

    private LinearLayout trackCard() {
        LinearLayout c = card();
        c.addView(cardTitle("Track"));
        track = new TrackView(this);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.height = dp(220);
        c.addView(track, lp);
        c.addView(secondary("Blue is the slowest part of the ride, red the fastest. The green dot is where "
                + "it started."));
        return c;
    }

    private LinearLayout chartCard() {
        LinearLayout c = card();
        LinearLayout head = row();
        head.addView(cardTitle("Values"), weight());
        String[] names = new String[SERIES.length];
        for (int i = 0; i < SERIES.length; i++) names[i] = SERIES[i].title;
        seriesSpinner = spinner(names, 0);
        seriesSpinner.setOnItemSelectedListener(onSelect(this::showChart));
        head.addView(seriesSpinner, controlWidth());
        c.addView(head);
        chart = new ChartView(this);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.height = dp(160);
        c.addView(chart, lp);
        cursor = secondary("");
        c.addView(cursor);
        chartCard = c;
        return c;
    }

    private void showChart(int which) {
        if (samples == null || seriesSpinner == null) return;
        ChartView.Series series = SERIES[Math.max(0, Math.min(SERIES.length - 1, which))];
        String availability = missingSeries(series) ? " · this ride has no " + series.title.toLowerCase(Locale.ROOT)
                : "";
        cursor.setText("Touch the line to see the second" + availability);
        chart.set(samples, series, index -> {
            track.set(samples, index);
            RideTsv.Sample s = samples.get(index);
            cursor.setText(String.format(Locale.ROOT, "%s · %.0f km/h · %d rpm · %s · %.0f %% · %.1f°",
                    seconds(s.tMs), num(s.kmh), Math.round(num(s.rpm)), gear(s.gear), num(s.thrPct), num(s.lean)));
        });
    }

    private boolean missingSeries(ChartView.Series series) {
        for (RideTsv.Sample s : samples) {
            double v = ChartView.value(s, series);
            if (v == v) return false;
        }
        return true;
    }

    private static double num(double v) {
        return v != v ? 0 : v;
    }

    private static String gear(double g) {
        if (g != g) return "-";
        int gear = (int) Math.round(g);
        return gear == 0 ? "N" : String.valueOf(gear);
    }

    private static String seconds(long ms) {
        long s = ms / 1000;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private void showSummary(Ride r) {
        summary.removeAllViews();
        LinearLayout c = card();
        add(c, "Distance", r.distance() + (r.distBikeM > 0 ? " (from the bike)" : " (from GPS)"));
        add(c, "Time", r.duration());
        if (r.avgKmh > 0) add(c, "Average speed", String.format(Locale.ROOT, "%.0f km/h", r.avgKmh));
        if (r.maxKmh > 0) add(c, "Top speed", String.format(Locale.ROOT, "%.0f km/h", r.maxKmh));
        if (r.maxRpm > 0) add(c, "Highest engine speed", String.format(Locale.ROOT, "%,d rpm", r.maxRpm));
        if (r.maxLeanL < 0 || r.maxLeanR > 0) {
            add(c, "Max lean", String.format(Locale.ROOT, "%.0f° left, %.0f° right", Math.abs(r.maxLeanL), r.maxLeanR));
        }
        if (r.fuelUsedL > 0) add(c, "Fuel used", String.format(Locale.ROOT, "%.2f L", r.fuelUsedL));
        if (r.odoStartKm == r.odoStartKm && r.odoEndKm == r.odoEndKm) {
            add(c, "Odometer", String.format(Locale.ROOT, "%,.0f → %,.0f km", r.odoStartKm, r.odoEndKm));
        }
        add(c, "Recorded", r.samples + " s" + (r.hasGps ? "" : ", without a GPS track"));
        summary.addView(c);
    }

    private void add(LinearLayout c, String label, String value) {
        LinearLayout line = row();
        line.setPadding(0, dp(4), 0, dp(4));
        line.addView(itemTitle(label), weight());
        TextView v = text(15, true);
        v.setText(value);
        v.setGravity(android.view.Gravity.END);
        line.addView(v);
        c.addView(line);
    }

    private LinearLayout actionsCard() {
        LinearLayout c = card();
        c.addView(cardTitle("This ride"));
        LinearLayout r = row();
        r.setPadding(0, dp(4), 0, 0);
        r.addView(button("Rename", v -> rename()), weight());
        r.addView(button("Delete", v -> confirmDelete()), weight());
        c.addView(r);
        LinearLayout e = row();
        e.setPadding(0, dp(4), 0, 0);
        e.addView(primaryButton("Export CSV", v -> export("csv")), weight());
        e.addView(button("Export GPX", v -> export("gpx")), weight());
        c.addView(e);
        c.addView(secondary("CSV has Rideology's columns, so the existing tools can read the ride. GPX is a "
                + "track for map programs. Both go to Download/KawaNav."));
        return c;
    }

    private void rename() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setText(ride.name);
        input.setSingleLine(true);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Rename this ride")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    ride.name = input.getText().toString().trim();
                    store.save(ride);
                    title.setText(ride.name.isEmpty() ? Ride.defaultName(ride.start) : ride.name);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDelete() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Delete this ride?")
                .setMessage("The recorded ride and its track are removed from the phone.")
                .setPositiveButton("Delete", (d, w) -> {
                    store.delete(id);
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Writes the ride to Download/KawaNav and offers to share it. */
    private void export(String kind) {
        if (ride == null || samples == null) return;
        String text = kind.equals("csv") ? RideExport.csv(ride, samples, "", "") : RideExport.gpx(ride, samples);
        byte[] bytes = kind.equals("csv") ? RideExport.csvBytes(text) : text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String name = RideExport.fileName(ride, kind);
        Uri uri = writeToDownloads(name, kind.equals("csv") ? "text/csv" : "application/gpx+xml", bytes);
        if (uri == null) {
            android.widget.Toast.makeText(this, "Export failed", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        Logger.log("Ride: exported " + name + " (" + bytes.length + " bytes)");
        Intent share = new Intent(Intent.ACTION_SEND).setType(kind.equals("csv") ? "text/csv" : "application/gpx+xml")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(share, "Share " + name));
        } catch (android.content.ActivityNotFoundException e) {
            android.widget.Toast.makeText(this, name + " saved to Download/KawaNav",
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private Uri writeToDownloads(String name, String mime, byte[] bytes) {
        android.content.ContentValues v = new android.content.ContentValues();
        v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name);
        v.put(android.provider.MediaStore.Downloads.MIME_TYPE, mime);
        v.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/KawaNav");
        Uri uri = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) return null;
        try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
            out.write(bytes);
            return uri;
        } catch (java.io.IOException e) {
            Logger.log("Ride: export failed: " + e);
            return null;
        }
    }
}
