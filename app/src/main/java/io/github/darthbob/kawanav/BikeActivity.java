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
 * The bike's own values, like Rideology's vehicle info: what the dash shows (odometer, trips, fuel,
 * range), live engine values while connected, maintenance reminders, riding modes, the dash clock, and
 * where the bike was parked. Shows the last values read, also while the bike is off.
 */
public class BikeActivity extends KawaActivity {

    /** Live values (riding log) count as current this long. */
    private static final long LIVE_MS = 10_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView title;
    private TextView vin;
    private TextView updated;
    private LinearLayout sections;
    // ride settings (debug builds until this has been tried on a bike)
    private LinearLayout settingsCard;
    private android.widget.Spinner modeSpinner, powerSpinner, ktrcSpinner, kqsSpinner;
    private TextView settingsStatus;
    private Button settingsSend;
    private LinearLayout parkedCard;
    private TextView parkedText;
    private Button parkedNavigate;
    /** The icon of a value group: what the rows behind it are about. Unknown groups get none. */
    private static int sectionIcon(String section) {
        switch (section) {
            case "On the dash": return R.drawable.ic_speed;
            case "Engine": return R.drawable.ic_thermo;
            case "Maintenance": return R.drawable.ic_sliders;
            case "Dash": return R.drawable.ic_clock;
            default:
                return section.startsWith("Riding modes") ? R.drawable.ic_sliders : 0;
        }
    }

