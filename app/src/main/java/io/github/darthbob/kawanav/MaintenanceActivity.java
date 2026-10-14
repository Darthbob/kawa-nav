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
        root.addView(headerBar("Maintenance", v -> finish(), null));
        summary = secondary("");
        summary.setPadding(dp(Ui.SPACE1), dp(Ui.SPACE2), dp(Ui.SPACE1), dp(Ui.SPACE2));
        root.addView(summary);
        dashCard = column();
        root.addView(dashCard);
        list = column();
        root.addView(list);
        root.addView(addCard());
        TextView note = note("Kept on the phone only. The reminders on top come from the dash itself; what you "
                + "write here is your own record of the work done.");
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
        c.addView(iconTitle(R.drawable.ic_clock, cardTitle("The dash says")));
        for (String line : due) c.addView(secondary(line));
        if (odo == odo) {
            c.addView(secondary(String.format(Locale.ROOT, "Odometer now: %,.0f km", odo)));
        }
        dashCard.addView(c);
    }

    private View addCard() {
        LinearLayout c = card();
        c.addView(cardTitle("Add a job"));
        c.addView(secondary("What you did, when, and at what odometer."));
        LinearLayout buttons = row();
        buttons.setPadding(0, dp(Ui.SPACE2), 0, 0);
        for (int i = 0; i < MaintenanceLog.ITEMS.length; i += 2) {
            LinearLayout line = row();
            for (int k = i; k < Math.min(i + 2, MaintenanceLog.ITEMS.length); k++) {
                String item = MaintenanceLog.ITEMS[k];
                Button b = button(item, v -> add(item));
                b.setTextSize(13);
                b.setTextColor(Ui.accent(night));
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
        odoInput.setHint("odometer in km");
        EditText noteInput = new EditText(this);
        noteInput.setHint("what was done, what it cost…");
        LinearLayout box = column();
        box.setPadding(dp(Ui.SPACE4), dp(Ui.SPACE2), dp(Ui.SPACE4), 0);
        box.addView(dateRow(day));
        box.addView(odoInput);
        box.addView(noteInput);
        EditText whatInput = new EditText(this);
        whatInput.setSingleLine(true);
        whatInput.setText(item.equals("Other") ? "" : item);
        whatInput.setHint("what was done");
        box.addView(whatInput, 0);
        new android.app.AlertDialog.Builder(this)
                .setTitle(item)
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
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
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void edit(MaintenanceLog.Entry e) {
        EditText noteInput = new EditText(this);
        noteInput.setSingleLine(true);
        noteInput.setHint("note");
        noteInput.setText(e.note);
        LinearLayout box = column();
        box.setPadding(dp(Ui.SPACE4), dp(Ui.SPACE2), dp(Ui.SPACE4), 0);
        box.addView(noteInput);
        new android.app.AlertDialog.Builder(this)
                .setTitle(e.describe())
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    for (MaintenanceLog.Entry x : entries) {
                        if (x == e) x.note = noteInput.getText().toString().trim();
                    }
                    save();
                })
                .setNeutralButton("Delete", (d, w) -> confirmDelete(e))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDelete(MaintenanceLog.Entry e) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Delete this entry?")
                .setMessage(e.describe())
                .setPositiveButton("Delete", (d, w) -> {
                    entries.remove(e);
                    save();
                })
                .setNegativeButton("Cancel", null)
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
        summary.setText(entries.isEmpty() ? "Nothing logged yet"
                : entries.size() + (entries.size() == 1 ? " entry" : " entries"));
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
                line.append(String.format(Locale.ROOT, "%,.0f km ago", since));
            }
            long days = (System.currentTimeMillis() - e.at) / (24 * 60 * 60_000L);
            String when = df.format(new java.util.Date(e.at));
            if (days > 0) when += " (" + days + (days == 1 ? " day ago)" : " days ago)");
            View row = listRow(R.drawable.ic_list, e.item, when + " · "
                    + (e.odoKm == e.odoKm ? String.format(Locale.ROOT, "%,.0f km", e.odoKm) : "no odometer"),
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
