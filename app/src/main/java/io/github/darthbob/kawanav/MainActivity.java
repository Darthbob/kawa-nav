package io.github.darthbob.kawanav;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The app screen: status and start/stop on top, then setup steps (only while something is
 * missing), autostart, riding options and offline maps. Test switches are adb-only, see
 * {@link #handleAdbIntent}; the log is in files/kawanav.log.
 */
public class MainActivity extends KawaActivity {

    private static final int REQ_PERMS = 1;
    private static final int REQ_BG_LOCATION = 3;
    private static final int[] LOOKAHEADS = {500, 1000, 1500, 2000};
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
    /** Turn ids that make sense on the Z900 meter (0-43); 44-55 (waypoints) show nothing. */

    private final Handler handler = new Handler(Looper.getMainLooper());
    private RoadStore roadStore;
    private OfflineRegions offlineRegions;
    /** Server data date per downloaded region (ms, 0 = unknown), checked once per app start. */
    private static final java.util.Map<String, Long> serverDates = new java.util.concurrent.ConcurrentHashMap<>();
    private LinearLayout regionsList;
    private boolean offlineWasRunning;

    // status
    private TextView statusDot;
    private TextView statusTitle;
    private TextView statusHint;
    private TextView meterArrow;
    private TextView meterText;
    private TextView meterSource;
    private Button startButton;
    // bike
    private TextView bikeTitle;
    private TextView bikeSummary;
    private TextView bikeUpdated;
    // setup
    // banner: settings that keep KawaNav from working fully
    private LinearLayout banner;
    private TextView bannerTitle;
    private View permRow, bluetoothRow, pairRow, gpsRow, accessRow, batteryRow, bgLocRow;
    private TextView pairSub;
    // first start: walks through every permission once (see continueSetup)
    private static final String KEY_SETUP_STEP = "setup_step";
    private static final String KEY_NOTICE_SEEN = "riding_notice_seen";
    static final String RIDING_NOTICE = "KawaNav is a riding aid, not a safety system. Directions and curves can "
            + "be late, missing or wrong. Always ride by what you see on the road and keep your eyes on it.";
    static final String AFFILIATION_NOTICE = "KawaNav is an independent project, not affiliated with or endorsed "
            + "by Kawasaki or Google.";
    private static final int SETUP_STEPS = 4;
    private boolean setupWaiting;  // a permission dialog or settings screen of the guide is open
    private boolean setupDialog;   // an explanation dialog of the guide is showing
    private boolean fromSetup;     // the pending permission request comes from the guide
    private boolean listenerSettingsOpened; // notification access settings opened, see onResume
    private boolean helmetsHidden; // the helmet list was built without Bluetooth permission
    // autostart
    private TextView autoBikeSub;
    private Button autoBikeButton;
    // riding
    private TextView freeModeStatus;
    // debug
    private LinearLayout debugCard;
    private LinearLayout bikeDebugCard;
    private TextView debugBike;
    private TextView debugIconsStatus;
    private TextView debugGatt;
    private int titleTaps;
    private long titleTapAt;
    // offline
    private TextView storedView;
    private AutoCompleteTextView regionInput;
    private Button regionButton;
    private TextView regionInfo;
    private ProgressBar offlineProgress;
    private TextView offlineStatus;
    private List<Geofabrik.Region> regions = new ArrayList<>();
    private Geofabrik.Region selectedRegion;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE); // the screen has its own title
        Logger.init(this);
        prefs = Hub.prefs(this);
        roadStore = new RoadStore(this);
        offlineRegions = new OfflineRegions(this, roadStore);
        BikeStore.load(this);
        setContentView(buildUi());
        // the launch intent is handled once: not again when Android recreates the activity
        // (rotation, restart after a crash, opened from recents)
        boolean fromHistory = (getIntent().getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0;
        if (savedInstanceState == null && !fromHistory) pendingIntent = getIntent();
        android.companion.AssociationInfo bike = BikePresence.association(this);
        if (bike != null) BikePresence.observe(this, bike); // make sure the system keeps watching

    }

    /** Intent from adb to act on once resumed: services using GPS may only start while we are visible. */
    private Intent pendingIntent;

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        pendingIntent = intent; // onResume follows
    }

    /**
     * Test switches from a PC, e.g.
     * adb shell am start -n io.github.darthbob.kawanav/.MainActivity --ez bridge true|false
     * Also: --ez free_mode true|false, --es offline_region bayern, --ez offline_cancel true
     */
    /**
     * Copies the log to Download/KawaNav (readable by adb and file managers, also from a release build):
     * {@code adb pull /sdcard/Download/KawaNav/}. Runs on the log's own thread, so no line is written or
     * the file rotated while it is copied.
     */
    private void exportLog() {
        android.content.ContentResolver cr = getContentResolver();
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new java.util.Date());
        for (String name : new String[]{"kawanav.log.1", "kawanav.log"}) {
            java.io.File f = new java.io.File(getFilesDir(), name);
            if (!f.exists()) continue;
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, stamp + "-" + name + ".txt");
            v.put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain");
            v.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/KawaNav");
            android.net.Uri uri = cr.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) continue;
            try (java.io.OutputStream out = cr.openOutputStream(uri)) {
                java.nio.file.Files.copy(f.toPath(), out);
            } catch (java.io.IOException e) {
                Logger.log("Log export failed: " + e);
            }
        }
        Logger.log("Log exported to Download/KawaNav");
    }

    private void handleAdbIntent(Intent i) {
        if (i == null) return;
        if (i.hasExtra("free_mode")) {
            prefs.edit().putBoolean(Hub.KEY_FREE_MODE, i.getBooleanExtra("free_mode", false)).apply();
            BridgeService.settingsChanged();
            setContentView(buildUi()); // refresh the switch
        }
        if (i.getBooleanExtra("offline_cancel", false)) OfflineService.stop(this);
        if (i.getBooleanExtra("export_log", false)) Logger.withLogFiles(this::exportLog);
        if (i.hasExtra(OfflineService.EXTRA_REGION)) OfflineService.startRegion(this, i.getStringExtra(OfflineService.EXTRA_REGION));
        if (Hub.DEBUG_TOOLS) {
            // debug builds: vehicle data tests, e.g. --es bike_read 41, --ei init_level 2, --es replay 20261013-190000.txt
            if (i.hasExtra("bike_read")) {
                int id;
                try {
                    id = Integer.parseInt(String.valueOf(i.getStringExtra("bike_read")), 16);
                } catch (NumberFormatException e) {
                    id = -1;
                }
                Logger.log("adb: read " + i.getStringExtra("bike_read") + ": " + BridgeService.read(id));
            }
            int level = i.getIntExtra("init_level", -1);
            if (level >= KawaProtocol.INIT_NONE && level <= KawaProtocol.INIT_FULL) {
                prefs.edit().putInt(Hub.KEY_DEBUG_INIT_LEVEL, level).apply();
            }
            if (i.hasExtra("raw_capture")) prefs.edit().putBoolean(Hub.KEY_DEBUG_CAPTURE, i.getBooleanExtra("raw_capture", false)).apply();
            if (i.hasExtra("replay")) {
                // a capture of this phone, or a file copied into the app's files/ (adb push + run-as cp)
                java.io.File f = new java.io.File(RawCapture.dir(this), i.getStringExtra("replay"));
                if (!f.isFile()) f = new java.io.File(getFilesDir(), i.getStringExtra("replay"));
                Logger.log("adb: replay " + f.getName() + ": " + BridgeService.replay(f));
            }
        }
        if (i.hasExtra("bridge")) {
            if (i.getBooleanExtra("bridge", false)) {
                if (!Hub.serviceRunning) BridgeService.start(this);
            } else {
                BridgeService.stop(this);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        BridgeService.appVisible();
        setupWaiting = false; // back from the guide's permission dialog or settings screen
        if (listenerSettingsOpened) {
            listenerSettingsOpened = false;
            // back without notification access: with a downloaded APK, Android greys the switch out
            if (!hasNotificationAccess() && installedFromFile()) handler.post(this::showRestrictedSettingsHelp);
        }
        handler.post(this::continueSetup);
        if (pendingIntent != null) {
            Intent i = pendingIntent;
            pendingIntent = null;
            handler.post(() -> handleAdbIntent(i)); // after the resume has completed
        }
        handler.post(refresh);
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

    // ------------------------------------------------------------------ permissions

    private List<String> missingPermissions() {
        List<String> need = new ArrayList<>();
        for (String p : new String[]{Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS}) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        }
        return need;
    }

    private void requestMissingPermissions() {
        List<String> need = missingPermissions();
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        boolean guide = fromSetup;
        fromSetup = false;
        if (guide) {
            // Android may answer without showing anything (declined before): go on right away
            setupWaiting = false;
            handler.post(this::continueSetup);
        }
        List<String> denied = requestCode == REQ_BG_LOCATION
                ? (hasBackgroundLocation() ? new ArrayList<>() : List.of(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                : missingPermissions();
        if (!denied.isEmpty()) {
            Logger.log("Permissions not granted: " + denied);
            // Android stops showing the request after it was declined: when asked from the banner,
            // go to the app's settings instead (the guide just moves on, the banner stays)
            boolean blocked = false;
            for (String d : denied) if (!shouldShowRequestPermissionRationale(d)) blocked = true;
            if (blocked && !guide) openAppSettings();
        }
        // Bluetooth just allowed: rebuild the screen so the helmet list shows the paired devices
        if (requestCode == REQ_PERMS && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED && helmetsHidden) {
            setContentView(buildUi());
        }
        updateUi();
    }

    private void openAppSettings() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + getPackageName())));
    }

    private boolean hasBackgroundLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Autostart begins in the background, where GPS needs location "Allow all the time". Android only
     * offers that on its settings page, so explain first what to pick there. No-op when granted.
     */
    void askBackgroundLocation() {
        askBackgroundLocation(null, null);
    }

    /**
     * @param onContinue run before Android's request opens (null: nothing)
     * @param onSkip     run when the user picks "Not now" (null: nothing)
     */
    private void askBackgroundLocation(Runnable onContinue, Runnable onSkip) {
        if (hasBackgroundLocation()) return;
        new android.app.AlertDialog.Builder(this)
                .setTitle("Location: \"Allow all the time\"")
                .setMessage("Autostart starts KawaNav while your phone is locked in your pocket. "
                        + "To show curve warnings then, KawaNav needs GPS in the background.\n\n"
                        + "On the next screen, select \"Allow all the time\".\n\n"
                        + "Without it, Google Maps arrows still work, but curve warnings only start "
                        + "after you open KawaNav.")
                .setPositiveButton("Continue", (d, w) -> {
                    if (onContinue != null) onContinue.run();
                    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        requestMissingPermissions(); // "all the time" can only follow "while using"
                    } else {
                        requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQ_BG_LOCATION);
                    }
                })
                .setNegativeButton("Not now", (d, w) -> {
                    if (onSkip != null) onSkip.run();
                })
                .setCancelable(false)
                .show();
    }

    // ------------------------------------------------------------------ first-start guide

    /**
     * First start: asks for everything KawaNav needs, one step at a time (permissions, location
     * "all the time", reading Maps' notification, unrestricted battery). Each step is offered once,
     * declined or not; whatever is still missing afterwards stays in the orange banner.
     * Called on every resume; continues after each permission dialog or settings screen.
     */
    private void continueSetup() {
        if (setupWaiting || setupDialog || isFinishing()) return;
        if (!prefs.getBoolean(KEY_NOTICE_SEEN, false)) {
            setupDialog = true;
            new AlertDialog.Builder(this)
                    .setTitle("Before you ride")
                    .setMessage(RIDING_NOTICE + "\n\n" + AFFILIATION_NOTICE)
                    .setPositiveButton("Understood", (d, w) -> {
                        prefs.edit().putBoolean(KEY_NOTICE_SEEN, true).apply();
                        setupDialog = false;
                        continueSetup();
                    })
                    .setCancelable(false)
                    .show();
            return;
        }
        int step = prefs.getInt(KEY_SETUP_STEP, 0);
        while (step < SETUP_STEPS) {
            int s = step++;
            prefs.edit().putInt(KEY_SETUP_STEP, step).apply();
            if (!setupNeeded(s)) continue;
            runSetupStep(s);
            return;
        }
    }

    private boolean setupNeeded(int step) {
        switch (step) {
            case 0: return !missingPermissions().isEmpty();
            case 1: return !hasBackgroundLocation()
                    && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            case 2: return Hub.mapsEnabled(this) && !hasNotificationAccess();
            default: return !batteryUnrestricted();
        }
    }

    private void runSetupStep(int step) {
        switch (step) {
            case 0:
                setupWaiting = true;
                fromSetup = true;
                requestMissingPermissions();
                break;
            case 1:
                setupDialog = true;
                askBackgroundLocation(() -> {
                    // Android's dialog opens: the guide goes on when KawaNav is back
                    setupDialog = false;
                    setupWaiting = true;
                    fromSetup = true;
                }, () -> {
                    setupDialog = false;
                    continueSetup();
                });
                break;
            case 2:
                setupDialog = true;
                new AlertDialog.Builder(this)
                        .setTitle("Read Google Maps directions")
                        .setMessage("To show Maps' directions on the dash, KawaNav reads Maps' navigation "
                                + "notification.\n\nOn the next screen, turn on \"KawaNav\".")
                        .setPositiveButton("Continue", (d, w) -> {
                            setupDialog = false;
                            setupWaiting = true;
                            openListenerSettings();
                        })
                        .setNegativeButton("Not now", (d, w) -> {
                            setupDialog = false;
                            continueSetup();
                        })
                        .setCancelable(false)
                        .show();
                break;
            default:
                setupWaiting = true;
                requestBatteryExemption(); // Android's own dialog explains it
                break;
        }
    }

    private void openListenerSettings() {
        listenerSettingsOpened = true;
        startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
    }

    /**
     * Installed from a downloaded APK (not an app store or adb): Android 13+ then blocks
     * "restricted settings" such as notification access until the user allows them in App info.
     */
    private boolean installedFromFile() {
        try {
            int source = getPackageManager().getInstallSourceInfo(getPackageName()).getPackageSource();
            return source == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
                    || source == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** How to unlock notification access for a downloaded APK, with a screenshot of the ⋮ menu. */
    private void showRestrictedSettingsHelp() {
        if (isFinishing()) return;
        setupDialog = true; // the first-start guide waits for this dialog
        LinearLayout c = column();
        c.setPadding(dp(24), dp(8), dp(24), 0);
        c.addView(itemTitle("KawaNav was installed from a downloaded file, so Android protects this setting. "
                + "Unlock it once:\n\n"
                + "1. \"Try the switch\": tap KawaNav and its greyed-out switch once. Android refuses, "
                + "but this makes step 2 appear.\n"
                + "2. \"Open app info\": tap ⋮ at the top right, then \"Allow restricted settings\" and confirm.\n"
                + "3. Back in KawaNav, tap \"Open\" for Google Maps directions: the switch works now."));
        android.widget.ImageView shot = new android.widget.ImageView(this);
        shot.setImageResource(R.drawable.restricted_settings_help);
        shot.setAdjustViewBounds(true);
        shot.setContentDescription("App info with the menu open: Allow restricted settings");
        LinearLayout.LayoutParams lp = matchWrap();
        lp.setMargins(0, dp(14), 0, 0);
        c.addView(shot, lp);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(c);
        boolean[] leaving = {false};
        new AlertDialog.Builder(this)
                .setTitle("Switch greyed out?")
                .setView(scroll)
                .setPositiveButton("Open app info", (d, w) -> {
                    leaving[0] = true; // the guide goes on when KawaNav is back (onResume)
                    openAppSettings();
                })
                .setNeutralButton("Try the switch", (d, w) -> {
                    leaving[0] = true;
                    openListenerSettings();
                })
                .setNegativeButton("Close", null)
                .setOnDismissListener(d -> {
                    setupDialog = false;
                    if (!leaving[0]) continueSetup();
                })
                .show();
    }

    private void requestBatteryExemption() {
        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:" + getPackageName())));
    }

    private boolean batteryUnrestricted() {
        return getSystemService(android.os.PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
    }

    private boolean hasNotificationAccess() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(new ComponentName(this, MapsListener.class).flattenToString());
    }

    // ------------------------------------------------------------------ UI

    private View buildUi() {
        night = isNight();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(24));

        TextView title = text(26, true);
        title.setText("KawaNav");
        if (Hub.DEBUG_TOOLS) title.setOnClickListener(v -> titleTapped());
        root.addView(title);
        TextView subtitle = secondary("Google Maps directions and curve warnings on your Kawasaki");
        subtitle.setPadding(0, 0, 0, dp(8));
        root.addView(subtitle);

        root.addView(banner());
        root.addView(statusCard());
        root.addView(bikeCard());
        root.addView(autostartCard());
        root.addView(ridingCard());
        root.addView(offlineCard());
        if (Hub.DEBUG_TOOLS) root.addView(debugCard());
        if (Hub.DEBUG_TOOLS) root.addView(bikeDebugCard());
        root.addView(footer());

        return page(root);
    }

    private LinearLayout statusCard() {
        LinearLayout c = card();
        LinearLayout head = row();
        statusDot = text(18, false);
        statusDot.setText("●");
        statusDot.setPadding(0, 0, dp(8), 0);
        head.addView(statusDot);
        statusTitle = text(19, true);
        head.addView(statusTitle, weight());
        c.addView(head);
        statusHint = secondary("");
        c.addView(statusHint);

        LinearLayout meter = row();
        meter.setPadding(0, dp(14), 0, dp(14));
        meterArrow = text(40, true);
        meterArrow.setGravity(Gravity.CENTER);
        meterArrow.setMinWidth(dp(64));
        meter.addView(meterArrow);
        LinearLayout mt = column();
        mt.setPadding(dp(12), 0, 0, 0);
        meterText = text(18, true);
        mt.addView(meterText);
        meterSource = secondary("");
        mt.addView(meterSource);
        meter.addView(mt, weight());
        c.addView(meter);

        startButton = primaryButton("", v -> toggleBridge());
        c.addView(startButton, matchWrap());
        return c;
    }

    /** The bike's last values (odometer, fuel, range, battery) and the way to its own screen. */
    private LinearLayout bikeCard() {
        LinearLayout c = card();
        bikeTitle = cardTitle("Your bike");
        c.addView(bikeTitle);
        bikeSummary = itemTitle("");
        c.addView(bikeSummary);
        bikeUpdated = secondary("");
        c.addView(bikeUpdated);
        LinearLayout buttons = row();
        buttons.setPadding(0, dp(6), 0, 0);
        buttons.addView(button("Bike details", v -> startActivity(new Intent(this, BikeActivity.class))), weight());
        c.addView(buttons);
        c.setOnClickListener(v -> startActivity(new Intent(this, BikeActivity.class)));
        return c;
    }

    /** Legal notes at the bottom: riding safety, affiliation, map data credit, licence (GPL-3.0 §5d). */
    private TextView footer() {
        TextView t = secondary(RIDING_NOTICE + "\n\n" + AFFILIATION_NOTICE
                + "\n\nRoad data © OpenStreetMap contributors, available under the Open Database License (ODbL), "
                + "downloaded from Geofabrik."
                + "\n\nKawaNav © 2026 Darthbob. Free software under the GNU General Public License v3, "
                + "without any warranty. Source code: github.com/Darthbob/kawa-nav");
        t.setTextSize(12);
        t.setPadding(dp(4), dp(18), dp(4), dp(8));
        t.setAutoLinkMask(android.text.util.Linkify.WEB_URLS);
        return t;
    }

    /** Orange banner on top: what still keeps KawaNav from working fully, each with a button to fix it. */
    private LinearLayout banner() {
        banner = column();
        banner.setPadding(dp(16), dp(14), dp(16), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(WARN_ORANGE);
        bg.setCornerRadius(dp(16));
        banner.setBackground(bg);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.setMargins(0, dp(6), 0, dp(6));
        banner.setLayoutParams(lp);
        bannerTitle = text(17, true);
        bannerTitle.setTextColor(BANNER_TEXT);
        banner.addView(bannerTitle);
        TextView sub = text(13, false);
        sub.setTextColor(BANNER_TEXT);
        sub.setText("For the best experience on the bike:");
        banner.addView(sub);
        permRow = bannerRow("Allow Bluetooth, location and notifications",
                "To talk to the bike and find the curves ahead", "Allow", v -> requestMissingPermissions());
        bluetoothRow = bannerRow("Turn on Bluetooth", "KawaNav cannot reach the bike while it is off", "Turn on",
                v -> startActivity(new Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)));
        pairRow = bannerRow("Pair your bike", PAIR_HINT, "Pair", v -> pairBike());
        pairSub = (TextView) pairRow.getTag();
        gpsRow = bannerRow("Turn on location", "Upcoming curves need GPS", "Open",
                v -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        accessRow = bannerRow("Allow reading Google Maps directions", installedFromFile()
                        ? "Turn on \"KawaNav\" in the list that opens. Greyed out? Tap for help"
                        : "Turn on \"KawaNav\" in the list that opens",
                "Open", v -> openListenerSettings());
        batteryRow = bannerRow("Allow unrestricted battery use", "So Android does not stop KawaNav in your pocket",
                "Allow", v -> requestBatteryExemption());
        bgLocRow = bannerRow("Set location to \"Allow all the time\"", "For curves after an automatic start",
                "Allow", v -> askBackgroundLocation());
        for (View r : new View[]{permRow, bluetoothRow, pairRow, gpsRow, accessRow, batteryRow, bgLocRow}) banner.addView(r);
        return banner;
    }

    private View bannerRow(String title, String sub, String action, View.OnClickListener l) {
        LinearLayout r = row();
        r.setPadding(0, dp(10), 0, dp(4));
        LinearLayout t = column();
        TextView tt = text(15, true);
        tt.setTextColor(BANNER_TEXT);
        tt.setText(title);
        t.addView(tt);
        TextView st = text(13, false);
        st.setTextColor(BANNER_TEXT);
        st.setAlpha(0.8f);
        st.setText(sub);
        t.addView(st);
        r.addView(t, weight());
        Button b = button(action, l);
        b.setTextColor(BANNER_TEXT);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setStateListAnimator(null);
        GradientDrawable bb = new GradientDrawable();
        bb.setColor(0xFFFFFFFF);
        bb.setCornerRadius(dp(20));
        b.setBackground(bb);
        b.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        lp.setMargins(dp(10), 0, 0, 0);
        r.addView(b, lp);
        r.setOnClickListener(l);
        r.setTag(st); // the subtitle, for rows that show progress
        return r;
    }

    private static final String PAIR_HINT = "Ignition on and Rideology closed, then pick your bike";

    /** Pairs the phone with the bike (and sets up autostart), see {@link BikePresence#pair}. */
    private void pairBike() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestMissingPermissions();
            return;
        }
        BikePresence.pair(this);
    }

    private LinearLayout autostartCard() {
        LinearLayout c = card();
        c.addView(cardTitle("Start automatically"));
        c.addView(secondary("No need to open KawaNav before a ride: it starts by itself, even with the phone locked."));

        LinearLayout bikeRow = settingRow();
        LinearLayout bt = column();
        bt.addView(itemTitle("When the bike is switched on"));
        autoBikeSub = secondary("");
        bt.addView(autoBikeSub);
        bikeRow.addView(bt, weight());
        autoBikeButton = button("", v -> toggleAutostart());
        bikeRow.addView(autoBikeButton);
        c.addView(bikeRow);

        LinearLayout helmetRow = settingRow();
        LinearLayout ht = column();
        ht.addView(itemTitle("When my helmet connects"));
        ht.addView(secondary("Your intercom connects by itself when switched on. While it is connected, the bike's display shows the headset icon"));
        helmetRow.addView(ht, weight());
        helmetsHidden = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED;
        List<android.bluetooth.BluetoothDevice> helmets = helmetsHidden ? new ArrayList<>() : HelmetTrigger.candidates(this);
        String savedHelmet = prefs.getString(Hub.KEY_HELMET, null);
        List<String> helmetAddrs = new ArrayList<>();
        List<String> helmetLabels = new ArrayList<>();
        helmetAddrs.add(null);
        helmetLabels.add("Off");
        for (android.bluetooth.BluetoothDevice d : helmets) {
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
        c.addView(helmetRow);
        return c;
    }

    private LinearLayout ridingCard() {
        LinearLayout c = card();
        c.addView(cardTitle("On the road"));
        c.addView(switchRow("Google Maps directions", "While Maps is navigating, the bike's display shows its directions",
                Hub.KEY_MAPS, true));

        Switch free = new Switch(this);
        free.setChecked(Hub.freeMode(this));
        free.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(Hub.KEY_FREE_MODE, on).apply();
            BridgeService.settingsChanged();
        });
        LinearLayout fr = settingRow();
        LinearLayout ft = column();
        ft.addView(itemTitle("Show upcoming curves"));
        ft.addView(secondary("Without navigation, the bike's display shows the next curve of the road you are on"));
        freeModeStatus = secondary("");
        ft.addView(freeModeStatus);
        fr.addView(ft, weight());
        fr.addView(free);
        c.addView(fr);

        c.addView(choiceRow("Curve warnings during navigation", "Shown up to 300 m before a curve, between Maps directions",
                new String[]{"Off", "Sharp only", "All"}, prefs.getInt(Hub.KEY_NAV_CURVES, 0), pos -> {
                    prefs.edit().putInt(Hub.KEY_NAV_CURVES, pos).apply();
                    BridgeService.settingsChanged();
                }));
        c.addView(choiceRow("Which curves count", "How much a bend must turn to be shown", CURVES_INFO,
                new String[]{"Only tight ones", "Normal", "Also gentle ones", "All over 15°"}, prefs.getInt(Hub.KEY_SENSITIVITY, 1),
                pos -> prefs.edit().putInt(Hub.KEY_SENSITIVITY, pos).apply()));
        String[] la = new String[LOOKAHEADS.length];
        int laSel = 1;
        for (int i = 0; i < LOOKAHEADS.length; i++) {
            la[i] = LOOKAHEADS[i] + " m";
            if (LOOKAHEADS[i] == prefs.getInt(Hub.KEY_LOOKAHEAD, 1000)) laSel = i;
        }
        c.addView(choiceRow("Look ahead", "How far ahead to search for the next curve", la, laSel,
                pos -> prefs.edit().putInt(Hub.KEY_LOOKAHEAD, LOOKAHEADS[pos]).apply()));
        c.addView(switchRow("Phone battery", "The bike's display shows the phone's battery (4 bars) and when it is charging",
                Hub.KEY_PHONE_BATTERY, true));
        c.addView(switchRow("Set the dash clock from the phone", "Once per connection, like Rideology. Keeps the "
                + "shift lamp as it is set on the bike", Hub.KEY_CLOCK_SYNC, false));
        c.addView(switchRow("Call icon on the dash", "An icon while a call comes in; needs notification access. No numbers or names are sent",
                Hub.KEY_DASH_CALLS, false, v -> MapsListener.alertsChanged(this)));
        c.addView(switchRow("Mail and message icon on the dash", "An icon while a mail or message arrives (Gmail, Messages, LINE…); needs notification access",
                Hub.KEY_DASH_MAIL, false, v -> MapsListener.alertsChanged(this)));
        c.addView(switchRow("Distances in feet", "Instead of metres", Hub.KEY_IMPERIAL, false));
        c.addView(switchRow("Left-hand traffic", "UK, Ireland…: roundabouts turn clockwise", Hub.KEY_LEFT_HAND, false));
        return c;
    }

    private LinearLayout offlineCard() {
        LinearLayout c = card();
        c.addView(cardTitle("Offline maps"));
        c.addView(secondary("Curves are only shown where the roads are downloaded. Download the regions you ride "
                + "in, over Wi-Fi. Regions add up: e.g. Bayern now, Tirol later."));
        c.addView(secondary("Road data © OpenStreetMap contributors (ODbL)"));
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
        c.addView(offlineProgress, matchWrap());
        offlineStatus = secondary("");
        c.addView(offlineStatus);

        LinearLayout buttons = row();
        buttons.setPadding(0, dp(6), 0, 0);
        regionButton = primaryButton("Download", v -> downloadRegion());
        buttons.addView(regionButton, weight());
        c.addView(buttons);
        loadRegions();
        refreshRegions();
        return c;
    }

    /** Debug builds: seven taps on the title show or hide the debug card. */
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
        android.widget.Toast.makeText(this, show ? "Debug options shown" : "Debug options hidden",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    /** Hidden card to try values of the meter's status icon fields (13 0C frame, see PROTOCOL.md). */
    private LinearLayout debugCard() {
        LinearLayout c = card();
        c.addView(cardTitle("Dash icons (debug)"));
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
     * Hidden card for the vehicle data: which requests go out after connecting, the scaling of speed and
     * rpm, frame capture, and everything decoded so far next to the raw frames, to check against the dash.
     */
    private LinearLayout bikeDebugCard() {
        LinearLayout c = card();
        c.addView(cardTitle("Bike data (debug)"));
        c.addView(secondary("Decoded values next to the last raw frame of each type. Compare them with the dash "
                + "to check the scaling (PROTOCOL.md)."));
        c.addView(choiceRow("Requests after connecting", "Applies on the next connect. Full is Rideology's sequence: "
                        + "it adds the phone name, the 0x08 init and the 0x45 riding log frames",
                new String[]{"Capabilities only (v0.3)", "Read-only", "Full (like Rideology)"}, Hub.initLevel(this),
                pos -> prefs.edit().putInt(Hub.KEY_DEBUG_INIT_LEVEL, pos).apply()));
        c.addView(switchRow("Capture frames", "Every frame to and from the bike, one file per connect: "
                + "adb pull /sdcard/Android/data/" + getPackageName() + "/files/captures/", Hub.KEY_DEBUG_CAPTURE, false));
        LinearLayout reads = row();
        reads.setPadding(0, dp(6), 0, 0);
        for (int[] r : new int[][]{{BikeFrames.VEHICLE_INFO}, {BikeFrames.VEHICLE_SETTINGS}, {BikeFrames.SERVICE_INDICATOR}}) {
            int id = r[0];
            reads.addView(button("Read " + String.format(Locale.ROOT, "%02X", id), v -> android.widget.Toast.makeText(this,
                    BridgeService.read(id) ? "Asked the bike for " + BikeFrames.name(id) : "Bike not connected",
                    android.widget.Toast.LENGTH_SHORT).show()), weight());
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
        input.setHint("e.g. Hello Z900");
        input.setText(prefs.getString(Hub.KEY_DEBUG_PHONE_NAME, ""));
        r.addView(input, weight());
        r.addView(button("Send", v -> {
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
        }));
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

    private void updateUi() {
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
        // --- status
        int color;
        String title;
        if (!Hub.serviceRunning) {
            color = 0xFF9E9E9E;
            title = "Not running";
            boolean autostart = BikePresence.enabled(this) || prefs.getString(Hub.KEY_HELMET, null) != null;
            statusHint.setText(autostart && Hub.autostartPaused(this)
                    ? "Stopped by hand: the automatic start waits until the bike is switched off. Tap Start to connect now."
                    : "Tap Start before the ride, or set up an automatic start below.");
        } else {
            color = Hub.bikeConnected ? OK_GREEN : WARN_ORANGE;
            if (Hub.bikeStatus.startsWith("No paired")) color = ERROR_RED;
            title = Hub.bikeStatus;
            statusHint.setText(Hub.bikeHint);
        }
        statusDot.setTextColor(color);
        statusTitle.setText(title);
        statusHint.setVisibility(statusHint.getText().length() > 0 ? View.VISIBLE : View.GONE);
        Hub.Output out = Hub.output;
        if (!Hub.serviceRunning) {
            meterArrow.setText("–");
            meterText.setText("Nothing sent to the bike");
            meterSource.setText("");
        } else if (out == null) {
            meterArrow.setText("–");
            meterText.setText("Nothing to show");
            boolean maps = Hub.mapsEnabled(this);
            meterSource.setText(maps && Hub.maps != null ? "Google Maps is starting…"
                    : Hub.freeMode(this) ? (Hub.curveStatus.contains("·") ? "Waiting for GPS" : Hub.curveStatus)
                    : maps ? "Start navigation in Google Maps, or turn on upcoming curves"
                    : "Turn on Google Maps directions or upcoming curves");
        } else {
            meterArrow.setText(arrow(out.turn));
            meterText.setText(turnName(out.turn) + (out.distanceM >= 0 ? " · " + distance(out.distanceM) : ""));
            meterSource.setText("Maps".equals(out.source) ? "From Google Maps" : "Curve ahead on your road");
        }
        meterArrow.setTextColor(out != null && Hub.serviceRunning ? KAWASAKI_GREEN : 0xFF9E9E9E);
        startButton.setText(Hub.serviceRunning ? "Stop" : "Start");
        tint(startButton, Hub.serviceRunning ? 0xFF616161 : KAWASAKI_GREEN);

        // --- bike
        bikeTitle.setText(BikeActivity.name(Hub.bike, prefs.getString(Hub.KEY_BIKE_NAME, null)));
        String summary = BikeActivity.summary(Hub.bike);
        bikeSummary.setText(summary);
        bikeSummary.setVisibility(summary.isEmpty() ? View.GONE : View.VISIBLE);
        bikeUpdated.setText(Hub.bikeConnected && !summary.isEmpty() ? "Connected"
                : BikeActivity.lastRead(Hub.bike, System.currentTimeMillis()));

        // --- banner: what still keeps KawaNav from working fully
        android.companion.AssociationInfo bike = BikePresence.association(this);
        boolean anyAuto = bike != null || prefs.getString(Hub.KEY_HELMET, null) != null;
        boolean curves = Hub.freeMode(this) || prefs.getInt(Hub.KEY_NAV_CURVES, 0) > 0;
        boolean perms = missingPermissions().isEmpty();
        android.bluetooth.BluetoothAdapter bt = getSystemService(android.bluetooth.BluetoothManager.class).getAdapter();
        int missing = 0;
        missing += show(permRow, !perms);
        boolean btOn = perms && bt != null && bt.isEnabled();
        missing += show(bluetoothRow, perms && bt != null && !bt.isEnabled());
        missing += show(pairRow, btOn && BridgeService.pairedBike(this) == null);
        pairSub.setText(Hub.pairStatus.isEmpty() ? PAIR_HINT : Hub.pairStatus);
        missing += show(gpsRow, curves && !getSystemService(android.location.LocationManager.class).isLocationEnabled());
        boolean dashAlerts = prefs.getBoolean(Hub.KEY_DASH_CALLS, false) || prefs.getBoolean(Hub.KEY_DASH_MAIL, false);
        missing += show(accessRow, (Hub.mapsEnabled(this) || dashAlerts) && !hasNotificationAccess());
        missing += show(batteryRow, !batteryUnrestricted());
        missing += show(bgLocRow, curves && anyAuto && !hasBackgroundLocation());
        banner.setVisibility(missing > 0 ? View.VISIBLE : View.GONE);
        bannerTitle.setText(missing == 1 ? "1 setting needs your attention" : missing + " settings need your attention");

        // --- autostart
        autoBikeSub.setText(bike != null ? "On for " + bike.getDisplayName()
                : "Set up once next to the bike, ignition on, Rideology closed");
        autoBikeButton.setText(bike != null ? "Turn off" : "Set up");

        // --- riding
        freeModeStatus.setVisibility(Hub.serviceRunning && Hub.freeMode(this)
                ? View.VISIBLE : View.GONE);
        freeModeStatus.setText(Hub.curveStatus.contains("·") ? "Active" : Hub.curveStatus);

        // --- offline
        if (offlineWasRunning && !Hub.offlineRunning) refreshRegions(); // a download just ended
        offlineWasRunning = Hub.offlineRunning;
        offlineProgress.setVisibility(Hub.offlineRunning ? View.VISIBLE : View.GONE);
        boolean showStatus = Hub.offlineRunning || !"–".equals(Hub.offlineStatus);
        offlineStatus.setVisibility(showStatus ? View.VISIBLE : View.GONE);
        offlineStatus.setText(Hub.offlineStatus);
        regionButton.setText(Hub.offlineRunning ? "Cancel" : "Download");
        tint(regionButton, Hub.offlineRunning ? 0xFF616161 : KAWASAKI_GREEN);

    }

    /** Rebuilds the list of downloaded regions; sizes and update checks run in the background. */
    private void refreshRegions() {
        new Thread(() -> {
            List<OfflineRegions.Region> list = offlineRegions.list();
            java.util.Map<String, Long> sizes = new java.util.HashMap<>();
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

    private void showRegions(List<OfflineRegions.Region> list, java.util.Map<String, Long> sizes, long otherBytes) {
        regionsList.removeAllViews();
        long total = otherBytes;
        for (Long b : sizes.values()) total += b;
        storedView.setText(list.isEmpty() && otherBytes == 0 ? "No regions downloaded yet"
                : String.format(Locale.ROOT, "Downloaded · %.0f MB", Math.max(1, total / 1e6)));
        java.text.DateFormat df = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM);
        for (OfflineRegions.Region r : list) {
            Long server = serverDates.get(r.id);
            boolean update = server != null && server - r.dataDate >= OfflineRegions.UPDATE_AFTER_MS;
            String state = server == null ? "Checking for newer data…"
                    : server == 0 ? "Could not check for newer data (offline?)"
                    : update ? "Newer data available from " + df.format(new java.util.Date(server))
                    : "Up to date";
            String sub = String.format(Locale.ROOT, "Data from %s · %.0f MB\n%s",
                    df.format(new java.util.Date(r.dataDate)), Math.max(1, sizes.get(r.id) / 1e6), state);
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
        r.addView(button("Delete", v -> onDelete.run()), lp);
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

    /** Shows the banner row when needed; 1 if shown. */
    private static int show(View row, boolean needed) {
        row.setVisibility(needed ? View.VISIBLE : View.GONE);
        return needed ? 1 : 0;
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

    // ------------------------------------------------------------------ meter display

    private String distance(int m) {
        if (prefs.getBoolean(Hub.KEY_IMPERIAL, false)) return Math.round(m / 0.3048) + " ft";
        return m >= 1000 ? String.format(Locale.ROOT, "%.1f km", m / 1000.0) : m + " m";
    }

    static String turnName(int turn) {
        if (turn >= 18 && turn <= 29) return "Roundabout, exit " + (turn - 17);
        if (turn >= 30 && turn <= 41) return "Roundabout, exit " + (turn - 29);
        return turn >= 0 && turn < KawaProtocol.TURN_NAMES.length ? KawaProtocol.TURN_NAMES[turn] : "?";
    }

    /** A symbol close to the meter's arrow. */
    static String arrow(int turn) {
        switch (turn) {
            case 0: case 11: case 42: return "↑";
            case 1: return "↰";
            case 2: return "↱";
            case 3: return "↙";
            case 4: return "↘";
            case 5: case 9: case 12: case 14: case 16: return "↖";
            case 6: case 10: case 13: case 15: case 17: return "↗";
            case 7: return "↶";
            case 8: return "↷";
            case 43: return "◎";
            default:
                if (turn >= 18 && turn <= 29) return "↻";
                if (turn >= 30 && turn <= 41) return "↺";
                return "•";
        }
    }

    private void toggleBridge() {
        if (Hub.serviceRunning) {
            BridgeService.stop(this);
            return;
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestMissingPermissions();
            return;
        }
        BridgeService.start(this);
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
                regionInfo.setText(size < 0 ? r.label() : String.format(java.util.Locale.ROOT,
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
            new android.app.AlertDialog.Builder(this)
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
