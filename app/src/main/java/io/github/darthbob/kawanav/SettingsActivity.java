package io.github.darthbob.kawanav;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Settings tab: starting by itself, what goes on the dash on the road, the dash icons and clock,
 * ride recording, offline maps, the app's light or dark theme, the debug tools (debug builds, seven
 * taps on the title), the legal notes and the log export. Other tabs open it at a section ({@link #EXTRA_SECTION}).
 */
public class SettingsActivity extends KawaActivity {

    private static final int[] LOOKAHEADS = {500, 1000, 1500, 2000};
    /** Distances a camera is announced from, metres. */
    private static final int[] CAMERA_WARNS = {300, 500, 800};

    /** "Also when too fast", index: the {@link CurveAlerts} pace. */
    private static String[] paceNames() {
        return new String[]{Lang.t(R.string.common_off), Lang.t(R.string.settings_pace_relaxed),
                Lang.t(R.string.settings_pace_normal), Lang.t(R.string.settings_pace_sporty),
                Lang.t(R.string.settings_pace_fast), Lang.t(R.string.settings_pace_from_rides)};
    }

    /** Explanation of "Also when too fast", with each pace's speeds from CurveAlerts. */
    private static String curvePaceInfo() {
        StringBuilder s = new StringBuilder(Lang.t(R.string.settings_pace_info_intro)).append('\n');
        String[] names = paceNames();
        for (int p = CurveAlerts.RELAXED; p <= CurveAlerts.FAST; p++) {
            double lean = CurveAlerts.PACE_LEAN[p];
            s.append("\n• ").append(Lang.t(R.string.settings_pace_info_line, names[p], lean,
                    CurveAlerts.okSpeed(30, lean) * 3.6, CurveAlerts.okSpeed(100, lean) * 3.6));
        }
        s.append("\n• ").append(Lang.t(R.string.settings_pace_info_rides, RideStats.BEND_RIDES, RideStats.MIN_BENDS,
                Ride.Bends.MIN_KMH, Ride.Bends.MIN_PEAK));
        return s.append("\n\n").append(Lang.t(R.string.settings_pace_info_too_fast,
                (CurveAlerts.TOO_FAST - 1) * 100, CurveAlerts.REACTION_S)).toString();
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private RoadStore roadStore;
    private HazardStore hazardStore;
    private OfflineRegions offlineRegions;
    /** Server data date per downloaded region (ms, 0 = unknown), checked once per app start. */
    private static final Map<String, Long> serverDates = new java.util.concurrent.ConcurrentHashMap<>();
    /** Section key to the view to scroll to. */
    private final Map<String, View> sections = new HashMap<>();
    private String pendingSection;

    // autostart
    private TextView autoBikeSub;
    private Button autoBikeButton;
    private boolean helmetsHidden; // the helmet list was built without Bluetooth permission
    // on the road
    private TextView freeModeStatus;
    /** Speaks "Hear a curve warning"; started on the first tap. */
    private CurveSound sampleSound;
    /** Under "Also when too fast": the lean from the rides, for "From my rides". */
    private TextView paceStatus;
    private RideStats.BendLean ridesLean;
    private boolean ridesLeanAsked;
    // offline
    private LinearLayout regionsList;
    private boolean offlineWasRunning;
    private TextView storedView;
    private AutoCompleteTextView regionInput;
    private Button regionButton;
    private TextView regionInfo;
    private ProgressBar offlineProgress;
    private TextView offlineStatus;
    private List<Geofabrik.Region> regions = new ArrayList<>();
    private Geofabrik.Region selectedRegion;
    // debug
    private LinearLayout debugCard;
    private LinearLayout bikeDebugCard;
    private LinearLayout notifDebugCard;
    private TextView notifStatus;
    private TextView debugBike;
    /** Debug: the ANCS category the dash-notification test sends. */
    private int ancsTest = 2;
    /** Debug: what the next capture mark says ({@link RawCapture#MARKS}). */
    private int markSel;
    private TextView debugIconsStatus;
    private TextView debugGatt;
    private int titleTaps;
    private long titleTapAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        Logger.init(this);
        prefs = Hub.prefs(this);
        roadStore = new RoadStore(this);
        hazardStore = new HazardStore(this);
        offlineRegions = new OfflineRegions(this, roadStore, hazardStore);
        setContentView(buildUi());
        pendingSection = getIntent().getStringExtra(EXTRA_SECTION);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        pendingSection = intent.getStringExtra(EXTRA_SECTION);
    }

    @Override
    protected void onResume() {
        super.onResume();
        BikePresence.invalidate(); // back from the system's Bluetooth settings: the link may be gone
        handler.post(refresh);
        if (pendingSection != null) {
            String s = pendingSection;
            pendingSection = null;
            View target = sections.get(s);
            if (target != null) pageScroll.post(() -> pageScroll.smoothScrollTo(0, Math.max(0, target.getTop() - dp(Ui.SPACE2))));
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (sampleSound != null) sampleSound.shutdown();
        super.onDestroy();
    }

    /**
     * Under "Also when too fast" with "From my rides" chosen: the lean the rides give, or why they give none
     * yet. Worked out once per screen, in the background (it reads the rides' files).
     */
    private void showPaceStatus(int pace) {
        if (pace != CurveAlerts.FROM_RIDES) {
            paceStatus.setVisibility(View.GONE);
            return;
        }
        paceStatus.setVisibility(View.VISIBLE);
        RideStats.BendLean l = ridesLean;
        if (l == null) {
            paceStatus.setText(Lang.t(R.string.settings_pace_looking));
            if (ridesLeanAsked) return;
            ridesLeanAsked = true;
            RideStore store = new RideStore(this);
            new Thread(() -> {
                RideStats.BendLean found = store.usualBendLean();
                runOnUiThread(() -> {
                    ridesLean = found;
                    showPaceStatus(prefs.getInt(Hub.KEY_CURVE_PACE, CurveAlerts.PACE_OFF));
                });
            }, "rides-lean").start();
        } else if (l.known()) {
            paceStatus.setText(Lang.plural(R.plurals.settings_pace_usual, l.rides,
                    Math.min(CurveAlerts.MAX_LEAN, l.degrees), l.bends, l.rides));
        } else {
            paceStatus.setText(Lang.t(R.string.settings_pace_counting, l.bends, RideStats.MIN_BENDS));
        }
    }

    /** "Hear a curve warning": what a sharp left bend gets, through whatever the phone plays on now. */
    private void playCurveSample() {
        if (sampleSound == null) sampleSound = new CurveSound(this);
        if (prefs.getBoolean(Hub.KEY_CURVE_BEEPS, false) || !sampleSound.canSpeak()) {
            sampleSound.beep(CurveBeeps.bends(3, false, -1));
        } else {
            sampleSound.say(NavText.current().curve(3));
        }
    }

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            updateUi();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        List<String> denied = requestCode == REQ_BG_LOCATION
                ? (hasBackgroundLocation() ? new ArrayList<>() : List.of(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                : missingPermissions();
        if (!denied.isEmpty()) {
            Logger.log("Permissions not granted: " + denied);
            // Android stops showing the request after it was declined: go to the app's settings instead
            boolean blocked = false;
            for (String d : denied) if (!shouldShowRequestPermissionRationale(d)) blocked = true;
            if (blocked) openAppSettings();
        }
        // Bluetooth just allowed: rebuild the screen so the helmet list shows the paired devices
        if (requestCode == REQ_PERMS && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED && helmetsHidden) {
            setContentView(buildUi());
        }
        updateUi();
    }

    // ------------------------------------------------------------------ UI

    private View buildUi() {
        night = isNight();
        sections.clear();
        LinearLayout root = column();
        root.setPadding(dp(Ui.SPACE4), 0, dp(Ui.SPACE4), dp(Ui.SPACE6));
        LinearLayout header = headerBar(Lang.t(R.string.settings_title), null, null);
        View title = header.findViewWithTag("title");
        if (Hub.DEBUG_TOOLS && title != null) title.setOnClickListener(v -> titleTapped());
        root.addView(header);

        root.addView(section("autostart", R.drawable.ic_power, Lang.t(R.string.settings_section_autostart), autostartGroup()));
        root.addView(section("road", R.drawable.ic_road, Lang.t(R.string.settings_section_road), roadGroup()));
        root.addView(section("navigation", R.drawable.ic_route, Lang.t(R.string.settings_section_navigation), navigationGroup()));
        root.addView(section("dash", R.drawable.ic_bell, Lang.t(R.string.settings_section_dash), dashGroup()));
        root.addView(section("recording", R.drawable.ic_route, Lang.t(R.string.settings_section_recording), recordingGroup()));
        root.addView(section("maps", R.drawable.ic_map, Lang.t(R.string.settings_section_maps), offlineGroup()));
        root.addView(section("appearance", R.drawable.ic_theme, Lang.t(R.string.settings_section_appearance), appearanceGroup()));
        if (Hub.DEBUG_TOOLS) root.addView(debugCard());
        if (Hub.DEBUG_TOOLS) root.addView(bikeDebugCard());
        if (Hub.DEBUG_TOOLS) root.addView(notifDebugCard());
        root.addView(section("about", R.drawable.ic_info, Lang.t(R.string.settings_section_about), aboutGroup()));
        return tabPage(TAB_SETTINGS, root);
    }

    /** A section: its header and its group, remembered under {@code key} for scrolling to it. */
    private LinearLayout section(String key, int drawable, String title, View group) {
        LinearLayout s = column();
        s.addView(sectionHeader(drawable, title));
        s.addView(group);
        sections.put(key, s);
        return s;
    }

    private LinearLayout autostartGroup() {
        LinearLayout c = group();
        LinearLayout bikeRow = settingRow();
        LinearLayout bt = column();
        bt.addView(itemTitle(Lang.t(R.string.settings_autostart_bike)));
        autoBikeSub = secondary("");
        bt.addView(autoBikeSub);
        bikeRow.addView(bt, weight());
        autoBikeButton = secondaryButton("", v -> toggleAutostart());
        bikeRow.addView(autoBikeButton);
        c.addView(bikeRow);

        LinearLayout helmetRow = settingRow();
        LinearLayout ht = column();
        ht.addView(itemTitle(Lang.t(R.string.settings_autostart_helmet)));
        ht.addView(secondary(Lang.t(R.string.settings_autostart_helmet_sub)));
        helmetRow.addView(ht, weight());
        helmetsHidden = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED;
        List<BluetoothDevice> helmets = helmetsHidden ? new ArrayList<>() : HelmetTrigger.candidates(this);
        String savedHelmet = prefs.getString(Hub.KEY_HELMET, null);
        List<String> helmetAddrs = new ArrayList<>();
        List<String> helmetLabels = new ArrayList<>();
        helmetAddrs.add(null);
        helmetLabels.add(Lang.t(R.string.common_off));
        for (BluetoothDevice d : helmets) {
            helmetAddrs.add(d.getAddress());
            helmetLabels.add(d.getName());
        }
        // a saved helmet Android does not list right now (Bluetooth permission missing, unpaired)
        // stays selected: the list's initial selection must not turn the trigger off
        if (savedHelmet != null && !helmetAddrs.contains(savedHelmet)) {
            helmetAddrs.add(savedHelmet);
            helmetLabels.add(Lang.t(R.string.settings_helmet_saved));
        }
        String[] helmetNames = helmetLabels.toArray(new String[0]);
        Spinner helmetSpinner = spinner(helmetNames, Math.max(0, helmetAddrs.indexOf(savedHelmet)));
        helmetSpinner.setOnItemSelectedListener(onSelect(pos -> {
            String addr = helmetAddrs.get(pos);
            if (java.util.Objects.equals(addr, prefs.getString(Hub.KEY_HELMET, null))) return;
            prefs.edit().putString(Hub.KEY_HELMET, addr).apply();
            Logger.log(addr == null ? "Helmet trigger off" : "Helmet trigger: " + helmetNames[pos]);
            if (addr != null) askBackgroundLocation();
        }));
        helmetRow.addView(helmetSpinner, controlWidth());
        addRow(c, helmetRow);
        return c;
    }

    /** KawaNav's own route planner and navigation. */
    private LinearLayout navigationGroup() {
        LinearLayout c = group();
        addRow(c, listRow(R.drawable.ic_route, Lang.t(R.string.settings_route_planner), Lang.t(R.string.settings_route_planner_sub),
                null, true, v -> startActivity(new Intent(this, PlannerActivity.class))));
        addRow(c, choiceRow(Lang.t(R.string.settings_roads), Lang.t(R.string.settings_roads_sub),
                new String[]{Lang.t(R.string.settings_roads_fastest), Lang.t(R.string.settings_roads_curvy), Lang.t(R.string.settings_roads_extra_curvy)},
                prefs.getInt(Hub.KEY_NAV_PROFILE, RouteProfile.Kind.CURVY.ordinal()),
                pos -> prefs.edit().putInt(Hub.KEY_NAV_PROFILE, pos).apply()));
        addRow(c, switchRow(Lang.t(R.string.settings_nav_voice), Lang.t(R.string.settings_nav_voice_sub),
                Hub.KEY_NAV_VOICE, true));
        addRow(c, switchRow(Lang.t(R.string.settings_avoid_motorways), Lang.t(R.string.settings_avoid_motorways_sub), Hub.KEY_NAV_AVOID_MOTORWAYS, false));
        addRow(c, switchRow(Lang.t(R.string.settings_avoid_tolls), Lang.t(R.string.settings_avoid_tolls_sub), Hub.KEY_NAV_AVOID_TOLLS, false));
        addRow(c, switchRow(Lang.t(R.string.settings_avoid_gravel), Lang.t(R.string.settings_avoid_gravel_sub), Hub.KEY_NAV_AVOID_UNPAVED, true));
        return c;
    }

    private LinearLayout roadGroup() {
        LinearLayout c = group();
        addRow(c, switchRow(Lang.t(R.string.settings_maps),
                Lang.t(R.string.settings_maps_sub), Hub.KEY_MAPS, true));

        Switch free = accentSwitch(Hub.freeMode(this));
        free.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(Hub.KEY_FREE_MODE, on).apply();
            BridgeService.settingsChanged();
        });
        LinearLayout fr = settingRow();
        LinearLayout ft = column();
        ft.addView(itemTitle(Lang.t(R.string.settings_free_mode)));
        ft.addView(secondary(Lang.t(R.string.settings_free_mode_sub)));
        freeModeStatus = secondary("");
        freeModeStatus.setTextColor(Ui.accentText(night));
        ft.addView(freeModeStatus);
        fr.addView(ft, weight());
        fr.addView(free);
        addRow(c, fr);

        addRow(c, choiceRow(Lang.t(R.string.settings_nav_curves), Lang.t(R.string.settings_nav_curves_sub),
                new String[]{Lang.t(R.string.common_off), Lang.t(R.string.settings_nav_curves_sharp), Lang.t(R.string.settings_nav_curves_all)}, prefs.getInt(Hub.KEY_NAV_CURVES, 0), pos -> {
                    prefs.edit().putInt(Hub.KEY_NAV_CURVES, pos).apply();
                    BridgeService.settingsChanged();
                }));
        addRow(c, choiceRow(Lang.t(R.string.settings_curve_sound), Lang.t(R.string.settings_curve_sound_sub),
                Lang.t(R.string.settings_curve_sound_info), new String[]{Lang.t(R.string.common_off), Lang.t(R.string.settings_curve_sound_hairpins), Lang.t(R.string.settings_curve_sound_sharp), Lang.t(R.string.settings_curve_sound_all)},
                prefs.getInt(Hub.KEY_CURVE_SOUND, CurveAlerts.OFF), pos -> {
                    prefs.edit().putInt(Hub.KEY_CURVE_SOUND, pos).apply();
                    BridgeService.settingsChanged();
                }));
        paceStatus = secondary("");
        paceStatus.setTextColor(Ui.accentText(night));
        int pace = prefs.getInt(Hub.KEY_CURVE_PACE, CurveAlerts.PACE_OFF);
        addRow(c, choiceRow(Lang.t(R.string.settings_pace), Lang.t(R.string.settings_pace_sub), curvePaceInfo(), paceStatus, paceNames(), pace, pos -> {
                    prefs.edit().putInt(Hub.KEY_CURVE_PACE, pos).apply();
                    BridgeService.settingsChanged();
                    showPaceStatus(pos);
                }));
        showPaceStatus(pace);
        addRow(c, switchRow(Lang.t(R.string.settings_headset_only), Lang.t(R.string.settings_headset_only_sub), Hub.KEY_CURVE_SOUND_HEADSET, true));
        addRow(c, switchRow(Lang.t(R.string.settings_beeps), Lang.t(R.string.settings_beeps_sub), Hub.KEY_CURVE_BEEPS, false));
        LinearLayout sr = settingRow();
        LinearLayout st = column();
        st.addView(itemTitle(Lang.t(R.string.settings_hear_sample)));
        st.addView(secondary(Lang.t(R.string.settings_hear_sample_sub, NavText.current().curve(3))));
        sr.addView(st, weight());
        sr.addView(secondaryButton(Lang.t(R.string.settings_play), v -> playCurveSample()));
        addRow(c, sr);
        addRow(c, choiceRow(Lang.t(R.string.settings_curves), Lang.t(R.string.settings_curves_sub), Lang.t(R.string.settings_curves_info),
                new String[]{Lang.t(R.string.settings_curves_tight), Lang.t(R.string.settings_pace_normal), Lang.t(R.string.settings_curves_gentle), Lang.t(R.string.settings_curves_all)}, prefs.getInt(Hub.KEY_SENSITIVITY, 1),
                pos -> prefs.edit().putInt(Hub.KEY_SENSITIVITY, pos).apply()));
        String[] la = new String[LOOKAHEADS.length];
        int laSel = 1;
        for (int i = 0; i < LOOKAHEADS.length; i++) {
            la[i] = Lang.t(R.string.unit_m, LOOKAHEADS[i]);
            if (LOOKAHEADS[i] == prefs.getInt(Hub.KEY_LOOKAHEAD, 1000)) laSel = i;
        }
        addRow(c, choiceRow(Lang.t(R.string.settings_lookahead), Lang.t(R.string.settings_lookahead_sub), la, laSel,
                pos -> prefs.edit().putInt(Hub.KEY_LOOKAHEAD, LOOKAHEADS[pos]).apply()));
        addRow(c, switchRow(Lang.t(R.string.settings_imperial), Lang.t(R.string.settings_imperial_sub), Hub.KEY_IMPERIAL, false));
        addRow(c, choiceRow(Lang.t(R.string.settings_fuel_unit), Lang.t(R.string.settings_fuel_unit_sub),
                FuelEconomy.LABELS, prefs.getInt(Hub.KEY_FUEL_UNIT, FuelEconomy.DEFAULT),
                pos -> prefs.edit().putInt(Hub.KEY_FUEL_UNIT, pos).apply()));
        addRow(c, switchRow(Lang.t(R.string.settings_left_hand), Lang.t(R.string.settings_left_hand_sub), Hub.KEY_LEFT_HAND, false));
        addRow(c, switchRow(Lang.t(R.string.settings_battery_warnings), Lang.t(R.string.settings_battery_warnings_sub), Hub.KEY_BATTERY_WARNINGS, true));
        addRow(c, switchRow(Lang.t(R.string.settings_clutch), Lang.t(R.string.settings_clutch_sub), Hub.KEY_CLUTCH_WARNINGS, true));
        addRow(c, switchRow(Lang.t(R.string.settings_cold_engine), Lang.t(R.string.settings_cold_engine_sub, NavText.current().coldEngine()),
                Hub.KEY_COLD_ENGINE, false));
        addRow(c, choiceRow(Lang.t(R.string.settings_cold_rpm), Lang.t(R.string.settings_cold_rpm_sub), ColdEngine.rpmLabels(),
                ColdEngine.indexOf(ColdEngine.RPM_CHOICES, prefs.getInt(Hub.KEY_COLD_ENGINE_RPM, ColdEngine.DEFAULT_RPM),
                        ColdEngine.DEFAULT_RPM),
                pos -> prefs.edit().putInt(Hub.KEY_COLD_ENGINE_RPM, ColdEngine.RPM_CHOICES[pos]).apply()));
        addRow(c, choiceRow(Lang.t(R.string.settings_cold_warm), Lang.t(R.string.settings_cold_warm_sub),
                ColdEngine.warmLabels(), ColdEngine.indexOf(ColdEngine.WARM_C_CHOICES,
                        prefs.getInt(Hub.KEY_COLD_ENGINE_C, ColdEngine.DEFAULT_WARM_C), ColdEngine.DEFAULT_WARM_C),
                pos -> prefs.edit().putInt(Hub.KEY_COLD_ENGINE_C, ColdEngine.WARM_C_CHOICES[pos]).apply()));
        addRow(c, switchRow(Lang.t(R.string.settings_cameras), Lang.t(R.string.settings_cameras_info), Hub.KEY_CAMERAS, false,
                on -> BridgeService.settingsChanged()));
        String[] warn = new String[CAMERA_WARNS.length];
        int warnSel = 1;
        for (int i = 0; i < CAMERA_WARNS.length; i++) {
            warn[i] = Lang.t(R.string.unit_m, CAMERA_WARNS[i]);
            if (CAMERA_WARNS[i] == prefs.getInt(Hub.KEY_CAMERA_WARN, Hub.CAMERA_WARN_DEFAULT)) warnSel = i;
        }
        addRow(c, choiceRow(Lang.t(R.string.settings_camera_warn), Lang.t(R.string.settings_camera_warn_sub),
                warn, warnSel, pos -> prefs.edit().putInt(Hub.KEY_CAMERA_WARN, CAMERA_WARNS[pos]).apply()));
        addRow(c, switchRow(Lang.t(R.string.settings_camera_voice), Lang.t(R.string.settings_camera_voice_sub), Hub.KEY_CAMERA_VOICE, false));
        return c;
    }

    private LinearLayout dashGroup() {
        LinearLayout c = group();
        // what the dash said it can do on the last connection: say so instead of doing nothing quietly
        BikeFrames.GeneralCaps caps = Hub.bike.caps;
        String noIcons = caps != null && !caps.statusIcons ? ". " + Lang.t(R.string.settings_dash_no_icons) : "";
        String noClock = caps != null && !caps.clockSettable() ? ". " + Lang.t(R.string.settings_dash_no_clock) : "";
        addRow(c, switchRow(Lang.t(R.string.settings_ignore_caps), Lang.t(R.string.settings_ignore_caps_sub), Hub.KEY_IGNORE_CAPS, false, on -> BridgeService.settingsChanged()));
        addRow(c, switchRow(Lang.t(R.string.settings_phone_battery), Lang.t(R.string.settings_phone_battery_sub)
                + noIcons, Hub.KEY_PHONE_BATTERY, true));
        addRow(c, switchRow(Lang.t(R.string.settings_clock), Lang.t(R.string.settings_clock_sub) + noClock, Hub.KEY_CLOCK_SYNC, false));
        addRow(c, switchRow(Lang.t(R.string.settings_dash_calls), Lang.t(R.string.settings_dash_calls_sub),
                Hub.KEY_DASH_CALLS, false, v -> MapsListener.alertsChanged(this)));
        addRow(c, switchRow(Lang.t(R.string.settings_dash_mail), Lang.t(R.string.settings_dash_mail_sub),
                Hub.KEY_DASH_MAIL, false, v -> MapsListener.alertsChanged(this)));
        return c;
    }

    private LinearLayout recordingGroup() {
        LinearLayout c = group();
        addRow(c, switchRow(Lang.t(R.string.settings_ride_auto), Lang.t(R.string.settings_ride_auto_sub),
                Hub.KEY_RIDE_AUTO, false));
        addRow(c, choiceRow(Lang.t(R.string.settings_ride_end), Lang.t(R.string.settings_ride_end_sub),
                RideEnd.labels(), prefs.getInt(Hub.KEY_RIDE_END, RideEnd.DEFAULT),
                pos -> prefs.edit().putInt(Hub.KEY_RIDE_END, pos).apply()));
        addRow(c, switchRow(Lang.t(R.string.settings_ride_map), Lang.t(R.string.settings_ride_map_sub),
                Hub.KEY_RIDE_MAP, true));
        addRow(c, listRow(R.drawable.ic_list, Lang.t(R.string.settings_rides), Lang.t(R.string.settings_rides_sub),
                null, true, v -> openTab(TAB_RIDES, null)));
        return c;
    }

    private LinearLayout offlineGroup() {
        LinearLayout c = group();
        LinearLayout intro = column();
        intro.setPadding(0, dp(10), 0, dp(6));
        intro.addView(secondary(Lang.t(R.string.settings_maps_intro)));
        intro.addView(note(Lang.t(R.string.settings_maps_credit)));
        c.addView(intro);
        storedView = itemTitle("");
        storedView.setPadding(0, dp(10), 0, dp(4));
        c.addView(storedView);
        regionsList = column();
        c.addView(regionsList);

        TextView add = itemTitle(Lang.t(R.string.settings_download_region));
        add.setPadding(0, dp(14), 0, dp(4));
        c.addView(add);
        regionInput = new AutoCompleteTextView(this);
        regionInput.setHint(Lang.t(R.string.settings_region_hint));
        regionInput.setThreshold(1);
        regionInput.setSingleLine(true);
        regionInput.setTextSize(15);
        regionInput.setTextColor(Ui.ink(night));
        regionInput.setHintTextColor(Ui.inkMuted(night));
        regionInput.setOnItemClickListener((parent, v, pos, id) -> {
            String label = (String) parent.getItemAtPosition(pos);
            for (Geofabrik.Region r : regions) if (r.label().equals(label)) selectRegion(r);
            hideKeyboard();
        });
        c.addView(regionInput, matchWrap());
        regionInfo = secondary("");
        c.addView(regionInfo);

        offlineProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        offlineProgress.setIndeterminate(true);
        offlineProgress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.accent(night)));
        c.addView(offlineProgress, matchWrap());
        offlineStatus = secondary("");
        c.addView(offlineStatus);

        LinearLayout buttons = row();
        buttons.setPadding(0, dp(6), 0, dp(10));
        regionButton = primaryButton(Lang.t(R.string.settings_download), v -> downloadRegion());
        buttons.addView(regionButton, weight());
        c.addView(buttons);
        loadRegions();
        refreshRegions();
        return c;
    }

    private LinearLayout appearanceGroup() {
        LinearLayout c = group();
        String[] langs = new String[Lang.CODES.length + 1];
        langs[0] = Lang.t(R.string.settings_language_system);
        System.arraycopy(Lang.NAMES, 0, langs, 1, Lang.NAMES.length);
        addRow(c, choiceRow(Lang.t(R.string.settings_language), Lang.t(R.string.settings_language_sub), langs,
                Lang.chosen(this), pos -> {
                    if (pos == Lang.chosen(this)) return; // the spinner's first call
                    Lang.choose(this, pos); // the open screens are recreated in the new language
                }));
        addRow(c, choiceRow(Lang.t(R.string.settings_theme), Lang.t(R.string.settings_theme_sub), Ui.themeLabels(),
                prefs.getInt(Hub.KEY_THEME, Ui.THEME_SYSTEM), pos -> {
                    if (pos == prefs.getInt(Hub.KEY_THEME, Ui.THEME_SYSTEM)) return; // the spinner's first call
                    prefs.edit().putInt(Hub.KEY_THEME, pos).apply();
                    Ui.applyTheme(this, pos); // the open screens are recreated in the new colours
                }));
        return c;
    }

    /** Legal notes: riding safety, affiliation, map data credit, licence (GPL-3.0 §5d), version; the log export. */
    private LinearLayout aboutGroup() {
        LinearLayout c = group();
        TextView t = note(Lang.t(R.string.main_riding_notice) + "\n\n" + Lang.t(R.string.main_affiliation_notice)
                + "\n\n" + Lang.t(R.string.settings_about_data) + "\n\n" + Lang.t(R.string.settings_about_licence)
                + "\n\n" + Lang.t(R.string.settings_about_version, BuildConfig.VERSION_NAME) + (Hub.DEBUG_TOOLS ? " (debug)" : ""));
        t.setPadding(0, dp(Ui.SPACE3), 0, dp(Ui.SPACE3));
        t.setAutoLinkMask(android.text.util.Linkify.WEB_URLS);
        t.setLinkTextColor(Ui.accentText(night));
        c.addView(t);
        addRow(c, listRow(R.drawable.ic_download, Lang.t(R.string.settings_export_log), Lang.t(R.string.settings_export_log_sub), null, false, v -> exportLog()));
        return c;
    }

    /**
     * "Export the log", in every build: the log only. The notification capture holds other apps' messages
     * and leaves the phone only from the debug card's export.
     */
    private void exportLog() {
        Logger.withLogFiles(() -> {
            int n = LogExport.export(this, false);
            runOnUiThread(() -> android.widget.Toast.makeText(this, n == 0 ? Lang.t(R.string.settings_log_none)
                    : Lang.plural(R.plurals.settings_log_files, n), android.widget.Toast.LENGTH_SHORT).show());
        });
    }

    // ------------------------------------------------------------------ debug

    /** Debug builds: seven taps on the title show or hide the debug cards. */
    private void titleTapped() {
        long now = System.currentTimeMillis();
        titleTaps = now - titleTapAt < 1000 ? titleTaps + 1 : 1;
        titleTapAt = now;
        if (titleTaps < 7) return;
        titleTaps = 0;
        boolean show = !prefs.getBoolean(Hub.KEY_DEBUG_ICONS, false);
        prefs.edit().putBoolean(Hub.KEY_DEBUG_ICONS, show).apply();
        debugCard.setVisibility(show ? View.VISIBLE : View.GONE);
        bikeDebugCard.setVisibility(show ? View.VISIBLE : View.GONE);
        notifDebugCard.setVisibility(show ? View.VISIBLE : View.GONE);
        android.widget.Toast.makeText(this, show ? "Debug options shown" : "Debug options hidden",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    /** Hidden card to try values of the meter's status icon fields (13 0C frame, see PROTOCOL.md). */
    private LinearLayout debugCard() {
        LinearLayout c = card();
        LinearLayout head = row();
        head.addView(cardTitle("Dash icons"), weight());
        head.addView(debugPill());
        c.addView(head);
        c.addView(secondary("Fixed values for the status icon frame, sent every 5 s while the bike is connected. "
                + "Auto: phone battery, helmet connected, voice and riding log 0. Tap the title 7 times to hide."));
        c.addView(iconRow("Battery", Hub.KEY_ICON_BATTERY, "0 low, 1–3 bars, 7–10 charging 0–3 bars, 14 error, 15 none"));
        c.addView(iconRow("Voice", Hub.KEY_ICON_VOICE, "0 idle, 1 wake word, 2 listening, 3 processing, 14 error, 15 none"));
        c.addView(iconRow("Headset", Hub.KEY_ICON_HEADSET, "0 off, 1 on, 2 blinking, 3 none"));
        c.addView(iconRow("Riding log", Hub.KEY_ICON_RIDING_LOG, "0 off, 1 recording, 2 paused, 14 error, 15 none"));
        c.addView(phoneNameRow());
        debugIconsStatus = secondary("");
        debugIconsStatus.setTypeface(Typeface.MONOSPACE);
        debugIconsStatus.setPadding(0, dp(10), 0, 0);
        c.addView(debugIconsStatus);
        c.addView(itemTitle("Bluetooth services of the bike"));
        c.addView(secondary("Listed on each connect while this card is shown. Read-only: only standard values "
                + "like the firmware version are read, nothing is written."));
        debugGatt = secondary("");
        debugGatt.setTypeface(Typeface.MONOSPACE);
        debugGatt.setTextIsSelectable(true);
        c.addView(debugGatt);
        c.setVisibility(prefs.getBoolean(Hub.KEY_DEBUG_ICONS, false) ? View.VISIBLE : View.GONE);
        debugCard = c;
        return c;
    }

    /**
     * Hidden card to write down what other apps put in their notifications, for a navigation app that
     * cannot be read like Google Maps (Waze). The file leaves the phone with the log export.
     */
    private LinearLayout notifDebugCard() {
        LinearLayout c = card();
        LinearLayout head = row();
        head.addView(cardTitle("Notifications"), weight());
        head.addView(debugPill());
        c.addView(head);
        c.addView(secondary("Writes down what other apps put in their notifications, to check what a navigation "
                + "app shows there before KawaNav learns to read it. It copies the text of those notifications, "
                + "messages included: switch it off after the test and read the file before sending it."));
        c.addView(switchRow("Capture notifications", "Waze, and anything posted with adb. The file is under "
                + "Android/data/" + getPackageName() + "/files/notifications", Hub.KEY_DEBUG_NOTIF_CAPTURE, false,
                on -> { if (!on) NotifCapture.stop(); }));
        c.addView(switchRow("Every app", "Also log every other app's notifications (more noise, more text)",
                Hub.KEY_DEBUG_NOTIF_CAPTURE_ALL, false));
        notifStatus = secondary("");
        notifStatus.setTypeface(Typeface.MONOSPACE);
        notifStatus.setPadding(0, dp(10), 0, 0);
        c.addView(notifStatus);
        Button export = secondaryButton("Export the log and the capture", v -> Logger.withLogFiles(() -> {
            int n = LogExport.export(this);
            runOnUiThread(() -> android.widget.Toast.makeText(this, n + " files in Download/KawaNav",
                    android.widget.Toast.LENGTH_SHORT).show());
        }));
        addRow(c, export);
        c.setVisibility(prefs.getBoolean(Hub.KEY_DEBUG_ICONS, false) ? View.VISIBLE : View.GONE);
        notifDebugCard = c;
        return c;
    }

    /**
     * Hidden card for the vehicle data: which requests go out after connecting, frame capture, and
     * everything decoded so far next to the raw frames, to check against the dash.
     */
    private LinearLayout bikeDebugCard() {
        LinearLayout c = card();
        LinearLayout dbgHead = row();
        dbgHead.addView(cardTitle("Bike data"), weight());
        dbgHead.addView(debugPill());
        c.addView(dbgHead);
        c.addView(secondary("Decoded values next to the last raw frame of each type. Compare them with the dash "
                + "to check the scaling (PROTOCOL.md)."));
        c.addView(listRow(R.drawable.ic_list, "Field tests", "What is still to be tried at the bike, with the "
                + "tyre numbers of the latest rides", null, true,
                v -> startActivity(new Intent(this, FieldTestsActivity.class))));
        c.addView(choiceRow("Requests after connecting", "Applies on the next connect. Full is Rideology's sequence: "
                        + "it adds the phone name and the 0x08 init",
                new String[]{"Capabilities only (v0.3)", "Read-only", "Full (like Rideology)"}, Hub.initLevel(this),
                pos -> prefs.edit().putInt(Hub.KEY_DEBUG_INIT_LEVEL, pos).apply()));
        c.addView(switchRow("Skip the riding log request", "Read-only connect and ride start leave out 45 00 00 "
                + "(as KawaNav 0.6 and before did; Rideology always sends it)", Hub.KEY_DEBUG_NO_EXT, false));
        c.addView(switchRow("Capture frames", "Every frame to and from the bike, one file per connect, from "
                + "now on if the bike is connected: adb pull /sdcard/Android/data/" + getPackageName()
                + "/files/captures/", Hub.KEY_DEBUG_CAPTURE, false, BridgeService::captureChanged));
        c.addView(markRow());
        LinearLayout reads = row();
        reads.setPadding(0, dp(6), 0, 0);
        for (int[] r : new int[][]{{BikeFrames.VEHICLE_INFO}, {BikeFrames.VEHICLE_SETTINGS}, {BikeFrames.SERVICE_INDICATOR}}) {
            int id = r[0];
            Button b = button("Read " + String.format(Locale.ROOT, "%02X", id), v -> android.widget.Toast.makeText(this,
                    BridgeService.read(id) ? "Asked the bike for " + BikeFrames.name(id) : "Bike not connected",
                    android.widget.Toast.LENGTH_SHORT).show());
            b.setTextColor(Ui.accentText(night));
            outline(b);
            LinearLayout.LayoutParams lp = weight();
            lp.rightMargin = dp(Ui.SPACE1);
            reads.addView(b, lp);
        }
        c.addView(reads);
        c.addView(ancsTestRow());
        debugBike = secondary("");
        debugBike.setTypeface(Typeface.MONOSPACE);
        debugBike.setTextIsSelectable(true);
        debugBike.setPadding(0, dp(10), 0, 0);
        c.addView(debugBike);
        c.setVisibility(prefs.getBoolean(Hub.KEY_DEBUG_ICONS, false) ? View.VISIBLE : View.GONE);
        bikeDebugCard = c;
        return c;
    }

    /**
     * Debug: marks a moment in the frame capture ("High beam on", "Horn" …), tapped as it is done at
     * the bike, so tools/frame_bytes.py can show which bytes changed with it: the way to learn what the
     * bytes and frames are that nobody decodes (0x30, the unused parts of 0x4A/0x4B …).
     */
    private View markRow() {
        LinearLayout box = column();
        box.addView(choiceRow("Test mark", "With the capture on and the bike connected: pick what you are about "
                + "to do, do it, tap Mark at that moment. At least 15 s between marks", RawCapture.MARKS, markSel,
                pos -> markSel = pos));
        Button mark = button("Mark now", v -> {
            String label = RawCapture.MARKS[markSel];
            String msg;
            if (!RawCapture.enabled(this)) {
                msg = "Switch \"Capture frames\" on first";
            } else if (!RawCapture.mark(label)) {
                msg = "Bike not connected: the capture starts when it connects";
            } else {
                Logger.log("Capture mark: " + label);
                msg = "Marked: " + label;
            }
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
        });
        mark.setTextColor(Ui.accentText(night));
        outline(mark);
        box.addView(mark, matchWrap());
        return box;
    }

    /**
     * Debug: sends a dash notification (0x12) of any ANCS category, to see whether the dash shows anything
     * for the ones Rideology never sends (missed call, social, schedule …). Display only: nothing is set.
     */
    private View ancsTestRow() {
        LinearLayout box = column();
        String[] names = new String[DashAlerts.ANCS.length];
        for (int i = 0; i < names.length; i++) names[i] = DashAlerts.ancsName(i);
        box.addView(choiceRow("Dash notification test", "Rideology only sends 1 (call) and 6 (mail). Show one, "
                + "look at the dash, then clear it", names, ancsTest, pos -> ancsTest = pos));
        LinearLayout r = row();
        for (int event : new int[]{DashAlerts.ADDED, DashAlerts.REMOVED}) {
            Button b = button(event == DashAlerts.ADDED ? "Show" : "Clear", v -> android.widget.Toast.makeText(this,
                    BridgeService.sendDashAlert(ancsTest, event) ? "Sent: " + DashAlerts.describe(ancsTest, event)
                            : "Bike not connected", android.widget.Toast.LENGTH_SHORT).show());
            b.setTextColor(Ui.accentText(night));
            outline(b);
            LinearLayout.LayoutParams lp = weight();
            lp.rightMargin = dp(Ui.SPACE1);
            r.addView(b, lp);
        }
        box.addView(r);
        return box;
    }

    /** Debug: text the meter gets as the phone's name (Bluetooth info on the dash?). */
    private View phoneNameRow() {
        LinearLayout box = column();
        box.setPadding(0, dp(10), 0, 0);
        box.addView(itemTitle("Phone name on the dash"));
        box.addView(secondary("Sent now and on every connect, up to " + KawaProtocol.PHONE_NAME_BYTES
                + " bytes (UTF-8). Empty: nothing is sent."));
        LinearLayout r = row();
        android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setTextSize(15);
        input.setTextColor(Ui.ink(night));
        input.setHint("e.g. Hello Z900");
        input.setText(prefs.getString(Hub.KEY_DEBUG_PHONE_NAME, ""));
        r.addView(input, weight());
        Button send = button("Send", v -> {
            String name = input.getText().toString().trim();
            prefs.edit().putString(Hub.KEY_DEBUG_PHONE_NAME, name).apply();
            hideKeyboard();
            String msg;
            if (name.isEmpty()) {
                msg = "Cleared: no name is sent";
            } else if (BridgeService.sendPhoneName()) {
                int bytes = KawaProtocol.phoneNameBytes(name).length;
                msg = "Sent" + (bytes < name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        ? " (cut to " + bytes + " bytes)" : "");
            } else {
                msg = "Saved: sent when the bike connects";
            }
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
        });
        send.setTextColor(Ui.accentText(night));
        outline(send);
        r.addView(send);
        box.addView(r);
        return box;
    }

    private View iconRow(String title, String key, String values) {
        String[] items = new String[17];
        items[0] = "Auto";
        for (int i = 0; i < 16; i++) items[i + 1] = String.valueOf(i);
        return choiceRow(title, values, items, Hub.iconSetting(this, key) + 1, pos -> {
            prefs.edit().putInt(key, pos - 1).apply();
            BridgeService.settingsChanged();
        });
    }

    // ------------------------------------------------------------------ refresh

    private void updateUi() {
        updateTabBar();
        if (debugCard != null && debugCard.getVisibility() == View.VISIBLE) {
            BikeFrames.GeneralCaps caps = Hub.bikeCapabilities;
            debugIconsStatus.setText("Bike display: " + (caps == null ? "–" : "turn-by-turn "
                    + (caps.turnByTurn ? "yes" : "no") + ", status icons " + (caps.statusIcons ? "yes" : "no"))
                    + "\nLast sent: " + Hub.iconsStatus);
            String gatt = Hub.gattDump.isEmpty() ? "Not read yet: connect the bike with this card shown." : Hub.gattDump;
            if (!gatt.contentEquals(debugGatt.getText())) debugGatt.setText(gatt); // keeps a text selection
            String bike = "MTU " + Hub.bikeMtu + "\n" + Hub.bike.describe(System.currentTimeMillis());
            if (!debugBike.hasSelection() && !bike.contentEquals(debugBike.getText())) debugBike.setText(bike);
        }
        if (notifDebugCard != null && notifDebugCard.getVisibility() == View.VISIBLE) {
            String status = (NotifCapture.enabled(this) ? "Capturing" : Lang.t(R.string.common_off)) + ": " + NotifCapture.status(this);
            if (!status.contentEquals(notifStatus.getText())) notifStatus.setText(status);
        }

        // --- autostart
        android.companion.AssociationInfo bike = BikePresence.association(this);
        autoBikeSub.setText(bike != null ? Lang.t(R.string.settings_autostart_on_for, bike.getDisplayName())
                : Lang.t(R.string.settings_autostart_set_up_sub));
        autoBikeButton.setText(bike != null ? Lang.t(R.string.settings_autostart_turn_off) : Lang.t(R.string.settings_autostart_set_up));

        // --- on the road
        freeModeStatus.setVisibility(Hub.serviceRunning && Hub.freeMode(this) ? View.VISIBLE : View.GONE);
        freeModeStatus.setText(Hub.curveStatus.contains("·") ? Lang.t(R.string.settings_free_mode_active) : Hub.curveStatus);

        // --- offline
        if (offlineWasRunning && !Hub.offlineRunning) refreshRegions(); // a download just ended
        offlineWasRunning = Hub.offlineRunning;
        offlineProgress.setVisibility(Hub.offlineRunning ? View.VISIBLE : View.GONE);
        boolean showStatus = Hub.offlineRunning || !"–".equals(Hub.offlineStatus);
        offlineStatus.setVisibility(showStatus ? View.VISIBLE : View.GONE);
        offlineStatus.setText(Hub.offlineStatus);
        regionButton.setText(Hub.offlineRunning ? Lang.t(R.string.common_cancel) : Lang.t(R.string.settings_download));
        if (Hub.offlineRunning) neutral(regionButton);
        else primary(regionButton);
    }

    private void toggleAutostart() {
        if (BikePresence.enabled(this)) {
            BikePresence.disable(this);
            return;
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestMissingPermissions();
            return;
        }
        BluetoothDevice paired = BridgeService.pairedBike(this);
        if (paired == null) {
            BikePresence.pair(this); // not paired yet: pair and set up autostart in one go
        } else {
            BikePresence.associate(this, paired);
        }
    }

    // ------------------------------------------------------------------ offline maps

    /** Rebuilds the list of downloaded regions; sizes and update checks run in the background. */
    private void refreshRegions() {
        new Thread(() -> {
            List<OfflineRegions.Region> list = offlineRegions.list();
            Map<String, Long> sizes = new HashMap<>();
            for (OfflineRegions.Region r : list) sizes.put(r.id, offlineRegions.bytes(r.tiles));
            java.util.Set<String> other = offlineRegions.unassigned();
            long otherBytes = offlineRegions.bytes(other);
            runOnUiThread(() -> showRegions(list, sizes, otherBytes));
            boolean checked = false;
            for (OfflineRegions.Region r : list) {
                if (serverDates.containsKey(r.id)) continue;
                serverDates.put(r.id, Geofabrik.head(r.pbfUrl)[1]);
                checked = true;
            }
            if (checked) runOnUiThread(() -> showRegions(list, sizes, otherBytes));
        }, "regions-list").start();
    }

    private void showRegions(List<OfflineRegions.Region> list, Map<String, Long> sizes, long otherBytes) {
        regionsList.removeAllViews();
        long total = otherBytes;
        for (Long b : sizes.values()) total += b;
        storedView.setText(list.isEmpty() && otherBytes == 0 ? Lang.t(R.string.settings_regions_none)
                : Lang.t(R.string.settings_regions_stored, Math.max(1, total / 1e6)));
        java.text.DateFormat df = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM);
        for (OfflineRegions.Region r : list) {
            Long server = serverDates.get(r.id);
            // a region from before the camera or the navigation data is worth downloading again even without newer data
            boolean routing = offlineRegions.hasRouting(r.id);
            boolean update = (server != null && server - r.dataDate >= OfflineRegions.UPDATE_AFTER_MS) || !r.hazards || !routing;
            String state = server == null ? Lang.t(R.string.settings_region_checking)
                    : server == 0 ? Lang.t(R.string.settings_region_check_failed)
                    : update ? Lang.t(R.string.settings_region_newer, df.format(new java.util.Date(server)))
                    : Lang.t(R.string.settings_region_up_to_date);
            String sub = Lang.t(R.string.settings_region_data, df.format(new java.util.Date(r.dataDate)),
                    Math.max(1, sizes.get(r.id) / 1e6)) + "\n" + state
                    + (r.hazards ? "" : "\n" + Lang.t(R.string.settings_region_no_cameras))
                    + (routing ? "" : "\n" + Lang.t(R.string.settings_region_no_routing));
            regionsList.addView(regionRow(r.label, sub, update ? () -> updateRegion(r) : null,
                    () -> confirmDeleteRegion(r)));
        }
        if (otherBytes > 0) {
            regionsList.addView(regionRow(Lang.t(R.string.settings_other_roads),
                    Lang.t(R.string.settings_other_roads_sub, Math.max(1, otherBytes / 1e6)),
                    null, this::confirmDeleteOther));
        }
    }

    private View regionRow(String title, String sub, Runnable onUpdate, Runnable onDelete) {
        LinearLayout r = settingRow();
        LinearLayout t = column();
        t.addView(itemTitle(title));
        t.addView(secondary(sub));
        r.addView(t, weight());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(6), 0, 0, 0);
        if (onUpdate != null) r.addView(primaryButton(Lang.t(R.string.settings_update), v -> onUpdate.run()), lp);
        r.addView(secondaryButton(Lang.t(R.string.common_delete), v -> onDelete.run()), lp);
        return r;
    }

    private void updateRegion(OfflineRegions.Region r) {
        if (Hub.offlineRunning) return;
        for (Geofabrik.Region g : regions) {
            if (g.id.equals(r.id)) {
                startDownload(g);
                return;
            }
        }
        startDownload(r.id, r.label);
    }

    private void confirmDeleteRegion(OfflineRegions.Region r) {
        if (Hub.offlineRunning) return;
        new AlertDialog.Builder(this)
                .setTitle(Lang.t(R.string.settings_delete_region_title, r.label))
                .setMessage(Lang.t(R.string.settings_delete_region_message))
                .setPositiveButton(Lang.t(R.string.common_delete), (d, w) -> new Thread(() -> {
                    offlineRegions.delete(r.id);
                    serverDates.remove(r.id);
                    runOnUiThread(this::refreshRegions);
                }, "region-delete").start())
                .setNegativeButton(Lang.t(R.string.common_cancel), null)
                .show();
    }

    private void confirmDeleteOther() {
        if (Hub.offlineRunning) return;
        new AlertDialog.Builder(this)
                .setTitle(Lang.t(R.string.settings_delete_other_title))
                .setMessage(Lang.t(R.string.settings_delete_other_message))
                .setPositiveButton(Lang.t(R.string.common_delete), (d, w) -> new Thread(() -> {
                    offlineRegions.deleteUnassigned();
                    runOnUiThread(this::refreshRegions);
                }, "region-delete").start())
                .setNegativeButton(Lang.t(R.string.common_cancel), null)
                .show();
    }

    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager imm = getSystemService(android.view.inputmethod.InputMethodManager.class);
        imm.hideSoftInputFromWindow(regionInput.getWindowToken(), 0);
        regionInput.clearFocus();
    }

    private void loadRegions() {
        regionInfo.setText("");
        new Thread(() -> {
            try {
                List<Geofabrik.Region> list = Geofabrik.regions(this);
                List<String> labels = new ArrayList<>();
                for (Geofabrik.Region r : list) labels.add(r.label());
                runOnUiThread(() -> {
                    regions = list;
                    regionInput.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, labels));
                    regionInfo.setText("");
                });
            } catch (Exception e) {
                runOnUiThread(() -> regionInfo.setText(Lang.t(R.string.settings_regions_offline)));
            }
        }, "regions").start();
    }

    private void selectRegion(Geofabrik.Region r) {
        selectedRegion = r;
        regionInfo.setText(Lang.t(R.string.settings_region_checking_size, r.label()));
        new Thread(() -> {
            long size = Geofabrik.size(r.pbfUrl);
            runOnUiThread(() -> {
                if (selectedRegion != r) return;
                long free = getCacheDir().getUsableSpace();
                regionInfo.setText(size < 0 ? r.label() : Lang.t(R.string.settings_region_size,
                        r.label(), size / 1e6, Math.max(1, size / 1e6 * 0.18), (size * 1.25 + 500e6) / 1e9, free / 1e9));
            });
        }, "region-size").start();
    }

    private void downloadRegion() {
        if (Hub.offlineRunning) {
            OfflineService.stop(this);
            return;
        }
        Geofabrik.Region r = selectedRegion;
        if (r == null || !r.label().equals(regionInput.getText().toString())) {
            r = null;
            String typed = regionInput.getText().toString().trim();
            for (Geofabrik.Region x : regions) {
                if (x.name.equalsIgnoreCase(typed) || x.label().equalsIgnoreCase(typed)) {
                    r = x;
                    break;
                }
            }
        }
        if (r == null) {
            regionInfo.setText(Lang.t(R.string.settings_pick_region));
            return;
        }
        startDownload(r);
    }

    private void startDownload(Geofabrik.Region r) {
        startDownload(r.id, r.label());
    }

    /** Downloads (or updates) a region; asks first when not on Wi-Fi. */
    private void startDownload(String id, String label) {
        android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
        if (cm.isActiveNetworkMetered()) {
            new AlertDialog.Builder(this)
                    .setTitle(Lang.t(R.string.settings_mobile_data))
                    .setMessage(Lang.t(R.string.settings_mobile_data_message, label))
                    .setPositiveButton(Lang.t(R.string.settings_download), (d, w) -> OfflineService.startRegion(this, id))
                    .setNegativeButton(Lang.t(R.string.common_cancel), null)
                    .show();
        } else {
            OfflineService.startRegion(this, id);
        }
    }
}
