package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The dash notification frame (0x12) and the "is there one" bookkeeping behind it. */
public class DashAlertsTest {

    @Test
    public void frameLayout() {
        assertEquals("120800ffff0a1200ff01010000000000".substring(0, 30),
                KawaProtocol.hex(DashAlerts.frame(DashAlerts.CALL, DashAlerts.ADDED)).replace(" ", "").toLowerCase());
        byte[] removed = DashAlerts.frame(DashAlerts.MAIL, DashAlerts.REMOVED);
        assertEquals(15, removed.length);
        assertEquals(0x12, removed[0] & 0xFF);
        assertEquals(0x08, removed[1] & 0xFF);
        assertEquals(0x0A, removed[5] & 0xFF);
        assertEquals(0x12, removed[6] & 0xFF);
        assertEquals(2, removed[7] & 0xFF);   // removed
        assertEquals(0xFF, removed[8] & 0xFF);
        assertEquals(6, removed[9] & 0xFF);   // mail
        assertEquals(0, removed[10] & 0xFF);  // not shown
    }

    @Test
    public void iconsShowOnceAndClearWhenTheLastGoesAway() {
        DashAlerts.clear(DashAlerts.CALL);
        assertTrue(DashAlerts.onPosted(DashAlerts.CALL, "dialer|1|null"));
        assertFalse(DashAlerts.onPosted(DashAlerts.CALL, "dialer|2|null")); // second call: already shown
        assertTrue(DashAlerts.active(DashAlerts.CALL));
        assertFalse(DashAlerts.onRemoved(DashAlerts.CALL, "dialer|1|null"));
        assertTrue(DashAlerts.onRemoved(DashAlerts.CALL, "dialer|2|null")); // last one gone
        assertFalse(DashAlerts.active(DashAlerts.CALL));
        assertFalse(DashAlerts.onRemoved(DashAlerts.CALL, "dialer|2|null")); // unknown key: no change
    }

    @Test
    public void updateOfTheSameNotificationCountsOnce() {
        DashAlerts.clear(DashAlerts.MAIL);
        assertTrue(DashAlerts.onPosted(DashAlerts.MAIL, "gm|7|tag"));
        assertFalse(DashAlerts.onPosted(DashAlerts.MAIL, "gm|7|tag"));
        assertTrue(DashAlerts.onRemoved(DashAlerts.MAIL, "gm|7|tag"));
    }

    @Test
    public void categoriesFromPackages() {
        assertEquals(DashAlerts.CALL, DashAlerts.categoryOf("com.google.android.dialer", "call"));
        assertEquals(DashAlerts.CALL, DashAlerts.categoryOf("com.samsung.android.dialer", "missed_call"));
        assertEquals(0, DashAlerts.categoryOf("com.google.android.dialer", "msg")); // chat, not a call
        assertEquals(DashAlerts.MAIL, DashAlerts.categoryOf("com.google.android.apps.messaging", "msg"));
        assertEquals(DashAlerts.MAIL, DashAlerts.categoryOf("com.google.android.gm", "email"));
        assertEquals(0, DashAlerts.categoryOf("com.whatsapp", "msg")); // not on Rideology's list
        assertEquals(0, DashAlerts.categoryOf(null, "call"));
    }
}
