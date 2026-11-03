package io.github.darthbob.kawanav;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The 12 V battery over time: the voltage the bike showed before the engine started, which is the one that
 * says whether the battery is going (once the engine runs, 13-14 V hides everything). The chart is the same
 * one the rides use, with the readings laid on it by their date.
 */
public class BatteryActivity extends KawaActivity {

    private BatteryStore store;
    private LinearLayout list;
    private TextView summary;
    private TextView verdict;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        Logger.init(this);
        prefs = Hub.prefs(this);
        store = new BatteryStore(this);
        night = isNight();

        LinearLayout root = column();
        root.setPadding(dp(Ui.SPACE4), 0, dp(Ui.SPACE4), dp(Ui.SPACE6));
        root.addView(headerBar(Lang.t(R.string.battery_title), v -> finish(), null));
        summary = secondary("");
        summary.setPadding(dp(Ui.SPACE1), dp(Ui.SPACE2), dp(Ui.SPACE1), dp(Ui.SPACE2));
        root.addView(summary);
        verdict = text(15, true);
        verdict.setPadding(dp(Ui.SPACE1), 0, dp(Ui.SPACE1), dp(Ui.SPACE2));
        root.addView(verdict);
        root.addView(chartCard());
        list = column();
        root.addView(list);
        TextView note = note(Lang.t(R.string.battery_note));
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

    private View chartCard() {
        LinearLayout c = card();
        c.addView(cardTitle(Lang.t(R.string.battery_at_rest)));
        ChartView chart = new ChartView(this);
        chart.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(160)));
        c.addView(chart);
        this.chart = chart;
        return c;
    }

    private ChartView chart;

    private void refresh() {
        List<BatteryLog.Reading> readings = store.list();
        if (readings == null) {
            summary.setText("");
            verdict.setText("");
            chart.set(new ArrayList<>(), ChartView.Series.BATTERY, null);
            list.removeAllViews();
            list.addView(emptyState(Lang.t(R.string.battery_unreadable),
                    Lang.t(R.string.battery_unreadable_sub)));
            return;
        }
        if (readings.isEmpty()) {
            summary.setText(Lang.t(R.string.battery_none));
            verdict.setText("");
            list.removeAllViews();
            list.addView(emptyState(Lang.t(R.string.battery_nothing),
                    Lang.t(R.string.battery_nothing_sub)));
            return;
        }
        double[] range = BatteryLog.range(readings);
        BatteryLog.Reading last = readings.get(readings.size() - 1);
        summary.setText(Lang.plural(R.plurals.battery_summary, readings.size(), readings.size(), last.volts, range[0]));
        double decline = BatteryLog.decline(readings);
        if (BatteryLog.declining(readings)) {
            verdict.setText(Lang.t(R.string.battery_declining, decline));
            verdict.setTextColor(Ui.warn(night));
        } else if (last.volts < 12.0) {
            verdict.setText(Lang.t(R.string.battery_low, last.volts));
            verdict.setTextColor(Ui.warn(night));
        } else {
            verdict.setText(Lang.t(R.string.battery_fine));
            verdict.setTextColor(Ui.ink(night));
        }
        ChartView.Series series = ChartView.Series.BATTERY;
        List<RideTsv.Sample> samples = new ArrayList<>();
        long first = readings.get(0).at;
        for (BatteryLog.Reading r : readings) {
            RideTsv.Sample s = new RideTsv.Sample();
            s.tMs = r.at - first;
            s.batteryV = r.volts;
            samples.add(s);
        }
        chart.setEpochAtZero(first);
        chart.set(samples, series, null);

        list.removeAllViews();
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        for (int i = readings.size() - 1; i >= 0 && i >= readings.size() - 20; i--) {
            BatteryLog.Reading r = readings.get(i);
            String line = String.format(Locale.ROOT, "%.1f V", r.volts)
                    + (i > 0 ? " · " + Lang.t(R.string.battery_since_first, r.volts - readings.get(0).volts) : "");
            list.addView(listRow(R.drawable.ic_battery, df.format(new java.util.Date(r.at)), line, null, false, null));
        }
    }
}
