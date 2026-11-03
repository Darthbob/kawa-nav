package io.github.darthbob.kawanav;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.List;
import java.util.Locale;

/**
 * What was done to the bike: the work the dash cannot know about, kept next to the reminders it does keep.
 * The odometer comes from the bike when it is connected, and can be typed when it is not.
 */
public class MaintenanceActivity extends KawaActivity {

    private MaintenanceStore store;
    private List<MaintenanceLog.Entry> entries = new java.util.ArrayList<>();
    private boolean unreadable;
    private LinearLayout list;
    private LinearLayout dashCard;
    private LinearLayout remindersCard;
    private TextView summary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        Logger.init(this);
        prefs = Hub.prefs(this);
        store = new MaintenanceStore(this);
        night = isNight();

        LinearLayout root = column();
        root.setPadding(dp(Ui.SPACE4), 0, dp(Ui.SPACE4), dp(Ui.SPACE6));
        root.addView(headerBar(Lang.t(R.string.bike_row_maintenance), v -> finish(), null));
        summary = secondary("");
        summary.setPadding(dp(Ui.SPACE1), dp(Ui.SPACE2), dp(Ui.SPACE1), dp(Ui.SPACE2));
        root.addView(summary);
        dashCard = column();
        root.addView(dashCard);
        remindersCard = column();
        root.addView(remindersCard);
        list = column();
        root.addView(list);
        root.addView(addCard());
        TextView note = note(Lang.t(R.string.maint_note));
        note.setPadding(dp(Ui.SPACE1), dp(Ui.SPACE4), dp(Ui.SPACE1), dp(Ui.SPACE2));
        root.addView(note);
        setContentView(page(root));
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (entries.isEmpty() && !unreadable) load();
        else refresh();
    }

    /** Reads the log once; the screen works on what it has, so a save cannot race the file. */
    private void load() {
        List<MaintenanceLog.Entry> from = store.list();
        unreadable = from == null;
        entries = unreadable ? new java.util.ArrayList<>() : from;
        refresh();
    }

    /** What the dash says is due, so the rider does not have to go back to the bike screen. */
    private void updateDash() {
        dashCard.removeAllViews();
        BikeFrames.VehicleInfo info = Hub.bike.info;
        double odo = info != null ? info.odometerKm : Double.NaN;
        List<String> due = MaintenanceLog.dueFromDash(Hub.bike.service, odo);
        if (due.isEmpty()) return;
        LinearLayout c = card();
        c.addView(iconTitle(R.drawable.ic_clock, cardTitle(Lang.t(R.string.maint_dash_says))));
        for (String line : due) c.addView(secondary(line));
        if (odo == odo) {
            c.addView(secondary(Lang.t(R.string.maint_odometer_now, odo)));
        }
        dashCard.addView(c);
    }

    /** The rider's own reminders: where each stands, a tap to change it, and a button for another. */
    private void updateReminders() {
        remindersCard.removeAllViews();
        LinearLayout c = card();
        c.addView(iconTitle(R.drawable.ic_bell, cardTitle(Lang.t(R.string.maint_your_reminders))));
        List<MaintenanceLog.Reminder> reminders = reminders();
        if (reminders.isEmpty()) {
            c.addView(secondary(Lang.t(R.string.maint_reminders_empty)));
        }
        DateFormat df = DateFormat.getDateInstance(DateFormat.MEDIUM);
        for (MaintenanceLog.Due d : MaintenanceLog.due(entries, reminders, odometer(), System.currentTimeMillis())) {
            String state = d.describe(df);
            if (d.soon()) state = Lang.t(R.string.maint_soon, state);
            c.addView(listRow(d.due() ? R.drawable.ic_alert : R.drawable.ic_bell, d.reminder.item,
                    d.reminder.interval(), state, true, v -> editReminder(d.reminder.item, d.reminder)));
        }
        LinearLayout buttons = row();
        buttons.setPadding(0, dp(Ui.SPACE2), 0, 0);
        buttons.addView(secondaryButton(Lang.t(R.string.maint_add_reminder), v -> pickReminder()), weight());
        c.addView(buttons);
        remindersCard.addView(c);
    }

    private List<MaintenanceLog.Reminder> reminders() {
        return MaintenanceLog.reminders(prefs.getString(Hub.KEY_MAINT_REMINDERS, ""));
    }

    /** The odometer the bike last sent, NaN when it never did. */
    private static double odometer() {
        BikeFrames.VehicleInfo info = Hub.bike.info;
        return info != null && info.odometerKm >= 0 ? info.odometerKm : Double.NaN;
    }

    /** Which job: the list's, then the ones logged under a name of their own, then one typed now. */
    private void pickReminder() {
        List<String> names = new java.util.ArrayList<>();
        String[] items = MaintenanceLog.items();
        for (int i = 0; i < items.length - 1; i++) names.add(items[i]); // all but "Other"
        for (MaintenanceLog.Entry e : entries) {
            boolean known = false;
            for (String n : names) known |= n.equalsIgnoreCase(e.item);
            if (!known) names.add(e.item);
        }
        names.add(Lang.t(R.string.maint_other_dots));
        new android.app.AlertDialog.Builder(this)
                .setTitle(Lang.t(R.string.maint_remind_me))
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    if (which < names.size() - 1) {
                        String item = names.get(which);
                        editReminder(item, MaintenanceLog.reminderFor(reminders(), item));
                    } else {
                        nameReminder();
                    }
                })
                .setNegativeButton(Lang.t(R.string.common_cancel), null)
                .show();
    }

    private void nameReminder() {
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint(Lang.t(R.string.maint_what_due));
        LinearLayout box = column();
        box.setPadding(dp(Ui.SPACE4), dp(Ui.SPACE2), dp(Ui.SPACE4), 0);
        box.addView(name);
        new android.app.AlertDialog.Builder(this)
                .setTitle(Lang.t(R.string.maint_remind_me))
                .setView(box)
                .setPositiveButton(Lang.t(R.string.maint_next), (d, w) -> {
                    String item = name.getText().toString().trim();
                    if (!item.isEmpty()) editReminder(item, MaintenanceLog.reminderFor(reminders(), item));
                })
                .setNegativeButton(Lang.t(R.string.common_cancel), null)
                .show();
    }

    /** How often {@code item} is due; {@code current} is its reminder, null for a new one. */
    private void editReminder(String item, MaintenanceLog.Reminder current) {
        EditText km = new EditText(this);
        km.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        km.setHint(Lang.t(R.string.maint_every_km));
        EditText months = new EditText(this);
        months.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        months.setHint(Lang.t(R.string.maint_every_months));
        if (current != null) {
            if (current.km > 0) km.setText(String.valueOf(current.km));
            if (current.months > 0) months.setText(String.valueOf(current.months));
        }
        LinearLayout box = column();
        box.setPadding(dp(Ui.SPACE4), dp(Ui.SPACE2), dp(Ui.SPACE4), 0);
        box.addView(secondary(Lang.t(R.string.maint_counted)));
        box.addView(km);
        box.addView(months);
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                .setTitle(item)
                .setView(box)
                .setPositiveButton(Lang.t(R.string.planner_save), (d, w) -> saveReminder(item,
                        new MaintenanceLog.Reminder(item, whole(km), whole(months))))
                .setNegativeButton(Lang.t(R.string.common_cancel), null);
        if (current != null) b.setNeutralButton(Lang.t(R.string.planner_remove), (d, w) -> saveReminder(item, null));
        b.show();
    }

    /** Puts the reminder for {@code item} in place of the old one; null, or neither km nor months, removes it. */
    private void saveReminder(String item, MaintenanceLog.Reminder r) {
        List<MaintenanceLog.Reminder> reminders = reminders();
        int at = reminders.size();
        for (int i = reminders.size() - 1; i >= 0; i--) {
            if (reminders.get(i).item.equalsIgnoreCase(item)) {
                reminders.remove(i);
                at = i;
            }
        }
        if (r != null && (r.km > 0 || r.months > 0)) reminders.add(Math.min(at, reminders.size()), r);
        prefs.edit().putString(Hub.KEY_MAINT_REMINDERS, MaintenanceLog.saveReminders(reminders)).apply();
        refresh();
    }

    private static int whole(EditText input) {
        try {
            return Math.max(0, Integer.parseInt(input.getText().toString().trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private View addCard() {
        LinearLayout c = card();
        c.addView(cardTitle(Lang.t(R.string.maint_add_job)));
        c.addView(secondary(Lang.t(R.string.maint_add_job_sub)));
        LinearLayout buttons = row();
        buttons.setPadding(0, dp(Ui.SPACE2), 0, 0);
        String[] items = MaintenanceLog.items();
        for (int i = 0; i < items.length; i += 2) {
            LinearLayout line = row();
            for (int k = i; k < Math.min(i + 2, items.length); k++) {
                String item = items[k];
                Button b = button(item, v -> add(item));
                b.setTextSize(13);
                b.setTextColor(Ui.accentText(night));
                line.addView(b, weight());
            }
            c.addView(line);
        }
        return c;
    }

    /** Asks for the day, the odometer and a note before writing the entry. */
    private void add(String item) {
        BikeFrames.VehicleInfo info = Hub.bike.info;
        double odo = info != null ? info.odometerKm : Double.NaN;
        long[] day = {System.currentTimeMillis()};
        EditText odoInput = new EditText(this);
        odoInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (odo == odo) odoInput.setText(String.format(Locale.ROOT, "%.0f", odo));
        odoInput.setHint(Lang.t(R.string.maint_odometer_hint));
        EditText noteInput = new EditText(this);
        noteInput.setHint(Lang.t(R.string.maint_note_hint));
        LinearLayout box = column();
        box.setPadding(dp(Ui.SPACE4), dp(Ui.SPACE2), dp(Ui.SPACE4), 0);
        box.addView(dateRow(day));
        box.addView(odoInput);
        box.addView(noteInput);
        EditText whatInput = new EditText(this);
        whatInput.setSingleLine(true);
        whatInput.setText(item.equals(Lang.t(R.string.maint_other)) ? "" : item);
        whatInput.setHint(Lang.t(R.string.maint_what_done));
        box.addView(whatInput, 0);
        new android.app.AlertDialog.Builder(this)
                .setTitle(item)
                .setView(box)
                .setPositiveButton(Lang.t(R.string.planner_save), (d, w) -> {
                    String what = whatInput.getText().toString().trim();
                    MaintenanceLog.Entry e = new MaintenanceLog.Entry();
                    e.at = day[0];
                    e.item = what.isEmpty() ? item : what;
                    double typed = parse(odoInput.getText().toString());
                    e.odoKm = typed >= 0 ? typed : Double.NaN;    // -1 is "the bike has no odometer"
                    e.note = noteInput.getText().toString().trim();
                    entries.add(e);
                    save();
                })
                .setNegativeButton(Lang.t(R.string.common_cancel), null)
                .show();
    }

    private void edit(MaintenanceLog.Entry e) {
        EditText noteInput = new EditText(this);
        noteInput.setSingleLine(true);
        noteInput.setHint(Lang.t(R.string.maint_note_short));
        noteInput.setText(e.note);
        LinearLayout box = column();
        box.setPadding(dp(Ui.SPACE4), dp(Ui.SPACE2), dp(Ui.SPACE4), 0);
        box.addView(noteInput);
        new android.app.AlertDialog.Builder(this)
                .setTitle(e.describe())
                .setView(box)
                .setPositiveButton(Lang.t(R.string.planner_save), (d, w) -> {
                    for (MaintenanceLog.Entry x : entries) {
                        if (x == e) x.note = noteInput.getText().toString().trim();
                    }
                    save();
                })
                .setNeutralButton(Lang.t(R.string.common_delete), (d, w) -> confirmDelete(e))
                .setNegativeButton(Lang.t(R.string.common_cancel), null)
                .show();
    }

    private void confirmDelete(MaintenanceLog.Entry e) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(Lang.t(R.string.maint_delete_title))
                .setMessage(e.describe())
                .setPositiveButton(Lang.t(R.string.common_delete), (d, w) -> {
                    entries.remove(e);
                    save();
                })
                .setNegativeButton(Lang.t(R.string.common_cancel), null)
                .show();
    }

    private void save() {
        entries = MaintenanceLog.sorted(entries);
        store.save(entries);
        Hub.maintenanceEntries = entries;
        refresh();
    }

    private void refresh() {
        updateDash();
        updateReminders();
        summary.setText(entries.isEmpty() ? Lang.t(R.string.maint_nothing_yet) : Lang.plural(R.plurals.maint_entries, entries.size()));
        list.removeAllViews();
        DateFormat df = DateFormat.getDateInstance(DateFormat.MEDIUM);
        BikeFrames.VehicleInfo info = Hub.bike.info;
        double odo = info != null ? info.odometerKm : Double.NaN;
        for (int i = entries.size() - 1; i >= 0; i--) {
            MaintenanceLog.Entry e = entries.get(i);
            double since = MaintenanceLog.kmSince(entries, e.item, odo);
            StringBuilder line = new StringBuilder(e.note);
            if (since == since) {
                if (line.length() > 0) line.append(" · ");
                line.append(Lang.t(R.string.bike_km_ago, since));
            }
            long days = (System.currentTimeMillis() - e.at) / (24 * 60 * 60_000L);
            String when = df.format(new java.util.Date(e.at));
            if (days > 0) when += " (" + Lang.plural(R.plurals.maint_days_ago, (int) days) + ")";
            View row = listRow(R.drawable.ic_list, e.item, when + " · "
                    + (e.odoKm == e.odoKm ? String.format(Locale.ROOT, "%,.0f km", e.odoKm) : Lang.t(R.string.maint_no_odometer)),
                    line.length() == 0 ? null : line.toString(), false, v -> edit(e));
            row.setOnLongClickListener(v -> {
                confirmDelete(e);
                return true;
            });
            list.addView(row);
        }
    }

    private static double parse(String s) {
        try {
            double v = Double.parseDouble(s.trim().replace(',', '.'));
            return v > 0 ? v : Double.NaN;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
