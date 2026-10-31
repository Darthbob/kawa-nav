package io.github.darthbob.kawanav;

import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * The navigation on the phone's screen: the map following the rider, the next maneuver with its
 * distance (the dash shows the same arrow), what is left and when the ride ends. The navigation itself
 * runs in {@link NavService}: leaving this screen does not stop it.
 */
public class NavActivity extends KawaActivity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private RouteMapView map;
    private RouteGraph graph;
    private Route shown;
    private Figure distance;
    private TextView action, remaining;
    private Button followButton, turnButton;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            update();
            main.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        Logger.init(this);
        prefs = Hub.prefs(this);
        night = isNight();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            graph = RoutingData.graph(this);
        } catch (Exception e) {
            graph = null;
        }

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Ui.bg(night));
        map = new RouteMapView(this);
        frame.addView(map, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout top = card();
        LinearLayout r = row();
        distance = figure(40, Gravity.START);
        r.addView(distance.view);
        action = text(18, true);
        action.setPadding(dp(Ui.SPACE3), 0, 0, 0);
        r.addView(action, weight());
        top.addView(r);
        remaining = secondary("");
        top.addView(remaining);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        tlp.setMargins(dp(Ui.SPACE3), dp(Ui.SPACE3), dp(Ui.SPACE3), 0);
        frame.addView(top, tlp);

        LinearLayout bottom = row();
        followButton = neutralButton("Overview", v -> {
            if (map.following()) {
                map.fitRoute();
                followButton.setText("Follow me");
            } else {
                map.setFollow(true);
                followButton.setText("Overview");
            }
        });
        // heading up (the way ahead points up) or north up; remembered
        turnButton = neutralButton("", v -> {
            boolean up = !prefs.getBoolean(Hub.KEY_NAV_HEADING_UP, true);
            prefs.edit().putBoolean(Hub.KEY_NAV_HEADING_UP, up).apply();
            map.setHeadingUp(up);
            turnLabel(up);
        });
        Button stop = destructiveButton("Stop", v -> {
            NavService.stop(this);
            finish();
        });
        LinearLayout.LayoutParams third = weight();
        third.rightMargin = dp(Ui.SPACE2);
        bottom.addView(followButton, third);
        LinearLayout.LayoutParams middle = weight();
        middle.rightMargin = dp(Ui.SPACE2);
        bottom.addView(turnButton, middle);
        bottom.addView(stop, weight());
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        blp.setMargins(dp(Ui.SPACE3), 0, dp(Ui.SPACE3), dp(Ui.SPACE3));
        frame.addView(bottom, blp);

        frame.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                    | android.view.WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return android.view.WindowInsets.CONSUMED;
        });
        setContentView(frame);
        boolean up = prefs.getBoolean(Hub.KEY_NAV_HEADING_UP, true);
        map.setHeadingUp(up);
        turnLabel(up);
        map.setFollow(true);
        update();
    }

    /** The button offers the other way of turning the map. */
    private void turnLabel(boolean headingUp) {
        turnButton.setText(headingUp ? "North up" : "Heading up");
        turnButton.setContentDescription(headingUp ? "The map turns with your direction: tap for north up"
                : "North is up: tap to turn the map with your direction");
    }

    @Override
    protected void onResume() {
        super.onResume();
        main.post(refresh);
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(refresh);
        super.onPause();
    }

    private void update() {
        Route r = Hub.route;
        if (r != shown) {
            shown = r;
            map.setRoute(r, graph);
            if (r != null) map.setPoints(r.roundTrip ? java.util.Arrays.asList(r.asked.get(0), r.asked.get(0)) : r.asked);
        }
        Location fix = Hub.lastFix;
        if (fix != null) {
            // the navigation's heading also comes from the movement when the GPS gives none
            double heading = Hub.navHeading;
            if (heading != heading && fix.hasBearing()) heading = fix.getBearing();
            map.setPosition(fix.getLatitude(), fix.getLongitude(), heading);
        }
        Hub.Nav nav = Hub.nav;
        boolean imperial = prefs.getBoolean(Hub.KEY_IMPERIAL, false);
        if (nav == null) {
            distance.set("–");
            action.setText(NavService.running() ? "Starting…" : "Navigation stopped");
            remaining.setText("");
            return;
        }
        if (nav.state == Navigator.State.NAVIGATING && nav.distanceM >= 0) {
            distance.set(imperial ? (nav.distanceM < 300 ? Math.round(nav.distanceM * 3.28084 / 10) * 10 + " ft"
                    : String.format(Locale.ROOT, "%.1f mi", nav.distanceM / 1609.344))
                    : nav.distanceM < 1000 ? CurveEngine.roundDistance(nav.distanceM) + " m"
                    : String.format(Locale.ROOT, "%.1f km", nav.distanceM / 1000.0));
        } else {
            distance.set(nav.state == Navigator.State.ARRIVED ? "🏁" : "…");
        }
        action.setText(nav.text);
        long etaMs = System.currentTimeMillis() + (long) (nav.remainingS * 1000);
        String eta = new java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(new java.util.Date(etaMs));
        remaining.setText(nav.state == Navigator.State.ARRIVED ? "" : String.format(Locale.ROOT, "%s left · arrival %s",
                imperial ? String.format(Locale.ROOT, "%.0f mi", nav.remainingM / 1609.344)
                        : String.format(Locale.ROOT, "%.0f km", nav.remainingM / 1000), eta));
    }
}
