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
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * The navigation on the phone's screen: the map following the rider, the next maneuver's arrow with its
 * distance (the dash shows the same arrow) and the one straight after it, what is left and when the
 * ride ends, a lost GPS signal, a speed camera ahead or a bike range too short, and at the bottom the
 * speed, the road's limit and its name, how winding the next km are and the list of the turns ahead
 * (where a via point can be skipped). The map greys the part ridden, marks the next maneuver and zooms
 * with the speed. The navigation itself runs in {@link NavService}: leaving this screen does not stop it.
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
    /** A position older than this: the GPS signal is lost. */
    private static final long FIX_LOST_MS = 5_000;
    /** The speed turns red this far over the limit (km/h). */
    private static final int OVER_LIMIT_KMH = 3;
    /** The bike's range must reach the destination with this to spare (km), or the screen says so. */
    private static final int RANGE_SPARE_KM = 10;
    /** The bike's range is believed this long after the dash last sent it. */
    private static final long RANGE_FRESH_MS = 5 * 60_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private RouteMapView map;
    private RouteGraph graph;
    private Route shown;
    private Figure distance, speed;
    private TextView action, remaining, thenText, limit, road, gpsBanner, cameraBanner, rangeBanner, curvesLabel;
    private TurnIcon arrow, thenArrow;
    private CurvesBar curves;
    private LinearLayout thenRow, info, curvesRow;
    /** The GPS speed (m/s) evened out over a few seconds, for the map's zoom. */
    private double smoothSpeed;
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
        setShowWhenLocked(true); // on the handlebar the phone may lock: the navigation stays in view
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

        // the next maneuver: its arrow, the distance over what to do, and the voice
        LinearLayout top = card();
        LinearLayout r = row();
        arrow = new TurnIcon(this);
        r.addView(arrow, new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout what = column();
        what.setPadding(dp(Ui.SPACE3), 0, 0, 0);
        distance = figure(40, Gravity.START);
        what.addView(distance.view);
        action = text(18, true);
        what.addView(action);
        r.addView(what, weight());
        voiceButton = headerAction(R.drawable.ic_volume, "", v -> {
            boolean on = !prefs.getBoolean(Hub.KEY_NAV_VOICE, true);
            prefs.edit().putBoolean(Hub.KEY_NAV_VOICE, on).apply();
            voiceLabel(on);
            if (on) audible();
        });
        r.addView(voiceButton, new LinearLayout.LayoutParams(dp(Ui.TOUCH_MIN), dp(Ui.TOUCH_MIN)));
        top.addView(r);
        // the maneuver straight after it
        thenRow = row();
        thenRow.setPadding(0, dp(Ui.SPACE2), 0, 0);
        thenArrow = new TurnIcon(this);
        thenArrow.colours(Ui.inkSecondary(night), Ui.surface(night));
        thenRow.addView(thenArrow, new LinearLayout.LayoutParams(dp(24), dp(24)));
        thenText = text(15, false);
        thenText.setTextColor(Ui.inkSecondary(night));
        thenText.setPadding(dp(Ui.SPACE2), 0, 0, 0);
        thenRow.addView(thenText, weight());
        top.addView(thenRow);
        remaining = secondary("");
        remaining.setPadding(0, dp(Ui.SPACE2), 0, 0);
        top.addView(remaining);
        // a tap on the maneuver says it again
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dp(Ui.RADIUS_GROUP));
        top.setForeground(new RippleDrawable(ColorStateList.valueOf(Ui.rippleOnPlain(night)), null, mask));
        top.setOnClickListener(v -> {
            if (audible()) NavService.sayNext(this);
        });
        // under it, while they last: the GPS signal lost, a speed camera ahead
        LinearLayout upper = column();
        upper.addView(top, matchWrap());
        gpsBanner = banner(Lang.t(R.string.nav_no_gps), Ui.dangerFill(night), 0xFFFFFFFF);
        upper.addView(gpsBanner);
        cameraBanner = banner("", Ui.warnFill(), Ui.bannerInk());
        upper.addView(cameraBanner);
        rangeBanner = banner("", Ui.warnFill(), Ui.bannerInk());
        upper.addView(rangeBanner);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        tlp.setMargins(dp(Ui.SPACE3), dp(Ui.SPACE3), dp(Ui.SPACE3), 0);
        frame.addView(upper, tlp);

        LinearLayout bottom = row();
        followButton = neutralButton(Lang.t(R.string.nav_overview), v -> {
            if (map.following()) {
                map.fitRoute();
            } else {
                map.setFollow(true);
                map.autoZoom();
            }
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
        stopButton = destructiveButton(Lang.t(R.string.nav_stop), v -> {
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
        // over the buttons: the speed, the road's limit and its name, the turns ahead; how winding the next km are
        info = column();
        info.setPadding(dp(Ui.SPACE3), dp(Ui.SPACE2), dp(Ui.SPACE1), dp(Ui.SPACE2));
        GradientDrawable infoBg = new GradientDrawable();
        infoBg.setColor(Ui.surface(night));
        infoBg.setCornerRadius(dp(Ui.RADIUS_GROUP));
        info.setBackground(infoBg);
        LinearLayout infoRow = row();
        info.addView(infoRow);
        speed = figure(28, Gravity.START);
        infoRow.addView(speed.view);
        limit = new TextView(this);
        limit.setGravity(Gravity.CENTER);
        limit.setTextSize(16);
        limit.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        limit.setTextColor(0xFF171717);
        limit.setIncludeFontPadding(false);
        GradientDrawable sign = new GradientDrawable();
        sign.setShape(GradientDrawable.OVAL);
        sign.setColor(0xFFFFFFFF); // a speed limit sign is white also at night
        sign.setStroke(dp(4), 0xFFD32F2F);
        limit.setBackground(sign);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(44), dp(44));
        llp.leftMargin = dp(Ui.SPACE3);
        infoRow.addView(limit, llp);
        road = text(15, false);
        road.setTextColor(Ui.inkSecondary(night));
        road.setSingleLine(true);
        road.setEllipsize(android.text.TextUtils.TruncateAt.END);
        road.setGravity(Gravity.END);
        road.setPadding(dp(Ui.SPACE3), 0, 0, 0);
        infoRow.addView(road, weight());
        infoRow.addView(headerAction(R.drawable.ic_list, Lang.t(R.string.nav_turns_ahead_description), v -> showTurns()),
                new LinearLayout.LayoutParams(dp(Ui.TOUCH_MIN), dp(Ui.TOUCH_MIN)));
        curvesRow = row();
        curvesRow.setPadding(0, dp(Ui.SPACE1), dp(Ui.SPACE2), dp(Ui.SPACE1));
        curvesLabel = secondary("");
        curvesRow.addView(curvesLabel);
        curves = new CurvesBar(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dp(8), 1);
        clp.leftMargin = dp(Ui.SPACE3);
        curvesRow.addView(curves, clp);
        info.addView(curvesRow);
        LinearLayout lower = column();
        LinearLayout.LayoutParams ilp = matchWrap();
        ilp.bottomMargin = dp(Ui.SPACE2);
        lower.addView(info, ilp);
        lower.addView(bottom, matchWrap());
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        blp.setMargins(dp(Ui.SPACE3), 0, dp(Ui.SPACE3), dp(Ui.SPACE3));
        frame.addView(lower, blp);

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

    /** A strong line under the maneuver for what needs the rider's eye now; hidden until set. */
    private TextView banner(String s, int fill, int ink) {
        TextView t = text(15, true);
        t.setText(s);
        t.setTextColor(ink);
        t.setPadding(dp(Ui.SPACE3), dp(Ui.SPACE2), dp(Ui.SPACE3), dp(Ui.SPACE2));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(Ui.RADIUS_GROUP));
        t.setBackground(bg);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(Ui.SPACE2);
        t.setLayoutParams(lp);
        t.setVisibility(android.view.View.GONE);
        return t;
    }

    /** The button offers the other way of turning the map. */
    private void turnLabel(boolean headingUp) {
        turnButton.setText(headingUp ? Lang.t(R.string.nav_north_up) : Lang.t(R.string.nav_heading_up));
        turnButton.setContentDescription(headingUp ? Lang.t(R.string.nav_heading_up_description)
                : Lang.t(R.string.nav_north_up_description));
    }

    /** The button offers what the map is not doing: the whole route while following, else following. */
    private void followLabel() {
        String s = map.following() ? Lang.t(R.string.nav_overview) : Lang.t(R.string.nav_follow);
        if (!s.contentEquals(followButton.getText())) followButton.setText(s);
    }

    private void stopLabel() {
        stopButton.setText(arrived != null ? Lang.t(R.string.common_close) : stopArmed ? Lang.t(R.string.nav_tap_again) : Lang.t(R.string.nav_stop));
        stopButton.setContentDescription(arrived != null ? Lang.t(R.string.nav_close_description)
                : stopArmed ? Lang.t(R.string.nav_tap_again_description) : Lang.t(R.string.nav_stop_description));
    }

    private void voiceLabel(boolean on) {
        voiceButton.setImageResource(on ? R.drawable.ic_volume : R.drawable.ic_volume_off);
        voiceButton.setColorFilter(on ? Ui.accentText(night) : Ui.inkSecondary(night));
        voiceButton.setContentDescription(on ? Lang.t(R.string.nav_voice_on) : Lang.t(R.string.nav_voice_off));
    }

    /** True when the voice can be heard now; else a toast says why not. */
    private boolean audible() {
        if (!prefs.getBoolean(Hub.KEY_CURVE_SOUND_HEADSET, true) || CurveSound.headsetConnected(this)) return true;
        Toast.makeText(this, Lang.t(R.string.nav_no_headset),
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
            distance.view.setVisibility(View.VISIBLE);
            stopButton.setTextColor(0xFFFFFFFF);
            tint(stopButton, Ui.dangerFill(night));
            stopLabel();
        }
        if (SystemClock.uptimeMillis() - touchedAt > FOLLOW_AGAIN_MS) {
            if (!map.following()) map.setFollow(true);
            map.autoZoom(); // a pinch is forgotten too
        }
        followLabel();
        boolean navigating = nav != null && nav.state == Navigator.State.NAVIGATING;
        // the zoom: farther out when fast, closer before a maneuver
        smoothSpeed += ((fresh(fix) && fix.hasSpeed() ? fix.getSpeed() : 0) - smoothSpeed) * 0.3;
        if (navigating && map.following()) map.zoomTo(Navigator.mapZoom(smoothSpeed, nav.distanceM));
        Route route = Hub.route;
        map.setProgress(nav == null ? 0 : nav.progressM,
                navigating && nav.distanceM >= 0 && route == shown && route != null ? route.pointAt(nav.progressM + nav.distanceM) : null);
        showAlerts(nav, fix, imperial);
        showInfo(navigating ? nav : null, fix, imperial);
        boolean then = navigating && nav.thenText != null;
        thenRow.setVisibility(then ? View.VISIBLE : View.GONE);
        if (nav == null) {
            arrow.setTurn(KawaProtocol.TURN_NONE);
            distance.set("–");
            action.setText(NavService.running() ? Lang.t(R.string.nav_starting) : Lang.t(R.string.nav_stopped));
            remaining.setText("");
            return;
        }
        if (navigating && nav.distanceM >= 0) {
            arrow.setTurn(nav.turn);
            distance.set(NavText.shown(nav.distanceM, imperial));
        } else {
            arrow.setTurn(KawaProtocol.TURN_NONE);
            distance.set("…");
        }
        action.setText(nav.text);
        if (then) {
            thenArrow.setTurn(nav.thenTurn);
            if (!nav.thenText.contentEquals(thenText.getText())) thenText.setText(nav.thenText);
        }
        long etaMs = System.currentTimeMillis() + (long) (nav.remainingS * 1000);
        String eta = new java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(new java.util.Date(etaMs));
        remaining.setText(Lang.t(R.string.nav_remaining, NavText.shown(nav.remainingM, imperial),
                NavText.duration(nav.remainingS), eta));
    }

    private static boolean fresh(Location fix) {
        return fix != null && SystemClock.elapsedRealtimeNanos() - fix.getElapsedRealtimeNanos() <= FIX_LOST_MS * 1_000_000;
    }

    /**
     * While navigating ({@code nav} not null): the GPS signal lost, a speed camera the bridge warns of (it
     * goes to the dash too), and a bike whose range does not reach the destination.
     */
    private void showAlerts(Hub.Nav nav, Location fix, boolean imperial) {
        gpsBanner.setVisibility(nav != null && !fresh(fix) ? View.VISIBLE : View.GONE);
        Hub.Output out = Hub.output;
        String alert = nav != null && out != null ? out.alert : null;
        cameraBanner.setVisibility(alert != null ? View.VISIBLE : View.GONE);
        if (alert != null && !alert.contentEquals(cameraBanner.getText())) cameraBanner.setText(alert);
        BikeFrames.VehicleInfo bike = Hub.bike.info;
        boolean low = nav != null && Hub.bikeConnected && bike != null && bike.rangeKm >= 0
                && System.currentTimeMillis() - Hub.bike.infoAt < RANGE_FRESH_MS
                && bike.rangeKm < nav.remainingM / 1000 + RANGE_SPARE_KM;
        rangeBanner.setVisibility(low ? View.VISIBLE : View.GONE);
        if (low) {
            String s = Lang.t(R.string.nav_refuel, NavText.shown(bike.rangeKm * 1000.0, imperial),
                    NavText.shown(nav.remainingM, imperial));
            if (!s.contentEquals(rangeBanner.getText())) rangeBanner.setText(s);
        }
    }

    /** The turns still ahead with their distance; a via point ahead can be left out from here. */
    private void showTurns() {
        Hub.Nav nav = Hub.nav;
        if (nav == null || nav.maneuvers == null) return;
        boolean imperial = prefs.getBoolean(Hub.KEY_IMPERIAL, false);
        NavText text = NavText.current();
        LinearLayout list = column();
        list.setPadding(dp(Ui.SPACE5), dp(Ui.SPACE2), dp(Ui.SPACE5), dp(Ui.SPACE2));
        int via = 0;
        for (Instructions.Maneuver m : nav.maneuvers) {
            if (m.kind == Instructions.Kind.DEPART || m.at <= nav.progressM + 0.5) continue;
            if (m.kind == Instructions.Kind.VIA && via == 0) via = m.number;
            LinearLayout r = row();
            r.setPadding(0, dp(Ui.SPACE2), 0, dp(Ui.SPACE2));
            TurnIcon icon = new TurnIcon(this);
            icon.setTurn(m.turn);
            r.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));
            TextView t = text(15, false);
            t.setText(text.upcoming(m));
            t.setPadding(dp(Ui.SPACE3), 0, dp(Ui.SPACE2), 0);
            r.addView(t, weight());
            r.addView(secondary(NavText.shown(m.at - nav.progressM, imperial)));
            list.addView(r);
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(list);
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                .setTitle(Lang.t(R.string.nav_turns_ahead))
                .setView(scroll)
                .setNegativeButton(Lang.t(R.string.common_close), null);
        // a via point the rider does not want any more: the route goes on to the next point
        if (via > 0) b.setPositiveButton(Lang.t(R.string.nav_skip_via, via), (d, w) -> NavService.skipVia(this));
        b.show();
    }

    /** The speed (red over the limit), the speed limit of the road ridden and its name; hidden when null. */
    private void showInfo(Hub.Nav nav, Location fix, boolean imperial) {
        info.setVisibility(nav == null ? View.GONE : View.VISIBLE);
        if (nav == null) return;
        double kmh = fresh(fix) && fix.hasSpeed() ? fix.getSpeed() * 3.6 : -1;
        speed.set(kmh < 0 ? "–" : Math.round(imperial ? kmh / 1.609344 : kmh) + (imperial ? " mph" : " km/h"));
        speed.color(nav.limitKmh > 0 && kmh > nav.limitKmh + OVER_LIMIT_KMH ? Ui.danger(night) : Ui.accentText(night));
        limit.setVisibility(nav.limitKmh > 0 ? View.VISIBLE : View.GONE);
        if (nav.limitKmh > 0) {
            // OpenStreetMap's limits are in km/h here; in mph they were set in steps of 5
            limit.setText(String.valueOf(imperial ? Math.round(nav.limitKmh / 1.609344 / 5) * 5 : nav.limitKmh));
            limit.setContentDescription(Lang.t(R.string.nav_limit_description, limit.getText()) + (imperial ? " mph" : " km/h"));
        }
        if (!nav.road.contentEquals(road.getText())) road.setText(nav.road);
        java.util.List<double[]> c = nav.curves;
        double span = c == null || c.isEmpty() ? 0 : c.get(c.size() - 1)[1];
        curvesRow.setVisibility(span >= 1000 ? View.VISIBLE : View.GONE);
        if (span >= 1000) {
            curves.set(c, span);
            curvesLabel.setText(Lang.t(R.string.nav_next_winding, NavText.shown(span, imperial),
                    Math.round(Navigator.windingShare(c) * 100)));
        }
    }

    /** At the destination: the flag, the ride summed up, the whole route in view and Close. */
    private void showArrival(Hub.Arrival done, boolean imperial) {
        if (done == arrived) return;
        arrived = done;
        arrow.setTurn(43);
        distance.view.setVisibility(View.GONE);
        thenRow.setVisibility(View.GONE);
        showAlerts(null, null, imperial);
        showInfo(null, null, imperial);
        map.setProgress(0, null);
        action.setText(done.text);
        String s = Lang.t(R.string.nav_done, NavText.shown(done.riddenM, imperial), NavText.duration(done.elapsedMs / 1000.0));
        if (done.movingMs >= 60_000) {
            double kmh = done.riddenM / (done.movingMs / 1000.0) * (imperial ? 2.236936 : 3.6);
            s += " · " + Lang.t(R.string.nav_moving_average, kmh, imperial ? "mph" : "km/h");
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
