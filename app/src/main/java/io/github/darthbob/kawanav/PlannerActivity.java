package io.github.darthbob.kawanav;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Plans a route on the downloaded regions: from here (or a chosen start) to a destination with via
 * points, or a round trip of a chosen length, direction and shape. Fastest, curvy or extra curvy, with
 * motorways, tolls and gravel avoided on request. The result can be ridden with turn-by-turn navigation
 * ({@link NavService}) or saved as GPX.
 */
public class PlannerActivity extends KawaActivity {

    private static final String KEY_MODE = "planner_mode";
    private static final String KEY_RT_KM = "planner_rt_km";
    private static final String KEY_RT_DIR = "planner_rt_dir";
    private static final String KEY_RT_SHAPE = "planner_rt_shape";
    private static final String[] DIRECTIONS = {"Any direction", "North", "North-east", "East", "South-east", "South",
            "South-west", "West", "North-west"};
    private static final String[] SHAPES = {"Any shape", "Triangle", "Square", "Pentagon", "Circle"};
    private static final int[] LENGTHS = {30, 50, 75, 100, 150, 200, 250, 300, 400};
    private static final String[] PROFILES = {"Fastest", "Curvy", "Extra curvy"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "planner"));
    private RouteMapView map;
    private RouteGraph graph;
    private Route route;

    private int mode; // 0 A to B, 1 round trip
    /** The start (null: my position), via points and destination of A to B; the round trip's start. */
    private double[] start;
    private final List<double[]> vias = new ArrayList<>();
    private double[] dest;
    private long seed;
    /** A position asked for since the planner opened (the phone's last known one can be hours old). */
    private Location fresh;

    private LinearLayout abBox, rtBox, resultBox;
    private Button modeAb, modeRt, calculate, another;
    private TextView pointsText, status, resultText;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        Logger.init(this);
        prefs = Hub.prefs(this);
        night = isNight();
        mode = prefs.getInt(KEY_MODE, 0);

        LinearLayout root = column();
        root.setPadding(dp(Ui.SPACE4), 0, dp(Ui.SPACE4), dp(Ui.SPACE6));
        root.addView(headerBar("Route planner", v -> finish(), headerAction(R.drawable.ic_pin, "My position", v -> toMyPosition())));

        map = new RouteMapView(this);
        map.setListener(new RouteMapView.Listener() {
            @Override
            public void onTap(double lat, double lon) {
                askWhatHere(lat, lon);
            }

            @Override
            public void onLongPress(double lat, double lon) {
                if (mode == 0) dest = new double[]{lat, lon};
                else start = new double[]{lat, lon};
                pointsChanged();
            }
        });
        root.addView(map, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(380)));
        root.addView(legend());

        LinearLayout modes = row();
        modes.setPadding(0, dp(Ui.SPACE3), 0, 0);
        modeAb = button("A to B", v -> setMode(0));
        modeRt = button("Round trip", v -> setMode(1));
        LinearLayout.LayoutParams half = weight();
        half.rightMargin = dp(Ui.SPACE2);
        modes.addView(modeAb, half);
        modes.addView(modeRt, weight());
        root.addView(modes);

        abBox = column();
        abBox.addView(searchRow());
        pointsText = secondary("");
        pointsText.setPadding(dp(Ui.SPACE1), dp(Ui.SPACE2), dp(Ui.SPACE1), 0);
        abBox.addView(pointsText);
        Button clear = secondaryButton("Clear the points", v -> {
            start = null;
            vias.clear();
            dest = null;
            showRoute(null);
            pointsChanged();
        });
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(Ui.SPACE2);
        abBox.addView(clear, lp);
        root.addView(abBox);

        rtBox = group();
        int km = prefs.getInt(KEY_RT_KM, 100);
        int kmIdx = 3;
        for (int i = 0; i < LENGTHS.length; i++) if (LENGTHS[i] == km) kmIdx = i;
        String[] kmItems = new String[LENGTHS.length];
        for (int i = 0; i < LENGTHS.length; i++) kmItems[i] = LENGTHS[i] + " km";
        addRow(rtBox, choiceRow("Length", "About this long", kmItems, kmIdx,
                p -> prefs.edit().putInt(KEY_RT_KM, LENGTHS[p]).apply()));
        addRow(rtBox, choiceRow("Direction", "Where the trip heads out to", DIRECTIONS, prefs.getInt(KEY_RT_DIR, 0),
                p -> prefs.edit().putInt(KEY_RT_DIR, p).apply()));
        addRow(rtBox, choiceRow("Shape", "The outline it follows", SHAPES, prefs.getInt(KEY_RT_SHAPE, 0),
                p -> prefs.edit().putInt(KEY_RT_SHAPE, p).apply()));
        root.addView(rtBox);

        LinearLayout options = group();
        addRow(options, choiceRow("Roads", "Curvy prefers winding roads and avoids towns; extra curvy takes long detours for bends",
                PROFILES, prefs.getInt(Hub.KEY_NAV_PROFILE, RouteProfile.Kind.CURVY.ordinal()),
                p -> prefs.edit().putInt(Hub.KEY_NAV_PROFILE, p).apply()));
        addRow(options, switchRow("Avoid motorways", "Only when there is no other way", Hub.KEY_NAV_AVOID_MOTORWAYS, false));
        addRow(options, switchRow("Avoid tolls", "Toll roads only when there is no other way", Hub.KEY_NAV_AVOID_TOLLS, false));
        addRow(options, switchRow("Avoid gravel", "Unpaved roads only when there is no other way", Hub.KEY_NAV_AVOID_UNPAVED, true));
        LinearLayout.LayoutParams olp = matchWrap();
        olp.topMargin = dp(Ui.SPACE3);
        root.addView(options, olp);

        calculate = primaryButton("Plan the route", v -> plan(false));
        LinearLayout.LayoutParams clp = matchWrap();
        clp.topMargin = dp(Ui.SPACE3);
        root.addView(calculate, clp);
        status = secondary("");
        status.setPadding(dp(Ui.SPACE1), dp(Ui.SPACE2), dp(Ui.SPACE1), 0);
        root.addView(status);

        resultBox = card();
        resultText = itemTitle("");
        resultBox.addView(resultText);
        LinearLayout actions = column();
        Button ride = primaryButton("Start navigation", v -> startNavigation());
        LinearLayout.LayoutParams rlp = matchWrap();
        rlp.topMargin = dp(Ui.SPACE3);
        actions.addView(ride, rlp);
        another = secondaryButton("Another round trip", v -> {
            seed++;
            plan(true);
        });
        LinearLayout.LayoutParams alp = matchWrap();
        alp.topMargin = dp(Ui.SPACE2);
        actions.addView(another, alp);
        actions.addView(secondaryButton("Save as GPX", v -> saveGpx()), alp);
        resultBox.addView(actions);
        resultBox.setVisibility(View.GONE);
        root.addView(resultBox);

        root.addView(note("Routes are computed on the phone from the downloaded OpenStreetMap regions (Settings, "
                + "Offline maps). Map and road data © OpenStreetMap contributors."));

        setContentView(page(root));
        setMode(mode);
        Location here = lastLocation();
        if (here != null) map.center(here.getLatitude(), here.getLongitude(), 11);
        askPosition(here == null);
        worker.execute(() -> {
            try {
                RouteGraph g = RoutingData.graph(this);
                main.post(() -> {
                    graph = g;
                    if (g.isEmpty()) status.setText(noDataText());
                    if (Hub.route != null) showRoute(Hub.route);
                });
            } catch (Exception e) {
                main.post(() -> status.setText("Routing data unreadable: download the region again (" + e.getMessage() + ")"));
            }
        });
        pointsChanged();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private static String noDataText() {
        return "No routing data yet: download a region under Settings, Offline maps. Regions downloaded with an older "
                + "KawaNav have to be downloaded once more.";
    }

    /** The colours of the route line. */
    private View legend() {
        LinearLayout r = row();
        r.setPadding(0, dp(Ui.SPACE2), 0, 0);
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

    private View searchRow() {
        LinearLayout r = row();
        r.setPadding(0, dp(Ui.SPACE3), 0, 0);
        EditText field = new EditText(this);
        field.setHint("Search a place (online)");
        field.setSingleLine(true);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        field.setTextColor(Ui.ink(night));
        field.setHintTextColor(Ui.inkMuted(night));
        Button go = secondaryButton("Search", v -> search(field.getText().toString()));
        field.setOnEditorActionListener((v, id, e) -> {
            search(field.getText().toString());
            return true;
        });
        r.addView(field, weight());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(Ui.SPACE2);
        r.addView(go, lp);
        return r;
    }

    private void setMode(int m) {
        mode = m;
        prefs.edit().putInt(KEY_MODE, m).apply();
        if (m == 0) {
            primary(modeAb);
            quiet(modeRt);
        } else {
            primary(modeRt);
            quiet(modeAb);
        }
        abBox.setVisibility(m == 0 ? View.VISIBLE : View.GONE);
        rtBox.setVisibility(m == 1 ? View.VISIBLE : View.GONE);
        another.setVisibility(m == 1 ? View.VISIBLE : View.GONE);
        calculate.setText(m == 0 ? "Plan the route" : "Plan a round trip");
        pointsChanged();
    }

    // ------------------------------------------------------------------ points

    private void askWhatHere(double lat, double lon) {
        double[] p = {lat, lon};
        String[] items = mode == 0
                ? new String[]{"Destination here", "Via point here", "Start here"}
                : new String[]{"Start the round trip here"};
        new AlertDialog.Builder(this)
                .setItems(items, (d, which) -> {
                    if (mode == 1) start = p;
                    else if (which == 0) dest = p;
                    else if (which == 1) vias.add(p);
                    else start = p;
                    pointsChanged();
                })
                .show();
    }

    private void pointsChanged() {
        List<double[]> pts = new ArrayList<>();
        double[] s = start != null ? start : here();
        if (s != null) pts.add(s);
        if (mode == 0) {
            pts.addAll(vias);
            if (dest != null) pts.add(dest);
        } else if (s != null) {
            pts.add(s);
        }
        if (map != null) map.setPoints(pts);
        if (pointsText != null) {
            pointsText.setText((start == null ? "Start: my position" : "Start: chosen on the map")
                    + (vias.isEmpty() ? "" : " · " + vias.size() + (vias.size() == 1 ? " via point" : " via points"))
                    + (dest == null ? " · Destination: tap the map or search" : " · Destination chosen")
                    + "\nTap the map for a destination or a via point; a long press sets the destination.");
        }
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
                        pointsChanged();
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
        else status.setText("Waiting for GPS… You can also tap the map and choose \"Start here\".");
        askPosition(true);
    }

    private void search(String q) {
        if (q.trim().isEmpty()) return;
        status.setText("Searching…");
        if (!Geocoder.isPresent()) {
            status.setText("This phone has no place search: tap the map instead.");
            return;
        }
        new Geocoder(this, Locale.getDefault()).getFromLocationName(q, 8, new Geocoder.GeocodeListener() {
            @Override
            public void onGeocode(List<Address> list) {
                main.post(() -> searchResults(list));
            }

            @Override
            public void onError(String message) {
                Logger.log("Planner: place search failed: " + message);
                main.post(() -> status.setText("Search failed (" + message + "): tap the map instead."));
            }
        });
    }

    private void searchResults(List<Address> found) {
        if (found == null || found.isEmpty()) {
            status.setText("Nothing found. Tap the map instead.");
            return;
        }
        status.setText("");
        // the nearest first: to the rider, or to what the map shows
        double[] ref = here() != null ? here() : map.centre();
        List<Address> list = new ArrayList<>(found);
        list.sort((a, b) -> Double.compare(Ride.distanceM(ref[0], ref[1], a.getLatitude(), a.getLongitude()),
                Ride.distanceM(ref[0], ref[1], b.getLatitude(), b.getLongitude())));
        String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++) {
            Address a = list.get(i);
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k <= a.getMaxAddressLineIndex(); k++) {
                if (k > 0) sb.append(", ");
                sb.append(a.getAddressLine(k));
            }
            names[i] = sb.length() > 0 ? sb.toString() : a.getFeatureName();
        }
        new AlertDialog.Builder(this)
                .setTitle("Destination")
                .setItems(names, (d, which) -> {
                    Address a = list.get(which);
                    dest = new double[]{a.getLatitude(), a.getLongitude()};
                    map.center(a.getLatitude(), a.getLongitude(), 12);
                    pointsChanged();
                })
                .show();
    }

    // ------------------------------------------------------------------ planning

    private void plan(boolean again) {
        if (busy) return;
        if (graph == null || graph.isEmpty()) {
            status.setText(graph == null ? "Opening the routing data…" : noDataText());
            return;
        }
        double[] s = start != null ? start : here();
        if (s == null) {
            status.setText("No position yet: tap the map and choose \"Start here\".");
            return;
        }
        if (mode == 0 && dest == null) {
            status.setText("Choose a destination: tap the map or search a place.");
            return;
        }
        if (!again && mode == 1) seed = System.currentTimeMillis() & 0xFFFF;
        busy = true;
        calculate.setEnabled(false);
        status.setText(mode == 0 ? "Planning the route…" : "Planning a round trip…");
        RouteProfile profile = Hub.routeProfile(this);
        boolean leftHand = prefs.getBoolean(Hub.KEY_LEFT_HAND, false);
        List<double[]> pts = new ArrayList<>();
        pts.add(s);
        pts.addAll(vias);
        if (dest != null) pts.add(dest);
        int km = prefs.getInt(KEY_RT_KM, 100), dir = prefs.getInt(KEY_RT_DIR, 0), shape = prefs.getInt(KEY_RT_SHAPE, 0);
        long useSeed = seed;
        RouteGraph g = graph;
        long t0 = System.currentTimeMillis();
        worker.execute(() -> {
            Route r = null;
            String error = null;
            try {
                if (mode == 0) {
                    Router.Request req = new Router.Request(pts, profile);
                    req.leftHandTraffic = leftHand;
                    r = new Router(g).route(req);
                } else {
                    RoundTrip.Request req = new RoundTrip.Request(s[0], s[1], km, profile);
                    req.heading = dir == 0 ? Double.NaN : (dir - 1) * 45;
                    req.shape = RoundTrip.Shape.values()[Math.max(0, Math.min(4, shape))];
                    req.seed = useSeed;
                    req.leftHandTraffic = leftHand;
                    r = RoundTrip.plan(g, req);
                }
            } catch (Router.NoRouteException e) {
                error = e.getMessage();
            } catch (Exception | OutOfMemoryError e) {
                error = "failed: " + e;
            }
            Route found = r;
            String err = error;
            long ms = System.currentTimeMillis() - t0;
            main.post(() -> {
                busy = false;
                calculate.setEnabled(true);
                if (found == null) {
                    status.setText("No route: " + err + ". Points must lie in a downloaded region.");
                    Logger.log("Planner: no route (" + err + ")");
                    return;
                }
                Logger.log(String.format(Locale.ROOT, "Planner: %.1f km %s in %d ms", found.length / 1000,
                        found.profile.kind, ms));
                status.setText("");
                Hub.route = found;
                showRoute(found);
                map.fitRoute();
            });
        });
    }

    private void showRoute(Route r) {
        route = r;
        map.setRoute(r, graph);
        if (r == null) {
            resultBox.setVisibility(View.GONE);
            return;
        }
        boolean imperial = prefs.getBoolean(Hub.KEY_IMPERIAL, false);
        String dist = imperial ? String.format(Locale.ROOT, "%.0f mi", r.length / 1609.344)
                : String.format(Locale.ROOT, "%.0f km", r.length / 1000);
        long min = Math.round(r.time / 60);
        String time = min >= 60 ? String.format(Locale.ROOT, "%d h %02d min", min / 60, min % 60) : min + " min";
        String asked = r.roundTrip ? String.format(Locale.ROOT, " (asked: %d km)", prefs.getInt(KEY_RT_KM, 100)) : "";
        resultText.setText(String.format(Locale.ROOT, "%s%s · %s\n%.0f%% on winding roads · %s", dist, asked, time,
                100 * r.windingShare(), PROFILES[r.profile.kind.ordinal()]));
        resultText.setGravity(Gravity.START);
        resultBox.setVisibility(View.VISIBLE);
        another.setVisibility(r.roundTrip ? View.VISIBLE : View.GONE);
        List<double[]> pts = new ArrayList<>(r.roundTrip ? java.util.Arrays.asList(r.asked.get(0), r.asked.get(0)) : r.asked);
        map.setPoints(pts);
    }

    private void startNavigation() {
        if (route == null) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestMissingPermissions();
            return;
        }
        Hub.route = route;
        if (NavService.running()) NavService.stop(this);
        main.postDelayed(() -> {
            NavService.start(this);
            startActivity(new Intent(this, NavActivity.class));
        }, NavService.running() ? 400 : 0);
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
