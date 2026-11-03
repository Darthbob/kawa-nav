package io.github.darthbob.kawanav;

import android.content.Intent;
import android.net.Uri;
import android.graphics.drawable.GradientDrawable;
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
 * was), how its bends were ridden and the grip it used ({@link RideAnalysis}), and what can be done with
 * it — rename, delete, export as Rideology's CSV or as a GPX track.
 */
public class RideActivity extends KawaActivity {

    public static final String EXTRA_ID = "ride";

    /** Every value a ride can have; the list offers the ones this ride recorded. */
    private static final ChartView.Series[] ALL_SERIES = ChartView.Series.values();
    private ChartView.Series[] SERIES = ALL_SERIES;
    /** What the line under the chart always shows for the touched second, whichever value is picked. */
    private static final java.util.Set<ChartView.Series> ALWAYS_SHOWN = java.util.EnumSet.of(ChartView.Series.SPEED,
            ChartView.Series.RPM, ChartView.Series.GEAR, ChartView.Series.THROTTLE, ChartView.Series.LEAN);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private RideStore store;
    private String id;
    private Ride ride;
    private List<RideTsv.Sample> samples;
    private TextView title;
    private Hero distance;
    private LinearLayout summary;
    private LinearLayout chartCard;
    private LinearLayout trackCard;
    private TrackView track;
    /** Under the map: what the track's colours mean right now. */
    private TextView trackNote;
    private ChartView chart;
    private TextView cursor;
    private android.widget.Spinner seriesSpinner;
    private LinearLayout bendsCard;
    private LinearLayout bendSummary;
    private GripView grip;
    private TextView gripNote;
    private android.widget.Spinner bendOrder;
    private LinearLayout bendList;
    private RideAnalysis analysis;
    /** How many bends the list shows. */
    private static final int BENDS_LISTED = 8;

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
        root.setPadding(dp(Ui.SPACE4), 0, dp(Ui.SPACE4), dp(Ui.SPACE6));
        LinearLayout header = headerBar("", v -> finish(),
                headerAction(R.drawable.ic_edit, Lang.t(R.string.ride_rename), v -> rename()));
        title = header.findViewWithTag("title");
        root.addView(header);
        LinearLayout top = card();
        top.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        TextView when = secondary("");
        when.setGravity(android.view.Gravity.CENTER);
        top.addView(when, matchWrap());
        distance = hero(52);
        distance.view.setPadding(0, dp(Ui.SPACE3), 0, dp(Ui.SPACE1));
        top.addView(distance.view, matchWrap());
        root.addView(top);
        summary = column();
        root.addView(summary);
        trackCard = trackCard();
        root.addView(trackCard);
        root.addView(chartCard());
        bendsCard = bendsCard();
        root.addView(bendsCard);
        root.addView(actionsCard());
        setContentView(page(root));
        load(when);
    }

    private void load(TextView when) {
        new Thread(() -> {
            Ride r = store.get(id);
            List<RideTsv.Sample> s = r == null ? List.of() : store.samples(id);
            // a ride saved before the summary kept the channels gets them computed here, once, in the
            // background: from then on opening it is instant. A ride still being recorded is computed in
            // memory only: its file belongs to the recorder.
            if (r != null && !s.isEmpty() && r.summaryVersion < Ride.SUMMARY_VERSION) {
                r.summarize(s);
                if (!r.isRecording() && !store.isOpen(r.id)) store.save(r);
            }
            RideAnalysis bends = s.isEmpty() ? null : RideAnalysis.of(s);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                ride = r;
                samples = s;
                if (r == null) {
                    title.setText(Lang.t(R.string.ride_not_found));
                    distance.view.setVisibility(View.GONE);
                    return;
                }
                distance.figure.set(r.distance());
                distance.caption.setText(Lang.t(R.string.ride_distance_caption, r.duration()));
                title.setText(r.name.isEmpty() ? Ride.defaultName(r.start) : r.name);
                when.setText(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.FULL,
                        java.text.DateFormat.SHORT).format(new java.util.Date(r.start))
                        + (r.endReason.isEmpty() ? "" : " · " + Lang.t(R.string.ride_ended, Ride.shown(r.endReason))));
                showSummary(r);
                // a track needs at least two positions, else there is nothing to draw
                long gpsPoints = samples.stream().filter(RideTsv.Sample::hasGps).count();
                boolean gps = r.hasGps && gpsPoints >= 2;
                track.set(gps ? samples : null, -1);
                trackCard.setVisibility(gps ? View.VISIBLE : View.GONE);
                // the chart is shown when the ride has any value at all, not only speed and engine speed
                boolean anySeries = refreshSeries();
                chartCard.setVisibility(anySeries ? View.VISIBLE : View.GONE);
                showChart(0);
                showBends(bends);
            });
        }, "ride-load").start();
    }

    private LinearLayout trackCard() {
        LinearLayout c = card();
        track = new TrackView(this);
        LinearLayout head = row();
        head.addView(iconTitle(R.drawable.ic_route, cardTitle(Lang.t(R.string.ride_track))), weight());
        head.addView(headerAction(R.drawable.ic_fit, Lang.t(R.string.ride_fit), v -> track.fit()),
                new LinearLayout.LayoutParams(dp(Ui.TOUCH_MIN), dp(Ui.TOUCH_MIN)));
        c.addView(head);
        GradientDrawable well = new GradientDrawable();
        well.setColor(Ui.surfaceSunken(night));
        well.setCornerRadius(dp(Ui.RADIUS_TILE));
        track.setBackground(well);
        track.setClipToOutline(true);   // the map keeps the well's round corners
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.addView(track, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        if (track.hasMap()) {
            android.widget.FrameLayout.LayoutParams at = new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.BOTTOM | android.view.Gravity.END);
            at.setMargins(0, 0, dp(Ui.SPACE1), dp(Ui.SPACE1));   // clear of the round corner
            frame.addView(attribution(), at);
        }
        LinearLayout.LayoutParams lp = matchWrap();
        lp.height = dp(260);
        lp.setMargins(0, dp(Ui.SPACE2), 0, dp(Ui.SPACE2));
        c.addView(frame, lp);
        trackNote = note("");
        c.addView(trackNote);
        return c;
    }

    /** What the track's colours mean: they follow the value picked for the chart. */
    private void describeColours(ChartView.Series series) {
        double lo = track.low(), hi = track.high();
        String f = "%." + series.decimals + "f";
        String unit = series.unit.isEmpty() ? "" : " " + series.unit;
        String title = Ride.shown(series.title).toLowerCase(Locale.getDefault());
        String scale = lo == lo
                ? Lang.t(R.string.ride_colours, title, String.format(Locale.ROOT, f, lo) + unit,
                        String.format(Locale.ROOT, f, hi) + unit, series.lineCount() > 1
                                ? ", " + Lang.t(R.string.ride_colours_line, Ride.shown(series.lines[series.lineCount() - 1])) : "")
                : Lang.t(R.string.ride_colours_none, title);
        trackNote.setText(scale + " " + Lang.t(R.string.ride_track_note));
    }

    /** OpenStreetMap's credit, on the map as its tile policy asks; a tap opens its copyright page. */
    private TextView attribution() {
        TextView t = text(10, false);
        t.setText(Lang.t(R.string.ride_osm));
        t.setTextColor(night ? 0xFFDDDDDD : 0xFF333333);
        t.setBackgroundColor(night ? 0xB3000000 : 0xB3FFFFFF);
        t.setPadding(dp(4), dp(1), dp(4), dp(1));
        t.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright")));
            } catch (android.content.ActivityNotFoundException e) {
                // no browser: the credit is still shown
            }
        });
        return t;
    }

    private LinearLayout chartCard() {
        LinearLayout c = card();
        LinearLayout head = row();
        head.addView(iconTitle(R.drawable.ic_speed, cardTitle(Lang.t(R.string.ride_values))), weight());
        String[] names = new String[SERIES.length];
        for (int i = 0; i < SERIES.length; i++) names[i] = Ride.shown(SERIES[i].title);
        seriesSpinner = spinner(names, 0);
        seriesSpinner.setOnItemSelectedListener(onSelect(this::showChart));
        head.addView(seriesSpinner, controlWidth());
        chart = new ChartView(this);
        head.addView(headerAction(R.drawable.ic_fit, Lang.t(R.string.ride_fit_chart), v -> chart.showAll()),
                new LinearLayout.LayoutParams(dp(Ui.TOUCH_MIN), dp(Ui.TOUCH_MIN)));
        c.addView(head);
        // the map fades what the chart is not showing, so a zoomed stretch can be found on the track
        chart.setOnWindow((from, to) -> track.setWindow(from, to));
        LinearLayout.LayoutParams lp = matchWrap();
        lp.height = dp(180);   // taller than before the chart could zoom: room to read a short stretch
        c.addView(chart, lp);
        cursor = secondary("");
        c.addView(cursor);
        chartCard = c;
        return c;
    }

    private void showChart(int which) {
        if (samples == null || seriesSpinner == null) return;
        ChartView.Series series = SERIES[Math.max(0, Math.min(SERIES.length - 1, which))];
        String availability = missingSeries(series)
                ? " · " + Lang.t(R.string.ride_chart_missing, Ride.shown(series.title).toLowerCase(Locale.getDefault())) : "";
        cursor.setText(Lang.t(R.string.ride_chart_hint) + availability
                + (hasInterventions() ? " · " + Lang.t(R.string.ride_chart_marks) : ""));
        // the track takes the chart's colours: the same value, from blue (lowest) to red (highest)
        double[] values = new double[samples.size()];
        for (int i = 0; i < values.length; i++) values[i] = ChartView.mapValue(samples.get(i), series);
        track.colourBy(values);
        describeColours(series);
        onPoint = index -> {
            track.set(samples, index);
            RideTsv.Sample s = samples.get(index);
            // the values every second shows, then the one picked when it is not among them
            String picked = ALWAYS_SHOWN.contains(series) ? "" : " · " + ChartView.describe(s, series);
            cursor.setText(String.format(Locale.ROOT, "%s · %.0f km/h · %d rpm · %s · %.0f %% · %.1f°",
                    seconds(s.tMs), num(s.kmh), Math.round(num(s.rpm)), gear(s.gear), num(s.thrPct), num(s.lean))
                    + picked);
        };
        chart.set(samples, series, onPoint);
    }

    /** What touching the chart does: the second on the track and under the chart. */
    private ChartView.OnPoint onPoint;

    /** How the ride's bends were ridden and the grip it used; hidden for a ride without a bend. */
    private LinearLayout bendsCard() {
        LinearLayout c = card();
        c.setVisibility(View.GONE);
        c.addView(iconTitle(R.drawable.ic_road, cardTitle(Lang.t(R.string.ride_bends_title))));
        bendSummary = column();
        c.addView(bendSummary);
        grip = new GripView(this);
        grip.setContentDescription(Lang.t(R.string.ride_grip_description));
        LinearLayout.LayoutParams lp = matchWrap();
        lp.height = dp(280);
        lp.setMargins(0, dp(Ui.SPACE3), 0, dp(Ui.SPACE2));
        c.addView(grip, lp);
        gripNote = note("");
        c.addView(gripNote);
        LinearLayout head = row();
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setPadding(0, dp(Ui.SPACE4), 0, dp(Ui.SPACE1));
        head.addView(itemTitle(Lang.t(R.string.ride_the_bends)), weight());
        bendOrder = spinner(RideAnalysis.orders(), 0);
        bendOrder.setOnItemSelectedListener(onSelect(this::showBendList));
        head.addView(bendOrder, controlWidth());
        c.addView(head);
        bendList = column();
        c.addView(bendList);
        TextView how = note(Lang.t(R.string.ride_bends_how));
        how.setPadding(0, dp(Ui.SPACE2), 0, 0);
        c.addView(how);
        return c;
    }

    private void showBends(RideAnalysis a) {
        analysis = a;
        boolean any = a != null && !a.bends.isEmpty();
        bendsCard.setVisibility(any ? View.VISIBLE : View.GONE);
        if (!any) return;
        bendSummary.removeAllViews();
        LinearLayout tiles = statGrid(2,
                statTile(Lang.t(R.string.ride_bends_title), String.valueOf(a.bends.size())),
                statTile(Lang.t(R.string.ride_typical_lean), String.format(Locale.ROOT, "%.0f°", a.median(b -> b.lean))),
                statTile(Lang.t(R.string.ride_typical_apex), String.format(Locale.ROOT, "%.0f km/h", a.median(b -> b.apexKmh))),
                statTile(Lang.t(R.string.ride_out_in), String.format(Locale.ROOT, "%+.0f km/h", a.median(RideAnalysis.Bend::gain))));
        LinearLayout tileSurface = group();
        tileSurface.setPadding(dp(Ui.SPACE2), dp(Ui.SPACE2), dp(Ui.SPACE2), dp(Ui.SPACE2));
        tileSurface.addView(tiles);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.setMargins(0, dp(Ui.SPACE2), 0, dp(Ui.SPACE2));
        bendSummary.addView(tileSurface, lp);
        LinearLayout g = group();
        add(g, Lang.t(R.string.ride_left_right), a.count(-1) + " / " + a.count(1));
        add(g, Lang.t(R.string.ride_braking_apex), bends(a.brakingAtApex()));
        add(g, Lang.t(R.string.ride_snap), bends(a.snapThrottle()));
        add(g, Lang.t(R.string.ride_downshift), bends(a.downshifting()));
        bendSummary.addView(g);
        grip.set(a);
        gripNote.setText(Lang.t(R.string.ride_grip_note, a.maxBrakeG, a.maxAccelG, a.maxSideG));
        showBendList(bendOrder.getSelectedItemPosition());
    }

    private static String bends(int n) {
        return n == 0 ? Lang.t(R.string.ride_bends_none) : Lang.plural(R.plurals.ride_bends, n);
    }

    /** The first bends in the order picked, each a tap away from the track and the chart. */
    private void showBendList(int order) {
        if (analysis == null || samples == null) return;
        bendList.removeAllViews();
        for (RideAnalysis.Bend b : analysis.top(order, BENDS_LISTED)) {
            String title = Lang.t(R.string.ride_bend_title, seconds(samples.get(b.apex).tMs),
                    b.side < 0 ? Lang.t(R.string.ride_left) : Lang.t(R.string.ride_right), b.lean, num(b.apexKmh));
            String line = Lang.t(R.string.ride_bend_line, num(b.entryKmh), num(b.exitKmh), num(b.radiusM));
            java.util.List<String> notes = new java.util.ArrayList<>();
            if (b.apexThrottle == b.apexThrottle) {
                notes.add(Lang.t(R.string.ride_bend_throttle, b.apexThrottle));
            }
            if (b.brakingAtApex) notes.add(Lang.t(R.string.ride_bend_braking));
            if (b.snapThrottle) notes.add(Lang.t(R.string.ride_bend_snap));
            if (b.downshifts > 0) notes.add(b.downshifts == 1 ? Lang.t(R.string.ride_bend_shifted)
                    : Lang.plural(R.plurals.ride_bend_shifted_gears, b.downshifts));
            bendList.addView(listRow(title, line, notes.isEmpty() ? null : String.join(" · ", notes), false,
                    v -> showBend(b)));
        }
    }

    /** A bend on the track and the chart: its apex marked, the page scrolled up to the track. */
    private void showBend(RideAnalysis.Bend b) {
        chart.mark(b.apex);
        if (onPoint != null) onPoint.onSample(b.apex);
        if (trackCard.getVisibility() != View.VISIBLE) return;
        View v = trackCard;
        while (v.getParent() instanceof View && !(v instanceof android.widget.ScrollView)) v = (View) v.getParent();
        if (v instanceof android.widget.ScrollView) ((android.widget.ScrollView) v).smoothScrollTo(0, trackCard.getTop());
    }

    /**
     * Once the ride is loaded: the list offers only the values this ride recorded. True when it offers
     * anything at all, which is what decides whether the chart card is worth showing.
     */
    private boolean refreshSeries() {
        java.util.List<ChartView.Series> have = new java.util.ArrayList<>();
        for (ChartView.Series s : ALL_SERIES) if (!missingSeries(s)) have.add(s);
        boolean any = !have.isEmpty();
        if (!any) have.add(ChartView.Series.SPEED);
        SERIES = have.toArray(new ChartView.Series[0]);
        String[] names = new String[SERIES.length];
        for (int i = 0; i < SERIES.length; i++) names[i] = Ride.shown(SERIES[i].title);
        android.widget.Spinner fresh = spinner(names, 0);
        fresh.setOnItemSelectedListener(onSelect(this::showChart));
        android.view.ViewGroup parent = (android.view.ViewGroup) seriesSpinner.getParent();
        int at = parent.indexOfChild(seriesSpinner);
        parent.removeViewAt(at);
        parent.addView(fresh, at, seriesSpinner.getLayoutParams());
        seriesSpinner = fresh;
        return any;
    }

    /** True when the ride has a traction-control or ABS event, from the summary the ride already holds. */
    private boolean hasInterventions() {
        return ride != null && (ride.tcsOnS > 0 || ride.absOnS > 0);
    }

    private boolean missingSeries(ChartView.Series series) {
        for (RideTsv.Sample s : samples) if (ChartView.has(s, series)) return false;
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
        summary.addView(headline(r));
        // one folding section per group of values, each opened only if it is the first with rows
        boolean opened = false;
        for (String name : r.sections()) {
            if (Ride.QUALITY.equals(name)) continue;   // its own section below, with the file's own rows
            Section sec = valueSection(r, name);
            if (sec == null) continue;
            if (!opened) {
                sec.setOpen(true);
                opened = true;
            }
            summary.addView(sec.view);
        }
        Section quality = qualitySection(r);
        if (!opened) quality.setOpen(true);
        summary.addView(quality.view);
    }

    /** The numbers a rider looks at first: six tiles, the fuel economy, and what is missing from the ride. */
    private LinearLayout headline(Ride r) {
        LinearLayout section = column();
        section.addView(sectionHeader(R.drawable.ic_speed, Lang.t(R.string.ride_summary)));
        LinearLayout tiles = statGrid(2,
                statTile(Lang.t(R.string.ride_time), r.duration()),
                r.avgKmh > 0 ? statTile(Lang.t(R.string.ride_average), String.format(Locale.ROOT, "%.0f km/h", r.avgKmh)) : null,
                r.maxKmh > 0 ? statTile(Lang.t(R.string.ride_top_speed), String.format(Locale.ROOT, "%.0f km/h", r.maxKmh)) : null,
                r.maxLeanL < 0 || r.maxLeanR > 0
                        ? statTile(Lang.t(R.string.ride_max_lean), String.format(Locale.ROOT, "%.0f° / %.0f°", Math.abs(r.maxLeanL), r.maxLeanR))
                        : null,
                r.maxRpm > 0 ? statTile(Lang.t(R.string.ride_max_rpm), String.format(Locale.ROOT, "%,d rpm", r.maxRpm)) : null,
                r.fuelL() > 0 ? statTile(Lang.t(R.string.ride_fuel_used), String.format(Locale.ROOT,
                        r.fuelEstimated() ? "≈ %.1f L" : "%.1f L", r.fuelL())) : null);
        LinearLayout tileSurface = group();
        tileSurface.setPadding(dp(Ui.SPACE2), dp(Ui.SPACE2), dp(Ui.SPACE2), dp(Ui.SPACE2));
        tileSurface.addView(tiles);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.setMargins(0, dp(Ui.SPACE2), 0, dp(Ui.SPACE2));
        section.addView(tileSurface, lp);

        double km = r.km();
        if (r.fuelL() > 0 && km > 0) {
            TextView economy = secondary(Lang.t(R.string.ride_economy, r.fuelL() / km * 100, km) + (r.fuelEstimated()
                    ? ", " + Lang.t(R.string.ride_economy_estimated) : ""));
            economy.setPadding(dp(Ui.SPACE1), 0, dp(Ui.SPACE1), dp(Ui.SPACE2));
            section.addView(economy);
        }
        if (!r.hasBike || !r.hasGps) {
            TextView what = note(!r.hasBike
                    ? Lang.t(R.string.ride_phone_only)
                    : Lang.t(R.string.ride_no_gps));
            what.setPadding(dp(Ui.SPACE1), 0, dp(Ui.SPACE1), dp(Ui.SPACE2));
            section.addView(what);
        }
        return section;
    }

    /** One section of values, folded: label left, "lowest / average / highest" right. */
    private Section valueSection(Ride r, String name) {
        Section sec = section(sectionIcon(name), Ride.shown(name), false);
        LinearLayout g = group();
        boolean any = false;
        for (Ride.Metric m : r.metrics) {
            if (!name.equals(m.section) || !m.known()) continue;
            add(g, Ride.shown(m.label), range(m));
            any = true;
        }
        // the electronics: how long they worked, not what level they were at (a level means nothing averaged)
        if (Ride.BRAKES.equals(name)) {
            any |= addSeconds(g, Lang.t(R.string.ride_tcs), r.tcsOnS);
            any |= addSeconds(g, Lang.t(R.string.ride_abs), r.absOnS);
            any |= addSeconds(g, Lang.t(R.string.ride_cruise), r.cruiseOnS);
        }
        if (Ride.CHASSIS.equals(name)) any |= addSeconds(g, Lang.t(R.string.ride_wheelie), r.wheelieS);
        if (Ride.ENGINE.equals(name)) {
            any |= addGears(g, r);
            any |= addClutch(g, r);
        }
        if (!any) return null;
        if (hasRanges(r, name)) {
            Ride.Metric economy = r.metric("fuel_inst");
            TextView legend = note((hasExtremes(r, name) ? Lang.t(R.string.ride_legend_extremes) : Lang.t(R.string.ride_legend))
                    + (economy.known() && name.equals(economy.section) ? " " + Lang.t(R.string.ride_legend_economy) : ""));
            legend.setPadding(0, dp(Ui.SPACE2), 0, 0);
            g.addView(legend);
        }
        sec.body.addView(g);
        sec.setPreview(preview(r, name));
        LinearLayout.LayoutParams lp = matchWrap();
        lp.setMargins(0, 0, 0, dp(Ui.CARD_GAP));
        sec.view.setLayoutParams(lp);
        return sec;
    }

    /** Where the ride's own file is concerned: what was recorded, and how completely. */
    private Section qualitySection(Ride r) {
        Section sec = section(R.drawable.ic_info, Ride.shown(Ride.QUALITY), false);
        LinearLayout g = group();
        for (Ride.Metric m : r.metrics) {
            if (Ride.QUALITY.equals(m.section) && m.known()) add(g, Ride.shown(m.label), range(m));
        }
        if (r.odoStartKm == r.odoStartKm && r.odoEndKm == r.odoEndKm) {
            add(g, Lang.t(R.string.main_odometer), String.format(Locale.ROOT, "%,.0f → %,.0f km", r.odoStartKm, r.odoEndKm));
        }
        // both distances when they disagree: the odometer counts whole wheel turns, GPS cuts corners
        if (r.distBikeM > 0 && r.distGpsM > 0 && Math.abs(r.distBikeM - r.distGpsM) > 0.03 * r.distBikeM) {
            add(g, Lang.t(R.string.ride_distance_odo_gps), String.format(Locale.ROOT, "%.1f / %.1f km",
                    r.distBikeM / 1000, r.distGpsM / 1000));
        }
        add(g, Lang.t(R.string.ride_recorded), Lang.plural(R.plurals.ride_recorded_value, (int) r.samples, r.duration(), r.samples));
        long expected = r.activeMs / 1000 + 1;
        if (expected > 0 && r.samples > 0) {
            add(g, Lang.t(R.string.ride_seconds_recorded), Lang.t(R.string.ride_seconds_value,
                    r.samples, expected, 100.0 * r.samples / expected));
        }
        sec.body.addView(g);
        sec.setPreview(Lang.t(r.hasGps ? R.string.ride_preview_gps : R.string.ride_preview_no_gps, r.samples));
        LinearLayout.LayoutParams lp = matchWrap();
        lp.setMargins(0, 0, 0, dp(Ui.CARD_GAP));
        sec.view.setLayoutParams(lp);
        return sec;
    }

    /** "lowest / average / highest unit", the three numbers a value row carries; "lowest to highest unit" for one that swings both ways. */
    private static String range(Ride.Metric m) {
        String f = "%," + "." + m.decimals + "f";
        if (m.extremes) return Lang.t(R.string.ride_range_to, String.format(Locale.ROOT, f, m.min),
                String.format(Locale.ROOT, f, m.max)) + " " + Ride.shown(m.unit);
        return String.format(Locale.ROOT, f + " / " + f + " / " + f + " %s", m.min, m.mean, m.max, Ride.shown(m.unit));
    }

    /** True when the section has a row that shows only its lowest and highest. */
    private static boolean hasExtremes(Ride r, String name) {
        for (Ride.Metric m : r.metrics) if (name.equals(m.section) && m.known() && m.extremes) return true;
        return false;
    }

    /**
     * The time in each gear, with its share of the ride, and the gear changes: all of them, then those the
     * quick shifter made. False when the ride has no gear.
     */
    private boolean addGears(LinearLayout group, Ride r) {
        if (r.gearS == null) return false;
        long total = 0;
        for (int s : r.gearS) total += s;
        if (total == 0) return false;
        for (int gear = 0; gear < r.gearS.length; gear++) {
            int s = r.gearS[gear];
            if (s == 0) continue;
            add(group, gear == 0 ? Lang.t(R.string.ride_neutral) : Lang.t(R.string.ride_in_gear, gear),
                    String.format(Locale.ROOT, "%s (%.0f %%)", seconds((double) s), 100.0 * s / total));
        }
        add(group, Lang.t(R.string.ride_gear_changes), Lang.t(R.string.ride_up_down, r.shiftsUp, r.shiftsDown));
        if (r.kqsUp == r.kqsUp) {
            add(group, Lang.t(R.string.ride_with_kqs), Lang.t(R.string.ride_up_down, Math.round(r.kqsUp), Math.round(r.kqsDown)));
        }
        return true;
    }

    /**
     * The clutch under load: the engine against the rear wheel at half throttle or more in 3rd gear and up,
     * compared with the same gear at light throttle ({@link WheelChecks#clutch}). Nothing without such
     * seconds.
     */
    private boolean addClutch(LinearLayout group, Ride r) {
        if (r.clutchLoadS != r.clutchLoadS) return false;
        String verdict = r.clutchSlipping()
                ? Lang.t(R.string.ride_clutch_slipping, r.clutchSlipS, r.clutchSlipRuns)
                : r.clutchSlipS > 0 ? Lang.t(R.string.ride_clutch_holds_once, r.clutchSlipS)
                : Lang.t(R.string.ride_clutch_holds);
        add(group, Lang.t(R.string.ride_clutch), verdict);
        add(group, Lang.t(R.string.ride_engine_gear), Lang.t(R.string.ride_engine_gear_value,
                r.clutchMedianPct, r.clutchMaxPct, seconds(r.clutchLoadS)));
        return true;
    }

    /** A line for a system that either worked or did not: its time when it did, "never" when it did not. */
    private boolean addSeconds(LinearLayout group, String label, double seconds) {
        if (seconds != seconds) return false;              // the ride has no such column at all
        add(group, label, seconds > 0 ? seconds(seconds) : Lang.t(R.string.ride_never));
        return true;
    }

    private static String seconds(double s) {
        long total = Math.round(s);
        return total >= 60 ? Lang.t(R.string.ride_min_s, total / 60, total % 60) : Lang.t(R.string.ride_s, total);
    }

    /** True when the section has at least one "lowest / average / highest" row (not only state lines). */
    private static boolean hasRanges(Ride r, String name) {
        for (Ride.Metric m : r.metrics) if (name.equals(m.section) && m.known()) return true;
        return false;
    }

    /** What a folded section says about itself: the highest value it holds. */
    private static String preview(Ride r, String name) {
        Ride.Metric first = null;
        for (Ride.Metric m : r.metrics) {
            if (name.equals(m.section) && m.known()) {
                first = m;
                break;
            }
        }
        if (first == null) return "";
        return Lang.t(R.string.ride_up_to, String.format(Locale.ROOT, "%," + "." + first.decimals + "f", first.max)) + " " + Ride.shown(first.unit);
    }

    private static int sectionIcon(String name) {
        if (Ride.ENGINE.equals(name)) return R.drawable.ic_speed;
        if (Ride.CHASSIS.equals(name)) return R.drawable.ic_sliders;
        if (Ride.BRAKES.equals(name)) return R.drawable.ic_alert;
        if (Ride.TEMPERATURES.equals(name)) return R.drawable.ic_thermo;
        return R.drawable.ic_info;
    }

    private void add(LinearLayout group, String label, String text) {
        LinearLayout line = row();
        line.setPadding(0, dp(Ui.ROW_PAD_V), 0, dp(Ui.ROW_PAD_V));
        line.setMinimumHeight(dp(Ui.TOUCH_MIN));
        line.addView(itemTitle(label), weight());
        line.addView(value(text));
        addRow(group, line);
    }

    private LinearLayout actionsCard() {
        LinearLayout c = card();
        c.addView(iconTitle(R.drawable.ic_share, cardTitle(Lang.t(R.string.ride_keep_share))));
        LinearLayout e = row();
        e.setPadding(0, dp(6), 0, 0);
        e.addView(iconButton(primaryButton(Lang.t(R.string.ride_export_csv), v -> export("csv")), R.drawable.ic_download), weight());
        e.addView(gap());
        e.addView(iconButton(secondaryButton(Lang.t(R.string.ride_export_gpx), v -> export("gpx")), R.drawable.ic_download), weight());
        c.addView(e);
        // the two that carry everything: every recorded value as a table, and the app's own file
        LinearLayout a = row();
        a.setPadding(0, dp(Ui.SPACE2), 0, 0);
        a.addView(iconButton(secondaryButton(Lang.t(R.string.ride_export_full), v -> export("full")), R.drawable.ic_download), weight());
        a.addView(gap());
        a.addView(iconButton(secondaryButton(Lang.t(R.string.ride_export_raw), v -> export("tsv")), R.drawable.ic_download), weight());
        c.addView(a);
        LinearLayout r = row();
        r.setPadding(0, dp(Ui.SPACE2), 0, 0);
        r.addView(iconButton(destructiveButton(Lang.t(R.string.common_delete), v -> confirmDelete()), R.drawable.ic_trash), weight());
        c.addView(r);
        TextView caption = note(Lang.t(R.string.ride_export_note));
        caption.setPadding(0, dp(Ui.SPACE2), 0, 0);
        c.addView(caption);
        return c;
    }

    private View gap() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(Ui.SPACE2), 1));
        return v;
    }

    private void rename() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setText(ride.name);
        input.setSingleLine(true);
        new android.app.AlertDialog.Builder(this)
                .setTitle(Lang.t(R.string.ride_rename))
                .setView(input)
                .setPositiveButton(Lang.t(R.string.planner_save), (d, w) -> {
                    ride.name = input.getText().toString().trim();
                    store.save(ride);
                    title.setText(ride.name.isEmpty() ? Ride.defaultName(ride.start) : ride.name);
                })
                .setNegativeButton(Lang.t(R.string.common_cancel), null)
                .show();
    }

    private void confirmDelete() {
        // Still being written: deleting it would unlink the file the recorder keeps appending to, and
        // the ride would come back finished, with no track at all, at the next stop. Say so instead.
        if (RideStore.isOpen(id)) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle(Lang.t(R.string.ride_recording_title))
                    .setMessage(Lang.t(R.string.ride_recording_message))
                    .setPositiveButton(Lang.t(R.string.common_ok), null)
                    .show();
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(Lang.t(R.string.ride_delete_title))
                .setMessage(Lang.t(R.string.ride_delete_message))
                .setPositiveButton(Lang.t(R.string.common_delete), (d, w) -> {
                    store.delete(id);
                    finish();
                })
                .setNegativeButton(Lang.t(R.string.common_cancel), null)
                .show();
    }

    /** Writes the ride to Download/KawaNav and offers to share it. */
    private void export(String kind) {
        if (ride == null || samples == null) return;
        // the ride as Rideology's CSV, as a CSV with every recorded value, as a GPX track, or as the raw
        // file the app keeps (all 64 columns of RideTsv.COLUMNS, what the other two only partly carry)
        String text;
        String mime;
        String name;
        switch (kind) {
            case "csv":
                text = RideExport.csv(ride, samples, "", "");
                mime = "text/csv";
                name = RideExport.fileName(ride, "csv");
                break;
            case "full":
                text = RideExport.fullCsv(ride, samples);
                mime = "text/csv";
                name = RideExport.fileName(ride, "full", "csv");
                break;
            case "tsv":
                text = RideExport.rawTsv(samples);
                mime = "text/tab-separated-values";
                name = RideExport.fileName(ride, "kawanav", "tsv");
                break;
            default:
                text = RideExport.gpx(ride, samples);
                mime = "application/gpx+xml";
                name = RideExport.fileName(ride, "gpx");
                break;
        }
        byte[] bytes = kind.equals("csv") ? RideExport.csvBytes(text)
                : text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Uri uri = writeToDownloads(name, mime, bytes);
        if (uri == null) {
            android.widget.Toast.makeText(this, Lang.t(R.string.ride_export_failed), android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        Logger.log("Ride: exported " + name + " (" + bytes.length + " bytes)");
        Intent share = new Intent(Intent.ACTION_SEND).setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(share, Lang.t(R.string.planner_share, name)));
        } catch (android.content.ActivityNotFoundException e) {
            android.widget.Toast.makeText(this, Lang.t(R.string.planner_gpx_saved, name),
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
