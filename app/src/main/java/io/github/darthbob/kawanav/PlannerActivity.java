package io.github.darthbob.kawanav;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Plans a route on the downloaded regions, on a map that fills the screen with a panel under it: from
 * here (or a chosen start) to a destination through via points, or a round trip of a chosen length,
 * direction and shape. Points are set by tapping the map or searching (favourites and recent places
 * first) and dragged on the map to move them. Every change plans the route again, for the three route
 * kinds side by side: fastest, curvy and extra curvy, with motorways, tolls and gravel avoided on
 * request. The route can be ridden with turn-by-turn navigation ({@link NavService}), kept for later
 * ({@link SavedRoutes}) or saved as GPX.
 */
public class PlannerActivity extends KawaActivity {

    private static final String KEY_MODE = "planner_mode";
    private static final String KEY_RT_KM = "planner_rt_km";
    private static final String KEY_RT_SHAPE = "planner_rt_shape";
    private static final String[] SHAPES = {"Any shape", "Triangle", "Square", "Pentagon", "Circle"};
    private static final String[] PROFILES = {"Fastest", "Curvy", "Extra curvy"};
    private static final int RT_MIN_KM = 20, RT_MAX_KM = 400, RT_STEP_KM = 10;
    /** Changes come in bursts (a drag, the length bar): the route is planned once they stop. */
    private static final long PLAN_DELAY_MS = 250;
    private static final String MY_POSITION = "My position";
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "planner"));
    /** Each change starts a new generation: routes still being planned for an older one stop and are dropped. */
    private final AtomicInteger generation = new AtomicInteger();
    /** The generation the routes are being planned for (-1: none). */
    private int planning = -1;
    private final Waypoints w = Hub.planner;
    /** The route of each kind for the points as they are now, or why there is none. */
    private final Route[] routes = new Route[3];
    private final String[] errors = new String[3];
    private RouteMapView map;
    private RouteGraph graph;
    private Route route;
    /** The next route found is fitted into the view (not after a tap or a drag: the rider is looking there). */
    private boolean fitNext = true;
    private long fittedAt;
    /** A position asked for since the planner opened (the phone's last known one can be hours old). */
    private Location fresh;
    /** What each point on the map is: {kind, index in its list}. */
    private final List<int[]> slots = new ArrayList<>();

    private LinearLayout topBox, bar, sheet, pointList, abBox, rtBox;
    private Button modeAb, modeRt, startButton, shapeButton;
    private final TextView[] tiles = new TextView[3];
    private TextView status, optionsText, kmText, dirText;
    private View dirClear;
    private SeekBar kmBar;
    private boolean kmTracking;

    private final Runnable plan = this::plan;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        Logger.init(this);
        prefs = Hub.prefs(this);
        night = isNight();
        if (w.seed == 0) w.seed = System.currentTimeMillis() & 0xFFFF;

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Ui.bg(night));
        map = new RouteMapView(this);
        map.setEditable(true);
        map.setListener(new RouteMapView.Listener() {
            @Override
            public void onTap(double lat, double lon) {
                tapped(lat, lon);
            }

            @Override
            public void onLongPress(double lat, double lon) {
                pressed(lat, lon);
            }

            @Override
            public void onMarkerMoved(int index, double lat, double lon) {
                moved(index, lat, lon);
            }

            @Override
            public void onMarkerTap(int index) {
                markerMenu(index);
            }
        });
        frame.addView(map, new FrameLayout.LayoutParams(MATCH, MATCH));

        // the bar and the position button float over the map
        topBox = column();
        bar = headerBar("Route planner", v -> finish(), headerAction(R.drawable.ic_list, "Saved routes", v -> savedRoutes()));
        bar.setBackground(rounded(Ui.surface(night), dp(Ui.RADIUS_GROUP)));
        bar.setElevation(dp(2));
        topBox.addView(bar, new LinearLayout.LayoutParams(MATCH, WRAP));
        ImageView locate = headerAction(R.drawable.ic_pin, MY_POSITION, v -> toMyPosition());
        GradientDrawable oval = new GradientDrawable();
        oval.setShape(GradientDrawable.OVAL);
        oval.setColor(Ui.surface(night));
        locate.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.rippleOnPlain(night)), oval, null));
        locate.setElevation(dp(2));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(Ui.TOUCH_MIN), dp(Ui.TOUCH_MIN));
        llp.gravity = Gravity.END;
        llp.topMargin = dp(Ui.SPACE2);
        topBox.addView(locate, llp);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP);
        tlp.setMargins(dp(Ui.SPACE3), dp(Ui.SPACE3), dp(Ui.SPACE3), 0);
        frame.addView(topBox, tlp);

        sheet = column();
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(Ui.surface(night));
        float r = dp(16);
        sbg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(sbg);
        sheet.setElevation(dp(8));
        sheet.setClickable(true); // touches on the panel stay off the map
        int maxSheet = (int) (getResources().getDisplayMetrics().heightPixels * 0.55f);
        ScrollView scroll = new ScrollView(this) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxSheet, MeasureSpec.AT_MOST));
            }
        };
        LinearLayout content = column();
        content.setPadding(dp(Ui.SPACE4), dp(Ui.SPACE3), dp(Ui.SPACE4), dp(Ui.SPACE3));
        scroll.addView(content);
        sheet.addView(scroll, new LinearLayout.LayoutParams(MATCH, WRAP));
        frame.addView(sheet, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM));
        buildPanel(content);

        frame.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                    | android.view.WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return android.view.WindowInsets.CONSUMED;
        });
        View.OnLayoutChangeListener cover = (v, a, b, c, d, e, f, g, h) -> main.post(this::cover);
        bar.addOnLayoutChangeListener(cover);
        sheet.addOnLayoutChangeListener(cover);
        setContentView(frame);

        showOptions();
        setMode(prefs.getInt(KEY_MODE, 0) == 1);
        Location here = lastLocation();
        if (here != null) map.center(here.getLatitude(), here.getLongitude(), 11);
        askPosition(here == null);
        worker.execute(() -> {
            try {
                RouteGraph g = RoutingData.graph(this);
                double[] middle = middle(g);
                main.post(() -> {
                    graph = g;
                    // no position and nothing planned yet: show the downloaded roads
                    if (middle != null && here() == null && w.start == null && w.dest == null) map.center(middle[0], middle[1], 10);
                    fitNext = true;
                    schedule();
                });
            } catch (Exception e) {
                main.post(() -> status.setText("Routing data unreadable: download the region again (" + e.getMessage() + ")"));
            }
        });
    }

    @Override
    protected void onDestroy() {
        generation.incrementAndGet(); // stops a route being planned
        main.removeCallbacks(plan);
        worker.shutdownNow();
        super.onDestroy();
    }

    /** The middle of the downloaded roads (from a sample of their junctions), or null without any. */
    private static double[] middle(RouteGraph g) {
        int n = g.nodeCount();
        if (n == 0) return null;
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        for (int i = 0; i < n; i += Math.max(1, n / 2000)) {
            minLat = Math.min(minLat, g.lat(i));
            maxLat = Math.max(maxLat, g.lat(i));
            minLon = Math.min(minLon, g.lon(i));
            maxLon = Math.max(maxLon, g.lon(i));
        }
        return new double[]{(minLat + maxLat) / 2, (minLon + maxLon) / 2};
    }

    private static String noDataText() {
        return "No routing data yet: download a region under Settings, Offline maps. Regions downloaded with an older "
                + "KawaNav have to be downloaded once more.";
    }

    // ------------------------------------------------------------------ the panel

    private void buildPanel(LinearLayout content) {
        LinearLayout modes = row();
        modeAb = small(button("A to B", v -> setMode(false)));
        modeRt = small(button("Round trip", v -> setMode(true)));
        LinearLayout.LayoutParams half = weight();
        half.rightMargin = dp(Ui.SPACE2);
        modes.addView(modeAb, half);
        modes.addView(modeRt, weight());
        content.addView(modes);

        pointList = column();
        pointList.setPadding(0, dp(Ui.SPACE2), 0, 0);
        content.addView(pointList);

        abBox = row();
        abBox.addView(chip("Via point", R.drawable.ic_pin, v -> {
            if (w.dest == null) pickFor(RouteMapView.DEST, 0);
            else pickFor(RouteMapView.VIA, -1);
        }), chipGap());
        abBox.addView(chip("Reverse", R.drawable.ic_swap, v -> reverse()), chipGap());
        content.addView(abBox);

        rtBox = column();
        LinearLayout lengthRow = row();
        lengthRow.addView(itemTitle("Length"), weight());
        kmText = value("");
        lengthRow.addView(kmText);
        rtBox.addView(lengthRow);
        kmBar = new SeekBar(this);
        kmBar.setMax((RT_MAX_KM - RT_MIN_KM) / RT_STEP_KM);
        kmBar.setProgress((rtKm() - RT_MIN_KM) / RT_STEP_KM);
        kmBar.setContentDescription("Length of the round trip");
        kmBar.setProgressTintList(ColorStateList.valueOf(Ui.accent(night)));
        kmBar.setThumbTintList(ColorStateList.valueOf(Ui.accent(night)));
        kmText.setText(kmLabel(rtKm()));
        kmBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                kmText.setText(kmLabel(RT_MIN_KM + p * RT_STEP_KM));
                if (fromUser && !kmTracking) lengthChosen(); // keys, accessibility
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                kmTracking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                kmTracking = false;
                lengthChosen();
            }
        });
        rtBox.addView(kmBar, new LinearLayout.LayoutParams(MATCH, dp(Ui.TOUCH_MIN)));
        LinearLayout dirRow = row();
        dirRow.setMinimumHeight(dp(40));
        dirText = secondary("");
        dirRow.addView(dirText, weight());
        dirClear = cross("Any direction", v -> {
            w.towards = null;
            w.newLoop();
            changed();
        });
        dirRow.addView(dirClear);
        rtBox.addView(dirRow);
        LinearLayout rtChips = row();
        shapeButton = chip("", R.drawable.ic_route, v -> chooseShape());
        shapeButton.setText(SHAPES[rtShape()]);
        rtChips.addView(shapeButton, chipGap());
        rtChips.addView(chip("Another one", R.drawable.ic_refresh, v -> {
            w.seed++;
            w.newLoop();
            fitNext = true;
            changed();
        }), chipGap());
        rtBox.addView(rtChips);
        content.addView(rtBox);

        // the three route kinds side by side: tap one to ride it
        LinearLayout kinds = row();
        kinds.setPadding(0, dp(Ui.SPACE3), 0, 0);
        for (int k = 0; k < 3; k++) {
            int kind = k;
            TextView t = text(13, false);
            t.setGravity(Gravity.CENTER);
            t.setMinHeight(dp(64));
            t.setPadding(dp(Ui.SPACE1), dp(Ui.SPACE2), dp(Ui.SPACE1), dp(Ui.SPACE2));
            t.setOnClickListener(v -> selectKind(kind));
            tiles[k] = t;
            LinearLayout.LayoutParams lp = weight();
            if (k < 2) lp.rightMargin = dp(Ui.SPACE2);
            kinds.addView(t, lp);
        }
        content.addView(kinds);
        content.addView(legend());

        LinearLayout options = row();
        options.setMinimumHeight(dp(Ui.TOUCH_MIN));
        options.addView(icon(R.drawable.ic_sliders, true), iconGap());
        LinearLayout ot = column();
        ot.addView(itemTitle("Options"));
        optionsText = secondary("");
        ot.addView(optionsText);
        options.addView(ot, weight());
        options.addView(chevron());
        options.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.rippleOnPlain(night)), null, null));
        options.setOnClickListener(v -> options());
        content.addView(options);

        status = secondary("");
        status.setPadding(dp(Ui.SPACE1), 0, dp(Ui.SPACE1), dp(Ui.SPACE2));
        content.addView(status);

        LinearLayout actions = row();
        startButton = primaryButton("Start navigation", v -> startNavigation());
        actions.addView(startButton, weight());
        actions.addView(square(R.drawable.ic_star, "Save the route", v -> saveRoute()), squareGap());
        actions.addView(square(R.drawable.ic_share, "Save as GPX", v -> saveGpx()), squareGap());
        content.addView(actions);
    }

    private Button small(Button b) {
        b.setMinHeight(dp(40));
        b.setMinimumHeight(dp(40));
        b.setTextSize(14);
        return b;
    }

    /** A quiet small button with an icon: "Via point", "Reverse", "Another one". */
    private Button chip(String label, int drawable, View.OnClickListener l) {
        Button b = small(button(label, l));
        quiet(b);
        b.setPadding(dp(Ui.SPACE3), 0, dp(Ui.SPACE3), 0);
        return iconButton(b, drawable);
    }

    private LinearLayout.LayoutParams chipGap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.rightMargin = dp(Ui.SPACE2);
        lp.topMargin = dp(Ui.SPACE1);
        return lp;
    }

    /** An outlined icon button beside the main action. */
    private ImageView square(int drawable, String description, View.OnClickListener l) {
        ImageView v = headerAction(drawable, description, l);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x00000000);
        bg.setStroke(Math.max(1, dp(1)), Ui.outline(night));
        bg.setCornerRadius(dp(Ui.RADIUS_BUTTON));
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.rippleOnPlain(night)), bg, null));
        return v;
    }

    private LinearLayout.LayoutParams squareGap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(Ui.TOUCH_PRIMARY), dp(Ui.TOUCH_PRIMARY));
        lp.leftMargin = dp(Ui.SPACE2);
        return lp;
    }

    /** A ✕ that takes a point away. */
    private TextView cross(String description, View.OnClickListener l) {
        TextView x = text(18, false);
        x.setText("✕");
        x.setTextColor(Ui.inkSecondary(night));
        x.setGravity(Gravity.CENTER);
        x.setContentDescription(description);
        x.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.rippleOnPlain(night)), null, null));
        x.setOnClickListener(l);
        x.setLayoutParams(new LinearLayout.LayoutParams(dp(Ui.TOUCH_MIN), dp(40)));
        return x;
    }

    private static GradientDrawable rounded(int colour, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(colour);
        g.setCornerRadius(radius);
        return g;
    }

    /** The colours of the route line. */
    private View legend() {
        LinearLayout r = row();
        r.setPadding(0, dp(Ui.SPACE2), 0, dp(Ui.SPACE1));
        String[] names = {"straight", "sweeping", "winding", "very winding"};
        for (int i = 0; i < 4; i++) {
            View sw = new View(this);
            sw.setBackgroundColor(RouteMapView.LEVEL_COLOURS[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(14), dp(4));
            lp.rightMargin = dp(4);
            r.addView(sw, lp);
            TextView t = note(names[i]);
            t.setPadding(0, 0, dp(Ui.SPACE3), 0);
            r.addView(t);
        }
        return r;
    }

    /** The map's padding: what the bar and the panel cover, so the route is fitted into the rest. */
    private void cover() {
        if (isFinishing()) return;
        int t = Math.max(0, topBox.getTop() + bar.getBottom() - map.getTop());
        int b = Math.max(0, map.getBottom() - sheet.getTop());
        if (t == map.getPaddingTop() && b == map.getPaddingBottom()) return;
        map.setPadding(0, t, 0, b);
        // a route fitted a moment ago is fitted again into what the panel leaves free
        if (route != null && System.currentTimeMillis() - fittedAt < 1500) map.fitRoute();
    }

    private void setMode(boolean roundTrip) {
        if (w.roundTrip != roundTrip) fitNext = true;
        w.roundTrip = roundTrip;
        prefs.edit().putInt(KEY_MODE, roundTrip ? 1 : 0).apply();
        if (roundTrip) {
            primary(modeRt);
            quiet(modeAb);
        } else {
            primary(modeAb);
            quiet(modeRt);
        }
        abBox.setVisibility(roundTrip ? View.GONE : View.VISIBLE);
        rtBox.setVisibility(roundTrip ? View.VISIBLE : View.GONE);
        changed();
    }

    // ------------------------------------------------------------------ points

    /** The points changed: show them, and plan again. */
    private void changed() {
        showPoints();
        schedule();
    }

    /** The points in the panel and on the map. */
    private void showPoints() {
        double[] here = here();
        pointList.removeAllViews();
        slots.clear();
        List<double[]> pts = new ArrayList<>();
        List<Integer> kinds = new ArrayList<>();
        pointList.addView(pointRow(RouteMapView.START, 0, w.start != null ? w.start.name : MY_POSITION, false,
                v -> pickFor(RouteMapView.START, 0), w.start == null ? null : v -> {
                    w.start = null;
                    if (w.roundTrip && !w.cornersEdited) w.newLoop();
                    changed();
                }));
        double[] s = w.start != null ? w.start.latLon() : here;
        if (s != null) mark(pts, kinds, s, RouteMapView.START, 0);
        if (!w.roundTrip) {
            for (int i = 0; i < w.vias.size(); i++) {
                int idx = i;
                Waypoints.Point v = w.vias.get(i);
                pointList.addView(pointRow(RouteMapView.VIA, i + 1, v.name, false, x -> pickFor(RouteMapView.VIA, idx), x -> {
                    w.vias.remove(idx);
                    changed();
                }));
                mark(pts, kinds, v.latLon(), RouteMapView.VIA, i);
            }
            pointList.addView(pointRow(RouteMapView.DEST, 0, w.dest != null ? w.dest.name : "Destination: tap the map or search",
                    w.dest == null, v -> pickFor(RouteMapView.DEST, 0), w.dest == null ? null : v -> {
                        w.removeDest();
                        changed();
                    }));
            if (w.dest != null) mark(pts, kinds, w.dest.latLon(), RouteMapView.DEST, 0);
            map.setDirection(null, null);
        } else {
            for (int i = 0; i < w.corners.size(); i++) mark(pts, kinds, w.corners.get(i).latLon(), RouteMapView.CORNER, i);
            dirText.setText(w.towards == null ? "Any direction: tap the map to point the way"
                    : "Heading towards " + w.towards.name);
            dirClear.setVisibility(w.towards == null ? View.GONE : View.VISIBLE);
            map.setDirection(w.towards != null ? s : null, w.towards != null ? w.towards.latLon() : null);
        }
        int[] k = new int[kinds.size()];
        for (int i = 0; i < k.length; i++) k[i] = kinds.get(i);
        map.setMarkers(pts, k);
        // the rider's position as a blue dot, unless a point is there already (the dot would hide it)
        boolean shown = here != null;
        for (double[] p : pts) if (shown && Ride.distanceM(p[0], p[1], here[0], here[1]) < 60) shown = false;
        if (shown) map.setPosition(here[0], here[1], Double.NaN);
        else map.setPosition(Double.NaN, Double.NaN, Double.NaN);
    }

    private void mark(List<double[]> pts, List<Integer> kinds, double[] p, int kind, int index) {
        pts.add(p);
        kinds.add(kind);
        slots.add(new int[]{kind, index});
    }

    /** A point in the panel: its badge as on the map, its name (tap: choose another place) and a ✕. */
    private View pointRow(int kind, int number, String name, boolean missing, View.OnClickListener choose,
                          View.OnClickListener remove) {
        LinearLayout r = row();
        r.setMinimumHeight(dp(44));
        TextView badge = text(11, true);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(kind == RouteMapView.START ? Ui.recordIdle() : kind == RouteMapView.DEST ? 0xFF171717 : 0xFFFFFFFF);
        dot.setStroke(Math.max(1, dp(2)), kind == RouteMapView.VIA ? 0xFF171717 : 0xFFFFFFFF);
        badge.setBackground(dot);
        badge.setTextColor(0xFF171717);
        if (kind == RouteMapView.VIA) badge.setText(String.valueOf(number));
        badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(22), dp(22));
        blp.rightMargin = dp(Ui.SPACE3);
        r.addView(badge, blp);
        TextView t = itemTitle(name);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (missing) t.setTextColor(Ui.inkMuted(night));
        t.setContentDescription((kind == RouteMapView.START ? "Start: " : kind == RouteMapView.DEST ? "Destination: "
                : "Via point " + number + ": ") + name + ". Tap to choose another place.");
        r.addView(t, weight());
        if (remove != null) r.addView(cross("Remove", remove));
        r.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.rippleOnPlain(night)), null, null));
        r.setOnClickListener(choose);
        return r;
    }

    private void tapped(double lat, double lon) {
        if (w.roundTrip) {
            w.towards = point(lat, lon);
            w.newLoop();
            fitNext = true;
            changed();
        } else if (w.dest == null) {
            w.dest = point(lat, lon);
            fitNext = true;
            changed();
        } else {
            new AlertDialog.Builder(this)
                    .setItems(new String[]{"Via point here", "Destination here", "Start here"}, (d, which) -> {
                        Waypoints.Point p = point(lat, lon);
                        if (which == 0) w.insertVia(p, here());
                        else if (which == 1) w.dest = p;
                        else w.start = p;
                        changed();
                    })
                    .show();
        }
    }

    private void pressed(double lat, double lon) {
        Waypoints.Point p = point(lat, lon);
        if (w.roundTrip) {
            w.start = p;
            w.newLoop();
        } else {
            w.dest = p;
        }
        changed();
    }

    /** A point was dragged on the map. */
    private void moved(int index, double lat, double lon) {
        if (index >= slots.size()) return;
        int[] s = slots.get(index);
        Waypoints.Point p = point(lat, lon);
        switch (s[0]) {
            case RouteMapView.START:
                w.start = p;
                if (w.roundTrip && !w.cornersEdited) w.newLoop();
                break;
            case RouteMapView.VIA:
                w.vias.set(s[1], p);
                break;
            case RouteMapView.DEST:
                w.dest = p;
                break;
            default:
                w.corners.set(s[1], p);
                w.cornersEdited = true;
                break;
        }
        changed();
    }

    private void markerMenu(int index) {
        if (index >= slots.size()) return;
        int[] s = slots.get(index);
        List<String> items = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        Waypoints.Point p;
        switch (s[0]) {
            case RouteMapView.START:
                p = w.start;
                if (p != null) {
                    items.add("Use my position");
                    acts.add(() -> {
                        w.start = null;
                        if (w.roundTrip && !w.cornersEdited) w.newLoop();
                        changed();
                    });
                }
                break;
            case RouteMapView.VIA:
                p = w.vias.get(s[1]);
                items.add("Remove");
                acts.add(() -> {
                    w.vias.remove(s[1]);
                    changed();
                });
                break;
            case RouteMapView.DEST:
                p = w.dest;
                items.add("Remove");
                acts.add(() -> {
                    w.removeDest();
                    changed();
                });
                break;
            default:
                p = w.corners.get(s[1]);
                if (w.corners.size() > 1) {
                    items.add("Remove this corner");
                    acts.add(() -> {
                        w.corners.remove(s[1]);
                        w.cornersEdited = true;
                        changed();
                    });
                }
                break;
        }
        double[] here = here();
        Waypoints.Point fav = p != null ? p : here == null ? null : new Waypoints.Point(here[0], here[1], roadName(here[0], here[1]));
        if (fav != null) {
            items.add("Save as favourite");
            acts.add(() -> askFavourite(fav));
        }
        if (items.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle(p != null ? p.name : MY_POSITION)
                .setItems(items.toArray(new String[0]), (d, which) -> acts.get(which).run())
                .show();
    }

    private void reverse() {
        double[] h = here();
        if (!w.reverse(h, h == null ? MY_POSITION : roadName(h[0], h[1]))) {
            status.setText(w.dest == null ? "Choose a destination first." : "Waiting for GPS…");
            return;
        }
        fitNext = true;
        changed();
    }

    /** A place on the map: named after its road at once, and after its address when the phone is online. */
    private Waypoints.Point point(double lat, double lon) {
        Waypoints.Point p = new Waypoints.Point(lat, lon, roadName(lat, lon));
        if (Geocoder.isPresent()) {
            new Geocoder(this, Locale.getDefault()).getFromLocation(lat, lon, 1, new Geocoder.GeocodeListener() {
                @Override
                public void onGeocode(List<Address> list) {
                    if (list == null || list.isEmpty()) return;
                    String name = shortName(list.get(0));
                    if (name == null) return;
                    main.post(() -> {
                        if (isFinishing()) return;
                        p.name = name;
                        p.named = true;
                        showPoints();
                    });
                }

                @Override
                public void onError(String message) {
                    // offline: the road's name stays
                }
            });
        }
        return p;
    }

    private static Waypoints.Point point(Places.Place place) {
        Waypoints.Point p = new Waypoints.Point(place.lat, place.lon, place.name);
        p.named = true;
        return p;
    }

    /**
     * The name (and number) of the nearest named road of the downloaded regions ("near" it when that is
     * some way off), else the coordinates.
     */
    private String roadName(double lat, double lon) {
        RouteGraph g = graph;
        if (g != null && !g.isEmpty()) {
            RouteGraph.Snap s = g.nearest(lat, lon, 2000, (e, f) -> !g.name(e).isEmpty() || !g.ref(e).isEmpty());
            if (s != null) {
                String name = g.name(s.edge), ref = g.ref(s.edge);
                String road = name.isEmpty() ? ref : ref.isEmpty() || name.contains(ref) ? name : name + " (" + ref + ")";
                return s.distance > 300 ? "Near " + road : road;
            }
        }
        return String.format(Locale.ROOT, "%.4f, %.4f", lat, lon);
    }

    /** "Street, Town" of an address, or its first line. */
    private static String shortName(Address a) {
        String street = a.getThoroughfare(), town = a.getLocality();
        if (street != null && town != null) return street + ", " + town;
        if (street != null) return street;
        if (a.getFeatureName() != null && town != null && !a.getFeatureName().equals(town)) return a.getFeatureName() + ", " + town;
        if (town != null) return town;
        return a.getMaxAddressLineIndex() >= 0 ? a.getAddressLine(0) : null;
    }

    private double[] here() {
        Location l = lastLocation();
        return l == null ? null : new double[]{l.getLatitude(), l.getLongitude()};
    }

    /** The rider's position: one asked for since the planner opened, else a recent one; null when there is none. */
    private Location lastLocation() {
        if (fresh != null) return fresh;
        Location fix = Hub.lastFix;
        if (recent(fix)) return fix;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null;
        try {
            Location l = getSystemService(LocationManager.class).getLastKnownLocation(LocationManager.GPS_PROVIDER);
            return recent(l) ? l : null;
        } catch (SecurityException e) {
            return null;
        }
    }

    /** A position from the last few minutes: an older one may be far from where the rider is now. */
    private static boolean recent(Location l) {
        return l != null && System.currentTimeMillis() - l.getTime() < 5 * 60_000;
    }

    /** Asks the GPS for the current position; the map moves to it when {@code center}. */
    private void askPosition(boolean center) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        try {
            getSystemService(LocationManager.class).getCurrentLocation(LocationManager.GPS_PROVIDER, null,
                    getMainExecutor(), l -> {
                        if (isFinishing() || l == null) return;
                        fresh = l;
                        if (center) map.center(l.getLatitude(), l.getLongitude(), 13);
                        // the start is the rider's position: plan from there if nothing is planned yet
                        if (w.start == null && route == null) changed();
                        else showPoints();
                    });
        } catch (SecurityException | IllegalArgumentException e) {
            Logger.log("Planner: no position: " + e);
        }
    }

    private void toMyPosition() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestMissingPermissions();
            return;
        }
        double[] h = here();
        if (h != null) map.center(h[0], h[1], 13);
        else status.setText("Waiting for GPS… You can also long-press the map for a start.");
        askPosition(true);
    }

    // ------------------------------------------------------------------ search, favourites, recent places

    /** Chooses a place for the start, a via point ({@code index}; -1: a new one) or the destination. */
    private void pickFor(int kind, int index) {
        String title = kind == RouteMapView.START ? (w.roundTrip ? "Round trip start" : "Start")
                : kind == RouteMapView.DEST ? "Destination" : index < 0 ? "New via point" : "Via point " + (index + 1);
        pick(title, kind == RouteMapView.START, place -> {
            Waypoints.Point p = place == null ? null : point(place);
            if (kind == RouteMapView.START) {
                w.start = p;
                if (w.roundTrip) w.newLoop();
            } else if (kind == RouteMapView.DEST) {
                w.dest = p;
            } else if (index < 0) {
                w.insertVia(p, here());
            } else {
                w.vias.set(index, p);
            }
            fitNext = true;
            changed();
        });
    }

    /**
     * The search: favourites and recent places until something is searched, then what the phone's place
     * search finds, the nearest first. {@code chosen} gets null for "My position".
     */
    private void pick(String title, boolean offerMyPosition, Consumer<Places.Place> chosen) {
        LinearLayout box = column();
        box.setPadding(dp(Ui.SPACE5), dp(Ui.SPACE2), dp(Ui.SPACE5), 0);
        LinearLayout r = row();
        EditText field = new EditText(this);
        field.setHint("Search a place (online)");
        field.setSingleLine(true);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        field.setTextColor(Ui.ink(night));
        field.setHintTextColor(Ui.inkMuted(night));
        r.addView(field, weight());
        Button go = secondaryButton("Search", null);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(WRAP, WRAP);
        glp.leftMargin = dp(Ui.SPACE2);
        r.addView(go, glp);
        box.addView(r);
        TextView info = note("");
        info.setPadding(0, dp(Ui.SPACE1), 0, dp(Ui.SPACE1));
        box.addView(info);
        ListView list = new ListView(this);
        box.addView(list, new LinearLayout.LayoutParams(MATCH, dp(300)));

        // what the list shows: places (null: my position) and whether each is a favourite or a search result
        List<Places.Place> entries = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        List<Integer> sources = new ArrayList<>(); // 0 my position, 1 favourite, 2 recent, 3 search result
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        list.setAdapter(adapter);
        Runnable known = () -> {
            entries.clear();
            labels.clear();
            sources.clear();
            if (offerMyPosition) {
                entries.add(null);
                labels.add("⌖  " + MY_POSITION);
                sources.add(0);
            }
            for (Places.Place f : Places.favourites(prefs)) {
                entries.add(f);
                labels.add("★  " + f.name);
                sources.add(1);
            }
            for (Places.Place f : Places.recent(prefs)) {
                entries.add(f);
                labels.add("🕘  " + f.name);
                sources.add(2);
            }
            adapter.notifyDataSetChanged();
            info.setText(sources.contains(1) || sources.contains(2) ? "Long-press a favourite to rename or remove it."
                    : "Your favourite places and recent destinations show here.");
        };
        known.run();
        AlertDialog d = new AlertDialog.Builder(this).setTitle(title).setView(box).setNegativeButton("Cancel", null).create();
        list.setOnItemClickListener((parent, v, pos, id) -> {
            Places.Place p = entries.get(pos);
            if (p != null && sources.get(pos) == 3) Places.addRecent(prefs, p);
            d.dismiss();
            chosen.accept(p);
        });
        list.setOnItemLongClickListener((parent, v, pos, id) -> {
            if (sources.get(pos) != 1) return false;
            favouriteMenu(entries.get(pos), known);
            return true;
        });
        Runnable search = () -> {
            String q = field.getText().toString().trim();
            if (q.isEmpty()) {
                known.run();
                return;
            }
            if (!Geocoder.isPresent()) {
                info.setText("This phone has no place search: tap the map instead.");
                return;
            }
            info.setText("Searching…");
            new Geocoder(this, Locale.getDefault()).getFromLocationName(q, 8, new Geocoder.GeocodeListener() {
                @Override
                public void onGeocode(List<Address> found) {
                    main.post(() -> {
                        if (!d.isShowing()) return;
                        List<Places.Place> l = places(found);
                        if (l.isEmpty()) {
                            info.setText("Nothing found. Tap the map instead.");
                            return;
                        }
                        entries.clear();
                        labels.clear();
                        sources.clear();
                        for (Places.Place p : l) {
                            entries.add(p);
                            labels.add(p.name);
                            sources.add(3);
                        }
                        adapter.notifyDataSetChanged();
                        info.setText("");
                    });
                }

                @Override
                public void onError(String message) {
                    Logger.log("Planner: place search failed: " + message);
                    main.post(() -> info.setText("Search failed (" + message + "): tap the map instead."));
                }
            });
        };
        go.setOnClickListener(v -> search.run());
        field.setOnEditorActionListener((v, id, e) -> {
            search.run();
            return true;
        });
        d.show();
    }

    /** Places found by the search, the nearest first: to the rider, or to what the map shows. */
    private List<Places.Place> places(List<Address> found) {
        List<Places.Place> l = new ArrayList<>();
        if (found == null) return l;
        for (Address a : found) {
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k <= a.getMaxAddressLineIndex(); k++) {
                if (k > 0) sb.append(", ");
                sb.append(a.getAddressLine(k));
            }
            l.add(new Places.Place(sb.length() > 0 ? sb.toString() : a.getFeatureName(), a.getLatitude(), a.getLongitude()));
        }
        double[] ref = here() != null ? here() : map.centre();
        l.sort((a, b) -> Double.compare(Ride.distanceM(ref[0], ref[1], a.lat, a.lon),
                Ride.distanceM(ref[0], ref[1], b.lat, b.lon)));
        return l;
    }

    private void favouriteMenu(Places.Place f, Runnable after) {
        new AlertDialog.Builder(this)
                .setTitle(f.name)
                .setItems(new String[]{"Rename", "Remove from favourites"}, (d, which) -> {
                    if (which == 0) {
                        askName("Rename", f.name, name -> {
                            replaceFavourite(f, new Places.Place(name, f.lat, f.lon));
                            after.run();
                        });
                    } else {
                        replaceFavourite(f, null);
                        after.run();
                    }
                })
                .show();
    }

    /** Puts {@code by} in the place of {@code f} among the favourites (null: removes it). */
    private void replaceFavourite(Places.Place f, Places.Place by) {
        List<Places.Place> l = new ArrayList<>();
        for (Places.Place p : Places.favourites(prefs)) {
            if (p.lat == f.lat && p.lon == f.lon && p.name.equals(f.name)) {
                if (by != null) l.add(by);
            } else {
                l.add(p);
            }
        }
        Places.saveFavourites(prefs, l);
    }

    private void askFavourite(Waypoints.Point p) {
        askName("Save as favourite", p.name, name -> {
            Places.addFavourite(prefs, new Places.Place(name, p.lat, p.lon));
            status.setText("“" + name + "” is now a favourite: it comes first in the search.");
        });
    }

    private void askName(String title, String before, Consumer<String> ok) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setText(before);
        field.setSelectAllOnFocus(true);
        field.setTextColor(Ui.ink(night));
        LinearLayout box = column();
        box.setPadding(dp(Ui.SPACE5), dp(Ui.SPACE2), dp(Ui.SPACE5), 0);
        box.addView(field);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("Save", (d, which) -> {
                    String name = field.getText().toString().trim();
                    ok.accept(name.isEmpty() ? before : name);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------ round trip and options

    private int rtKm() {
        int km = prefs.getInt(KEY_RT_KM, 100);
        return Math.max(RT_MIN_KM, Math.min(RT_MAX_KM, Math.round(km / (float) RT_STEP_KM) * RT_STEP_KM));
    }

    private int rtShape() {
        return Math.max(0, Math.min(SHAPES.length - 1, prefs.getInt(KEY_RT_SHAPE, 0)));
    }

    private String kmLabel(int km) {
        return prefs.getBoolean(Hub.KEY_IMPERIAL, false) ? String.format(Locale.ROOT, "%.0f mi", km / 1.609344) : km + " km";
    }

    private void lengthChosen() {
        int km = RT_MIN_KM + kmBar.getProgress() * RT_STEP_KM;
        if (km == prefs.getInt(KEY_RT_KM, -1)) return;
        prefs.edit().putInt(KEY_RT_KM, km).apply();
        w.newLoop();
        fitNext = true;
        changed();
    }

    private void chooseShape() {
        new AlertDialog.Builder(this)
                .setTitle("Shape of the round trip")
                .setSingleChoiceItems(SHAPES, rtShape(), (d, which) -> {
                    d.dismiss();
                    if (which == rtShape()) return;
                    prefs.edit().putInt(KEY_RT_SHAPE, which).apply();
                    shapeButton.setText(SHAPES[which]);
                    w.newLoop();
                    fitNext = true;
                    changed();
                })
                .show();
    }

    private void options() {
        LinearLayout box = column();
        box.setPadding(dp(Ui.SPACE5), dp(Ui.SPACE2), dp(Ui.SPACE5), 0);
        addRow(box, switchRow("Avoid motorways", "Only when there is no other way", Hub.KEY_NAV_AVOID_MOTORWAYS, false,
                on -> optionsChanged()));
        addRow(box, switchRow("Avoid tolls", "Toll roads only when there is no other way", Hub.KEY_NAV_AVOID_TOLLS, false,
                on -> optionsChanged()));
        addRow(box, switchRow("Avoid gravel", "Unpaved roads only when there is no other way", Hub.KEY_NAV_AVOID_UNPAVED, true,
                on -> optionsChanged()));
        TextView n = note("Curvy prefers winding roads and avoids towns; extra curvy takes long detours for bends. "
                + "Routes are computed on the phone from the downloaded OpenStreetMap regions (Settings, Offline maps).");
        n.setPadding(0, dp(Ui.SPACE2), 0, dp(Ui.SPACE2));
        box.addView(n);
        new AlertDialog.Builder(this).setTitle("Route options").setView(box).setPositiveButton("Done", null).show();
    }

    private void optionsChanged() {
        showOptions();
        if (w.roundTrip && !w.cornersEdited) w.newLoop();
        schedule();
    }

    private void showOptions() {
        List<String> avoided = new ArrayList<>();
        if (prefs.getBoolean(Hub.KEY_NAV_AVOID_MOTORWAYS, false)) avoided.add("motorways");
        if (prefs.getBoolean(Hub.KEY_NAV_AVOID_TOLLS, false)) avoided.add("tolls");
        if (prefs.getBoolean(Hub.KEY_NAV_AVOID_UNPAVED, true)) avoided.add("gravel");
        String s;
        if (avoided.isEmpty()) s = "Avoiding nothing";
        else if (avoided.size() == 1) s = "Avoiding " + avoided.get(0);
        else s = "Avoiding " + String.join(", ", avoided.subList(0, avoided.size() - 1)) + " and " + avoided.get(avoided.size() - 1);
        optionsText.setText(s);
    }

    // ------------------------------------------------------------------ planning

    private int kind() {
        return Math.max(0, Math.min(2, prefs.getInt(Hub.KEY_NAV_PROFILE, RouteProfile.Kind.CURVY.ordinal())));
    }

    /** Something changed: plan again once the changes stop (a route still being planned is stopped). */
    private void schedule() {
        generation.incrementAndGet();
        Arrays.fill(routes, null);
        Arrays.fill(errors, null);
        startButton.setEnabled(false);
        startButton.setAlpha(0.5f);
        showTiles();
        main.removeCallbacks(plan);
        main.postDelayed(plan, PLAN_DELAY_MS);
    }

    private void plan() {
        RouteGraph g = graph;
        if (g == null) {
            status.setText("Opening the routing data…");
            return;
        }
        if (g.isEmpty()) {
            status.setText(noDataText());
            return;
        }
        double[] here = here();
        RouteProfile chosen = Hub.routeProfile(this);
        boolean leftHand = prefs.getBoolean(Hub.KEY_LEFT_HAND, false);
        int gen = generation.get();
        Router.Cancel cancel = () -> generation.get() != gen;
        if (!w.roundTrip) {
            List<double[]> pts = w.forRouter(here);
            if (pts == null) {
                show(null);
                status.setText(w.start == null && here == null ? "Waiting for GPS… or tap Start to choose one."
                        : "Tap the map or search for a destination.");
                return;
            }
            double[] a = pts.get(0), b = pts.get(1);
            if (pts.size() == 2 && Ride.distanceM(a[0], a[1], b[0], b[1]) < 50) {
                show(null);
                status.setText("The start and the destination are the same place.");
                return;
            }
            planning = gen;
            status.setText("Planning the route…");
            int first = chosen.kind.ordinal();
            worker.execute(() -> {
                // the chosen kind first, then the other two to compare
                for (int n = 0; n < 3; n++) {
                    int k = (first + n) % 3;
                    RouteProfile p = new RouteProfile(RouteProfile.Kind.values()[k], chosen.avoidMotorways,
                            chosen.avoidTolls, chosen.avoidUnpaved);
                    long t0 = System.currentTimeMillis();
                    Route r = null;
                    String err = null;
                    try {
                        Router.Request req = new Router.Request(pts, p);
                        req.leftHandTraffic = leftHand;
                        req.cancel = cancel;
                        r = new Router(g).route(req);
                    } catch (Router.CancelledException e) {
                        return;
                    } catch (Router.NoRouteException e) {
                        err = e.getMessage();
                    } catch (Exception | OutOfMemoryError e) {
                        err = "failed: " + e;
                    }
                    long ms = System.currentTimeMillis() - t0;
                    Route found = r;
                    String why = err;
                    main.post(() -> planned(gen, k, found, why, ms));
                }
            });
        } else {
            double[] s = w.start != null ? w.start.latLon() : here;
            if (s == null) {
                show(null);
                status.setText("Waiting for GPS… or long-press the map for the start.");
                return;
            }
            List<double[]> loop = w.cornersEdited && !w.corners.isEmpty() ? w.forLoop(here) : null;
            int km = rtKm();
            double heading = w.heading(here);
            RoundTrip.Shape shape = RoundTrip.Shape.values()[rtShape()];
            long seed = w.seed;
            int k = chosen.kind.ordinal();
            planning = gen;
            status.setText("Planning a round trip…");
            worker.execute(() -> {
                long t0 = System.currentTimeMillis();
                Route r = null;
                String err = null;
                try {
                    if (loop != null) {
                        // through the corners the rider moved
                        Router.Request req = new Router.Request(loop, chosen);
                        req.reusePenalty = RoundTrip.REUSE_PENALTY;
                        req.leftHandTraffic = leftHand;
                        req.cancel = cancel;
                        r = new Router(g).route(req);
                        r.roundTrip = true;
                    } else {
                        RoundTrip.Request req = new RoundTrip.Request(s[0], s[1], km, chosen);
                        req.heading = heading;
                        req.shape = shape;
                        req.seed = seed;
                        req.leftHandTraffic = leftHand;
                        req.cancel = cancel;
                        r = RoundTrip.plan(g, req);
                    }
                } catch (Router.CancelledException e) {
                    return;
                } catch (Router.NoRouteException e) {
                    err = e.getMessage();
                } catch (Exception | OutOfMemoryError e) {
                    err = "failed: " + e;
                }
                long ms = System.currentTimeMillis() - t0;
                Route found = r;
                String why = err;
                main.post(() -> planned(gen, k, found, why, ms));
            });
        }
    }

    /** A route of kind {@code k} (or why there is none) for generation {@code gen}. */
    private void planned(int gen, int k, Route r, String err, long ms) {
        if (gen != generation.get() || isFinishing()) return;
        routes[k] = r;
        errors[k] = err;
        if (r != null) {
            Logger.log(String.format(Locale.ROOT, "Planner: %.1f km %s in %d ms", r.length / 1000, r.profile.kind, ms));
        } else {
            Logger.log("Planner: no route (" + err + ")");
        }
        if (w.roundTrip && r != null && !w.cornersEdited) {
            // the corners it went through, for the rider to move
            w.corners.clear();
            for (int i = 1; i + 1 < r.asked.size(); i++) {
                double[] c = r.asked.get(i);
                w.corners.add(new Waypoints.Point(c[0], c[1], roadName(c[0], c[1])));
            }
            showPoints();
        }
        if (k == kind()) showKind(k);
        if (w.roundTrip && r != null && k == kind() && !w.cornersEdited) {
            // the roads around may not allow the length asked for (the downloaded regions end, mountains)
            double asked = rtKm() * 1000.0;
            String km = kmLabel(rtKm());
            if (r.length < asked * (1 - RoundTrip.TOLERANCE)) {
                status.setText("Shorter than the " + km + " asked: not enough roads around for more.");
            } else if (r.length > asked * (1 + RoundTrip.TOLERANCE)) {
                status.setText("Longer than the " + km + " asked: no closer fit on the roads around.");
            }
        }
        showTiles();
    }

    /** The route of kind k on the map, or why there is none. */
    private void showKind(int k) {
        if (routes[k] != null) {
            status.setText("");
            show(routes[k]);
        } else if (errors[k] != null) {
            show(null);
            status.setText("No route: " + errors[k] + ". Points must lie in a downloaded region.");
        }
    }

    private void show(Route r) {
        route = r;
        map.setRoute(r, graph);
        startButton.setEnabled(r != null);
        startButton.setAlpha(r != null ? 1f : 0.5f);
        if (r != null && fitNext) {
            fitNext = false;
            map.fitRoute();
            fittedAt = System.currentTimeMillis();
        }
    }

    /** Tapped a route kind: shown at once when it is planned already. */
    private void selectKind(int k) {
        if (k == kind()) return;
        prefs.edit().putInt(Hub.KEY_NAV_PROFILE, k).apply();
        if (w.roundTrip) {
            if (!w.cornersEdited) w.newLoop();
            changed();
            return;
        }
        if (routes[k] != null || errors[k] != null) showKind(k);
        else if (planning == generation.get()) status.setText("Planning the route…"); // shown when it is ready
        else schedule();
        showTiles();
    }

    /** The three route kinds: length, time and winding share of each, the chosen one outlined. */
    private void showTiles() {
        int sel = kind();
        for (int k = 0; k < 3; k++) {
            Route r = routes[k];
            String line;
            if (r != null) line = distance(r) + " · " + duration(r) + "\n" + Math.round(100 * r.windingShare()) + "% winding";
            else if (errors[k] != null) line = "no route\n";
            else if (w.roundTrip && k != sel) line = "tap to plan\n";
            else line = "…\n";
            SpannableStringBuilder sb = new SpannableStringBuilder(PROFILES[k]);
            sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), 0);
            sb.append('\n').append(line);
            tiles[k].setText(sb);
            GradientDrawable bg = rounded(Ui.surfaceSunken(night), dp(Ui.RADIUS_TILE));
            if (k == sel) bg.setStroke(dp(2), Ui.accent(night));
            tiles[k].setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.rippleOnPlain(night)), bg, null));
            tiles[k].setSelected(k == sel);
        }
    }

    private String distance(Route r) {
        double v = prefs.getBoolean(Hub.KEY_IMPERIAL, false) ? r.length / 1609.344 : r.length / 1000;
        String unit = prefs.getBoolean(Hub.KEY_IMPERIAL, false) ? "mi" : "km";
        return String.format(Locale.ROOT, v < 10 ? "%.1f %s" : "%.0f %s", v, unit);
    }

    private static String duration(Route r) {
        long min = Math.round(r.time / 60);
        return min >= 60 ? String.format(Locale.ROOT, "%d h %02d", min / 60, min % 60) : min + " min";
    }

    // ------------------------------------------------------------------ riding and keeping the route

    private void startNavigation() {
        Route r = route;
        if (r == null) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestMissingPermissions();
            return;
        }
        if (!r.roundTrip && w.dest != null) Places.addRecent(prefs, new Places.Place(w.dest.name, w.dest.lat, w.dest.lon));
        Hub.route = r;
        if (NavService.running()) NavService.stop(this);
        main.postDelayed(() -> {
            NavService.start(this);
            startActivity(new Intent(this, NavActivity.class));
        }, NavService.running() ? 400 : 0);
    }

    private File routesDir() {
        return new File(getFilesDir(), "routes");
    }

    /** The name of the route's point {@code i} (of {@link Route#asked}). */
    private String askedName(Route r, int i) {
        int last = r.asked.size() - 1;
        if (i == 0 || (r.roundTrip && i == last)) {
            return w.start != null ? w.start.name : roadName(r.asked.get(0)[0], r.asked.get(0)[1]);
        }
        if (i == last) return w.dest != null ? w.dest.name : "Destination";
        List<Waypoints.Point> between = r.roundTrip ? w.corners : w.vias;
        return i - 1 < between.size() ? between.get(i - 1).name : "Via point " + i;
    }

    private void saveRoute() {
        Route r = route;
        if (r == null) return;
        String start = askedName(r, 0);
        String name = r.roundTrip ? String.format(Locale.ROOT, "Round trip %s from %s", distance(r), start)
                : start + " → " + askedName(r, r.asked.size() - 1);
        askName("Save the route", name, chosen -> {
            SavedRoutes.Saved s = new SavedRoutes.Saved();
            s.name = chosen;
            s.created = System.currentTimeMillis();
            s.roundTrip = r.roundTrip;
            s.kind = r.profile.kind;
            s.avoidMotorways = r.profile.avoidMotorways;
            s.avoidTolls = r.profile.avoidTolls;
            s.avoidUnpaved = r.profile.avoidUnpaved;
            s.km = r.length / 1000;
            for (int i = 0; i < r.asked.size(); i++) {
                double[] p = r.asked.get(i);
                s.points.add(new Places.Place(askedName(r, i), p[0], p[1]));
            }
            try {
                SavedRoutes.save(routesDir(), s);
                Logger.log("Planner: saved the route " + chosen);
                status.setText("Saved as “" + chosen + "”: open it with the list button at the top.");
            } catch (IOException e) {
                status.setText("Saving failed: " + e.getMessage());
            }
        });
    }

    private void savedRoutes() {
        List<SavedRoutes.Saved> l = SavedRoutes.list(routesDir());
        if (l.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Saved routes")
                    .setMessage("No saved routes yet: plan one and tap the star to keep it.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        java.text.DateFormat date = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM);
        boolean imperial = prefs.getBoolean(Hub.KEY_IMPERIAL, false);
        String[] items = new String[l.size()];
        for (int i = 0; i < items.length; i++) {
            SavedRoutes.Saved s = l.get(i);
            String len = imperial ? String.format(Locale.ROOT, "%.0f mi", s.km / 1.609344) : String.format(Locale.ROOT, "%.0f km", s.km);
            items[i] = s.name + "\n" + len + " · " + PROFILES[s.kind.ordinal()] + (s.roundTrip ? " round trip" : "")
                    + " · " + date.format(new java.util.Date(s.created));
        }
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Saved routes (long-press to edit)")
                .setItems(items, (x, which) -> load(l.get(which)))
                .setNegativeButton("Close", null)
                .create();
        d.show();
        d.getListView().setOnItemLongClickListener((parent, v, pos, id) -> {
            d.dismiss();
            savedMenu(l.get(pos));
            return true;
        });
    }

    private void savedMenu(SavedRoutes.Saved s) {
        new AlertDialog.Builder(this)
                .setTitle(s.name)
                .setItems(new String[]{"Rename", "Delete"}, (d, which) -> {
                    if (which == 0) {
                        askName("Rename", s.name, name -> {
                            s.name = name;
                            try {
                                SavedRoutes.save(routesDir(), s);
                            } catch (IOException e) {
                                status.setText("Renaming failed: " + e.getMessage());
                            }
                            savedRoutes();
                        });
                    } else {
                        new AlertDialog.Builder(this)
                                .setTitle("Delete “" + s.name + "”?")
                                .setPositiveButton("Delete", (x, y) -> {
                                    SavedRoutes.delete(s);
                                    savedRoutes();
                                })
                                .setNegativeButton("Cancel", null)
                                .show();
                    }
                })
                .show();
    }

    /** Opens a saved route: its points, kind and options, planned again. */
    private void load(SavedRoutes.Saved s) {
        prefs.edit()
                .putInt(Hub.KEY_NAV_PROFILE, s.kind.ordinal())
                .putBoolean(Hub.KEY_NAV_AVOID_MOTORWAYS, s.avoidMotorways)
                .putBoolean(Hub.KEY_NAV_AVOID_TOLLS, s.avoidTolls)
                .putBoolean(Hub.KEY_NAV_AVOID_UNPAVED, s.avoidUnpaved)
                .apply();
        w.clear();
        List<Places.Place> pts = s.points;
        w.start = point(pts.get(0));
        for (int i = 1; i + 1 < pts.size(); i++) {
            if (s.roundTrip) w.corners.add(point(pts.get(i)));
            else w.vias.add(point(pts.get(i)));
        }
        if (s.roundTrip) w.cornersEdited = !w.corners.isEmpty();
        else w.dest = point(pts.get(pts.size() - 1));
        Logger.log("Planner: opened the saved route " + s.name);
        showOptions();
        fitNext = true;
        setMode(s.roundTrip);
    }

    private void saveGpx() {
        if (route == null) return;
        String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ROOT).format(new java.util.Date());
        String name = "KawaNav_route_" + stamp + ".gpx";
        byte[] bytes = RouteGpx.of(route, "KawaNav route " + stamp).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        android.content.ContentValues v = new android.content.ContentValues();
        v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name);
        v.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/gpx+xml");
        v.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/KawaNav");
        Uri uri = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) {
            status.setText("Saving failed");
            return;
        }
        try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
            out.write(bytes);
        } catch (java.io.IOException e) {
            status.setText("Saving failed: " + e.getMessage());
            return;
        }
        Logger.log("Planner: saved " + name);
        Intent share = new Intent(Intent.ACTION_SEND).setType("application/gpx+xml")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(share, "Share " + name));
        } catch (android.content.ActivityNotFoundException e) {
            status.setText(name + " saved to Download/KawaNav");
        }
    }
}
