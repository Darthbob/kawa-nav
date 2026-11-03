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

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView title;
    private TextView vin;
    private TextView updated;
    private Hero range;
    private SegmentBar fuel;
    private TextView fuelText;
    private TextView battery;
    private LinearLayout sections;
    // ride settings and shift lamp
    private LinearLayout settingsCard;
    private android.widget.Spinner modeSpinner, powerSpinner, ktrcSpinner, kqsSpinner;
    /** Says how many riding modes the bike announced, so "Rider 4" missing from the list makes sense. */
    private TextView modeNote;
    private TextView settingsStatus;
    /** The profile name sent with the settings (shown on the dash? not known yet). */
    private android.widget.EditText settingsName;
    private Button settingsSend;
    private LinearLayout lampCard;
    private android.widget.Spinner lampStatusSpinner, lampRpmSpinner;
    private TextView lampStatusText;
    private Button lampSend;
    private int lampMinLevel = 1;
    /** The ride-settings pickers were filled from the bike once. */
    private boolean settingsPrefilled;
    /** "changed" next to a ride-settings picker that is not what the bike has. */
    private TextView modeChanged, powerChanged, ktrcChanged, kqsChanged;
    /**
     * The riding-mode position KawaNav set itself (the bike's, or the list's first one): its selection
     * callback must not put a preset over the bike's power and KTRC. -1 once the rider picked one.
     */
    private int modeSetByApp = 0;
    /** The same for power and KTRC: a value filled in from the bike does not turn a preset into Rider. */
    private int powerSetByApp = 0, ktrcSetByApp = 0;
    /** Under the riding mode: why it became Rider (a preset's power or KTRC was changed). */
    private TextView presetNote;
    /** Above power and KTRC: where they come from ("Sport: Full power, KTRC 1", "Your own: Rider"). */
    private TextView presetLine;
    /** Ride settings kept on the phone under a name, the picker that fills them in, and its delete button. */
    private List<SettingsProfiles.Profile> profiles = new ArrayList<>();
    private android.widget.Spinner profileSpinner;
    private Button profileDelete;
    private LinearLayout parkedCard;
    private TextView parkedText;
    private Button parkedNavigate;
    /** The icon of a value group: what the rows behind it are about. Unknown groups get none. */
    private static int sectionIcon(String section) {
        switch (section) {
            case BASIC: return R.drawable.ic_speed;
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
            case "Odometer": case "Engine speed": return R.drawable.ic_speed;
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

    /**
     * The groups and labels of {@link #rows} are English inside (the icons and the colours go by them) and
     * shown in the app's language.
     */
    private static final java.util.Map<String, Integer> SHOWN = new java.util.HashMap<>();

    static {
        SHOWN.put("Basic information", R.string.bike_row_basic_information);
        SHOWN.put("Engine", R.string.bike_row_engine);
        SHOWN.put("Suspension", R.string.bike_row_suspension);
        SHOWN.put("Maintenance", R.string.bike_row_maintenance);
        SHOWN.put("Riding modes", R.string.bike_row_riding_modes);
        SHOWN.put("Dash", R.string.bike_row_dash);
        SHOWN.put("Odometer", R.string.bike_row_odometer);
        SHOWN.put("Trip A", R.string.bike_row_trip_a);
        SHOWN.put("Trip B", R.string.bike_row_trip_b);
        SHOWN.put("Average fuel economy", R.string.bike_row_average_fuel_economy);
        SHOWN.put("Fuel consumption", R.string.bike_row_fuel_consumption);
        SHOWN.put("Average speed", R.string.bike_row_average_speed);
        SHOWN.put("Total time", R.string.bike_row_total_time);
        SHOWN.put("Outside temperature", R.string.bike_row_outside_temperature);
        SHOWN.put("Max lean left", R.string.bike_row_max_lean_left);
        SHOWN.put("Max lean right", R.string.bike_row_max_lean_right);
        SHOWN.put("Fuel used in total", R.string.bike_row_fuel_used_in_total);
        SHOWN.put("Battery (engine control unit)", R.string.bike_row_battery_engine_control_unit);
        SHOWN.put("Battery (dash)", R.string.bike_row_battery_dash);
        SHOWN.put("Distance in total", R.string.bike_row_distance_in_total);
        SHOWN.put("Speed", R.string.bike_row_speed);
        SHOWN.put("Engine speed", R.string.bike_row_engine_speed);
        SHOWN.put("Gear", R.string.bike_row_gear);
        SHOWN.put("Throttle", R.string.bike_row_throttle);
        SHOWN.put("Lean angle", R.string.bike_row_lean_angle);
        SHOWN.put("Acceleration", R.string.bike_row_acceleration);
        SHOWN.put("Front brake (unverified)", R.string.bike_row_front_brake_unverified);
        SHOWN.put("Traction control reading (unverified)", R.string.bike_row_traction_control_reading_unverified);
        SHOWN.put("ABS", R.string.bike_row_abs);
        SHOWN.put("Wheelie", R.string.bike_row_wheelie);
        SHOWN.put("Cruise control", R.string.bike_row_cruise_control);
        SHOWN.put("Torque asked by the throttle", R.string.bike_row_torque_asked_by_the_throttle);
        SHOWN.put("Torque the engine control asks for", R.string.bike_row_torque_the_engine_control_asks_for);
        SHOWN.put("Torque from the engine", R.string.bike_row_torque_from_the_engine);
        SHOWN.put("Front brake master (unverified)", R.string.bike_row_front_brake_master_unverified);
        SHOWN.put("Rear brake master (unverified)", R.string.bike_row_rear_brake_master_unverified);
        SHOWN.put("Rear brake caliper (unverified)", R.string.bike_row_rear_brake_caliper_unverified);
        SHOWN.put("ABS working", R.string.bike_row_abs_working);
        SHOWN.put("ABS switching", R.string.bike_row_abs_switching);
        SHOWN.put("Fuel use now", R.string.bike_row_fuel_use_now);
        SHOWN.put("Fuel economy now", R.string.bike_row_fuel_economy_now);
        SHOWN.put("Coolant temperature", R.string.bike_row_coolant_temperature);
        SHOWN.put("Oil temperature", R.string.bike_row_oil_temperature);
        SHOWN.put("Intake air temperature", R.string.bike_row_intake_air_temperature);
        SHOWN.put("Front tyre", R.string.bike_row_front_tyre);
        SHOWN.put("Rear tyre", R.string.bike_row_rear_tyre);
        SHOWN.put("Front travel", R.string.bike_row_front_travel);
        SHOWN.put("Rear travel", R.string.bike_row_rear_travel);
        SHOWN.put("Acceleration (forward, + = accelerating)", R.string.bike_row_acceleration_forward_accelerating);
        SHOWN.put("Acceleration (sideways)", R.string.bike_row_acceleration_sideways);
        SHOWN.put("Acceleration (up)", R.string.bike_row_acceleration_up);
        SHOWN.put("Yaw rate (+ = turning right)", R.string.bike_row_yaw_rate_turning_right);
        SHOWN.put("Roll rate (+ = rolling right)", R.string.bike_row_roll_rate_rolling_right);
        SHOWN.put("Pitch rate", R.string.bike_row_pitch_rate);
        SHOWN.put("Front wheel speed (filtered)", R.string.bike_row_front_wheel_speed_filtered);
        SHOWN.put("Rear wheel speed (filtered)", R.string.bike_row_rear_wheel_speed_filtered);
        SHOWN.put("Traction control level", R.string.bike_row_traction_control_level);
        SHOWN.put("Quick shifter", R.string.bike_row_quick_shifter);
        SHOWN.put("Wheelie (suspension unit)", R.string.bike_row_wheelie_suspension_unit);
        SHOWN.put("Damping now", R.string.bike_row_damping_now);
        SHOWN.put("Where the suspension stands", R.string.bike_row_where_the_suspension_stands);
        SHOWN.put("Riding mode", R.string.bike_row_riding_mode);
        SHOWN.put("Power mode", R.string.bike_row_power_mode);
        SHOWN.put("KTRC", R.string.bike_row_ktrc);
        SHOWN.put("Quickshifter (KQS)", R.string.bike_row_quickshifter_kqs);
        SHOWN.put("Engine brake (KEBC)", R.string.bike_row_engine_brake_kebc);
        SHOWN.put("Suspension mode (KECS)", R.string.bike_row_suspension_mode_kecs);
        SHOWN.put("KECS preload", R.string.bike_row_kecs_preload);
        SHOWN.put("KECS load adjustment", R.string.bike_row_kecs_load_adjustment);
        SHOWN.put("Front compression", R.string.bike_row_front_compression);
        SHOWN.put("Front rebound", R.string.bike_row_front_rebound);
        SHOWN.put("Rear compression", R.string.bike_row_rear_compression);
        SHOWN.put("Rear rebound", R.string.bike_row_rear_rebound);
        SHOWN.put("Clock (when read)", R.string.bike_row_clock_when_read);
        SHOWN.put("Shift lamp", R.string.bike_row_shift_lamp);
    }

    /** A group or label of {@link #rows} as shown. */
    static String shown(String english) {
        Integer id = SHOWN.get(english);
        return id == null ? english : Lang.t(id);
    }

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
        root.addView(headerBar(Lang.t(R.string.bike_title), null, headerAction(R.drawable.ic_refresh, Lang.t(R.string.bike_read_again), v -> {
            boolean asked = BridgeService.read(BikeFrames.VEHICLE_INFO);
            android.widget.Toast.makeText(this, asked ? Lang.t(R.string.bike_asked) : Lang.t(R.string.bike_not_connected),
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
        TextView note = note(Lang.t(R.string.bike_values_note));
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

    /** The gauge in words: "4 bars", "1 bar", "Reserve" (the last bar blinking); null when not read. */
    static String fuelWords(int bars) {
        if (bars < 0) return null;
        if (bars == BikeFrames.VehicleInfo.FUEL_RESERVE) return Lang.t(R.string.bike_fuel_reserve);
        return Lang.plural(R.plurals.bike_fuel_bars, bars);
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
        section.addView(sectionHeader(R.drawable.ic_sliders, Lang.t(R.string.bike_ride_settings)));
        LinearLayout c = group();
        c.addView(secondary(Lang.t(R.string.bike_ride_settings_note)));
        profiles = SettingsProfiles.parse(prefs.getString(Hub.KEY_SETTINGS_PROFILES, ""));
        profileSpinner = spinner(profileNames(), 0);
        watchProfiles();
        addRow(c, settingRowWith(Lang.t(R.string.bike_profile), profileSpinner));
        modeSpinner = spinner(java.util.Arrays.copyOf(SettingsWrite.MODES, SettingsWrite.WRITABLE_MODES), 0);
        modeNote = secondary("");
        powerSpinner = spinner(SettingsWrite.POWER, 0);
        ktrcSpinner = spinner(SettingsWrite.KTRC, 0);
        kqsSpinner = spinner(SettingsWrite.ON_OFF, 0);
        modeChanged = changedPill();
        powerChanged = changedPill();
        ktrcChanged = changedPill();
        kqsChanged = changedPill();
        presetNote = secondary("");
        presetNote.setTextColor(Ui.accentText(night));
        presetNote.setVisibility(View.GONE);
        watchMode();
        powerSpinner.setOnItemSelectedListener(onSelect(pos -> {
            boolean byRider = pos != powerSetByApp;
            powerSetByApp = -1;
            if (byRider) leavePreset();
            refreshSettingMarks();
        }));
        ktrcSpinner.setOnItemSelectedListener(onSelect(pos -> {
            boolean byRider = pos != ktrcSetByApp;
            ktrcSetByApp = -1;
            if (byRider) leavePreset();
            refreshSettingMarks();
        }));
        kqsSpinner.setOnItemSelectedListener(onSelect(pos -> refreshSettingMarks()));
        c.addView(modeNote);
        addRow(c, settingRowWith(Lang.t(R.string.bike_row_riding_mode), modeChanged, modeSpinner));
        c.addView(presetNote);
        // power and KTRC belong to the riding mode: indented under it, with where they come from
        presetLine = secondary("");
        presetLine.setPadding(0, dp(Ui.SPACE1), 0, 0);
        LinearLayout byMode = column();
        byMode.addView(presetLine);
        byMode.addView(settingRowWith(Lang.t(R.string.bike_power), powerChanged, powerSpinner));
        addRow(byMode, settingRowWith(Lang.t(R.string.bike_row_ktrc), ktrcChanged, ktrcSpinner));
        c.addView(indented(byMode));
        TextView apart = secondary(Lang.t(R.string.bike_not_in_mode));
        apart.setPadding(0, dp(Ui.SPACE3), 0, dp(Ui.SPACE1));
        addRow(c, apart);
        c.addView(settingRowWith(Lang.t(R.string.bike_kqs), kqsChanged, kqsSpinner));
        settingsName = new android.widget.EditText(this);
        settingsName.setSingleLine(true);
        settingsName.setTextSize(15);
        settingsName.setTextColor(Ui.ink(night));
        settingsName.setHint(Lang.t(R.string.bike_name_hint));
        settingsName.setText(prefs.getString(Hub.KEY_SETTINGS_NAME, ""));
        LinearLayout nameRow = settingRow();
        nameRow.addView(itemTitle(Lang.t(R.string.bike_name)), weight());
        nameRow.addView(settingsName, controlWidth());
        addRow(c, nameRow);
        c.addView(secondary(Lang.t(R.string.bike_name_note, SettingsWrite.NAME_BYTES)));
        LinearLayout profileButtons = row();
        profileButtons.setPadding(0, dp(Ui.SPACE2), 0, 0);
        LinearLayout.LayoutParams first = weight();
        first.rightMargin = dp(Ui.SPACE2);
        profileButtons.addView(secondaryButton(Lang.t(R.string.bike_save_profile), v -> saveProfile()), first);
        profileDelete = secondaryButton(Lang.t(R.string.bike_delete_profile), v -> deleteProfile());
        profileDelete.setEnabled(false);
        profileButtons.addView(profileDelete, weight());
        c.addView(profileButtons);
        settingsStatus = secondary("");
        settingsStatus.setPadding(0, dp(Ui.SPACE2), 0, dp(10));
        c.addView(settingsStatus);
        c.addView(secondaryButton(Lang.t(R.string.bike_back_to_bike), v -> fillFromBike(Hub.bike.settings)), matchWrap());
        settingsSend = primaryButton(Lang.t(R.string.bike_send), v -> confirmSend());
        c.addView(settingsSend, matchWrap());
        section.addView(c);
        section.setVisibility(View.GONE);
        settingsCard = section;
        return section;
    }

    private LinearLayout settingRowWith(String label, android.widget.Spinner s) {
        return settingRowWith(label, null, s);
    }

    /** @param mark shown between the label and the picker (the "changed" pill), or null */
    private LinearLayout settingRowWith(String label, TextView mark, android.widget.Spinner s) {
        LinearLayout r = settingRow();
        r.addView(itemTitle(label), weight());
        if (mark != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(Ui.SPACE2);
            r.addView(mark, lp);
        }
        r.addView(s, controlWidth());
        return r;
    }

    /** Rows that belong to the row above: indented, with a line in the accent colour down their left side. */
    private LinearLayout indented(View content) {
        LinearLayout r = row();
        View bar = new View(this);
        bar.setBackgroundColor((Ui.accent(night) & 0x00FFFFFF) | 0x66000000);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(2), LinearLayout.LayoutParams.MATCH_PARENT);
        lp.leftMargin = dp(Ui.SPACE2);
        lp.rightMargin = dp(Ui.SPACE3);
        r.addView(bar, lp);
        r.addView(content, weight());
        return r;
    }

    private TextView changedPill() {
        TextView t = pill(Lang.t(R.string.bike_changed), (Ui.accent(night) & 0x00FFFFFF) | 0x33000000, Ui.accentText(night));
        t.setVisibility(View.GONE);
        return t;
    }

    /**
     * Picking Sport, Road or Rain sets power and KTRC to that mode's own values, as Rideology's preset
     * buttons do; a Rider mode leaves them. A position KawaNav set itself does not.
     */
    private void watchMode() {
        modeSpinner.setOnItemSelectedListener(onSelect(pos -> {
            if (pos == modeSetByApp) {
                modeSetByApp = -1;
            } else {
                modeSetByApp = -1;
                presetNote.setVisibility(View.GONE); // the rider picked the mode: nothing to explain
                int[] preset = SettingsWrite.preset(pos + 1);
                if (preset != null) {
                    powerSpinner.setSelection(preset[0] - 1);
                    ktrcSpinner.setSelection(preset[1]);
                }
            }
            refreshSettingMarks();
        }));
    }

    /**
     * Power or KTRC changed by the rider while Sport, Road or Rain is picked, away from that mode's own:
     * the riding mode becomes Rider, and the note under it says why.
     */
    private void leavePreset() {
        int mode = modeSpinner.getSelectedItemPosition() + 1;
        if (!SettingsWrite.leavesPreset(mode, powerSpinner.getSelectedItemPosition() + 1,
                ktrcSpinner.getSelectedItemPosition())) {
            return;
        }
        int rider = SettingsWrite.RIDER - 1;
        if (modeSpinner.getSelectedItemPosition() != rider) modeSetByApp = rider;
        modeSpinner.setSelection(rider);
        presetNote.setText(SettingsWrite.riderNote(mode));
        presetNote.setVisibility(View.VISIBLE);
    }

    /** The pickers set to what the bike reported (first fill, and "Back to the bike's values"). */
    private void fillFromBike(BikeFrames.VehicleSettings s) {
        if (s == null) return;
        fillPickers(SettingsWrite.Choice.of(s));
        settingsPrefilled = true;
        refreshSettingMarks();
    }

    /**
     * The pickers set by KawaNav, not the rider: to the bike's values or a profile's. That applies no
     * preset and turns no preset into Rider. A value the pickers do not offer (not known, or a Rider 3 on
     * a bike with four modes) is left as it is.
     *
     * @return false when a value was left as it was
     */
    private boolean fillPickers(SettingsWrite.Choice v) {
        presetNote.setVisibility(View.GONE);
        boolean all = true;
        if (v.mode >= 1 && v.mode <= modeSpinner.getCount()) {
            if (modeSpinner.getSelectedItemPosition() != v.mode - 1) modeSetByApp = v.mode - 1;
            modeSpinner.setSelection(v.mode - 1);
        } else {
            all = false;
        }
        if (v.power >= 1 && v.power <= SettingsWrite.POWER.length) {
            if (powerSpinner.getSelectedItemPosition() != v.power - 1) powerSetByApp = v.power - 1;
            powerSpinner.setSelection(v.power - 1);
        } else {
            all = false;
        }
        if (v.ktrcLevel >= 0 && v.ktrcLevel < SettingsWrite.KTRC.length) {
            if (ktrcSpinner.getSelectedItemPosition() != v.ktrcLevel) ktrcSetByApp = v.ktrcLevel;
            ktrcSpinner.setSelection(v.ktrcLevel);
        } else {
            all = false;
        }
        if (v.kqs >= 1 && v.kqs <= SettingsWrite.ON_OFF.length) {
            kqsSpinner.setSelection(v.kqs - 1);
        } else {
            all = false;
        }
        return all;
    }

    // ------------------------------------------------------------------ profiles

    /** "None", then the profiles' names: the picker's choices. */
    private String[] profileNames() {
        String[] names = new String[profiles.size() + 1];
        names[0] = Lang.t(R.string.bike_none);
        for (int i = 0; i < profiles.size(); i++) names[i + 1] = profiles.get(i).name;
        return names;
    }

    /** Picking a profile fills its values and its name into the pickers, ready to be sent. */
    private void watchProfiles() {
        profileSpinner.setOnItemSelectedListener(onSelect(pos -> {
            if (profileDelete != null) profileDelete.setEnabled(pos > 0);
            if (pos <= 0 || pos > profiles.size()) return;
            SettingsProfiles.Profile p = profiles.get(pos - 1);
            settingsPrefilled = true; // the bike's values, coming in later, must not overwrite the profile
            if (!fillPickers(p.choice())) {
                toast(Lang.t(R.string.bike_profile_no_mode, SettingsWrite.MODES[p.mode - 1]));
            }
            settingsName.setText(p.name);
            refreshSettingMarks();
        }));
    }

    /** Keeps the pickers on the phone under the name in the Name field, over a profile of that name. */
    private void saveProfile() {
        String name = SettingsProfiles.clean(settingsName.getText().toString());
        if (name.isEmpty()) {
            toast(Lang.t(R.string.bike_profile_name_first));
            return;
        }
        SettingsWrite.Choice now = picked();
        SettingsProfiles.Profile p = new SettingsProfiles.Profile(name, now.mode, now.power, now.ktrcLevel, now.kqs);
        if (SettingsProfiles.indexOf(profiles, name) < 0) {
            storeProfile(p);
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(Lang.t(R.string.bike_profile_replace_title, name))
                .setMessage(Lang.t(R.string.bike_profile_replace_message))
                .setPositiveButton(Lang.t(R.string.bike_replace), (d, w) -> storeProfile(p))
                .setNegativeButton(Lang.t(R.string.common_cancel), null)
                .show();
    }

    private void storeProfile(SettingsProfiles.Profile p) {
        List<SettingsProfiles.Profile> next = SettingsProfiles.put(profiles, p);
        if (next == null) {
            toast(Lang.t(R.string.bike_profiles_full, SettingsProfiles.MAX));
            return;
        }
        setProfiles(next, p.name);
        toast(Lang.t(R.string.bike_profile_saved, p.name));
    }

    private void deleteProfile() {
        int pos = profileSpinner.getSelectedItemPosition();
        if (pos <= 0 || pos > profiles.size()) return;
        String name = profiles.get(pos - 1).name;
        new android.app.AlertDialog.Builder(this)
                .setTitle(Lang.t(R.string.bike_profile_delete_title, name))
                .setMessage(Lang.t(R.string.bike_profile_delete_message))
                .setPositiveButton(Lang.t(R.string.common_delete), (d, w) -> setProfiles(SettingsProfiles.remove(profiles, name), null))
                .setNegativeButton(Lang.t(R.string.common_cancel), null)
                .show();
    }

    /** Stores the profiles and builds the picker again, on {@code select} (null: "None"). */
    private void setProfiles(List<SettingsProfiles.Profile> next, String select) {
        profiles = next;
        prefs.edit().putString(Hub.KEY_SETTINGS_PROFILES, SettingsProfiles.format(next)).apply();
        int at = select == null ? 0 : SettingsProfiles.indexOf(next, select) + 1;
        profileSpinner = swapSpinner(profileSpinner, profileNames(), at);
        watchProfiles();
        profileDelete.setEnabled(at > 0);
    }

    /** A picker with other choices in the place of {@code old}, its row keeping its place. */
    private android.widget.Spinner swapSpinner(android.widget.Spinner old, String[] items, int selected) {
        android.widget.Spinner fresh = spinner(items, selected);
        android.view.ViewGroup parent = (android.view.ViewGroup) old.getParent();
        if (parent == null) return fresh;
        int at = parent.indexOfChild(old);
        parent.removeViewAt(at);
        parent.addView(fresh, at, old.getLayoutParams());
        return fresh;
    }

    private void toast(String text) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show();
    }

    /** What the pickers say, in the numbers of a write. */
    private SettingsWrite.Choice picked() {
        return new SettingsWrite.Choice(modeSpinner.getSelectedItemPosition() + 1,
                powerSpinner.getSelectedItemPosition() + 1, ktrcSpinner.getSelectedItemPosition(),
                kqsSpinner.getSelectedItemPosition() + 1);
    }

    /**
     * The "changed" pills next to the pickers that are not what the bike has, and Send only while
     * something would change (a value, or the name since the last write). Before the bike said what it
     * has, nothing is marked and Send stays open.
     */
    private void refreshSettingMarks() {
        if (modeChanged == null || settingsSend == null) return;
        SettingsWrite.Choice bike = SettingsWrite.Choice.of(Hub.bike.settings);
        SettingsWrite.Choice now = picked();
        presetLine.setText(SettingsWrite.presetLine(now.mode, now.power, now.ktrcLevel));
        modeChanged.setVisibility(bike != null && bike.mode != now.mode ? View.VISIBLE : View.GONE);
        powerChanged.setVisibility(bike != null && bike.power != now.power ? View.VISIBLE : View.GONE);
        ktrcChanged.setVisibility(bike != null && bike.ktrcLevel != now.ktrcLevel ? View.VISIBLE : View.GONE);
        kqsChanged.setVisibility(bike != null && bike.kqs != now.kqs ? View.VISIBLE : View.GONE);
        boolean nameChanged = !SettingsWrite.fitName(settingsName.getText().toString())
                .equals(prefs.getString(Hub.KEY_SETTINGS_NAME, ""));
        settingsSend.setEnabled(bike == null || now.differsFrom(bike) || nameChanged);
    }

    /**
     * Asks before writing, with every value next to what the bike has: they all go in the one write, so
     * the ones that stay are listed too ("Power: Full"), the changes with an arrow ("KTRC: 2 → 1").
     */
    private void confirmSend() {
        BikeFrames.VehicleSettings s = Hub.bike.settings;
        String name = SettingsWrite.fitName(settingsName.getText().toString());
        int kebcNow = s != null && s.kebc >= 0 ? s.kebc : 0;
        String lines = String.join("\n", SettingsWrite.summary(SettingsWrite.Choice.of(s), picked(), kebcNow, name));
        new android.app.AlertDialog.Builder(this)
                .setTitle(Lang.t(R.string.bike_send_title))
                .setMessage(Lang.t(R.string.bike_send_message, lines))
                .setPositiveButton(Lang.t(R.string.bike_send_button), (d, w) -> {
                    prefs.edit().putString(Hub.KEY_SETTINGS_NAME, name).apply();
                    // spinner positions start at 0, the bike's numbers at 1; KEBC goes back as the bike has it
                    BikeFrames.VehicleSettings now2 = Hub.bike.settings;
                    int kebc = now2 != null && now2.kebc >= 0 ? now2.kebc : 0;
                    String problem = BridgeService.writeSettings(modeSpinner.getSelectedItemPosition() + 1,
                            powerSpinner.getSelectedItemPosition() + 1, ktrcSpinner.getSelectedItemPosition(),
                            kqsSpinner.getSelectedItemPosition() + 1, kebc, name);
                    if (problem != null) {
                        BridgeService.noteSettings(Lang.t(R.string.bike_not_sent, problem));
                        settingsStatus.setText(BridgeService.settingsOutcome);
                    }
                })
                .setNegativeButton(Lang.t(R.string.common_cancel), null)
                .show();
    }

    /**
     * The dash's shift lamp: when it lights and at which engine speed. Tried on a Z900 (2026): the dash
     * takes the new setting. The meter's own limits (0x1A) decide which statuses and levels are offered.
     */
    private LinearLayout lampCard() {
        LinearLayout section = column();
        section.addView(sectionHeader(R.drawable.ic_clock, Lang.t(R.string.bike_shift_lamp)));
        LinearLayout c = group();
        c.addView(secondary(Lang.t(R.string.bike_shift_lamp_note)));
        lampStatusSpinner = spinner(new String[]{Lang.t(R.string.common_off)}, 0);
        lampRpmSpinner = spinner(new String[]{Lang.t(R.string.unit_rpm, 3000)}, 0);
        addRow(c, settingRowWith(Lang.t(R.string.bike_lights_up), lampStatusSpinner));
        addRow(c, settingRowWith(Lang.t(R.string.bike_at), lampRpmSpinner));
        lampStatusText = secondary("");
        lampStatusText.setPadding(0, dp(Ui.SPACE2), 0, dp(10));
        c.addView(lampStatusText);
        lampSend = primaryButton(Lang.t(R.string.bike_send_dash), v -> confirmShiftLamp());
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
            rpm[i] = Lang.t(R.string.unit_rpm, SettingsWrite.lampRpm(caps.shiftLampMin + i));
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
                .setTitle(Lang.t(R.string.bike_lamp_send_title))
                .setMessage(status == 0 ? Lang.t(R.string.bike_lamp_message_off, SettingsWrite.lampRpm(level))
                        : Lang.t(R.string.bike_lamp_message, SettingsWrite.lampRpm(level), lampStatusSpinner.getSelectedItem()))
                .setPositiveButton(Lang.t(R.string.bike_send_button), (d, w) -> {
                    String problem = BridgeService.writeShiftLamp(status, level);
                    if (problem != null) {
                        BridgeService.noteLamp(Lang.t(R.string.bike_not_sent, problem));
                        lampStatusText.setText(BridgeService.lampOutcome);
                    }
                })
                .setNegativeButton(Lang.t(R.string.common_cancel), null)
                .show();
    }

    private LinearLayout parkedCard() {
        LinearLayout section = column();
        section.addView(sectionHeader(R.drawable.ic_pin, Lang.t(R.string.bike_parked)));
        LinearLayout c = group();
        parkedText = secondary("");
        c.addView(parkedText);
        LinearLayout buttons = row();
        buttons.setPadding(0, dp(10), 0, dp(12));
        parkedNavigate = primaryButton(Lang.t(R.string.main_walk), v -> navigateToBike());
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
        head.addView(iconTitle(R.drawable.ic_fuel, cardTitle(Lang.t(R.string.bike_refuels))), weight());
        c.addView(head);
        fuelLine = secondary("");
        c.addView(fuelLine);
        View row = listRow(R.drawable.ic_fuel, Lang.t(R.string.bike_refuel_diary), Lang.t(R.string.bike_refuel_diary_sub),
                null, true, v -> startActivity(new Intent(this, FuelActivity.class)));
        c.addView(row);
        return c;
    }

    /** The 12 V readings, one tap from the battery figure they come from. */
    private void openBatteryLog() {
        startActivity(new Intent(this, BatteryActivity.class));
    }

    /** The maintenance log, next to the reminders the dash keeps; the rider's own reminders that are due first. */
    private void updateMaintenanceLine() {
        java.util.List<MaintenanceLog.Entry> entries = Hub.maintenanceEntries;
        BikeFrames.VehicleInfo odo = Hub.bike.info;
        java.util.List<String> due = new java.util.ArrayList<>();
        for (MaintenanceLog.Due d : MaintenanceLog.due(entries,
                MaintenanceLog.reminders(prefs.getString(Hub.KEY_MAINT_REMINDERS, "")),
                odo != null && odo.odometerKm >= 0 ? odo.odometerKm : Double.NaN, System.currentTimeMillis())) {
            if (d.due()) due.add(d.reminder.item);
        }
        if (!due.isEmpty()) {
            maintenanceLine.setText(Lang.t(R.string.bike_maintenance_due, String.join(", ", due)));
            return;
        }
        if (entries.isEmpty()) {
            maintenanceLine.setText(Lang.t(R.string.bike_maintenance_none));
            return;
        }
        MaintenanceLog.Entry last = entries.get(entries.size() - 1);
        BikeFrames.VehicleInfo info = Hub.bike.info;
        double km = MaintenanceLog.kmSince(entries, last.item, info != null ? info.odometerKm : Double.NaN);
        maintenanceLine.setText(last.describe()
                + (km == km ? " · " + Lang.t(R.string.bike_km_ago, km) : ""));
    }

    private LinearLayout maintenanceCard() {
        LinearLayout c = card();
        LinearLayout head = row();
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.addView(iconTitle(R.drawable.ic_list, cardTitle(Lang.t(R.string.bike_row_maintenance))), weight());
        c.addView(head);
        maintenanceLine = secondary("");
        c.addView(maintenanceLine);
        c.addView(listRow(R.drawable.ic_list, Lang.t(R.string.bike_maintenance_log),
                Lang.t(R.string.bike_maintenance_log_sub), null, true,
                v -> startActivity(new Intent(this, MaintenanceActivity.class))));
        return c;
    }

    private void updateFuelLine() {
        java.util.List<FuelLog.Entry> entries = Hub.fuelEntries;
        if (entries.isEmpty()) {
            fuelLine.setText(Lang.t(R.string.bike_fuel_none));
            return;
        }
        FuelLog.Entry last = entries.get(entries.size() - 1);
        double per100 = entries.size() > 1 ? FuelLog.consumptionLPer100(entries.get(entries.size() - 2), last) : Double.NaN;
        fuelLine.setText(Lang.t(R.string.bike_last_fill, last.describe())
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
            android.widget.Toast.makeText(this, Lang.t(R.string.main_no_maps_app), android.widget.Toast.LENGTH_SHORT).show();
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
        updated.setText(Hub.bikeConnected ? Lang.t(R.string.bike_connected_updating) : lastRead(b, now));
        BikeFrames.VehicleInfo info = b.info;
        if (info != null && info.rangeKm >= 0) {
            range.figure.set(Lang.t(R.string.unit_km, info.rangeKm));
            range.caption.setText(Lang.t(R.string.bike_remaining_range));
        } else {
            range.figure.set("–");
            range.caption.setText(info == null ? Lang.t(R.string.bike_remaining_range_unread) : Lang.t(R.string.bike_remaining_range));
        }
        int bars = info == null ? -1 : info.fuelBars;
        fuel.set(Math.max(0, bars), fuelSegments(bars));
        fuel.setReserve(bars == BikeFrames.VehicleInfo.FUEL_RESERVE);
        String lowFuel = info != null && info.lowFuel == 1 ? " · " + Lang.t(R.string.bike_low_fuel) : "";
        fuelText.setText((bars < 0 ? Lang.t(R.string.bike_fuel_unknown) : fuelWords(bars)) + lowFuel);
        double v = info == null ? Double.NaN : battery(info);
        battery.setText(Double.isNaN(v) ? Lang.t(R.string.bike_battery_unknown) : Lang.t(R.string.bike_battery, v));

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
            String onDash = b.general != null && b.general.shiftLampRpm() > 0
                    ? Lang.t(R.string.bike_lamp_on_dash,
                    SettingsWrite.lampStatusNames(caps.shiftLampMode)[Math.max(0,
                            Math.min(b.general.shiftLampStatus, SettingsWrite.lampStatusNames(caps.shiftLampMode).length - 1))],
                    b.general.shiftLampRpm())
                    : Lang.t(R.string.bike_lamp_not_read);
            lampStatusText.setText(SettingsWrite.status(BridgeService.settingsJob, BridgeService.lampOutcome,
                    now - BridgeService.lampOutcomeAt, onDash));
        }

        boolean canWrite = live && b.tuning != null && b.tuning.any();
        settingsCard.setVisibility(canWrite ? View.VISIBLE : View.GONE);
        if (canWrite) {
            int modes = SettingsWrite.modeCount(b.tuning);
            modeNote.setText(modes > SettingsWrite.WRITABLE_MODES
                    ? Lang.t(R.string.bike_modes_seven)
                    : b.tuning == null || !b.tuning.valid
                    ? Lang.t(R.string.bike_modes_unknown)
                    : Lang.t(R.string.bike_modes_four));
            if (modeSpinner.getAdapter() == null || modeSpinner.getAdapter().getCount() != modes) {
                int was = modeSpinner.getSelectedItemPosition();
                // the rider's pick is kept by the clamp; only a first fill comes from the bike, and the new
                // list's own first selection must not set a preset
                modeSpinner = swapSpinner(modeSpinner, java.util.Arrays.copyOf(SettingsWrite.MODES, modes),
                        Math.max(0, Math.min(was, modes - 1)));
                modeSetByApp = modeSpinner.getSelectedItemPosition();
                watchMode();
            }
            BikeFrames.VehicleSettings s = b.settings;
            if (s != null && !settingsPrefilled) fillFromBike(s); // once, from what the bike reported
            refreshSettingMarks();
            // the same checks the write makes, so "Ready" is only shown when a write would go out
            String blocked = !Hub.bikeConnected ? Lang.t(R.string.bike_not_connected_reason)
                    : SettingsWrite.blockedBecause(b.live, now - b.liveAt, b.tuning);
            settingsStatus.setText(SettingsWrite.status(BridgeService.settingsJob, BridgeService.settingsOutcome,
                    now - BridgeService.settingsOutcomeAt, SettingsWrite.readiness(blocked)));
        }

        BikeData.Place p = b.parked;
        parkedCard.setVisibility(p != null ? View.VISIBLE : View.GONE);
        if (p != null) {
            parkedText.setText(Lang.t(R.string.bike_parked_text,
                    Hub.bikeConnected ? Lang.t(R.string.bike_last_parked) : Lang.t(R.string.bike_parked),
                    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                            .format(new java.util.Date(p.at)), Math.max(5, p.accuracyM)));
        }
    }

    /** Rebuilds the values: a section header and a group of value rows per group. */
    private void buildSections(List<String[]> rows) {
        sections.removeAllViews();
        valueViews.clear();
        if (rows.isEmpty()) {
            sections.addView(emptyState(Lang.t(R.string.bike_no_values),
                    Lang.t(R.string.bike_no_values_sub)));
            return;
        }
        String section = null;
        LinearLayout group = null;
        for (String[] r : rows) {
            if (!r[0].equals(section)) {
                section = r[0];
                LinearLayout container = column();
                container.addView(sectionHeader(sectionIcon(section), shown(section)));
                if (section.startsWith("Riding modes")) {
                    TextView n = note(Lang.t(R.string.bike_modes_note));
                    n.setPadding(dp(Ui.SPACE1), 0, dp(Ui.SPACE1), dp(Ui.SPACE2));
                    container.addView(n);
                }
                group = group();
                container.addView(group);
                sections.addView(container);
            }
            ValueRow line = valueRow(rowIcon(r[1]), shown(r[1]), r[2]);
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
        return b.model == null ? Lang.t(R.string.bike_title) : "Kawasaki " + b.model.model;
    }

    static String vinText(BikeData b, boolean full) {
        if (b.model == null || b.model.vin == null) return "";
        return Lang.t(R.string.bike_vin, full ? b.model.vin : BikeData.maskedVin(b.model.vin));
    }

    /** "Read 5 min ago" for the newest stored value, or a hint when nothing was read yet. */
    static String lastRead(BikeData b, long now) {
        long at = Math.max(b.infoAt, Math.max(b.settingsAt, b.extAt));
        if (at == 0) return Lang.t(R.string.bike_not_read);
        return Lang.t(R.string.bike_read_ago, ago(at, now));
    }

    static String ago(long at, long now) {
        long s = Math.max(0, (now - at) / 1000);
        if (s < 60) return Lang.t(R.string.bike_ago_now);
        if (s < 3600) return Lang.t(R.string.bike_ago_min, s / 60);
        if (s < 48 * 3600) return Lang.t(R.string.bike_ago_h, s / 3600);
        return java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(new java.util.Date(at));
    }

    /** One line for the main screen: odometer, fuel, range, battery; "" if nothing was read yet. */
    static String summary(BikeData b) {
        BikeFrames.VehicleInfo i = b.info;
        if (i == null) return "";
        List<String> parts = new ArrayList<>();
        if (i.odometerKm >= 0) parts.add(Lang.t(R.string.unit_km_grouped, i.odometerKm));
        if (i.fuelBars >= 0) parts.add(Lang.t(R.string.bike_summary_fuel, fuelWords(i.fuelBars)));
        if (i.rangeKm >= 0) parts.add(Lang.t(R.string.bike_summary_range, i.rangeKm));
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
                if (t.rpm >= 0) r.add(new String[]{s, "Engine speed", Lang.t(R.string.unit_rpm, t.rpm)});
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
                            Lang.t(R.string.bike_value_tcs, Math.max(0, t.tcsHigh), Math.max(0, t.tcsLow))});
                }
                if (t.abs >= 0) r.add(new String[]{s, "ABS", absText(t.abs)});
                if (t.wheelie > 0) {
                    r.add(new String[]{s, "Wheelie", Double.isNaN(t.wheelieDeg) ? Lang.t(R.string.common_yes)
                            : Lang.t(R.string.bike_value_wheelie, t.wheelieDeg)});
                }
                if (t.cruiseOn >= 0) {
                    // byte 88 is Rideology's wheel-speed setting, not a cruise speed: it is not shown here
                    r.add(new String[]{s, "Cruise control", t.cruiseOn == 1
                            ? (t.cruiseMode > 0 ? Lang.t(R.string.bike_value_cruise_mode, t.cruiseMode) : Lang.t(R.string.common_on))
                            : Lang.t(R.string.common_off_lower)});
                }
                // torque in the bike's own unit: the numbers compare with each other, not with a spec sheet
                if (t.riderTorque >= 0) r.add(new String[]{s, "Torque asked by the throttle", Lang.t(R.string.bike_value_bike_units, t.riderTorque)});
                if (t.engineTorqueRequest >= 0) r.add(new String[]{s, "Torque the engine control asks for", Lang.t(R.string.bike_value_bike_units, t.engineTorqueRequest)});
                if (t.engineTorque >= 0) r.add(new String[]{s, "Torque from the engine", Lang.t(R.string.bike_value_bike_units, t.engineTorque)});
                // brake pressures: the masters are raw, the caliper scaling comes from Rideology
                if (t.brakeFrontMaster >= 0) r.add(new String[]{s, "Front brake master (unverified)", String.valueOf(t.brakeFrontMaster)});
                if (t.brakeRearMaster >= 0) r.add(new String[]{s, "Rear brake master (unverified)", String.valueOf(t.brakeRearMaster)});
                if (t.brakeRearCaliper >= 0) r.add(new String[]{s, "Rear brake caliper (unverified)", String.valueOf(t.brakeRearCaliper)});
                if (t.absTarget >= 0 || t.absIndicator >= 0) {
                    r.add(new String[]{s, "ABS working", Lang.t(R.string.bike_value_abs_working, t.absTarget, t.absIndicator)});
                }
                if (t.absSwitching >= 0 && t.absSwitching != 0) {
                    r.add(new String[]{s, "ABS switching", String.valueOf(t.absSwitching)});
                }
            }
            if (ext) {
                add(r, s, "Fuel use now", b.ext.fuelRate, Lang.t(R.string.bike_value_dash_units_2));
                add(r, s, "Fuel economy now", b.ext.instantFuel, Lang.t(R.string.bike_value_dash_units_1));
                add(r, s, "Coolant temperature", b.ext.waterC, "%.0f °C");
                add(r, s, "Oil temperature", b.ext.oilC, "%.0f °C");
                add(r, s, "Intake air temperature", b.ext.intakeC, "%.0f °C");
                add(r, s, "Front tyre", b.ext.tyreFrontKpa / 100, "%.2f bar");
                add(r, s, "Rear tyre", b.ext.tyreRearKpa / 100, "%.2f bar");
            }
        }
        // the IMU's accelerations and rates (0x4B; a Z900 sends them too), travel on bikes with the electronic suspension
        BikeFrames.ImuSusp m0 = b.imu;
        if (m0 != null && now - b.imuAt < LIVE_MS) {
            String s = "Suspension";
            add(r, s, "Front travel", m0.frontStrokeMm, "%.0f mm");
            add(r, s, "Rear travel", m0.rearStrokeMm, "%.0f mm");
            add(r, s, "Acceleration (forward, + = accelerating)", m0.longitudinalG, "%+.2f g");
            add(r, s, "Acceleration (sideways)", m0.lateralG, "%+.2f g");
            add(r, s, "Acceleration (up)", m0.verticalG, "%+.2f g");
            // turned into the bike's axes like the accelerations (PROTOCOL.md, 0x4B)
            add(r, s, "Yaw rate (+ = turning right)", m0.yawRate, "%+.1f °/s");
            add(r, s, "Roll rate (+ = rolling right)", m0.rollRate, "%+.1f °/s");
            add(r, s, "Pitch rate", m0.pitchRate, "%+.1f °/s");
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
                r.add(new String[]{s, "Quick shifter", Lang.t(R.string.bike_value_kqs, m0.kqsDown, m0.kqsUp)});
            }
            if (m0.wheelieFlag > 0) r.add(new String[]{s, "Wheelie (suspension unit)", Lang.t(R.string.common_yes)});
        }
        if (live && b.live.damping >= 0) {
            BikeFrames.Telemetry t = b.live;
            r.add(new String[]{"Suspension", "Damping now", level(t.damping)});
            if (t.suspCalibration >= 0) {
                r.add(new String[]{"Suspension", "Where the suspension stands",
                        Lang.t(R.string.bike_value_suspension, t.suspCalibration, t.suspStatus, t.suspEcu)});
            }
        }
        if (b.service != null) {
            java.text.DateFormat df = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM);
            double odo = b.info != null && b.info.odometerKm >= 0 ? b.info.odometerKm : Double.NaN;
            for (int k = 0; k < 3; k++) {
                String due = MaintenanceLog.due(b.service.reminders[k], odo, df);
                if (due != null) r.add(new String[]{"Maintenance", BikeFrames.ServiceIndicator.names()[k], due});
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
            if (g.shiftLampRpm() > 0) r.add(new String[]{s, "Shift lamp", Lang.t(R.string.unit_rpm, g.shiftLampRpm())});
        }
        return r;
    }

    /** The ABS state bits of 0x4A byte 37 in words (Rideology's split: qe/b.java). */
    static String absText(int abs) {
        if ((abs & 0x0C) != 0) return Lang.t(R.string.bike_abs_fault);
        if ((abs & 0x10) != 0) return Lang.t(R.string.bike_abs_inspection);
        if ((abs & 0x03) == 0x03) return Lang.t(R.string.bike_abs_both);
        if ((abs & 0x01) != 0) return Lang.t(R.string.bike_abs_front);
        if ((abs & 0x02) != 0) return Lang.t(R.string.bike_abs_rear);
        return Lang.t(R.string.bike_abs_ready);
    }

    /** A damping level the bike reports; Rideology's scale for it is not known yet, so the number stands. */
    private static String level(int value) {
        return String.valueOf(value);
    }

    private static void add(List<String[]> r, String section, String label, double value, String fmt) {
        if (!Double.isNaN(value)) r.add(new String[]{section, label, String.format(Locale.ROOT, fmt, value)});
    }
}
