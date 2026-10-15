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
 * ride recording, offline maps, the debug tools (debug builds, seven taps on the title) and the
 * legal notes. Other tabs open it at a section ({@link #EXTRA_SECTION}).
 */
public class SettingsActivity extends KawaActivity {

    private static final int[] LOOKAHEADS = {500, 1000, 1500, 2000};
    /** Distances a camera is announced from, metres. */
    private static final int[] CAMERA_WARNS = {300, 500, 800};
    private static final String CAMERAS_INFO =
            "From the offline map data: the phone shows it and the bike's display gets the distance. "
                    + "Download the region again if it was downloaded before this version. Using camera "
                    + "warnings while driving is illegal in some countries (Germany, France, Switzerland)";
    /** Explanation of "Which curves count"; values from CurveDetector.Sensitivity and its arrow choice. */
    private static final String CURVES_INFO =
            "The angle is the total change of direction through the whole bend: 90° is a right-angle turn, "
            + "180° a hairpin. A bend also has to be tight enough; long sweeping highway bends are not curves.\n\n"
            + "Only tight ones: from 45°, radius up to ~170 m. Only bends you really have to brake for.\n\n"
            + "Normal: from 30°, radius up to ~250 m.\n\n"
            + "Also gentle ones: from 20°, radius up to ~350 m. Two bends in the same direction close "
            + "together can merge into one.\n\n"
            + "All over 15°: from 16°, radius up to ~570 m. Everything except the faintest bends; bends close "
            + "together stay separate. Recommended.\n\n"
            + "Arrow on the bike's display:\n"
            + "• Slight: under 55° and wider than 60 m radius\n"
            + "• Normal: 55° or more, or tighter than 60 m\n"
            + "• Sharp: 100° or more, or tighter than 30 m\n"
            + "• Hairpin (U-turn arrow): 150° or more and tighter than 35 m";

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
        LinearLayout header = headerBar("Settings", null, null);
        View title = header.findViewWithTag("title");
        if (Hub.DEBUG_TOOLS && title != null) title.setOnClickListener(v -> titleTapped());
        root.addView(header);

        root.addView(section("autostart", R.drawable.ic_power, "Start automatically", autostartGroup()));
        root.addView(section("road", R.drawable.ic_road, "On the road", roadGroup()));
        root.addView(section("dash", R.drawable.ic_bell, "Dash icons and clock", dashGroup()));
        root.addView(section("recording", R.drawable.ic_route, "Ride recording", recordingGroup()));
        root.addView(section("maps", R.drawable.ic_map, "Offline maps", offlineGroup()));
        if (Hub.DEBUG_TOOLS) root.addView(debugCard());
        if (Hub.DEBUG_TOOLS) root.addView(bikeDebugCard());
        if (Hub.DEBUG_TOOLS) root.addView(notifDebugCard());
        root.addView(section("about", R.drawable.ic_info, "About", aboutGroup()));
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
        bt.addView(itemTitle("When the bike is switched on"));
        autoBikeSub = secondary("");
        bt.addView(autoBikeSub);
        bikeRow.addView(bt, weight());
        autoBikeButton = secondaryButton("", v -> toggleAutostart());
        bikeRow.addView(autoBikeButton);
        c.addView(bikeRow);

        LinearLayout helmetRow = settingRow();
        LinearLayout ht = column();
        ht.addView(itemTitle("When my helmet connects"));
        ht.addView(secondary("Your intercom connects by itself when switched on. While it is connected, the bike's display shows the headset icon"));
        helmetRow.addView(ht, weight());
        helmetsHidden = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED;
        List<BluetoothDevice> helmets = helmetsHidden ? new ArrayList<>() : HelmetTrigger.candidates(this);
        String savedHelmet = prefs.getString(Hub.KEY_HELMET, null);
        List<String> helmetAddrs = new ArrayList<>();
        List<String> helmetLabels = new ArrayList<>();
        helmetAddrs.add(null);
        helmetLabels.add("Off");
        for (BluetoothDevice d : helmets) {
            helmetAddrs.add(d.getAddress());
            helmetLabels.add(d.getName());
        }
        // a saved helmet Android does not list right now (Bluetooth permission missing, unpaired)
        // stays selected: the list's initial selection must not turn the trigger off
        if (savedHelmet != null && !helmetAddrs.contains(savedHelmet)) {
            helmetAddrs.add(savedHelmet);
            helmetLabels.add("Saved");
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

    private LinearLayout roadGroup() {
        LinearLayout c = group();
        addRow(c, switchRow("Google Maps directions",
                "While Maps is navigating, the bike's display shows its directions", Hub.KEY_MAPS, true));

        Switch free = accentSwitch(Hub.freeMode(this));
        free.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(Hub.KEY_FREE_MODE, on).apply();
            BridgeService.settingsChanged();
        });
        LinearLayout fr = settingRow();
        LinearLayout ft = column();
        ft.addView(itemTitle("Show upcoming curves"));
        ft.addView(secondary("Without navigation, the bike's display shows the next curve of the road you are on"));
        freeModeStatus = secondary("");
        freeModeStatus.setTextColor(Ui.accent(night));
        ft.addView(freeModeStatus);
        fr.addView(ft, weight());
        fr.addView(free);
        addRow(c, fr);

        addRow(c, choiceRow("Curve warnings during navigation", "Shown up to 300 m before a curve, between Maps directions",
                new String[]{"Off", "Sharp only", "All"}, prefs.getInt(Hub.KEY_NAV_CURVES, 0), pos -> {
                    prefs.edit().putInt(Hub.KEY_NAV_CURVES, pos).apply();
                    BridgeService.settingsChanged();
                }));
        addRow(c, choiceRow("Which curves count", "How much a bend must turn to be shown", CURVES_INFO,
                new String[]{"Only tight ones", "Normal", "Also gentle ones", "All over 15°"}, prefs.getInt(Hub.KEY_SENSITIVITY, 1),
                pos -> prefs.edit().putInt(Hub.KEY_SENSITIVITY, pos).apply()));
        String[] la = new String[LOOKAHEADS.length];
        int laSel = 1;
        for (int i = 0; i < LOOKAHEADS.length; i++) {
            la[i] = LOOKAHEADS[i] + " m";
            if (LOOKAHEADS[i] == prefs.getInt(Hub.KEY_LOOKAHEAD, 1000)) laSel = i;
        }
        addRow(c, choiceRow("Look ahead", "How far ahead to search for the next curve", la, laSel,
                pos -> prefs.edit().putInt(Hub.KEY_LOOKAHEAD, LOOKAHEADS[pos]).apply()));
        addRow(c, switchRow("Distances in feet", "Instead of metres", Hub.KEY_IMPERIAL, false));
        addRow(c, switchRow("Left-hand traffic", "UK, Ireland…: roundabouts turn clockwise", Hub.KEY_LEFT_HAND, false));
        addRow(c, switchRow("Speed camera warnings", CAMERAS_INFO, Hub.KEY_CAMERAS, false,
                on -> BridgeService.settingsChanged()));
        String[] warn = new String[CAMERA_WARNS.length];
        int warnSel = 1;
        for (int i = 0; i < CAMERA_WARNS.length; i++) {
            warn[i] = CAMERA_WARNS[i] + " m";
            if (CAMERA_WARNS[i] == prefs.getInt(Hub.KEY_CAMERA_WARN, Hub.CAMERA_WARN_DEFAULT)) warnSel = i;
        }
        addRow(c, choiceRow("Warn about a camera", "How far before it the warning comes",
                warn, warnSel, pos -> prefs.edit().putInt(Hub.KEY_CAMERA_WARN, CAMERA_WARNS[pos]).apply()));
        return c;
    }

    private LinearLayout dashGroup() {
        LinearLayout c = group();
        // what the dash said it can do on the last connection: say so instead of doing nothing quietly
        BikeFrames.GeneralCaps caps = Hub.bike.caps;
        String noIcons = caps != null && !caps.statusIcons ? ". Your bike's dash said it shows no status icons" : "";
        String noClock = caps != null && !caps.clockSettable() ? ". Your bike's dash said the phone may not set its clock" : "";
        addRow(c, switchRow("Phone battery", "The bike's display shows the phone's battery (4 bars) and when it is charging"
                + noIcons, Hub.KEY_PHONE_BATTERY, true));
        addRow(c, switchRow("Set the dash clock from the phone", "Once per connection, like Rideology. Keeps the "
                + "shift lamp as it is set on the bike" + noClock, Hub.KEY_CLOCK_SYNC, false));
        addRow(c, switchRow("Call icon on the dash", "An icon while a call comes in; needs notification access. No numbers or names are sent",
                Hub.KEY_DASH_CALLS, false, v -> MapsListener.alertsChanged(this)));
        addRow(c, switchRow("Mail and message icon on the dash", "An icon while a mail or message arrives (Gmail, Messages, LINE…); needs notification access",
                Hub.KEY_DASH_MAIL, false, v -> MapsListener.alertsChanged(this)));
        return c;
    }

    private LinearLayout recordingGroup() {
        LinearLayout c = group();
        addRow(c, switchRow("Start recording by itself", "As soon as the bike is connected and moving",
                Hub.KEY_RIDE_AUTO, false));
        addRow(c, listRow(R.drawable.ic_list, "Recorded rides", "Everything you have recorded, with track and charts",
                null, true, v -> openTab(TAB_RIDES, null)));
        return c;
    }

    private LinearLayout offlineGroup() {
        LinearLayout c = group();
        LinearLayout intro = column();
        intro.setPadding(0, dp(10), 0, dp(6));
        intro.addView(secondary("Curves are only shown where the roads are downloaded. Download the regions you "
                + "ride in, over Wi-Fi. Regions add up: e.g. Bayern now, Tirol later."));
        intro.addView(note("Road data © OpenStreetMap contributors (ODbL)"));
        c.addView(intro);
        storedView = itemTitle("");
        storedView.setPadding(0, dp(10), 0, dp(4));
        c.addView(storedView);
        regionsList = column();
        c.addView(regionsList);

        TextView add = itemTitle("Download a region");
        add.setPadding(0, dp(14), 0, dp(4));
        c.addView(add);
        regionInput = new AutoCompleteTextView(this);
        regionInput.setHint("Search a region: Bayern, Tirol, Schweiz…");
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
        regionButton = primaryButton("Download", v -> downloadRegion());
        buttons.addView(regionButton, weight());
        c.addView(buttons);
        loadRegions();
        refreshRegions();
        return c;
    }

    /** Legal notes: riding safety, affiliation, map data credit, licence (GPL-3.0 §5d), version. */
    private LinearLayout aboutGroup() {
        LinearLayout c = group();
        TextView t = note(MainActivity.RIDING_NOTICE + "\n\n" + MainActivity.AFFILIATION_NOTICE
                + "\n\nRoad data © OpenStreetMap contributors, available under the Open Database License (ODbL), "
                + "downloaded from Geofabrik."
                + "\n\nKawaNav © 2026 Darthbob; this version modified by jackyes (2026). Free software under "
                + "the GNU General Public License v3, without any warranty. Source code: github.com/jackyes/kawa-nav "
                + "(based on github.com/Darthbob/kawa-nav)"
                + "\n\nVersion " + BuildConfig.VERSION_NAME + (Hub.DEBUG_TOOLS ? " (debug)" : ""));
        t.setPadding(0, dp(Ui.SPACE3), 0, dp(Ui.SPACE3));
        t.setAutoLinkMask(android.text.util.Linkify.WEB_URLS);
        t.setLinkTextColor(Ui.accent(night));
        c.addView(t);
        return c;
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
        c.addView(choiceRow("Requests after connecting", "Applies on the next connect. Full is Rideology's sequence: "
                        + "it adds the phone name and the 0x08 init",
                new String[]{"Capabilities only (v0.3)", "Read-only", "Full (like Rideology)"}, Hub.initLevel(this),
                pos -> prefs.edit().putInt(Hub.KEY_DEBUG_INIT_LEVEL, pos).apply()));
        c.addView(switchRow("Send icons and arrows anyway", "Ignores the dash's answer that it has no status "
                + "icons or no turn-by-turn (the 0x1A frame). For a dash whose answer is misread: without "
                + "this, the arrows would go missing.", Hub.KEY_DEBUG_IGNORE_CAPS, false,
                on -> BridgeService.settingsChanged()));
        c.addView(switchRow("Skip the riding log request", "Read-only connect and ride start leave out 45 00 00 "
                + "(as KawaNav 0.6 and before did; Rideology always sends it)", Hub.KEY_DEBUG_NO_EXT, false));
        c.addView(switchRow("Capture frames", "Every frame to and from the bike, one file per connect: "
                + "adb pull /sdcard/Android/data/" + getPackageName() + "/files/captures/", Hub.KEY_DEBUG_CAPTURE, false));
        LinearLayout reads = row();
        reads.setPadding(0, dp(6), 0, 0);
        for (int[] r : new int[][]{{BikeFrames.VEHICLE_INFO}, {BikeFrames.VEHICLE_SETTINGS}, {BikeFrames.SERVICE_INDICATOR}}) {
            int id = r[0];
            Button b = button("Read " + String.format(Locale.ROOT, "%02X", id), v -> android.widget.Toast.makeText(this,
                    BridgeService.read(id) ? "Asked the bike for " + BikeFrames.name(id) : "Bike not connected",
                    android.widget.Toast.LENGTH_SHORT).show());
            b.setTextColor(Ui.accent(night));
            outline(b);
            LinearLayout.LayoutParams lp = weight();
            lp.rightMargin = dp(Ui.SPACE1);
            reads.addView(b, lp);
        }
        c.addView(reads);
        debugBike = secondary("");
        debugBike.setTypeface(Typeface.MONOSPACE);
        debugBike.setTextIsSelectable(true);
        debugBike.setPadding(0, dp(10), 0, 0);
        c.addView(debugBike);
        c.setVisibility(prefs.getBoolean(Hub.KEY_DEBUG_ICONS, false) ? View.VISIBLE : View.GONE);
        bikeDebugCard = c;
        return c;
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
        send.setTextColor(Ui.accent(night));
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
            String status = (NotifCapture.enabled(this) ? "Capturing" : "Off") + ": " + NotifCapture.status(this);
            if (!status.contentEquals(notifStatus.getText())) notifStatus.setText(status);
        }

        // --- autostart
        android.companion.AssociationInfo bike = BikePresence.association(this);
        autoBikeSub.setText(bike != null ? "On for " + bike.getDisplayName()
                : "Set up once next to the bike, ignition on, Rideology closed");
        autoBikeButton.setText(bike != null ? "Turn off" : "Set up");

        // --- on the road
        freeModeStatus.setVisibility(Hub.serviceRunning && Hub.freeMode(this) ? View.VISIBLE : View.GONE);
        freeModeStatus.setText(Hub.curveStatus.contains("·") ? "Active" : Hub.curveStatus);

        // --- offline
        if (offlineWasRunning && !Hub.offlineRunning) refreshRegions(); // a download just ended
        offlineWasRunning = Hub.offlineRunning;
        offlineProgress.setVisibility(Hub.offlineRunning ? View.VISIBLE : View.GONE);
        boolean showStatus = Hub.offlineRunning || !"–".equals(Hub.offlineStatus);
        offlineStatus.setVisibility(showStatus ? View.VISIBLE : View.GONE);
        offlineStatus.setText(Hub.offlineStatus);
        regionButton.setText(Hub.offlineRunning ? "Cancel" : "Download");
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
        storedView.setText(list.isEmpty() && otherBytes == 0 ? "No regions downloaded yet"
                : String.format(Locale.ROOT, "Downloaded · %.0f MB", Math.max(1, total / 1e6)));
        java.text.DateFormat df = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM);
        for (OfflineRegions.Region r : list) {
            Long server = serverDates.get(r.id);
            // a region from before the camera data is worth downloading again even without newer data
            boolean update = (server != null && server - r.dataDate >= OfflineRegions.UPDATE_AFTER_MS) || !r.hazards;
            String state = server == null ? "Checking for newer data…"
                    : server == 0 ? "Could not check for newer data (offline?)"
                    : update ? "Newer data available from " + df.format(new java.util.Date(server))
                    : "Up to date";
            String sub = String.format(Locale.ROOT, "Data from %s · %.0f MB\n%s%s",
                    df.format(new java.util.Date(r.dataDate)), Math.max(1, sizes.get(r.id) / 1e6), state,
                    r.hazards ? "" : "\nNo speed cameras in this data yet: download the region again");
            regionsList.addView(regionRow(r.label, sub, update ? () -> updateRegion(r) : null,
                    () -> confirmDeleteRegion(r)));
        }
        if (otherBytes > 0) {
            regionsList.addView(regionRow("Other roads",
                    String.format(Locale.ROOT, "%.0f MB from earlier downloads, not part of a region", Math.max(1, otherBytes / 1e6)),
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
        if (onUpdate != null) r.addView(primaryButton("Update", v -> onUpdate.run()), lp);
        r.addView(secondaryButton("Delete", v -> onDelete.run()), lp);
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
                .setTitle("Delete " + r.label + "?")
                .setMessage("Its roads are removed from the phone. Roads that another downloaded region also "
                        + "covers stay.")
                .setPositiveButton("Delete", (d, w) -> new Thread(() -> {
                    offlineRegions.delete(r.id);
                    serverDates.remove(r.id);
                    runOnUiThread(this::refreshRegions);
                }, "region-delete").start())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDeleteOther() {
        if (Hub.offlineRunning) return;
        new AlertDialog.Builder(this)
                .setTitle("Delete other roads?")
                .setMessage("Roads from earlier downloads that belong to no region in the list are removed.")
                .setPositiveButton("Delete", (d, w) -> new Thread(() -> {
                    offlineRegions.deleteUnassigned();
                    runOnUiThread(this::refreshRegions);
                }, "region-delete").start())
                .setNegativeButton("Cancel", null)
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
                runOnUiThread(() -> regionInfo.setText("Region list not available. Are you online?"));
            }
        }, "regions").start();
    }

    private void selectRegion(Geofabrik.Region r) {
        selectedRegion = r;
        regionInfo.setText(r.label() + ": checking size…");
        new Thread(() -> {
            long size = Geofabrik.size(r.pbfUrl);
            runOnUiThread(() -> {
                if (selectedRegion != r) return;
                long free = getCacheDir().getUsableSpace();
                regionInfo.setText(size < 0 ? r.label() : String.format(Locale.ROOT,
                        "%s: %.0f MB download, about %.0f MB stored\nNeeds %.1f GB free while converting (%.1f GB free)",
                        r.label(), size / 1e6, size / 1e6 * 0.09, (size * 1.25 + 500e6) / 1e9, free / 1e9));
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
            regionInfo.setText("Pick a region from the list");
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
                    .setTitle("Mobile data")
                    .setMessage("You are not on Wi-Fi. The file for " + label + " can be several hundred MB. Download anyway?")
                    .setPositiveButton("Download", (d, w) -> OfflineService.startRegion(this, id))
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            OfflineService.startRegion(this, id);
        }
    }
}
