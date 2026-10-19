package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/** The dash notification frame (0x12) and the "is there one" bookkeeping behind it. */
public class DashAlertsTest {

    /** As in a fresh process: the meter has been told nothing, and nothing is on the phone. */
    @Before
    public void reset() {
        DashAlerts.forgetSent();
        DashAlerts.clear(DashAlerts.CALL);
        DashAlerts.clear(DashAlerts.MAIL);
    }

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

    // ------------------------------------------------------------------ what the dash is told on connect

    /**
     * A call that arrives while the bridge is down is never reported again by {@code onPosted}: the icon
     * has to be sent when the link comes up, or it is lost for the whole call.
     */
    @Test
    public void anAlertAlreadyOnIsSentAgainWhenTheLinkComesUp() {
        assertTrue(DashAlerts.onPosted(DashAlerts.CALL, "dialer|1|null"));
        assertEquals("nothing told yet, the dash shows nothing",
                DashAlerts.ADDED, DashAlerts.desiredEvent(DashAlerts.CALL, true, true));
        DashAlerts.sent(DashAlerts.CALL, DashAlerts.ADDED);
        assertEquals("told once: not again on the next connect",
                DashAlerts.NOTHING, DashAlerts.desiredEvent(DashAlerts.CALL, true, true));
    }

    @Test
    public void aFailedSendIsRetriedOnTheNextConnect() {
        // the frame never went out (the link was not ready), so nothing was recorded and the wish stands
        assertTrue(DashAlerts.onPosted(DashAlerts.MAIL, "gm|7|tag"));
        assertEquals(DashAlerts.ADDED, DashAlerts.desiredEvent(DashAlerts.MAIL, true, true));
        assertEquals(DashAlerts.ADDED, DashAlerts.desiredEvent(DashAlerts.MAIL, true, true));
    }

    @Test
    public void aSwitchedOffIconIsSentAsRemoved() {
        assertTrue(DashAlerts.onPosted(DashAlerts.CALL, "dialer|1|null"));
        DashAlerts.sent(DashAlerts.CALL, DashAlerts.ADDED);
        assertEquals("the setting was turned off while the icon was on",
                DashAlerts.REMOVED, DashAlerts.desiredEvent(DashAlerts.CALL, true, false));
        DashAlerts.sent(DashAlerts.CALL, DashAlerts.REMOVED);
        assertEquals(DashAlerts.NOTHING, DashAlerts.desiredEvent(DashAlerts.CALL, true, false));
    }

    @Test
    public void nothingIsSentWhenTheIconWasNeverOn() {
        // the dash is not ours to clear: a dash that shows a mail icon from before KawaNav is left alone
        assertEquals(DashAlerts.NOTHING, DashAlerts.desiredEvent(DashAlerts.CALL, false, true));
        assertEquals(DashAlerts.NOTHING, DashAlerts.desiredEvent(DashAlerts.MAIL, false, true));
        assertEquals(DashAlerts.NOTHING, DashAlerts.desiredEvent(DashAlerts.MAIL, false, false));
    }

    @Test
    public void aNewConnectionIsNotToldWhatTheOldOneHeard() {
        DashAlerts.sent(DashAlerts.CALL, DashAlerts.ADDED);
        assertEquals(DashAlerts.NOTHING, DashAlerts.desiredEvent(DashAlerts.CALL, true, true));
        DashAlerts.forgetSent();   // the bridge reconnected: the new dash may show nothing
        assertEquals(DashAlerts.ADDED, DashAlerts.desiredEvent(DashAlerts.CALL, true, true));
    }
}
