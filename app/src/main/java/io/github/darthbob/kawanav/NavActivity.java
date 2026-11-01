package io.github.darthbob.kawanav;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * The navigation on the phone's screen: the map following the rider, the next maneuver with its
 * distance (the dash shows the same arrow), what is left and when the ride ends. The navigation itself
 * runs in {@link NavService}: leaving this screen does not stop it.
 *
 * Made for gloves: the map follows the rider again a while after it was moved or the overview shown,
 * Stop needs a second tap, a tap on the maneuver says it again and the speaker turns the voice off and
 * on. At the destination the screen sums the ride up.
 */
public class NavActivity extends KawaActivity {

    /** The map follows the rider again this long after the screen was last touched. */
    private static final long FOLLOW_AGAIN_MS = 10_000;
    /** Stop needs a second tap within this time. */
    private static final long STOP_CONFIRM_MS = 3_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private RouteMapView map;
    private RouteGraph graph;
    private Route shown;
    private Figure distance;
    private TextView action, remaining;
    private ImageView voiceButton;
    private Button followButton, turnButton, stopButton;
    private long touchedAt;
    private boolean stopArmed;
    /** The arrival on the screen, null while navigating. */
    private Hub.Arrival arrived;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            update();
            main.postDelayed(this, 1000);
        }
    };

    private final Runnable disarm = () -> {
        stopArmed = false;
        stopLabel();
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
        voiceButton = headerAction(R.drawable.ic_volume, "", v -> {
            boolean on = !prefs.getBoolean(Hub.KEY_NAV_VOICE, true);
            prefs.edit().putBoolean(Hub.KEY_NAV_VOICE, on).apply();
            voiceLabel(on);
            if (on) audible();
        });
        r.addView(voiceButton, new LinearLayout.LayoutParams(dp(Ui.TOUCH_MIN), dp(Ui.TOUCH_MIN)));
        top.addView(r);
        remaining = secondary("");
        top.addView(remaining);
        // a tap on the maneuver says it again
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dp(Ui.RADIUS_GROUP));
        top.setForeground(new RippleDrawable(ColorStateList.valueOf(Ui.rippleOnPlain(night)), null, mask));
        top.setOnClickListener(v -> {
            if (audible()) NavService.sayNext(this);
        });
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        tlp.setMargins(dp(Ui.SPACE3), dp(Ui.SPACE3), dp(Ui.SPACE3), 0);
        frame.addView(top, tlp);

        LinearLayout bottom = row();
        followButton = neutralButton("Overview", v -> {
            if (map.following()) map.fitRoute();
            else map.setFollow(true);
            followLabel();
        });
        // heading up (the way ahead points up) or north up; remembered
        turnButton = neutralButton("", v -> {
            boolean up = !prefs.getBoolean(Hub.KEY_NAV_HEADING_UP, true);
            prefs.edit().putBoolean(Hub.KEY_NAV_HEADING_UP, up).apply();
            map.setHeadingUp(up);
            turnLabel(up);
        });
        // one tap with a glove must not end the navigation: the first tap asks for a second one
        stopButton = destructiveButton("Stop", v -> {
            if (arrived == null && !stopArmed) {
                stopArmed = true;
                stopLabel();
                main.postDelayed(disarm, STOP_CONFIRM_MS);
                return;
            }
            if (NavService.running()) NavService.stop(this);
            finish();
        });
        LinearLayout.LayoutParams third = weight();
        third.rightMargin = dp(Ui.SPACE2);
        bottom.addView(followButton, third);
        LinearLayout.LayoutParams middle = weight();
        middle.rightMargin = dp(Ui.SPACE2);
        bottom.addView(turnButton, middle);
        bottom.addView(stopButton, weight());
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
        stopLabel();
        update();
    }

    /** The button offers the other way of turning the map. */
    private void turnLabel(boolean headingUp) {
        turnButton.setText(headingUp ? "North up" : "Heading up");
        turnButton.setContentDescription(headingUp ? "The map turns with your direction: tap for north up"
                : "North is up: tap to turn the map with your direction");
    }

    /** The button offers what the map is not doing: the whole route while following, else following. */
    private void followLabel() {
        String s = map.following() ? "Overview" : "Follow me";
        if (!s.contentEquals(followButton.getText())) followButton.setText(s);
    }

    private void stopLabel() {
        stopButton.setText(arrived != null ? "Close" : stopArmed ? "Tap again" : "Stop");
        stopButton.setContentDescription(arrived != null ? "Close the navigation"
                : stopArmed ? "Tap again to stop the navigation" : "Stop the navigation");
    }

    private void voiceLabel(boolean on) {
        voiceButton.setImageResource(on ? R.drawable.ic_volume : R.drawable.ic_volume_off);
        voiceButton.setColorFilter(on ? Ui.accent(night) : Ui.inkSecondary(night));
        voiceButton.setContentDescription(on ? "Voice on: tap to turn it off" : "Voice off: tap to turn it on");
    }

    /** True when the voice can be heard now; else a toast says why not. */
    private boolean audible() {
        if (!prefs.getBoolean(Hub.KEY_CURVE_SOUND_HEADSET, true) || CurveSound.headsetConnected(this)) return true;
        Toast.makeText(this, "No headset connected: KawaNav only speaks through a headset (Settings)",
                Toast.LENGTH_LONG).show();
        return false;
    }

    /** Any touch counts: the map follows the rider again only after the screen was left alone a while. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        touchedAt = SystemClock.uptimeMillis();
        boolean handled = super.dispatchTouchEvent(e);
        followLabel();
        return handled;
    }

    @Override
    protected void onResume() {
        super.onResume();
        voiceLabel(prefs.getBoolean(Hub.KEY_NAV_VOICE, true));
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
        Hub.Arrival done = Hub.navArrival;
        boolean imperial = prefs.getBoolean(Hub.KEY_IMPERIAL, false);
        if (done != null && (nav == null || nav.state == Navigator.State.ARRIVED)) {
            showArrival(done, imperial);
            return;
        }
        if (arrived != null) {
            // a new navigation started from the planner
            arrived = null;
            stopButton.setTextColor(0xFFFFFFFF);
            tint(stopButton, Ui.dangerFill(night));
            stopLabel();
        }
        if (!map.following() && SystemClock.uptimeMillis() - touchedAt > FOLLOW_AGAIN_MS) map.setFollow(true);
        followLabel();
        if (nav == null) {
            distance.set("–");
            action.setText(NavService.running() ? "Starting…" : "Navigation stopped");
            remaining.setText("");
            return;
        }
        if (nav.state == Navigator.State.NAVIGATING && nav.distanceM >= 0) {
            distance.set(NavText.shown(nav.distanceM, imperial));
        } else {
            distance.set("…");
        }
        action.setText(nav.text);
        long etaMs = System.currentTimeMillis() + (long) (nav.remainingS * 1000);
        String eta = new java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(new java.util.Date(etaMs));
        remaining.setText(String.format(Locale.ROOT, "%s left · %s · arrival %s", NavText.shown(nav.remainingM, imperial),
                NavText.duration(nav.remainingS), eta));
    }

    /** At the destination: the flag, the ride summed up, the whole route in view and Close. */
    private void showArrival(Hub.Arrival done, boolean imperial) {
        if (done == arrived) return;
        arrived = done;
        distance.set("🏁");
        action.setText(done.text);
        String s = NavText.shown(done.riddenM, imperial) + " in " + NavText.duration(done.elapsedMs / 1000.0);
        if (done.movingMs >= 60_000) {
            double speed = done.riddenM / (done.movingMs / 1000.0) * (imperial ? 2.236936 : 3.6);
            s += String.format(Locale.ROOT, " · %.0f %s moving average", speed, imperial ? "mph" : "km/h");
        }
        remaining.setText(s);
        main.removeCallbacks(disarm);
        stopArmed = false;
        primary(stopButton);
        stopLabel();
        // after the layout: the screen may have just opened
        map.post(() -> {
            map.fitRoute();
            followLabel();
        });
    }
}
