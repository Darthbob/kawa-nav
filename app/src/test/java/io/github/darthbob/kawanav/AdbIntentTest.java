package io.github.darthbob.kawanav;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link MainActivity} is exported, so a release build must not act on any launch-intent extra an
 * installed app could send. {@link Hub#adbAllowed} is the gate; this pins its contract, not the call
 * sites (a JVM test cannot see an {@code Intent} or {@code BuildConfig.DEBUG}).
 */
public class AdbIntentTest {

    /** The five control extras {@code MainActivity.handleAdbIntent} reads from the launch intent. */
    private static final String[] CONTROLS = {"free_mode", "offline_cancel", "export_log", "bridge",
            OfflineService.EXTRA_REGION};

    @Test
    public void everyControlExtraIsShutInRelease() {
        for (String extra : CONTROLS) {
            assertFalse(extra, Hub.adbAllowed(extra, false));
        }
    }

    @Test
    public void everyListedExtraIsShutInRelease() {
        for (String extra : Hub.DEBUG_EXTRAS) {
            assertFalse(extra, Hub.adbAllowed(extra, false));
        }
    }

    @Test
    public void debugBuildStillActs() {
        for (String extra : Hub.DEBUG_EXTRAS) {
            assertTrue(extra, Hub.adbAllowed(extra, true));
        }
        assertTrue(Hub.adbAllowed(OfflineService.EXTRA_REGION, true));
    }

    @Test
    public void theControlsAreInTheList() {
        // the gate reads DEBUG_EXTRAS (plus the region extra): a control that leaves the list would
        // silently stop working in a debug build, which is what the first test guards in release
        for (String extra : CONTROLS) {
            if (OfflineService.EXTRA_REGION.equals(extra)) continue;
            assertTrue(extra + " is missing from Hub.DEBUG_EXTRAS",
                    java.util.Arrays.asList(Hub.DEBUG_EXTRAS).contains(extra));
        }
    }

    @Test
    public void unknownOrMissingExtraIsRefused() {
        assertFalse(Hub.adbAllowed(null, true));
        assertFalse(Hub.adbAllowed(null, false));
        assertFalse(Hub.adbAllowed("not_a_kawanav_extra", true));
    }
}
