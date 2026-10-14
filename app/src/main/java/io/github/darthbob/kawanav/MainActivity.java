package io.github.darthbob.kawanav;

import android.Manifest;
import android.app.AlertDialog;
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
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Home tab: the link to the bike and what the dash shows right now, the ride being recorded,
 * the bike's last values, and a menu of tiles, like Rideology's home screen. Settings that keep
 * KawaNav from working show up in the orange banner, and the first start walks through them.
 * Test switches are adb-only, see {@link #handleAdbIntent}; the log is in files/kawanav.log.
 */
public class MainActivity extends KawaActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());

    // status
    private TextView statusChip;
    private TextView statusHint;
    private TextView meterArrow;
    private Figure meterDistance;
    private TextView meterText;
    private TextView meterSource;
    // speed camera warning (offline map data)
    private LinearLayout cameraCard;
    private TextView cameraText;
    // warnings the bike sends: tyres, ABS, battery
    private LinearLayout alertCard;
    private TextView alertText;
    private LinearLayout meterTextBlock;
    private Button startButton;
    // ride being recorded
    private LinearLayout rideStrip;
    private TextView rideTitle;
    private TextView rideStatus;
    private Button ridePauseButton;
    // bike
    private TextView bikeTitle;
    private Figure bikeRange;
    private SegmentBar bikeFuel;
    private TextView bikeFuelText;
    private Figure bikeOdometer;
    private TextView bikeUpdated;
    private LinearLayout bikeStats;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE); // the screen has its own title
        Logger.init(this);
        prefs = Hub.prefs(this);
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

    /** Copies the log (and a notification capture) to Download/KawaNav, see {@link LogExport}. */
    private void exportLog() {
        LogExport.export(this);
    }

    /**
     * Test switches from a PC, e.g.
     * adb shell am start -n io.github.jackyes.kawanav/io.github.darthbob.kawanav.MainActivity --ez bridge true|false
     * Also: --ez free_mode true|false, --es offline_region bayern, --ez offline_cancel true, --ez export_log true,
     * --ez notif_capture true|false, --ez notif_capture_all true|false, --ez cameras true|false,
     * --ei camera_warn 500 (debug builds)
     */
    private void handleAdbIntent(Intent i) {
        if (i == null) return;
        if (i.hasExtra("free_mode")) {
            prefs.edit().putBoolean(Hub.KEY_FREE_MODE, i.getBooleanExtra("free_mode", false)).apply();
            BridgeService.settingsChanged();
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
            if (i.hasExtra("cameras")) {
                prefs.edit().putBoolean(Hub.KEY_CAMERAS, i.getBooleanExtra("cameras", false)).apply();
                BridgeService.settingsChanged();
            }
            if (i.hasExtra("camera_warn")) {
                prefs.edit().putInt(Hub.KEY_CAMERA_WARN, i.getIntExtra("camera_warn", Hub.CAMERA_WARN_DEFAULT)).apply();
            }
            if (i.hasExtra("notif_capture")) {
                prefs.edit().putBoolean(Hub.KEY_DEBUG_NOTIF_CAPTURE, i.getBooleanExtra("notif_capture", false)).apply();
                if (!i.getBooleanExtra("notif_capture", false)) NotifCapture.stop();
            }
            if (i.hasExtra("notif_capture_all")) {
                prefs.edit().putBoolean(Hub.KEY_DEBUG_NOTIF_CAPTURE_ALL, i.getBooleanExtra("notif_capture_all", false)).apply();
            }
            // open a screen directly, e.g. --es screen rides | bike | settings | ride --es ride <id>
            if (i.hasExtra("screen")) {
                String screen = i.getStringExtra("screen");
                if ("rides".equals(screen)) openTab(TAB_RIDES, null);
                else if ("bike".equals(screen)) openTab(TAB_BIKE, null);
                else if ("settings".equals(screen)) openTab(TAB_SETTINGS, i.getStringExtra(EXTRA_SECTION));
                else if ("fuel".equals(screen)) startActivity(new Intent(this, FuelActivity.class));
                else if ("ride".equals(screen)) {
                    Intent target = new Intent(this, RideActivity.class);
                    if (i.hasExtra("ride")) target.putExtra(RideActivity.EXTRA_ID, i.getStringExtra("ride"));
                    startActivity(target);
                }
            }
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
        updateUi();
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
        LinearLayout root = column();
        root.setPadding(dp(Ui.SPACE4), dp(Ui.SPACE3), dp(Ui.SPACE4), dp(Ui.SPACE6));

        LinearLayout head = row();
        head.setPadding(0, dp(Ui.SPACE2), 0, dp(Ui.SPACE2));
        head.setMinimumHeight(dp(56));
        TextView title = display("KawaNav");
        head.addView(title, weight());
        statusChip = pill("", Ui.surface(night), Ui.inkSecondary(night));
        statusChip.setTextSize(13);
        statusChip.setPadding(dp(10), dp(5), dp(12), dp(5));
        head.addView(statusChip);
        root.addView(head);

        root.addView(banner());
        root.addView(bikeAlertCard());
        root.addView(cameraWarning());
        root.addView(dashPanel());
        root.addView(rideStrip());
        root.addView(sectionHeader(R.drawable.ic_moto, "My vehicle"));
        root.addView(vehicleCard());
        root.addView(sectionHeader(R.drawable.ic_grid, "Menu"));
        root.addView(menu());
        return tabPage(TAB_HOME, root);
    }

    /** Warnings from the bike (tyre pressure, TPMS sensor, ABS, 12 V battery), while they last. */
    private LinearLayout bikeAlertCard() {
        alertCard = column();
        alertCard.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.dangerFill(night));
        bg.setCornerRadius(dp(16));
        alertCard.setBackground(bg);
        LinearLayout r = row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView warn = icon(R.drawable.ic_alert, false);
        warn.setColorFilter(0xFFFFFFFF);
        r.addView(warn, iconGap());
        alertText = text(15, true);
        alertText.setTextColor(0xFFFFFFFF);
        r.addView(alertText, weight());
        alertCard.addView(r);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.setMargins(0, dp(6), 0, dp(6));
        alertCard.setLayoutParams(lp);
        alertCard.setVisibility(View.GONE);
        return alertCard;
    }

    /**
     * The speed camera ahead, while it is inside the warning distance: the one thing the rider cannot
     * see coming. Hidden the rest of the time (the dash shows it too, see BridgeService).
     */
    private LinearLayout cameraWarning() {
        cameraCard = column();
        cameraCard.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.warnFill());
        bg.setCornerRadius(dp(16));
        cameraCard.setBackground(bg);
        LinearLayout r = row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.addView(icon(R.drawable.ic_camera, false), iconGap());
        LinearLayout t = column();
        cameraText = itemTitle("");
        cameraText.setTextColor(Ui.bannerInk());
        cameraText.setTextSize(16);
        t.addView(cameraText);
        TextView sub = secondary("From the offline map data");
        sub.setTextColor(Ui.bannerInk());
        t.addView(sub);
        r.addView(t, weight());
        cameraCard.addView(r);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.setMargins(0, 0, 0, dp(Ui.SPACE3));
        cameraCard.setLayoutParams(lp);
        cameraCard.setVisibility(View.GONE);
        return cameraCard;
    }

    /**
     * What the rider checks most: what the dash shows right now (the arrow and its distance, big, in a
     * dark panel like the dash itself), the link to the bike, and Start / Stop.
     */
    private LinearLayout dashPanel() {
        LinearLayout c = panel();
        TextView label = secondary("On the dash now");
        c.addView(label);
        LinearLayout meter = row();
        meter.setPadding(0, dp(Ui.SPACE2), 0, dp(Ui.SPACE2));
        meterArrow = text(52, true);
        meterArrow.setGravity(Gravity.CENTER);
        meterArrow.setMinWidth(dp(68));
        meterArrow.setTextColor(Ui.accent(night));
        meter.addView(meterArrow);
        LinearLayout mt = column();
        meterTextBlock = mt;
        meterDistance = figure(40, Gravity.START);
        mt.addView(meterDistance.view);
        meterText = text(16, false);
        meterText.setTypeface(Ui.titles());
        mt.addView(meterText);
        meterSource = text(13, false);
        meterSource.setTextColor(Ui.highlight(night));
        mt.addView(meterSource);
        meter.addView(mt, weight());
        c.addView(meter);
        statusHint = secondary("");
        statusHint.setPadding(0, 0, 0, dp(Ui.SPACE3));
        c.addView(statusHint);
        startButton = primaryButton("", v -> toggleBridge());
        c.addView(startButton, matchWrap());
        return c;
    }

    /** While a ride is recorded: its time and distance, Pause / Continue and Stop. Gone otherwise. */
    private LinearLayout rideStrip() {
        LinearLayout c = card();
        LinearLayout head = row();
        rideTitle = text(16, false);
        rideTitle.setTypeface(Ui.titles());
        head.addView(rideTitle, weight());
        c.addView(head);
        rideStatus = number(20);
        rideStatus.setPadding(0, dp(Ui.SPACE1), 0, dp(Ui.SPACE2));
        c.addView(rideStatus);
        LinearLayout buttons = row();
        ridePauseButton = secondaryButton("Pause", v -> {
            BridgeService.toggleRidePause();
            handler.postDelayed(this::updateUi, 300);
        });
        buttons.addView(ridePauseButton, weight());
        View gap = new View(this);
        buttons.addView(gap, new LinearLayout.LayoutParams(dp(Ui.SPACE2), 1));
        buttons.addView(neutralButton("Stop ride", v -> {
            BridgeService.stopRide();
            handler.postDelayed(this::updateUi, 300);
        }), weight());
        c.addView(buttons);
        c.setVisibility(View.GONE);
        rideStrip = c;
        return c;
    }

    /**
     * The bike's last values, like Rideology's "My vehicle": its name, the range, the fuel gauge and the
     * odometer. The card opens the Bike tab with everything else.
     */
    private LinearLayout vehicleCard() {
        LinearLayout c = card();
        LinearLayout head = row();
        bikeTitle = text(17, false);
        bikeTitle.setTypeface(Ui.titles());
        head.addView(bikeTitle, weight());
        head.addView(chevron());
        c.addView(head);
        bikeUpdated = secondary("");
        c.addView(bikeUpdated);

        bikeStats = row();
        bikeStats.setPadding(0, dp(Ui.SPACE3), 0, dp(Ui.SPACE1));
        bikeStats.setGravity(Gravity.BOTTOM);
        LinearLayout range = column();
        bikeRange = figure(28, Gravity.START);
        range.addView(bikeRange.view);
        range.addView(note("Range"));
        bikeStats.addView(range, weight());
        LinearLayout fuel = column();
        bikeFuel = new SegmentBar(this);
        fuel.addView(bikeFuel, new LinearLayout.LayoutParams(dp(96), dp(14)));
        bikeFuelText = note("Fuel");
        bikeFuelText.setPadding(0, dp(Ui.SPACE2), 0, 0);
        fuel.addView(bikeFuelText);
        bikeStats.addView(fuel, weight());
        LinearLayout odo = column();
        bikeOdometer = figure(22, Gravity.START);
        odo.addView(bikeOdometer.view);
        odo.addView(note("Odometer"));
        bikeStats.addView(odo, weight());
        c.addView(bikeStats);
        c.setOnClickListener(v -> openTab(TAB_BIKE, null));
        return c;
    }

    /** Rideology's tile menu: the places a rider goes to now and then. */
    private View menu() {
        return statGrid(3,
                menuTile(R.drawable.ic_list, "Recorded rides", v -> openTab(TAB_RIDES, null)),
                menuTile(R.drawable.ic_pin, "Walk to the bike", v -> walkToBike()),
                menuTile(R.drawable.ic_map, "Offline maps", v -> openTab(TAB_SETTINGS, "maps")),
                menuTile(R.drawable.ic_power, "Start automatically", v -> openTab(TAB_SETTINGS, "autostart")),
                menuTile(R.drawable.ic_road, "On the road", v -> openTab(TAB_SETTINGS, "road")),
                menuTile(R.drawable.ic_bell, "Dash icons", v -> openTab(TAB_SETTINGS, "dash")));
    }

    /** Opens Maps on foot to where the bike was parked (where the link to it was lost). */
    private void walkToBike() {
        BikeData.Place p = Hub.bike.parked;
        if (p == null) {
            android.widget.Toast.makeText(this, "No parked position yet: it is saved when the link to the bike is lost",
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        android.net.Uri uri = android.net.Uri.parse(String.format(Locale.ROOT,
                "https://www.google.com/maps/dir/?api=1&destination=%.6f,%.6f&travelmode=walking", p.lat, p.lon));
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (android.content.ActivityNotFoundException e) {
            android.widget.Toast.makeText(this, "No maps app found", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void updateUi() {
        updateTabBar();
        // --- status: a short state in the chip, the details in the panel
        String chip;
        int chipInk;
        if (!Hub.serviceRunning) {
            chip = "Off";
            chipInk = Ui.inkSecondary(night);
            boolean autostart = BikePresence.enabled(this) || prefs.getString(Hub.KEY_HELMET, null) != null;
            statusHint.setText(autostart && Hub.autostartPaused(this)
                    ? "Stopped by hand: the automatic start waits until the bike is switched off. Start connects now."
                    : "Start before the ride, or let KawaNav start by itself (Settings).");
        } else {
            boolean noBike = Hub.bikeStatus.startsWith("No paired");
            chip = Hub.bikeConnected ? "Connected" : noBike ? "Not paired" : "Searching";
            chipInk = Hub.bikeConnected ? Ui.ok(night) : noBike ? Ui.danger(night) : Ui.warn(night);
            statusHint.setText(Hub.bikeStatus + (Hub.bikeHint.isEmpty() ? "" : "\n" + Hub.bikeHint));
        }
        String chipText = "● " + chip;
        if (!chipText.contentEquals(statusChip.getText())) statusChip.setText(chipText);
        statusChip.setTextColor(chipInk);

        Hub.Output out = Hub.output;
        // the bike's own warnings, one line each, while they last
        java.util.List<BikeAlerts.Alert> alerts = Hub.bikeAlerts;
        alertCard.setVisibility(alerts.isEmpty() ? View.GONE : View.VISIBLE);
        if (!alerts.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (BikeAlerts.Alert a : alerts) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(a.title).append(" — ").append(a.detail);
            }
            if (!sb.toString().contentEquals(alertText.getText())) alertText.setText(sb);
        }
        // the camera warning is shown while the bridge says one is ahead (it also goes to the dash)
        boolean camera = out != null && out.alert != null;
        cameraCard.setVisibility(camera ? View.VISIBLE : View.GONE);
        if (camera && !out.alert.contentEquals(cameraText.getText())) cameraText.setText(out.alert);
        if (!Hub.serviceRunning || out == null) {
            meterArrow.setVisibility(View.GONE);
            meterDistance.view.setVisibility(View.GONE);
            meterText.setText(Hub.serviceRunning ? "Nothing to show" : "Nothing sent to the bike");
            boolean maps = Hub.mapsEnabled(this);
            meterSource.setText(!Hub.serviceRunning ? ""
                    : maps && Hub.maps != null ? "Google Maps is starting…"
                    : Hub.freeMode(this) ? (Hub.curveStatus.contains("·") ? "Waiting for GPS" : Hub.curveStatus)
                    : maps ? "Start navigation in Google Maps, or turn on upcoming curves"
                    : "Turn on Google Maps directions or upcoming curves");
        } else {
            meterArrow.setVisibility(View.VISIBLE);
            meterArrow.setText(arrow(out.turn));
            boolean dist = out.distanceM >= 0;
            meterDistance.view.setVisibility(dist ? View.VISIBLE : View.GONE);
            if (dist) meterDistance.set(distance(out.distanceM));
            meterText.setText(turnName(out.turn));
            meterSource.setText("Maps".equals(out.source) ? "From Google Maps"
                    : "Camera".equals(out.source) ? "Speed camera from the offline map data"
                    : "Curve ahead on your road");
        }
        meterSource.setVisibility(meterSource.getText().length() > 0 ? View.VISIBLE : View.GONE);
        // the text sits next to the arrow, or flush left when there is none
        meterTextBlock.setPadding(meterArrow.getVisibility() == View.VISIBLE ? dp(Ui.SPACE3) : 0, 0, 0, 0);
        startButton.setText(Hub.serviceRunning ? "Stop KawaNav" : "Start KawaNav");
        if (Hub.serviceRunning) neutral(startButton);
        else primary(startButton);

        // --- ride being recorded
        boolean recording = Hub.rideState != RideRecorder.IDLE;
        rideStrip.setVisibility(recording ? View.VISIBLE : View.GONE);
        if (recording) {
            boolean paused = Hub.rideState == RideRecorder.PAUSED;
            rideTitle.setText(paused ? "● Ride paused" : "● Recording the ride");
            rideTitle.setTextColor(paused ? Ui.warn(night) : Ui.recording());
            rideStatus.setText(Hub.rideText);
            ridePauseButton.setText(paused ? "Continue" : "Pause");
        }

        // --- bike
        BikeData b = Hub.bike;
        bikeTitle.setText(BikeActivity.name(b, prefs.getString(Hub.KEY_BIKE_NAME, null)));
        BikeFrames.VehicleInfo info = b.info;
        bikeStats.setVisibility(info != null ? View.VISIBLE : View.GONE);
        if (info != null) {
            bikeRange.set(info.rangeKm >= 0 ? info.rangeKm + " km" : "–");
            bikeOdometer.set(info.odometerKm >= 0 ? String.format(Locale.ROOT, "%,d km", info.odometerKm) : "–");
            bikeFuel.set(info.fuelBars, BikeActivity.fuelSegments(info.fuelBars));
            bikeFuelText.setText(info.fuelBars >= 0 ? "Fuel · " + info.fuelBars + (info.fuelBars == 1 ? " bar" : " bars") : "Fuel");
        }
        bikeUpdated.setText(Hub.bikeConnected && info != null ? "Connected"
                : info == null ? "Odometer, fuel, temperatures, maintenance and riding modes appear once the bike is connected"
                : BikeActivity.lastRead(b, System.currentTimeMillis()));

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
    }

    /** Shows the banner row when needed; 1 if shown. */
    private static int show(View row, boolean needed) {
        row.setVisibility(needed ? View.VISIBLE : View.GONE);
        return needed ? 1 : 0;
    }

    /** Orange banner on top: what still keeps KawaNav from working fully, each with a button to fix it. */
    private LinearLayout banner() {
        banner = column();
        banner.setPadding(dp(16), dp(14), dp(16), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.warnFill());
        bg.setCornerRadius(dp(16));
        banner.setBackground(bg);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.setMargins(0, dp(6), 0, dp(6));
        banner.setLayoutParams(lp);
        bannerTitle = text(17, true);
        bannerTitle.setTextColor(Ui.bannerInk());
        LinearLayout bannerHead = row();
        android.widget.ImageView warn = icon(R.drawable.ic_alert, false);
        warn.setColorFilter(Ui.bannerInk());
        bannerHead.addView(warn);
        LinearLayout.LayoutParams warnLp = weight();
        warnLp.leftMargin = dp(Ui.SPACE2);
        bannerHead.addView(bannerTitle, warnLp);
        banner.addView(bannerHead);
        TextView sub = text(13, false);
        sub.setTextColor(Ui.bannerInk());
        sub.setText("For the best experience on the bike:");
        banner.addView(sub);
        permRow = bannerRow("Allow Bluetooth, location and notifications",
                "To talk to the bike and find the curves ahead", "Allow", v -> requestMissingPermissions());
        bluetoothRow = bannerRow("Turn on Bluetooth", "KawaNav cannot reach the bike while it is off", "Turn on",
                v -> enableBluetooth());
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
        tt.setTextColor(Ui.bannerInk());
        tt.setText(title);
        t.addView(tt);
        TextView st = text(13, false);
        st.setTextColor(Ui.bannerInk());
        st.setAlpha(0.8f);
        st.setText(sub);
        t.addView(st);
        r.addView(t, weight());
        Button b = button(action, l);
        b.setTextColor(Ui.bannerInk());
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setStateListAnimator(null);
        b.setMinWidth(dp(72));
        GradientDrawable bb = new GradientDrawable();
        bb.setColor(0xFFFFFFFF);
        bb.setCornerRadius(dp(22));
        b.setBackground(bb);
        b.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        b.setMinHeight(dp(Ui.TOUCH_MIN));
        lp.setMargins(dp(10), 0, 0, 0);
        r.addView(b, lp);
        r.setOnClickListener(l);
        r.setTag(st); // the subtitle, for rows that show progress
        return r;
    }

    private static final String PAIR_HINT = "Ignition on and Rideology closed, then pick your bike";

    /** Pairs the phone with the bike (and sets up autostart), see {@link BikePresence#pair}. */
    /**
     * Asks Android to switch Bluetooth on. The row only shows with the Bluetooth permission, but the
     * permission can be taken back while the screen is open: ask for it again then.
     */
    private void enableBluetooth() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestMissingPermissions();
            return;
        }
        startActivity(new Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE));
    }

    private void pairBike() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestMissingPermissions();
            return;
        }
        BikePresence.pair(this);
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
}
