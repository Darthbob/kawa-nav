package io.github.darthbob.kawanav;

import java.util.Locale;

/**
 * The call and mail/message icons on the dash: the phone tells the meter about them with a vendor frame
 * (0x12). Rideology watches its own notifications and shows an icon for an incoming call and for mail
 * or messages; KawaNav does the same, from the notification listener.
 *
 * <p>Only the fact "there is one" is sent — never text, sender or app name. Two icons exist (call,
 * mail), each with added/removed.
 */
public final class DashAlerts {
    private DashAlerts() {}

    /** The two icons the meter can show. */
    public static final int CALL = 1, MAIL = 6;
    /** What happened to the icon. */
    public static final int ADDED = 0, REMOVED = 2;

    /**
     * Frame 0x12 (15 bytes), as Rideology sends it (oh/b.java:301-323):
     * {@code 12 08 00 FF FF 0A 12 <event> FF <category> <state> 00 00 00 00}.
     */
    public static byte[] frame(int category, int event) {
        byte[] b = new byte[15]; // zero-filled, as in the app's frame
        b[0] = 0x12;
        b[1] = 0x08;
        b[2] = 0x00;
        b[3] = (byte) 0xFF;
        b[4] = (byte) 0xFF;
        b[5] = 0x0A;
        b[6] = 0x12;
        b[7] = (byte) event;
        b[8] = (byte) 0xFF;
        b[9] = (byte) category;
        b[10] = (byte) (event == ADDED ? 1 : 0);
        return b;
    }

    /**
     * Which icon a notification is about, or 0 for none. Like Rideology (MyNotificationListenerService):
     * calls from the dialer apps, mail and messages from the usual mail/messaging apps.
     * Only the package name is looked at, never the content.
     */
    public static int categoryOf(String packageName, String category) {
        if (packageName == null) return 0;
        if (CALL_PACKAGES.contains(packageName)) {
            // only while it is actually ringing/missed, not for a chat notification of the dialer
            return android.app.Notification.CATEGORY_CALL.equals(category)
                    || android.app.Notification.CATEGORY_MISSED_CALL.equals(category) ? CALL : 0;
        }
        if (MAIL_PACKAGES.contains(packageName)) return MAIL;
        return 0;
    }

    private static final java.util.Set<String> CALL_PACKAGES = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.google.android.dialer", "com.samsung.android.dialer", "com.sonymobile.android.dialer",
            "android.dialer"));
    private static final java.util.Set<String> MAIL_PACKAGES = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.google.android.gm", "jp.co.yahoo.android.ymail", "jp.naver.line.android",
            "com.google.android.apps.messaging", "com.kddi.android.cmail"));

    /** Which icon the rider switched on in the settings. */
    public static boolean enabled(android.content.Context c, int category) {
        String key = category == CALL ? Hub.KEY_DASH_CALLS : Hub.KEY_DASH_MAIL;
        return Hub.prefs(c).getBoolean(key, false);
    }

    public static String name(int category) {
        return category == CALL ? "call" : "mail/message";
    }

    /** Notification keys currently behind each icon; the icon shows while a set is not empty. */
    private static final java.util.Map<Integer, java.util.Set<String>> ACTIVE = new java.util.HashMap<>();

    /**
     * A notification appeared: true when the icon has to be shown now (it was not shown before).
     * The key must identify the notification (package|id|tag), so updates of the same one do not count twice.
     */
    public static boolean onPosted(int category, String key) {
        java.util.Set<String> set = ACTIVE.computeIfAbsent(category, k -> new java.util.HashSet<>());
        boolean wasEmpty = set.isEmpty();
        set.add(key);
        return wasEmpty;
    }

    /** A notification went away: true when the icon has to be cleared now. */
    public static boolean onRemoved(int category, String key) {
        java.util.Set<String> set = ACTIVE.get(category);
        if (set == null || !set.remove(key)) return false;
        return set.isEmpty();
    }

    /** The rider switched the icon off: forget the notifications behind it (true when there were some). */
    public static boolean clear(int category) {
        java.util.Set<String> set = ACTIVE.get(category);
        if (set == null || set.isEmpty()) return false;
        set.clear();
        return true;
    }

    /** Whether any notification is behind this icon right now. */
    public static boolean active(int category) {
        java.util.Set<String> set = ACTIVE.get(category);
        return set != null && !set.isEmpty();
    }

    /** Debug text. */
    public static String describe(int category, int event) {
        return String.format(Locale.ROOT, "%s %s", name(category), event == ADDED ? "shown" : "cleared");
    }
}
