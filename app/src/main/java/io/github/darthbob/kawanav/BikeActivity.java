package io.github.darthbob.kawanav;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.graphics.drawable.GradientDrawable;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Bike tab, like Rideology's vehicle information: the range big in the middle with the fuel gauge
 * and the battery under it, then the dash's values (odometer, trips…), live engine values while
 * connected, maintenance reminders, riding modes, the dash clock, and where the bike was parked.
 * Shows the last values read, also while the bike is off.
 */
public class BikeActivity extends KawaActivity {

    /** Live values (riding log) count as current this long. */
    private static final long LIVE_MS = 10_000;
    /** On both write cards and their confirmations, until the writes have been tried on a bike. */
    private static final String NOT_TRIED = "Not tried on a bike yet: check the dash after sending.";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView title;
    private TextView vin;
    private TextView updated;
    private Hero range;
    private SegmentBar fuel;
    private TextView fuelText;
    private TextView battery;
    private LinearLayout sections;
    // ride settings (debug builds until this has been tried on a bike)
    private LinearLayout settingsCard;
    private android.widget.Spinner modeSpinner, powerSpinner, ktrcSpinner, kqsSpinner;
    /** Says how many riding modes the bike announced, so "Rider 4" missing from the list makes sense. */
    private TextView modeNote;
    private TextView settingsStatus;
    private Button settingsSend;
    private LinearLayout lampCard;
    private android.widget.Spinner lampStatusSpinner, lampRpmSpinner;
    private TextView lampStatusText;
    private Button lampSend;
    private int lampMinLevel = 1;
    /** The ride-settings pickers were filled from the bike once. */
    private boolean settingsPrefilled;
    private LinearLayout parkedCard;
    private TextView parkedText;
    private Button parkedNavigate;
    /** The icon of a value group: what the rows behind it are about. Unknown groups get none. */
    private static int sectionIcon(String section) {
        switch (section) {
            case BASIC: return R.drawable.ic_gauge;
            case "Engine": return R.drawable.ic_thermo;
            case "Maintenance": return R.drawable.ic_sliders;
            case "Dash": return R.drawable.ic_clock;
            default:
                return section.startsWith("Riding modes") ? R.drawable.ic_sliders : 0;
        }
    }

    /** The icon in front of a value, by what it is. */
    private static int rowIcon(String label) {
        switch (label) {
            case "Odometer": case "Engine speed": return R.drawable.ic_gauge;
            case "Trip A": case "Trip B": return R.drawable.ic_route;
            case "Average fuel economy": case "Fuel consumption": return R.drawable.ic_fuel;
            case "Average speed": case "Speed": return R.drawable.ic_speed;
            case "Total time": case "Clock (when read)": return R.drawable.ic_clock;
            case "Battery": return R.drawable.ic_battery;
            case "Outside temperature": case "Coolant temperature": case "Oil temperature":
            case "Intake air temperature": return R.drawable.ic_thermo;
            default: return 0;
        }
    }

    /** The dash's values, under the range and the fuel gauge (Rideology's "Basic information"). */
    static final String BASIC = "Basic information";

