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
        assertEquals(DashAlerts.CALL, DashAlerts.categoryOf("com.google.android.dialer", "call", true));
        assertEquals(DashAlerts.CALL, DashAlerts.categoryOf("com.samsung.android.dialer", "missed_call", false));
        assertEquals(0, DashAlerts.categoryOf("com.google.android.dialer", "msg", false)); // chat, not a call
        assertEquals(DashAlerts.MAIL, DashAlerts.categoryOf("com.google.android.apps.messaging", "msg", false));
        assertEquals(DashAlerts.MAIL, DashAlerts.categoryOf("com.google.android.gm", "email", false));
        assertEquals(0, DashAlerts.categoryOf("com.example.game", "msg", false)); // on no list
        assertEquals(0, DashAlerts.categoryOf(null, "call", false));
    }

    @Test
    public void messengersBeyondRideologysList() {
        for (String app : new String[]{"com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger",
                "org.thoughtcrime.securesms", "com.facebook.orca", "ch.threema.app"}) {
            assertEquals(app, DashAlerts.MAIL, DashAlerts.categoryOf(app, "msg", false));
            assertEquals(app, DashAlerts.MAIL, DashAlerts.categoryOf(app, null, false)); // no category set
            // a WhatsApp/Telegram/Signal call rings the call icon, as a dialer's does
            assertEquals(app, DashAlerts.CALL, DashAlerts.categoryOf(app, "call", true));
            assertEquals(app, DashAlerts.CALL, DashAlerts.categoryOf(app, "missed_call", false));
        }
        assertEquals(DashAlerts.MAIL, DashAlerts.categoryOf("com.microsoft.office.outlook", "email", false));
        assertEquals(DashAlerts.MAIL, DashAlerts.categoryOf("com.samsung.android.messaging", "msg", false));
        // a mail app's call-category notification is not a call: only messengers have calls
        assertEquals(DashAlerts.MAIL, DashAlerts.categoryOf("com.google.android.gm", "call", false));
    }

    @Test
    public void ongoingNotificationsOfMessengersAreNotMessages() {
        // "WhatsApp Web is active", a backup in progress, a call already answered and going on
        assertEquals(0, DashAlerts.categoryOf("com.whatsapp", "service", true));
        assertEquals(0, DashAlerts.categoryOf("com.whatsapp", "progress", true));
        assertEquals(0, DashAlerts.categoryOf("org.telegram.messenger", null, true));
        assertEquals(0, DashAlerts.categoryOf("com.google.android.gm", "progress", true));
    }

    @Test
    public void ancsNamesForTheDebugTest() {
        assertEquals("call", DashAlerts.name(DashAlerts.CALL));
        assertEquals("mail/message", DashAlerts.name(DashAlerts.MAIL));
        assertEquals("ANCS 2 Missed call", DashAlerts.name(2));
        assertEquals("ANCS 4 Social", DashAlerts.name(4));
        assertEquals("11 Entertainment", DashAlerts.ancsName(11));
        assertEquals("12", DashAlerts.ancsName(12));
        assertEquals(12, DashAlerts.ANCS.length);
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
