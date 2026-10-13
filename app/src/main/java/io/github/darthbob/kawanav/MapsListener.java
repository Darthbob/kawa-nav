package io.github.darthbob.kawanav;

import android.app.Notification;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Watches Google Maps' turn-by-turn notification and publishes the current maneuver to {@link Hub}.
 * Needs "Notification access" for KawaNav in Android settings.
 */
public class MapsListener extends NotificationListenerService {
    public static final String MAPS = "com.google.android.apps.maps";

    private IconMemory icons;
    private String lastKey = "";
    private int navNotificationId = Integer.MIN_VALUE;

    @Override
    public void onCreate() {
        super.onCreate();
        Logger.init(this);
        icons = new IconMemory(this);
    }

    @Override
    public void onListenerConnected() {
        Logger.log("Notification access active");
        try {
            for (StatusBarNotification sbn : getActiveNotifications()) onNotificationPosted(sbn);
            // notifications that were already there: show their icon too
            for (StatusBarNotification sbn : getActiveNotifications()) dashAlert(sbn, true);
        } catch (SecurityException e) {
            Logger.log("Cannot read active notifications: " + e.getMessage());
        }
    }

    @Override
    public void onListenerDisconnected() {
        Logger.log("Notification access disconnected");
        setMaps(null);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        dashAlert(sbn, true);
        if (!MAPS.equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        boolean nav = Notification.CATEGORY_NAVIGATION.equals(n.category)
                || (n.flags & Notification.FLAG_ONGOING_EVENT) != 0;
        if (!nav) return;
        navNotificationId = sbn.getId();

        List<String> texts = new ArrayList<>();
        Bundle ex = n.extras;
        for (String k : new String[]{Notification.EXTRA_TITLE, Notification.EXTRA_TEXT,
                Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_INFO_TEXT,
                Notification.EXTRA_SUMMARY_TEXT}) {
            CharSequence cs = ex.getCharSequence(k);
            if (cs != null && cs.length() > 0 && !texts.contains(cs.toString())) texts.add(cs.toString());
        }

        // Maps (Android 16 live-update style) puts the maneuver arrow in the small icon as a bitmap;
        // its static app icon would be a resource, so only take bitmap small icons
        Icon arrow = n.getLargeIcon();
        if (arrow == null && n.getSmallIcon() != null && n.getSmallIcon().getType() == Icon.TYPE_BITMAP) {
            arrow = n.getSmallIcon();
        }
        Bitmap icon = loadIcon(arrow);
        if (texts.isEmpty() || icon == null) {
            // Maps may render the turn card as a custom layout; read its views instead
            Bitmap fromLayout = readRemoteViews(n.bigContentView != null ? n.bigContentView : n.contentView,
                    texts, icon == null);
            if (icon == null) icon = fromLayout;
        }

        String hash = icon != null ? IconMemory.hash(icon) : null;
        String joined = String.join(" | ", texts);
        String key = joined + "#" + hash;
        if (key.equals(lastKey)) return;
        boolean firstOfSession = lastKey.isEmpty();
        lastKey = key;

        if (firstOfSession) dumpExtras(n);

        SharedPreferences p = Hub.prefs(this);
        boolean leftHand = p.getBoolean(Hub.KEY_LEFT_HAND, false);
        // the title is the instruction ("60 m · Rechts abbiegen auf X"); the text is the ETA line
        CharSequence title = ex.getCharSequence(Notification.EXTRA_TITLE);
        String instruction = title != null && title.length() > 0 ? title.toString() : joined;
        int parsed = MapsParser.parseTurn(instruction, leftHand);
        int dist = MapsParser.parseDistance(instruction);
        IconMemory.Hit hit = icons.lookup(hash);

        // Decision order: explicit roundabout exit number in the text (the icon shows the exit's
        // direction, not its number) > icon (taught > library > learned) > text.
        int turn;
        String how;
        if (MapsParser.hasRoundaboutExitNumber(instruction)) {
            turn = parsed;
            how = "text, roundabout exit number";
        } else if (hit != null && (hit.turn <= 10 || hit.turn == 16 || hit.turn == 17)
                && parsed >= 12 && parsed <= 17) {
            // Maps draws on-ramps and most exits with the plain turn/straight arrows, and all merges
            // with one icon; the text says which one it is
            turn = parsed;
            how = hit.source + " + text";
        } else if (hit != null) {
            turn = hit.turn;
            how = hit.source;
        } else {
            turn = parsed;
            how = parsed == KawaProtocol.TURN_NONE ? "not recognised" : "text";
        }

        icons.collect(hash, icon, parsed, instruction);
        if (hit != null && parsed != KawaProtocol.TURN_NONE && hit.turn != parsed
                && !MapsParser.isRoundabout(instruction)) {
            Logger.log("Note: icon says " + turnName(hit.turn) + " (" + hit.source + "), text says " + turnName(parsed));
        }

        String iconInfo = icon == null ? "no icon" : "icon " + icon.getWidth() + "px #" + IconMemory.shortId(hash);
        Logger.log("Maps: \"" + joined + "\" -> " + turnName(turn) + ", " + dist + " m (" + how + ", " + iconInfo + ")");
        setMaps(new Hub.MapsTurn(turn, dist, turnName(turn) + (dist >= 0 ? " · " + dist + " m" : "") + " (" + how + ")"));
    }

    /** A call or mail/message notification appeared or went away: tell the bike (icon only, no content). */
    private void dashAlert(StatusBarNotification sbn, boolean posted) {
        if (getPackageName().equals(sbn.getPackageName())) return;
        String category = sbn.getNotification().category;
        int icon = DashAlerts.categoryOf(sbn.getPackageName(), category);
        if (icon == 0) return;
        String key = sbn.getPackageName() + "|" + sbn.getId() + "|" + sbn.getTag();
        boolean change = posted ? DashAlerts.onPosted(icon, key) : DashAlerts.onRemoved(icon, key);
        if (!change) return;
        boolean on = DashAlerts.active(icon) && DashAlerts.enabled(this, icon);
        BridgeService.sendDashAlert(icon, on ? DashAlerts.ADDED : DashAlerts.REMOVED);
        Logger.log("Dash " + DashAlerts.name(icon) + " icon " + (on ? "on" : "off"));
    }

    /** The rider switched a dash icon off: clear it on the bike now. */
    public static void alertsChanged(android.content.Context c) {
        for (int icon : new int[]{DashAlerts.CALL, DashAlerts.MAIL}) {
            if (!DashAlerts.enabled(c, icon) && DashAlerts.active(icon)) {
                DashAlerts.clear(icon);
                BridgeService.sendDashAlert(icon, DashAlerts.REMOVED);
            }
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        dashAlert(sbn, false);
        if (MAPS.equals(sbn.getPackageName()) && sbn.getId() == navNotificationId) {
            Logger.log("Maps navigation ended");
            lastKey = "";
            setMaps(null);
        }
    }

    private static void setMaps(Hub.MapsTurn m) {
        Hub.maps = m;
        BridgeService.poke();
    }

    static String turnName(int turn) {
        return turn >= 0 && turn < KawaProtocol.TURN_NAMES.length ? KawaProtocol.TURN_NAMES[turn] : "unknown";
    }

    private Bitmap loadIcon(Icon icon) {
        if (icon == null) return null;
        try {
            Drawable d = icon.loadDrawable(this);
            if (d == null) return null;
            if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
                return ((BitmapDrawable) d).getBitmap();
            }
            return IconMemory.render(d);
        } catch (RuntimeException e) {
            Logger.log("Icon load failed: " + e);
            return null;
        }
    }

    /** Collects texts (and optionally the first image) from a custom notification layout. */
    private Bitmap readRemoteViews(RemoteViews rv, List<String> texts, boolean wantIcon) {
        if (rv == null) return null;
        Bitmap[] found = wantIcon ? new Bitmap[1] : null;
        try {
            walk(rv.apply(this, new FrameLayout(this)), texts, found);
        } catch (RuntimeException e) {
            Logger.log("Reading Maps notification layout failed: " + e);
        }
        return found != null ? found[0] : null;
    }

    private static void walk(View v, List<String> texts, Bitmap[] icon) {
        if (v instanceof TextView) {
            CharSequence cs = ((TextView) v).getText();
            if (cs != null && cs.length() > 0 && !texts.contains(cs.toString())) texts.add(cs.toString());
        } else if (v instanceof ImageView && icon != null && icon[0] == null) {
            Drawable d = ((ImageView) v).getDrawable();
            if (d != null && d.getIntrinsicWidth() > 0) icon[0] = IconMemory.render(d);
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), texts, icon);
        }
    }

    /** Logs the whole notification once per navigation session, to calibrate the parser. */
    private void dumpExtras(Notification n) {
        StringBuilder sb = new StringBuilder("Maps notification dump: category=").append(n.category)
                .append(" flags=0x").append(Integer.toHexString(n.flags))
                .append(" customView=").append(n.contentView != null || n.bigContentView != null);
        for (String k : n.extras.keySet()) {
            Object v = n.extras.get(k);
            String s = String.valueOf(v);
            if (s.length() > 120) s = s.substring(0, 120) + "…";
            sb.append("\n    ").append(k).append(" = ").append(s);
        }
        Logger.log(sb.toString());
    }
}