    /** Row labels shown now; the sections are only rebuilt when they change (keeps the scroll position). */
    private List<String> shownLabels = new ArrayList<>();
    private final List<TextView> valueViews = new ArrayList<>();
    private boolean vinShown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        Logger.init(this);
        prefs = Hub.prefs(this);
        BikeStore.load(this);
        night = isNight();
        LinearLayout root = column();
        root.setPadding(dp(16), dp(20), dp(16), dp(24));
        title = display("");
        root.addView(title);
        vin = secondary("");
        vin.setOnClickListener(v -> vinShown = !vinShown);
        root.addView(vin);
        updated = secondary("");
        updated.setPadding(0, dp(2), 0, dp(Ui.SPACE2));
        root.addView(updated);
        sections = column();
        root.addView(sections);
        root.addView(settingsCard());
        root.addView(parkedCard());
        TextView note = note("Values as the bike reported them when it was last connected. Tap the VIN to show "
                + "all of it.");
        note.setPadding(dp(Ui.SPACE1), dp(Ui.SPACE5), dp(Ui.SPACE1), dp(Ui.SPACE2));
        root.addView(note);
        setContentView(page(root));
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
     * Sends ride settings to the bike: riding mode, power, KTRC and KQS. Only in debug builds for now —
     * the write has never been tried on a bike — and only while the bike is standing still, in neutral
     * and with the throttle closed (the app itself only wants it standing still).
     */
    private LinearLayout settingsCard() {
        LinearLayout section = column();
        LinearLayout headRow = row();
        headRow.addView(sectionHeader(R.drawable.ic_sliders, "Ride settings"), weight());
        headRow.addView(pill("DEBUG", 0x336BBE45, Ui.accentText(night)));
        section.addView(headRow);
        LinearLayout c = group();
        c.addView(secondary("Writes riding mode, power, KTRC and KQS to the bike, like Rideology's tuning "
                + "screen. The bike must be standing still, in neutral and with the throttle closed."));
        modeSpinner = spinner(SettingsWrite.MODES, 0);
        powerSpinner = spinner(SettingsWrite.POWER, 0);
        ktrcSpinner = spinner(SettingsWrite.KTRC, 0);
        kqsSpinner = spinner(SettingsWrite.ON_OFF, 0);
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
                        + SettingsWrite.ON_OFF[kqsSpinner.getSelectedItemPosition()] + ".")
                .setPositiveButton("Send", (d, w) -> {
                    String problem = BridgeService.writeSettings(modeSpinner.getSelectedItemPosition(),
                            powerSpinner.getSelectedItemPosition(), ktrcSpinner.getSelectedItemPosition(),
                            kqsSpinner.getSelectedItemPosition(), 0, "");
                    if (problem != null) {
                        settingsStatus.setText("Not sent: " + problem);
                    }
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
        title.setText(name(b, prefs.getString(Hub.KEY_BIKE_NAME, null)));
        title.setTextSize(26);
        vin.setText(vinText(b, vinShown));
        vin.setVisibility(b.model != null && b.model.vin != null ? View.VISIBLE : View.GONE);
        updated.setText(Hub.bikeConnected ? "Connected" : lastRead(b, now));

        List<String[]> rows = rows(b, now);
        List<String> labels = new ArrayList<>();
        for (String[] r : rows) labels.add(r[0] + "/" + r[1]);
        if (!labels.equals(shownLabels)) {
            shownLabels = labels;
            buildSections(rows);
        } else {
            for (int i = 0; i < rows.size(); i++) {
                String v = rows.get(i)[2];
                if (!v.contentEquals(valueViews.get(i).getText())) valueViews.get(i).setText(v);
            }
        }

        boolean canWrite = Hub.DEBUG_TOOLS && Hub.bikeConnected && b.tuning != null && b.tuning.any();
        settingsCard.setVisibility(canWrite ? View.VISIBLE : View.GONE);
        if (canWrite) {
            BikeFrames.VehicleSettings s = b.settings;
            if (s != null && !settingsSend.isEnabled()) { // prefill from the bike right after connecting
                if (s.ridingMode >= 0 && s.ridingMode < SettingsWrite.MODES.length) modeSpinner.setSelection(s.ridingMode);
                if (s.power >= 0 && s.power < SettingsWrite.POWER.length) powerSpinner.setSelection(s.power);
                int k = SettingsWrite.ktrcFromWire(s.ktrc);
                if (k >= 0) ktrcSpinner.setSelection(k);
                if (s.kqs >= 0 && s.kqs < SettingsWrite.ON_OFF.length) kqsSpinner.setSelection(s.kqs);
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

    /** Rebuilds the values: a section header and a group of rows per group; the first four dash values as tiles. */
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
        LinearLayout tiles = null;
        List<View> tileList = new ArrayList<>();
        int sectionIndex = -1;
        int inSection = 0;
        for (int i = 0; i < rows.size(); i++) {
            String[] r = rows.get(i);
            if (!r[0].equals(section)) {
                section = r[0];
                LinearLayout container = column();
                container.addView(sectionHeader(sectionIcon(section), section));
                group = group();
                container.addView(group);
                sections.addView(container);
                sectionIndex++;
                tiles = null;
                tileList = new ArrayList<>();
                inSection = 0;
            }
            // the handful of numbers a rider checks at a glance, right at the top of the first group
            // only the first group ("On the dash") starts with tiles; the rest stay quiet rows
            if (sectionIndex == 0 && tiles == null && inSection < 4) {
                LinearLayout tile = column();
                tile.setPadding(dp(Ui.SPACE3), dp(10), dp(Ui.SPACE3), dp(10));
                tile.setMinimumHeight(dp(Ui.TOUCH_MIN));
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(Ui.surfaceSunken(night));
                bg.setCornerRadius(dp(Ui.RADIUS_TILE));
                tile.setBackground(bg);
                TextView v = text(20, true);
                v.setText(r[2]);
                TextView l = text(12, false);
                l.setText(r[1]);
                l.setTextColor(Ui.inkSecondary(night));
                tile.addView(v);
                tile.addView(l);
                tileList.add(tile);
                valueViews.add(v);
                inSection++;
                if (tileList.size() == 4 || i == rows.size() - 1) {
                    View[] arr = tileList.toArray(new View[0]);
                    tiles = statGrid(2, arr);
                    LinearLayout holder = column();
                    holder.setPadding(0, dp(10), 0, dp(10));
                    holder.addView(tiles);
                    group.addView(holder);
                }
                continue;
            }
            inSection++;
            LinearLayout line = row();
            line.setPadding(0, dp(Ui.ROW_PAD_V), 0, dp(Ui.ROW_PAD_V));
            line.setMinimumHeight(dp(Ui.TOUCH_MIN));
            line.addView(itemTitle(r[1]), weight());
            TextView value = value(r[2]);
            value.setTextSize(15);
            line.addView(value);
            addRow(group, line);
            valueViews.add(value);
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
    static List<String[]> rows(BikeData b, long now) {
        List<String[]> r = new ArrayList<>();
        BikeFrames.VehicleInfo i = b.info;
        if (i != null) {
            String s = "On the dash";
            if (i.odometerKm >= 0) r.add(new String[]{s, "Odometer", String.format(Locale.ROOT, "%,d km", i.odometerKm)});
            add(r, s, "Trip A", i.tripAKm, "%.1f km");
            add(r, s, "Trip B", i.tripBKm, "%.1f km");
            if (i.fuelBars >= 0) r.add(new String[]{s, "Fuel", i.fuelBars + (i.fuelBars == 1 ? " bar" : " bars")});
            if (i.rangeKm >= 0) r.add(new String[]{s, "Range", i.rangeKm + " km"});
            add(r, s, "Average fuel economy", i.avgMileage, "%.1f (dash units)");
            add(r, s, "Fuel consumption", i.fuelConsumption, "%.1f (dash units)");
            if (i.avgSpeedKmh >= 0) r.add(new String[]{s, "Average speed", i.avgSpeedKmh + " km/h"});
            if (i.totalTime >= 0) r.add(new String[]{s, "Total time", i.totalTime + " h"});
            add(r, s, "Battery", battery(i), "%.1f V");
            add(r, s, "Outside temperature", i.outsideC, "%.0f °C");
        }
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
            }
            if (ext) {
                add(r, s, "Coolant temperature", b.ext.waterC, "%.0f °C");
                add(r, s, "Oil temperature", b.ext.oilC, "%.0f °C");
                add(r, s, "Intake air temperature", b.ext.intakeC, "%.0f °C");
                add(r, s, "Front tyre", b.ext.tyreFrontKpa / 100, "%.2f bar");
                add(r, s, "Rear tyre", b.ext.tyreRearKpa / 100, "%.2f bar");
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
            String s = "Riding modes (as numbers until verified)";
            if (v.ridingMode >= 0) r.add(new String[]{s, "Riding mode", String.valueOf(v.ridingMode)});
            if (v.power >= 0) r.add(new String[]{s, "Power mode", String.valueOf(v.power)});
            if (v.ktrc >= 0) r.add(new String[]{s, "KTRC", String.valueOf(v.ktrc)});
            if (v.kqs >= 0) r.add(new String[]{s, "KQS", String.valueOf(v.kqs)});
            if (v.kebc >= 0) r.add(new String[]{s, "KEBC", String.valueOf(v.kebc)});
            if (v.kecsMode >= 0) r.add(new String[]{s, "KECS mode", String.valueOf(v.kecsMode)});
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

    private static void add(List<String[]> r, String section, String label, double value, String fmt) {
        if (!Double.isNaN(value)) r.add(new String[]{section, label, String.format(Locale.ROOT, fmt, value)});
    }
}
