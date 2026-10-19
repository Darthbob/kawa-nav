package io.github.darthbob.kawanav;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * "Nearby devices" can be revoked while the companion-device association survives — the autostart path
 * then reaches Bluetooth with no permission and throws {@link SecurityException} on the main thread.
 * {@link Hub#bluetoothAllowed} needs a {@code Context}, so what is pinned here is the contract the call
 * sites rely on: the hint a rider is shown. The defect itself (the crash on a real device) is only
 * reachable on a device; see the plan's device check.
 */
public class BluetoothPermissionTest {

    @Test
    public void theHintNamesWhatTheRiderHasToAllow() {
        assertFalse(Hub.BLUETOOTH_HINT, Hub.BLUETOOTH_HINT == null || Hub.BLUETOOTH_HINT.isEmpty());
        assertTrue(Hub.BLUETOOTH_HINT, Hub.BLUETOOTH_HINT.contains("Bluetooth"));
        assertTrue("the rider has to know where to look",
                Hub.BLUETOOTH_HINT.contains("Nearby devices"));
        assertTrue("and it has to say KawaNav is the one asking",
                Hub.BLUETOOTH_HINT.contains("KawaNav"));
    }

    @Test
    public void theHintStaysASingleLine() {
        // it is written into the log and into Hub.bikeHint, which is shown as one line of the dash card
        assertFalse(Hub.BLUETOOTH_HINT, Hub.BLUETOOTH_HINT.contains("\n"));
        assertTrue(Hub.BLUETOOTH_HINT.length() < 200);
    }
}
