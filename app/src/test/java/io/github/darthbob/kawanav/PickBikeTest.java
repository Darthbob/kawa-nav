package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

/** Which paired device KawaNav connects to (issue #22: two Kawasakis paired, both "Kawasaki-ZR900S"). */
public class PickBikeTest {
    private static final String DEMO = "AA:BB:CC:DD:E1:CE", OWN = "AA:BB:CC:DD:F3:63", HEADSET = "11:22:33:44:55:66";
    private static final List<String> ADDRESSES = List.of(HEADSET, DEMO, OWN);
    private static final List<String> NAMES = List.of("Cardo PACKTALK", "Kawasaki-ZR900S", "Kawasaki-ZR900S");

    @Test
    public void theBikeAutostartIsLinkedToWins() {
        // the association's address comes lower-case from Android's MacAddress
        assertEquals(2, BridgeService.pickBike(OWN.toLowerCase(), null, ADDRESSES, NAMES));
        assertEquals(2, BridgeService.pickBike(OWN.toLowerCase(), DEMO, ADDRESSES, NAMES));
    }

    @Test
    public void thenTheOneKawaNavPaired() {
        assertEquals(2, BridgeService.pickBike(null, OWN, ADDRESSES, NAMES));
        // linked to a bike that is no longer paired: the saved one
        assertEquals(2, BridgeService.pickBike("aa:bb:cc:dd:00:00", OWN, ADDRESSES, NAMES));
    }

    @Test
    public void withNothingPickedTheFirstKawasaki() {
        assertEquals(1, BridgeService.pickBike(null, null, ADDRESSES, NAMES));
        assertEquals(2, BridgeService.kawasakis(NAMES));
        assertEquals(-1, BridgeService.pickBike(null, null, List.of(HEADSET), List.of("Cardo PACKTALK")));
    }
}
