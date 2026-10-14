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
 * The refuels: what the app saw at the pump (the meter never says how many litres went in, so they are
 * estimated from the gauge and can be corrected here) and what the rider adds by hand. Fuel used between
 * two fills is worked out from the odometer.
 */
public class FuelActivity extends KawaActivity {

    private FuelStore store;
    private LinearLayout list;
    private LinearLayout empty;
    private TextView summary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        Logger.init(this);
        prefs = Hub.prefs(this);
        store = new FuelStore(this);
        night = isNight();

        LinearLayout root = column();
        root.setPadding(dp(Ui.SPACE4), 0, dp(Ui.SPACE4), dp(Ui.SPACE6));
        root.addView(headerBar("Refuels", v -> finish(), null));
        summary = secondary("");
        summary.setPadding(dp(Ui.SPACE1), dp(Ui.SPACE2), dp(Ui.SPACE1), dp(Ui.SPACE2));
        root.addView(summary);
        root.addView(tankCard());
        empty = column();
        root.addView(empty);
        list = column();
        root.addView(list);
        root.addView(addCard());
        TextView note = note("The bike does not report how much fuel goes in: it reports the gauge, the range "
                + "and the fuel it has burned in total. Refuels are worked out from the gauge jumping up while "
                + "the bike stands still; the litres are an estimate until you correct them.");
        note.setPadding(dp(Ui.SPACE1), dp(Ui.SPACE4), dp(Ui.SPACE1), dp(Ui.SPACE2));
        root.addView(note);
        setContentView(page(root));
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private double tankL() {
        return prefs.getFloat(FuelLog.KEY_TANK_L, (float) FuelLog.DEFAULT_TANK_L);
    }

    /** The tank size: what the gauge is turned into litres with. */
    private View tankCard() {
        LinearLayout c = card();
        LinearLayout head = row();
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.addView(iconTitle(R.drawable.ic_fuel, cardTitle("Tank")), weight());
        TextView value = number(16);
        value.setText(String.format(Locale.ROOT, "%.1f L", tankL()));
        head.addView(value);
        c.addView(head);
        c.addView(secondary("What a full tank holds, for turning the gauge into litres. Your bike's manual "
                + "has the number."));
        Button set = neutralButton("Change", v -> askNumber("Tank capacity in litres", tankL(), 1, 40, "%.1f",
                litres -> {
                    prefs.edit().putFloat(FuelLog.KEY_TANK_L, (float) litres).apply();
                    recreate();
                }));
        LinearLayout buttons = row();
        buttons.setPadding(0, dp(Ui.SPACE2), 0, 0);
        buttons.addView(set, weight());
        c.addView(buttons);
        return c;
    }

    private View addCard() {
        LinearLayout c = card();
        c.addView(cardTitle("Add a refuel"));
        c.addView(secondary("For a fill the app did not see: the bike was off, or the gauge did not move."));
        Button add = button("Add by hand", v -> askNumber("Litres added", Double.NaN, 0, 40, "%.1f", litres -> {
            FuelLog.Entry e = new FuelLog.Entry();
            e.at = System.currentTimeMillis();
            BikeFrames.VehicleInfo info = Hub.bike.info;
            e.odoKm = info != null ? info.odometerKm : Double.NaN;
            e.litres = litres;
            e.note = "added by hand";
            save(e, true);
        }));
        LinearLayout buttons = row();
        buttons.setPadding(0, dp(Ui.SPACE2), 0, 0);
        buttons.addView(add, weight());
        c.addView(buttons);
        return c;
    }

    private void refresh() {
        List<FuelLog.Entry> entries = store.list();
        summary.setText(entries.isEmpty() ? "No refuels yet" : summary(entries));
        empty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        if (entries.isEmpty()) {
            empty.removeAllViews();
            empty.addView(emptyState("No refuels yet",
                    "Standing at the pump with the app connected, a fill is noticed by itself."));
        }
        list.removeAllViews();
        FuelLog.Entry previous = null;
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        for (FuelLog.Entry e : entries) {
            double per100 = FuelLog.consumptionLPer100(previous, e);
            String line2 = per100 == per100
                    ? String.format(Locale.ROOT, "%.1f L/100 km since the last fill", per100)
                    : (previous == null ? "First fill in the log" : "Litres not known: tap to fill them in");
            String line1 = e.describe() + (e.note.isEmpty() ? "" : " · " + e.note);
            View row = listRow(R.drawable.ic_fuel, df.format(new java.util.Date(e.at)), line1, line2, true,
                    v -> edit(e));
            row.setOnLongClickListener(v -> {
                confirmDelete(e);
                return true;
            });
            list.addView(row);
            previous = e;
        }
    }

    private String summary(List<FuelLog.Entry> entries) {
        double litres = 0;
        boolean any = false;
        for (FuelLog.Entry e : entries) {
            if (e.litres == e.litres) {
                litres += e.litres;
                any = true;
            }
        }
        FuelLog.Entry first = entries.get(0), last = entries.get(entries.size() - 1);
        StringBuilder sb = new StringBuilder();
        sb.append(entries.size()).append(entries.size() == 1 ? " refuel" : " refuels");
        if (any) sb.append(String.format(Locale.ROOT, " · %,.1f L in total", litres));
        if (first.odoKm == first.odoKm && last.odoKm == last.odoKm && last.odoKm > first.odoKm) {
            sb.append(String.format(Locale.ROOT, " · %,.0f km", last.odoKm - first.odoKm));
        }
        return sb.toString();
    }

    /** Corrects the litres of a refuel, or adds a note. */
    private void edit(FuelLog.Entry e) {
        EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (e.litres == e.litres) input.setText(String.format(Locale.ROOT, "%.1f", e.litres));
        input.setHint("litres");
        LinearLayout box = column();
        box.setPadding(dp(Ui.SPACE4), dp(Ui.SPACE2), dp(Ui.SPACE4), 0);
        box.addView(input);
        EditText noteInput = new EditText(this);
        noteInput.setSingleLine(true);
        noteInput.setHint("note (where, what you paid…)");
        noteInput.setText(e.note);
        box.addView(noteInput);
        new android.app.AlertDialog.Builder(this)
                .setTitle(e.describe())
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    e.litres = parse(input.getText().toString(), e.litres);
                    e.estimated = false;
                    e.note = noteInput.getText().toString().trim();
                    save(e, false);
                })
                .setNeutralButton("Delete", (d, w) -> confirmDelete(e))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDelete(FuelLog.Entry e) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Delete this refuel?")
                .setMessage(e.describe())
                .setPositiveButton("Delete", (d, w) -> {
                    List<FuelLog.Entry> entries = store.list();
                    entries.removeIf(x -> x.at == e.at);
                    store.save(entries);
                    Hub.fuelEntries = FuelLog.sorted(entries);
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void save(FuelLog.Entry e, boolean add) {
        List<FuelLog.Entry> entries = store.list();
        if (add) entries.add(e);
        for (FuelLog.Entry x : entries) {
            if (x.at == e.at) {
                x.litres = e.litres;
                x.estimated = e.estimated;
                x.note = e.note;
                if (x.odoKm != x.odoKm) x.odoKm = e.odoKm;
            }
        }
        store.save(entries);
        Hub.fuelEntries = FuelLog.sorted(entries);
        refresh();
    }

    /** A number typed by the rider, or {@code before} when the field is left empty or is not a number. */
    private static double parse(String s, double before) {
        try {
            double v = Double.parseDouble(s.trim().replace(',', '.'));
            return v > 0 ? v : before;
        } catch (NumberFormatException e) {
            return before;
        }
    }
}
