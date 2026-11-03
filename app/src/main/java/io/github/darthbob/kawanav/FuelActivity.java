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
    /** What is on screen and what a save writes: the file is only read when the screen opens. */
    private List<FuelLog.Entry> entries = new java.util.ArrayList<>();
    private boolean unreadable;
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
        root.addView(headerBar(Lang.t(R.string.bike_refuels), v -> finish(), null));
        summary = secondary("");
        summary.setPadding(dp(Ui.SPACE1), dp(Ui.SPACE2), dp(Ui.SPACE1), dp(Ui.SPACE2));
        root.addView(summary);
        root.addView(tankCard());
        empty = column();
        root.addView(empty);
        list = column();
        root.addView(list);
        root.addView(addCard());
        TextView note = note(Lang.t(R.string.fuel_note));
        note.setPadding(dp(Ui.SPACE1), dp(Ui.SPACE4), dp(Ui.SPACE1), dp(Ui.SPACE2));
        root.addView(note);
        setContentView(page(root));
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        update();
    }

    /** Reads the log once; the screen then works on what it has, so a save cannot race the file. */
    private void load() {
        List<FuelLog.Entry> from = store.list();
        unreadable = from == null;
        entries = unreadable ? new java.util.ArrayList<>() : from;
        refresh();
    }

    private void update() {
        if (entries.isEmpty() && !unreadable) load();
        else refresh();
    }

    private double tankL() {
        return prefs.getFloat(FuelLog.KEY_TANK_L, (float) FuelLog.DEFAULT_TANK_L);
    }

    /** The tank size: what the gauge is turned into litres with. */
    private View tankCard() {
        LinearLayout c = card();
        LinearLayout head = row();
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.addView(iconTitle(R.drawable.ic_fuel, cardTitle(Lang.t(R.string.fuel_tank))), weight());
        TextView value = number(16);
        value.setText(String.format(Locale.ROOT, "%.1f L", tankL()));
        head.addView(value);
        c.addView(head);
        c.addView(secondary(Lang.t(R.string.fuel_tank_note)));
        Button set = neutralButton(Lang.t(R.string.fuel_change), v -> askNumber(Lang.t(R.string.fuel_tank_capacity), tankL(), 1, 40, "%.1f",
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
        c.addView(cardTitle(Lang.t(R.string.fuel_add)));
        c.addView(secondary(Lang.t(R.string.fuel_add_note)));
        Button add = button(Lang.t(R.string.fuel_add_by_hand), v -> askNumber(Lang.t(R.string.fuel_litres_added), Double.NaN, 0, 40, "%.1f", litres -> {
            if (litres != litres) return;                 // nothing typed: no entry
            FuelLog.Entry e = new FuelLog.Entry();
            e.at = System.currentTimeMillis();
            BikeFrames.VehicleInfo info = Hub.bike.info;
            double odo = info != null ? info.odometerKm : Double.NaN;
            e.odoKm = odo >= 0 ? odo : Double.NaN;        // -1 is "the bike has no odometer"
            e.litres = litres;
            e.note = Lang.t(R.string.fuel_added_by_hand);
            save(e, true);
        }));
        LinearLayout buttons = row();
        buttons.setPadding(0, dp(Ui.SPACE2), 0, 0);
        buttons.addView(add, weight());
        c.addView(buttons);
        return c;
    }

    private void refresh() {
        if (unreadable) {
            summary.setText("");
            list.removeAllViews();
            empty.removeAllViews();
            empty.setVisibility(View.VISIBLE);
            empty.addView(emptyState(Lang.t(R.string.fuel_unreadable),
                    Lang.t(R.string.fuel_unreadable_sub)));
            return;
        }
        // the card below says "No refuels yet": the line on top would only repeat it
        summary.setText(entries.isEmpty() ? "" : summary(entries));
        empty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        if (entries.isEmpty()) {
            empty.removeAllViews();
            empty.addView(emptyState(Lang.t(R.string.fuel_none),
                    Lang.t(R.string.fuel_none_sub)));
        }
        list.removeAllViews();
        FuelLog.Entry previous = null;
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        for (FuelLog.Entry e : entries) {
            double per100 = FuelLog.consumptionLPer100(previous, e);
            String line2 = per100 == per100
                    ? Lang.t(R.string.fuel_since_last, per100)
                    : (previous == null ? Lang.t(R.string.fuel_first) : Lang.t(R.string.fuel_litres_unknown));
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
        sb.append(Lang.plural(R.plurals.fuel_count, entries.size()));
        if (any) sb.append(" · ").append(Lang.t(R.string.fuel_total, litres));
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
        input.setHint(Lang.t(R.string.fuel_litres_hint));
        LinearLayout box = column();
        box.setPadding(dp(Ui.SPACE4), dp(Ui.SPACE2), dp(Ui.SPACE4), 0);
        box.addView(input);
        EditText noteInput = new EditText(this);
        noteInput.setSingleLine(true);
        noteInput.setHint(Lang.t(R.string.fuel_note_hint));
        noteInput.setText(e.note);
        box.addView(noteInput);
        new android.app.AlertDialog.Builder(this)
                .setTitle(e.describe())
                .setView(box)
                .setPositiveButton(Lang.t(R.string.planner_save), (d, w) -> {
                    double typed = parse(input.getText().toString(), e.litres);
                    if (typed != e.litres) e.estimated = false;   // the rider's own figure now
                    e.litres = typed;
                    e.note = noteInput.getText().toString().trim();
                    save(e, false);
                })
                .setNeutralButton(Lang.t(R.string.common_delete), (d, w) -> confirmDelete(e))
                .setNegativeButton(Lang.t(R.string.common_cancel), null)
                .show();
    }

    private void confirmDelete(FuelLog.Entry e) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(Lang.t(R.string.fuel_delete_title))
                .setMessage(e.describe())
                .setPositiveButton(Lang.t(R.string.common_delete), (d, w) -> {
                    entries.removeIf(x -> x.at == e.at && x.litres == e.litres);
                    saveAll();
                })
                .setNegativeButton(Lang.t(R.string.common_cancel), null)
                .show();
    }

    private void save(FuelLog.Entry e, boolean add) {
        if (add) entries.add(e);
        for (FuelLog.Entry x : entries) {
            if (x == e) continue;
            if (x.at == e.at && x.litres == e.litres && x.note.equals(e.note)) {
                x.litres = e.litres;
                x.estimated = e.estimated;
                x.note = e.note;
                if (x.odoKm != x.odoKm) x.odoKm = e.odoKm;
            }
        }
        saveAll();
    }

    /** Writes what is on screen and keeps the shared copy in step, so the bike screen agrees. */
    private void saveAll() {
        // a fill the bridge recorded while this screen was open is not on screen yet: keep it
        for (FuelLog.Entry fromBike : Hub.fuelEntries) {
            boolean known = false;
            for (FuelLog.Entry x : entries) {
                if (x.at == fromBike.at && x.litres == fromBike.litres) known = true;
            }
            if (!known) entries.add(fromBike);
        }
        entries = FuelLog.sorted(entries);
        store.save(entries);
        Hub.fuelEntries = entries;
        refresh();
    }

    /** A number typed by the rider, or {@code before} when the field is left empty or is not a number. */
    private static double parse(String s, double before) {
        Double v = number(s);
        return v != null && v > 0 ? v : before;
    }

    /** What the rider typed, whatever they type with: "1.234,5" and "1234.5" are the same number. */
    static Double number(String s) {
        String t = s.trim().replace(" ", "").replace("\u00A0", "");
        if (t.isEmpty()) return null;
        int lastComma = t.lastIndexOf(','), lastDot = t.lastIndexOf('.');
        if (lastComma >= 0 && lastDot >= 0) {
            // both are there: the last one is the decimal point, the other groups thousands
            if (lastComma > lastDot) t = t.replace(".", "").replace(',', '.');
            else t = t.replace(",", "");
        } else if (lastComma >= 0) {
            // a lone comma: a decimal point unless it groups three digits
            boolean grouping = t.length() - lastComma == 4 && t.indexOf(',') == lastComma;
            t = grouping ? t.replace(",", "") : t.replace(',', '.');
        }
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