    /** Row labels shown now; the sections are only rebuilt when they change (keeps the scroll position). */
    private List<String> shownLabels = new ArrayList<>();
    private final List<Figure> valueViews = new ArrayList<>();
    private boolean vinShown;
    private TextView fuelLine;
    private TextView maintenanceLine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        Logger.init(this);
        prefs = Hub.prefs(this);
        BikeStore.load(this);
        // the diary cards are filled by the bridge: opened without it (or before it has loaded) they
        // would claim there is nothing while the diary screens show the log
        if (Hub.fuelEntries.isEmpty()) {
            java.util.List<FuelLog.Entry> fuel = new FuelStore(this).list();
            if (fuel != null) Hub.fuelEntries = fuel;
        }
        if (Hub.maintenanceEntries.isEmpty()) {
            java.util.List<MaintenanceLog.Entry> work = new MaintenanceStore(this).list();
            if (work != null) Hub.maintenanceEntries = work;
        }
        night = isNight();
        LinearLayout root = column();
        root.setPadding(dp(Ui.SPACE4), 0, dp(Ui.SPACE4), dp(Ui.SPACE6));
        root.addView(headerBar("Your bike", null, headerAction(R.drawable.ic_refresh, "Read the values again", v -> {
            boolean asked = BridgeService.read(BikeFrames.VEHICLE_INFO);
            android.widget.Toast.makeText(this, asked ? "Asked the bike for its values" : "The bike is not connected",
                    android.widget.Toast.LENGTH_SHORT).show();
        })));
        root.addView(rangeCard());
        sections = column();
        root.addView(sections);
        root.addView(fuelCard());
        root.addView(maintenanceCard());
        battery.setOnClickListener(v -> openBatteryLog());
        root.addView(settingsCard());
        root.addView(lampCard());
        root.addView(parkedCard());
        TextView note = note("Values as the bike reported them when it was last connected. Tap the VIN to show "
                + "all of it.");
        note.setPadding(dp(Ui.SPACE1), dp(Ui.SPACE5), dp(Ui.SPACE1), dp(Ui.SPACE2));
        root.addView(note);
        setContentView(tabPage(TAB_BIKE, root));
    }

    /**
     * The top of the screen, like Rideology's vehicle information: the bike's name and VIN, the range
     * big in the middle, the fuel gauge in segments, the battery and when the values were read.
     */
    private LinearLayout rangeCard() {
        LinearLayout c = card();
        c.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        title = text(17, false);
        title.setTypeface(Ui.titles());
        title.setGravity(android.view.Gravity.CENTER);
        c.addView(title);
        vin = note("");
        vin.setGravity(android.view.Gravity.CENTER);
        vin.setOnClickListener(v -> vinShown = !vinShown);
        c.addView(vin);
        range = hero(52);
        range.view.setPadding(0, dp(Ui.SPACE4), 0, dp(Ui.SPACE3));
        c.addView(range.view, matchWrap());

        LinearLayout fuelRow = row();
        fuelRow.setPadding(0, dp(Ui.SPACE1), 0, dp(Ui.SPACE2));
        fuelRow.addView(icon(R.drawable.ic_fuel, true), iconGap());
        fuel = new SegmentBar(this);
        fuelRow.addView(fuel, new LinearLayout.LayoutParams(0, dp(16), 1f));
        fuelText = secondary("");
        fuelText.setPadding(dp(Ui.SPACE3), 0, 0, 0);
        fuelText.setMinWidth(dp(56));
        fuelRow.addView(fuelText);
        c.addView(fuelRow, matchWrap());

        LinearLayout batteryRow = row();
        batteryRow.setPadding(0, dp(Ui.SPACE1), 0, 0);
        batteryRow.addView(icon(R.drawable.ic_battery, true), iconGap());
        battery = secondary("");
        batteryRow.addView(battery, weight());
        c.addView(batteryRow, matchWrap());
        updated = note("");
        updated.setGravity(android.view.Gravity.CENTER);
        updated.setPadding(0, dp(Ui.SPACE3), 0, 0);
        c.addView(updated, matchWrap());
        return c;
    }

    /** Segments of the fuel gauge: six, or as many bars as the bike reports if that is more. */
    static int fuelSegments(int bars) {
        return Math.max(6, bars);
    }

    @Override
    protected void onResume() {
        super.onResume();
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
            update();
            handler.postDelayed(this, 1000);
        }
    };

    /**
     * Sends ride settings to the bike: riding mode, power, KTRC and KQS. The write has not been tried on a
     * bike yet, so the card says so; it is only sent while the bike is standing still, in neutral and with
     * the throttle closed (the app itself only wants it standing still).
     */
    private LinearLayout settingsCard() {
        LinearLayout section = column();
        section.addView(sectionHeader(R.drawable.ic_sliders, "Ride settings"));
        LinearLayout c = group();
        c.addView(secondary("Writes riding mode, power, KTRC and KQS to the bike, like Rideology's tuning "
                + "screen. The bike must be standing still, in neutral and with the throttle closed. "
                + NOT_TRIED));
        modeSpinner = spinner(java.util.Arrays.copyOf(SettingsWrite.MODES, SettingsWrite.WRITABLE_MODES), 0);
        modeNote = secondary("");
        powerSpinner = spinner(SettingsWrite.POWER, 0);
        ktrcSpinner = spinner(SettingsWrite.KTRC, 0);
        kqsSpinner = spinner(SettingsWrite.ON_OFF, 0);
        c.addView(modeNote);
        addRow(c, settingRowWith("Riding mode", modeSpinner));
        addRow(c, settingRowWith("Power", powerSpinner));
        addRow(c, settingRowWith("KTRC", ktrcSpinner));
        addRow(c, settingRowWith("KQS", kqsSpinner));
        settingsStatus = secondary("");
        settingsStatus.setPadding(0, dp(Ui.SPACE2), 0, dp(10));
        c.addView(settingsStatus);
        settingsSend = primaryButton("Send to the bike", v -> confirmSend());
        c.addView(settingsSend, matchWrap());
        section.addView(c);
        section.setVisibility(View.GONE);
        settingsCard = section;
        return section;
    }

    private LinearLayout settingRowWith(String label, android.widget.Spinner s) {
        LinearLayout r = settingRow();
        r.addView(itemTitle(label), weight());
        r.addView(s, controlWidth());
        return r;
    }

    private void confirmSend() {
        BikeFrames.VehicleSettings s = Hub.bike.settings;
        String now = s == null ? "unknown" : "mode " + s.ridingMode + ", power " + s.power + ", KTRC " + s.ktrc
                + ", KQS " + s.kqs + " (numbers as the bike reports them)";
        new android.app.AlertDialog.Builder(this)
                .setTitle("Send settings to the bike?")
                .setMessage("The bike must stand still, be in neutral and have the throttle closed. KawaNav "
                        + "re-checks this before sending and reads the settings back afterwards.\n\nOn the bike now: "
                        + now + "\n\nSending: " + SettingsWrite.MODES[modeSpinner.getSelectedItemPosition()] + ", power "
                        + SettingsWrite.POWER[powerSpinner.getSelectedItemPosition()] + ", KTRC "
                        + SettingsWrite.KTRC[ktrcSpinner.getSelectedItemPosition()] + ", KQS "
                        + SettingsWrite.ON_OFF[kqsSpinner.getSelectedItemPosition()] + ".\n\n" + NOT_TRIED)
                .setPositiveButton("Send", (d, w) -> {
                    // spinner positions start at 0, the bike's numbers at 1; KEBC goes back as the bike has it
                    BikeFrames.VehicleSettings now2 = Hub.bike.settings;
                    int kebc = now2 != null && now2.kebc >= 0 ? now2.kebc : 0;
                    String problem = BridgeService.writeSettings(modeSpinner.getSelectedItemPosition() + 1,
                            powerSpinner.getSelectedItemPosition() + 1, ktrcSpinner.getSelectedItemPosition(),
                            kqsSpinner.getSelectedItemPosition() + 1, kebc, "");
                    if (problem != null) {
                        settingsStatus.setText("Not sent: " + problem);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * The dash's shift lamp: when it lights and at which engine speed. Like the ride-settings write it has
     * not been tried on a bike yet; the meter's own limits (0x1A) decide which statuses and levels are
     * offered.
     */
    private LinearLayout lampCard() {
        LinearLayout section = column();
        section.addView(sectionHeader(R.drawable.ic_clock, "Dash shift lamp"));
        LinearLayout c = group();
        c.addView(secondary("Writes only the shift lamp; the dash clock stays as it is. The bike must be "
                + "connected (it does not have to be standing still). " + NOT_TRIED));
        lampStatusSpinner = spinner(new String[]{"Off"}, 0);
        lampRpmSpinner = spinner(new String[]{"3,000 rpm"}, 0);
        addRow(c, settingRowWith("Lights up", lampStatusSpinner));
        addRow(c, settingRowWith("At", lampRpmSpinner));
        lampStatusText = secondary("");
        lampStatusText.setPadding(0, dp(Ui.SPACE2), 0, dp(10));
        c.addView(lampStatusText);
        lampSend = primaryButton("Send to the dash", v -> confirmShiftLamp());
        c.addView(lampSend, matchWrap());
        section.addView(c);
        section.setVisibility(View.GONE);
        lampCard = section;
        return section;
    }

    /** Builds the two drop-downs from what the meter said it takes (0x1A capability). */
    private void fillLampChoices(BikeFrames.GeneralCaps caps) {
        if (caps == null || !caps.shiftLampSettable) return;
        lampMinLevel = caps.shiftLampMin;
        int levels = Math.max(1, caps.shiftLampMax - caps.shiftLampMin + 1);
        String[] rpm = new String[levels];
        for (int i = 0; i < levels; i++) {
            rpm[i] = String.format(Locale.ROOT, "%,d rpm", SettingsWrite.lampRpm(caps.shiftLampMin + i));
        }
        lampRpmSpinner.setAdapter(new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, rpm));
        String[] statuses = SettingsWrite.lampStatusNames(caps.shiftLampMode);
        lampStatusSpinner.setAdapter(new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, statuses));
        BikeFrames.GeneralSettings g = Hub.bike.general;
        if (g != null) {
            if (g.shiftLampStatus >= 0 && g.shiftLampStatus < statuses.length) {
                lampStatusSpinner.setSelection(g.shiftLampStatus);
            }
            if (g.shiftLampLevel >= caps.shiftLampMin) {
                lampRpmSpinner.setSelection(Math.min(levels - 1, g.shiftLampLevel - caps.shiftLampMin));
            }
        }
    }

    private void confirmShiftLamp() {
        int status = lampStatusSpinner.getSelectedItemPosition();
        int level = lampMinLevel + lampRpmSpinner.getSelectedItemPosition();
        new android.app.AlertDialog.Builder(this)
                .setTitle("Send the shift lamp to the dash?")
                .setMessage("The dash will light its shift lamp at "
                        + String.format(Locale.ROOT, "%,d rpm", SettingsWrite.lampRpm(level))
                        + (status == 0 ? ", or not at all." : ", in the mode " + lampStatusSpinner.getSelectedItem() + ".")
                        + "\n\n" + NOT_TRIED)
                .setPositiveButton("Send", (d, w) -> {
                    String problem = BridgeService.writeShiftLamp(status, level);
                    if (problem != null) lampStatusText.setText("Not sent: " + problem);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private LinearLayout parkedCard() {
        LinearLayout section = column();
        section.addView(sectionHeader(R.drawable.ic_pin, "Parked"));
        LinearLayout c = group();
        parkedText = secondary("");
        c.addView(parkedText);
        LinearLayout buttons = row();
        buttons.setPadding(0, dp(10), 0, dp(12));
        parkedNavigate = primaryButton("Walk to the bike", v -> navigateToBike());
        buttons.addView(parkedNavigate, weight());
        c.addView(buttons);
        section.addView(c);
        parkedCard = section;
        return section;
    }

    /** The refuels, one tap from the numbers they come from. */
    private LinearLayout fuelCard() {
        LinearLayout c = card();
        LinearLayout head = row();
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.addView(iconTitle(R.drawable.ic_fuel, cardTitle("Refuels")), weight());
        c.addView(head);
        fuelLine = secondary("");
        c.addView(fuelLine);
        View row = listRow(R.drawable.ic_fuel, "Refuel diary", "Litres, odometer and what the bike used between fills",
                null, true, v -> startActivity(new Intent(this, FuelActivity.class)));
        c.addView(row);
        return c;
    }

    /** The 12 V readings, one tap from the battery figure they come from. */
    private void openBatteryLog() {
        startActivity(new Intent(this, BatteryActivity.class));
    }

    /** The maintenance log, next to the reminders the dash keeps. */
    private void updateMaintenanceLine() {
        java.util.List<MaintenanceLog.Entry> entries = Hub.maintenanceEntries;
        if (entries.isEmpty()) {
            maintenanceLine.setText("Nothing logged: keep a record of the work the dash cannot see.");
            return;
        }
        MaintenanceLog.Entry last = entries.get(entries.size() - 1);
        BikeFrames.VehicleInfo info = Hub.bike.info;
        double km = MaintenanceLog.kmSince(entries, last.item, info != null ? info.odometerKm : Double.NaN);
        maintenanceLine.setText(last.describe()
                + (km == km ? String.format(Locale.ROOT, " · %,.0f km ago", km) : ""));
    }

    private LinearLayout maintenanceCard() {
        LinearLayout c = card();
        LinearLayout head = row();
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.addView(iconTitle(R.drawable.ic_list, cardTitle("Maintenance")), weight());
        c.addView(head);
        maintenanceLine = secondary("");
        c.addView(maintenanceLine);
        c.addView(listRow(R.drawable.ic_list, "Maintenance log",
                "What you had done, when and at what odometer", null, true,
                v -> startActivity(new Intent(this, MaintenanceActivity.class))));
        return c;
    }

    private void updateFuelLine() {
        java.util.List<FuelLog.Entry> entries = Hub.fuelEntries;
        if (entries.isEmpty()) {
            fuelLine.setText("Nothing recorded yet: fill up with the app connected and it is noticed.");
            return;
        }
        FuelLog.Entry last = entries.get(entries.size() - 1);
        double per100 = entries.size() > 1 ? FuelLog.consumptionLPer100(entries.get(entries.size() - 2), last) : Double.NaN;
        fuelLine.setText("Last fill " + last.describe()
                + (per100 == per100 ? String.format(Locale.ROOT, " · %.1f L/100 km", per100) : ""));
    }

    private void navigateToBike() {
        BikeData.Place p = Hub.bike.parked;
        if (p == null) return;
        Uri uri = Uri.parse(String.format(Locale.ROOT,
                "https://www.google.com/maps/dir/?api=1&destination=%.6f,%.6f&travelmode=walking", p.lat, p.lon));
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (android.content.ActivityNotFoundException e) {
            android.widget.Toast.makeText(this, "No maps app found", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void update() {
        BikeData b = Hub.bike;
        long now = System.currentTimeMillis();
        updateTabBar();
        updateFuelLine();
        updateMaintenanceLine();
        title.setText(name(b, prefs.getString(Hub.KEY_BIKE_NAME, null)));
        vin.setText(vinText(b, vinShown));
        vin.setVisibility(b.model != null && b.model.vin != null ? View.VISIBLE : View.GONE);
        updated.setText(Hub.bikeConnected ? "Connected · values update while the bike is on" : lastRead(b, now));
        BikeFrames.VehicleInfo info = b.info;
        if (info != null && info.rangeKm >= 0) {
            range.figure.set(info.rangeKm + " km");
            range.caption.setText("Remaining range");
        } else {
            range.figure.set("–");
            range.caption.setText(info == null ? "Remaining range: not read yet" : "Remaining range");
        }
        int bars = info == null ? -1 : info.fuelBars;
        fuel.set(Math.max(0, bars), fuelSegments(bars));
        fuelText.setText(bars < 0 ? "Fuel –" : bars + (bars == 1 ? " bar" : " bars"));
        double v = info == null ? Double.NaN : battery(info);
        battery.setText(Double.isNaN(v) ? "Battery –" : String.format(Locale.ROOT, "Battery %.1f V", v));

        List<String[]> rows = rows(b, now, prefs.getInt(Hub.KEY_FUEL_UNIT, FuelEconomy.DEFAULT));
        List<String> labels = new ArrayList<>();
        for (String[] r : rows) labels.add(r[0] + "/" + r[1]);
        if (!labels.equals(shownLabels)) {
            shownLabels = labels;
            buildSections(rows);
        } else {
            for (int i = 0; i < rows.size(); i++) valueViews.get(i).set(rows.get(i)[2]);
        }

        BikeFrames.GeneralCaps caps = b.caps;
        // "connected" or data that just arrived (a replay, or a link that dropped a moment ago)
        boolean live = Hub.bikeConnected || now - b.liveAt < 5000 || now - b.settingsAt < 5000;
        boolean canLamp = live && caps != null && caps.shiftLampSettable;
        lampCard.setVisibility(canLamp ? View.VISIBLE : View.GONE);
        if (canLamp) {
            if (lampRpmSpinner.getAdapter() == null || lampRpmSpinner.getAdapter().getCount() <= 1) {
                fillLampChoices(caps);
            }
            String job = BridgeService.settingsJob;
            lampStatusText.setText(job.isEmpty()
                    ? (b.general != null && b.general.shiftLampRpm() > 0
                    ? String.format(Locale.ROOT, "On the dash now: %s, %,d rpm",
                    SettingsWrite.lampStatusNames(caps.shiftLampMode)[Math.max(0,
                            Math.min(b.general.shiftLampStatus, SettingsWrite.lampStatusNames(caps.shiftLampMode).length - 1))],
                    b.general.shiftLampRpm())
                    : "Not read yet: switch the ignition on with KawaNav running")
                    : job);
        }

        boolean canWrite = live && b.tuning != null && b.tuning.any();
        settingsCard.setVisibility(canWrite ? View.VISIBLE : View.GONE);
        if (canWrite) {
            int modes = SettingsWrite.modeCount(b.tuning);
            modeNote.setText(modes > SettingsWrite.WRITABLE_MODES
                    ? "This bike reports 7 riding modes: Rider 2-4 are in the list."
                    : b.tuning == null || !b.tuning.valid
                    ? "How many riding modes the bike has is not known yet (no ready 0x47 answer): the list "
                            + "offers the four every bike has."
                    : "This bike reports 4 riding modes, so the list stops at Rider.");
            if (modeSpinner.getAdapter() == null || modeSpinner.getAdapter().getCount() != modes) {
                int was = modeSpinner.getSelectedItemPosition();
                android.widget.Spinner fresh = spinner(java.util.Arrays.copyOf(SettingsWrite.MODES, modes),
                        Math.max(0, Math.min(was, modes - 1)));
                android.view.ViewGroup parent = (android.view.ViewGroup) modeSpinner.getParent();
                android.widget.LinearLayout rowParent = parent instanceof android.widget.LinearLayout
                        ? (android.widget.LinearLayout) parent : null;
                if (rowParent != null) {
                    int at = rowParent.indexOfChild(modeSpinner);
                    rowParent.removeViewAt(at);
                    rowParent.addView(fresh, at, modeSpinner.getLayoutParams());
                    modeSpinner = fresh;
                    // the rider's pick is kept by the clamp above; only a first fill comes from the bike
                }
            }
            BikeFrames.VehicleSettings s = b.settings;
            if (s != null && !settingsPrefilled) { // prefill once, from what the bike reported
                if (s.ridingMode >= 1 && s.ridingMode <= SettingsWrite.modeCount(b.tuning)) {
                    modeSpinner.setSelection(s.ridingMode - 1);
                }
                if (s.power >= 1 && s.power <= SettingsWrite.POWER.length) powerSpinner.setSelection(s.power - 1);
                int k = SettingsWrite.ktrcFromWire(s.ktrc);
                if (k >= 0) ktrcSpinner.setSelection(k);
                if (s.kqs >= 1 && s.kqs <= SettingsWrite.ON_OFF.length) kqsSpinner.setSelection(s.kqs - 1);
                settingsPrefilled = true;
            }
            settingsSend.setEnabled(true);
            String job = BridgeService.settingsJob;
            settingsStatus.setText(job.isEmpty() ? "Ready" : job);
        }

        BikeData.Place p = b.parked;
        parkedCard.setVisibility(p != null ? View.VISIBLE : View.GONE);
        if (p != null) {
            parkedText.setText(String.format(Locale.ROOT, "%s, %s · position within about %.0f m",
                    Hub.bikeConnected ? "Last parked" : "Parked",
                    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                            .format(new java.util.Date(p.at)), Math.max(5, p.accuracyM)));
        }
    }

    /** Rebuilds the values: a section header and a group of value rows per group. */
    private void buildSections(List<String[]> rows) {
        sections.removeAllViews();
        valueViews.clear();
        if (rows.isEmpty()) {
            sections.addView(emptyState("No values yet",
                    "Start KawaNav and switch on the ignition: the bike sends its values once connected."));
            return;
        }
        String section = null;
        LinearLayout group = null;
        for (String[] r : rows) {
            if (!r[0].equals(section)) {
                section = r[0];
                LinearLayout container = column();
                container.addView(sectionHeader(sectionIcon(section), section));
                if (section.startsWith("Riding modes")) {
                    TextView n = note("Names come from Rideology; the number the bike sent is in brackets. "
                            + "Shown only where the bike says it has the setting.");
                    n.setPadding(dp(Ui.SPACE1), 0, dp(Ui.SPACE1), dp(Ui.SPACE2));
                    container.addView(n);
                }
                group = group();
                container.addView(group);
                sections.addView(container);
            }
            ValueRow line = valueRow(rowIcon(r[1]), r[1], r[2]);
            // temperatures and the live engine values in the second value colour, like Rideology's cyan
            if ("Engine".equals(section)) line.figure.color(Ui.highlight(night));
            addRow(group, line.view);
            valueViews.add(line.figure);
        }
    }

    // ------------------------------------------------------------------ texts (also used by the main screen)

    /** The bike's Bluetooth name ("Kawasaki-ZR900S" as "Kawasaki ZR900S"), else its model code. */
    static String name(BikeData b, String bluetoothName) {
        if (bluetoothName != null && !bluetoothName.isEmpty()) return bluetoothName.replace('-', ' ');
        return b.model == null ? "Your bike" : "Kawasaki " + b.model.model;
    }

    static String vinText(BikeData b, boolean full) {
        if (b.model == null || b.model.vin == null) return "";
        return "VIN " + (full ? b.model.vin : BikeData.maskedVin(b.model.vin));
    }

    /** "Read 5 min ago" for the newest stored value, or a hint when nothing was read yet. */
    static String lastRead(BikeData b, long now) {
        long at = Math.max(b.infoAt, Math.max(b.settingsAt, b.extAt));
        if (at == 0) return "Not read yet: values appear once the bike is connected";
        return "Read " + ago(at, now);
    }

    static String ago(long at, long now) {
        long s = Math.max(0, (now - at) / 1000);
        if (s < 60) return "just now";
        if (s < 3600) return s / 60 + " min ago";
        if (s < 48 * 3600) return s / 3600 + " h ago";
        return java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(new java.util.Date(at));
    }

    /** One line for the main screen: odometer, fuel, range, battery; "" if nothing was read yet. */
    static String summary(BikeData b) {
        BikeFrames.VehicleInfo i = b.info;
        if (i == null) return "";
        List<String> parts = new ArrayList<>();
        if (i.odometerKm >= 0) parts.add(String.format(Locale.ROOT, "%,d km", i.odometerKm));
        if (i.fuelBars >= 0) parts.add("fuel " + i.fuelBars + (i.fuelBars == 1 ? " bar" : " bars"));
        if (i.rangeKm >= 0) parts.add("range " + i.rangeKm + " km");
        double v = battery(i);
        if (!Double.isNaN(v)) parts.add(String.format(Locale.ROOT, "%.1f V", v));
        return String.join(" · ", parts);
    }

    /** Battery voltage: the ECU's reading, else the meter's. */
    static double battery(BikeFrames.VehicleInfo i) {
        return !Double.isNaN(i.ecuBatteryV) ? i.ecuBatteryV : i.meterBatteryV;
    }

    /** All rows to show: {section, label, value}; values the bike does not have are left out. */
    static List<String[]> rows(BikeData b, long now, int fuelUnit) {
        List<String[]> r = new ArrayList<>();
        BikeFrames.VehicleInfo i = b.info;
        if (i != null) {
            // range, fuel and battery are at the top of the screen
            String s = BASIC;
            if (i.odometerKm >= 0) r.add(new String[]{s, "Odometer", String.format(Locale.ROOT, "%,d km", i.odometerKm)});
            add(r, s, "Trip A", i.tripAKm, "%,.1f km");
            add(r, s, "Trip B", i.tripBKm, "%,.1f km");
            // the dash sends both in km/L, whatever its own display is set to (issue #9)
            String avg = FuelEconomy.format(i.avgMileage, fuelUnit);
            if (!avg.isEmpty()) r.add(new String[]{s, "Average fuel economy", avg});
            String consumption = FuelEconomy.format(i.fuelConsumption, fuelUnit);
            if (!consumption.isEmpty()) r.add(new String[]{s, "Fuel consumption", consumption});
            if (i.avgSpeedKmh >= 0) r.add(new String[]{s, "Average speed", i.avgSpeedKmh + " km/h"});
            if (i.totalTime >= 0) r.add(new String[]{s, "Total time", Ride.hoursMinutes(i.totalTime)});
            add(r, s, "Outside temperature", i.outsideC, "%.0f °C");
            // the largest lean the dash keeps for the trip (0x41 bytes 53/54)
            if (i.maxLeanLeft >= 0) r.add(new String[]{s, "Max lean left", i.maxLeanLeft + "°"});
            if (i.maxLeanRight >= 0) r.add(new String[]{s, "Max lean right", i.maxLeanRight + "°"});
            add(r, s, "Fuel used in total", i.totalFuelL, "%,.1f L");
            // both batteries when the bike reports two (the top of the screen shows one of them)
            if (!Double.isNaN(i.ecuBatteryV) && !Double.isNaN(i.meterBatteryV)) {
                add(r, s, "Battery (engine control unit)", i.ecuBatteryV, "%.1f V");
                add(r, s, "Battery (dash)", i.meterBatteryV, "%.1f V");
            }
        }
        if (b.ext != null) add(r, BASIC, "Distance in total", b.ext.totalDistanceKm, "%,.1f km");
        boolean live = b.live != null && now - b.liveAt < LIVE_MS;
        boolean ext = b.ext != null && now - b.extAt < 120_000;
        if (live || ext) {
            String s = "Engine";
            if (live) {
                BikeFrames.Telemetry t = b.live;
                add(r, s, "Speed", t.speedKmh, "%.0f km/h");
                if (t.rpm >= 0) r.add(new String[]{s, "Engine speed", t.rpm + " rpm"});
                if (t.gear >= 0) r.add(new String[]{s, "Gear", t.gear == 0 ? "N" : String.valueOf(t.gear)});
                add(r, s, "Throttle", t.throttlePct, "%.0f %%");
                add(r, s, "Lean angle", t.leanDeg, "%.0f°");
                add(r, s, "Acceleration", t.accelG, "%+.2f g");
                // the front brake pressure: scaling taken from Rideology, not yet compared on a bike
                add(r, s, "Front brake (unverified)", t.brakeFrontBar, "%.1f bar");
                if (t.tcsHigh >= 0 || t.tcsLow >= 0) {
                    // what these nibbles report is not established (PROTOCOL.md): the numbers stand, the
                    // meaning does not, so no "working / not working" claim
                    r.add(new String[]{s, "Traction control reading (unverified)",
                            "high " + Math.max(0, t.tcsHigh) + " / low " + Math.max(0, t.tcsLow)});
                }
                if (t.abs >= 0) r.add(new String[]{s, "ABS", absText(t.abs)});
                if (t.wheelie > 0) {
                    r.add(new String[]{s, "Wheelie", Double.isNaN(t.wheelieDeg) ? "yes"
                            : String.format(Locale.ROOT, "yes, %.0f°", t.wheelieDeg)});
                }
                if (t.cruiseOn >= 0) {
                    // byte 88 is Rideology's wheel-speed setting, not a cruise speed: it is not shown here
                    r.add(new String[]{s, "Cruise control", t.cruiseOn == 1
                            ? (t.cruiseMode > 0 ? "on, mode " + t.cruiseMode : "on") : "off"});
                }
                // torque in the bike's own unit: the numbers compare with each other, not with a spec sheet
                if (t.riderTorque >= 0) r.add(new String[]{s, "Torque asked by the throttle", t.riderTorque + " (bike units)"});
                if (t.engineTorqueRequest >= 0) r.add(new String[]{s, "Torque the engine control asks for", t.engineTorqueRequest + " (bike units)"});
                if (t.engineTorque >= 0) r.add(new String[]{s, "Torque from the engine", t.engineTorque + " (bike units)"});
                // brake pressures: the masters are raw, the caliper scaling comes from Rideology
                if (t.brakeFrontMaster >= 0) r.add(new String[]{s, "Front brake master (unverified)", String.valueOf(t.brakeFrontMaster)});
                if (t.brakeRearMaster >= 0) r.add(new String[]{s, "Rear brake master (unverified)", String.valueOf(t.brakeRearMaster)});
                if (t.brakeRearCaliper >= 0) r.add(new String[]{s, "Rear brake caliper (unverified)", String.valueOf(t.brakeRearCaliper)});
                if (t.absTarget >= 0 || t.absIndicator >= 0) {
                    r.add(new String[]{s, "ABS working", "target " + t.absTarget + ", indicator " + t.absIndicator});
                }
                if (t.absSwitching >= 0 && t.absSwitching != 0) {
                    r.add(new String[]{s, "ABS switching", String.valueOf(t.absSwitching)});
                }
            }
            if (ext) {
                add(r, s, "Fuel use now", b.ext.fuelRate, "%.2f (dash units)");
                add(r, s, "Fuel economy now", b.ext.instantFuel, "%.1f (dash units)");
                add(r, s, "Coolant temperature", b.ext.waterC, "%.0f °C");
                add(r, s, "Oil temperature", b.ext.oilC, "%.0f °C");
                add(r, s, "Intake air temperature", b.ext.intakeC, "%.0f °C");
                add(r, s, "Front tyre", b.ext.tyreFrontKpa / 100, "%.2f bar");
                add(r, s, "Rear tyre", b.ext.tyreRearKpa / 100, "%.2f bar");
            }
        }
        // suspension travel and the body's acceleration, on bikes with the electronic suspension (0x4B)
        BikeFrames.ImuSusp m0 = b.imu;
        if (m0 != null && now - b.imuAt < LIVE_MS) {
            String s = "Suspension";
            add(r, s, "Front travel", m0.frontStrokeMm, "%.0f mm");
            add(r, s, "Rear travel", m0.rearStrokeMm, "%.0f mm");
            add(r, s, "Acceleration (forward, + = accelerating)", m0.longitudinalG, "%+.2f g");
            add(r, s, "Acceleration (sideways)", m0.lateralG, "%+.2f g");
            add(r, s, "Acceleration (up)", m0.verticalG, "%+.2f g");
            // the second and third are the roll and yaw rates (PROTOCOL.md, 0x4B); the first is not known
            add(r, s, "IMU rate 1 (unverified)", m0.rate1, "%+.1f");
            add(r, s, "IMU roll rate", m0.rate2, "%+.1f");
            add(r, s, "IMU yaw rate", m0.rate3, "%+.1f");
        }
        // the filtered wheel speeds the suspension unit reports: its own reading of what the wheels do
        if (m0 != null && now - b.imuAt < LIVE_MS && (m0.frontWheelKmh == m0.frontWheelKmh || m0.rearWheelKmh == m0.rearWheelKmh)) {
            String s = "Engine";
            add(r, s, "Front wheel speed (filtered)", m0.frontWheelKmh, "%.0f km/h");
            add(r, s, "Rear wheel speed (filtered)", m0.rearWheelKmh, "%.0f km/h");
            if (m0.tcsLevelHigh >= 0 || m0.tcsLevelLow >= 0) {
                r.add(new String[]{s, "Traction control level", m0.tcsLevelHigh + " / " + m0.tcsLevelLow});
            }
            if (m0.kqsDown >= 0 || m0.kqsUp >= 0) {
                r.add(new String[]{s, "Quick shifter", "down " + m0.kqsDown + ", up " + m0.kqsUp});
            }
            if (m0.wheelieFlag > 0) r.add(new String[]{s, "Wheelie (suspension unit)", "yes"});
        }
        if (live && b.live.damping >= 0) {
            BikeFrames.Telemetry t = b.live;
            r.add(new String[]{"Suspension", "Damping now", level(t.damping)});
            if (t.suspCalibration >= 0) {
                r.add(new String[]{"Suspension", "Where the suspension stands",
                        "calibration " + t.suspCalibration + ", status " + t.suspStatus
                                + ", control unit " + t.suspEcu});
            }
        }
        if (b.service != null) {
            java.text.DateFormat df = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM);
            for (int k = 0; k < 3; k++) {
                BikeFrames.ServiceIndicator.Reminder m = b.service.reminders[k];
                if (!m.supported || (!m.hasDate() && m.distanceKm < 0)) continue;
                List<String> due = new ArrayList<>();
                if (m.hasDate()) {
                    java.util.Calendar cal = java.util.Calendar.getInstance();
                    cal.clear();
                    cal.set(m.year, m.month - 1, m.day);
                    due.add(df.format(cal.getTime()));
                }
                if (m.distanceKm >= 0) due.add(String.format(Locale.ROOT, "%,d km", m.distanceKm));
                r.add(new String[]{"Maintenance", BikeFrames.ServiceIndicator.NAMES[k], String.join(" or ", due)});
            }
        }
        BikeFrames.VehicleSettings v = b.settings;
        if (v != null) {
            String s = "Riding modes";
            // the names come from Rideology's own tables; the number the bike sent stays in brackets until
            // this has been checked on a bike with these settings
            if (v.ridingMode >= 0) r.add(new String[]{s, "Riding mode", SettingsWrite.named(SettingsWrite.MODES, v.ridingMode)});
            if (v.power >= 0) r.add(new String[]{s, "Power mode", SettingsWrite.named(SettingsWrite.POWER, v.power)});
            if (v.ktrc >= 0) r.add(new String[]{s, "KTRC", SettingsWrite.ktrcName(v.ktrc)});
            if (v.kqs >= 0) r.add(new String[]{s, "Quickshifter (KQS)", SettingsWrite.named(SettingsWrite.ON_OFF, v.kqs)});
            // KEBC 0: the bike has no adjustable engine brake (Rideology sends 0 for those)
            if (v.kebc > 0) r.add(new String[]{s, "Engine brake (KEBC)", SettingsWrite.named(SettingsWrite.KEBC, v.kebc)});
            if (v.kecsMode >= 0) r.add(new String[]{s, "Suspension mode (KECS)", SettingsWrite.named(SettingsWrite.KECS_MODES, v.kecsMode)});
            if (v.kecsPreload >= 0) r.add(new String[]{s, "KECS preload", SettingsWrite.named(SettingsWrite.KECS_PRELOAD, v.kecsPreload)});
            if (v.kecsLoad >= 0) r.add(new String[]{s, "KECS load adjustment", SettingsWrite.adjustment(v.kecsLoad)});
            if (v.kecsFrontCom >= 0) r.add(new String[]{s, "Front compression", level(v.kecsFrontCom)});
            if (v.kecsFrontTen >= 0) r.add(new String[]{s, "Front rebound", level(v.kecsFrontTen)});
            if (v.kecsRearCom >= 0) r.add(new String[]{s, "Rear compression", level(v.kecsRearCom)});
            if (v.kecsRearTen >= 0) r.add(new String[]{s, "Rear rebound", level(v.kecsRearTen)});
        }
        BikeFrames.GeneralSettings g = b.general;
        if (g != null) {
            String s = "Dash";
            if (g.hour >= 0 && g.minute >= 0) {
                r.add(new String[]{s, "Clock (when read)", String.format(Locale.ROOT, "%02d:%02d", g.hour, g.minute)
                        + (g.day > 0 && g.month > 0 && g.year > 0 ? String.format(Locale.ROOT, ", %d.%d.%d", g.day, g.month, g.year) : "")});
            }
            if (g.shiftLampRpm() > 0) r.add(new String[]{s, "Shift lamp", String.format(Locale.ROOT, "%,d rpm", g.shiftLampRpm())});
        }
        return r;
    }

    /** The ABS state bits of 0x4A byte 37 in words (Rideology's split: qe/b.java). */
    static String absText(int abs) {
        if ((abs & 0x0C) != 0) return "fault";
        if ((abs & 0x10) != 0) return "inspection mode";
        if ((abs & 0x03) == 0x03) return "acting front and rear";
        if ((abs & 0x01) != 0) return "acting front";
        if ((abs & 0x02) != 0) return "acting rear";
        return "ready";
    }

    /** A damping level the bike reports; Rideology's scale for it is not known yet, so the number stands. */
    private static String level(int value) {
        return String.valueOf(value);
    }

    private static void add(List<String[]> r, String section, String label, double value, String fmt) {
        if (!Double.isNaN(value)) r.add(new String[]{section, label, String.format(Locale.ROOT, fmt, value)});
    }
}
