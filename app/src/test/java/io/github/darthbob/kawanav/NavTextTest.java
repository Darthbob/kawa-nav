package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The phrases in English, Italian and German. */
public class NavTextTest {

    private static Instructions.Maneuver m(Instructions.Kind kind, int side, int number, String road) {
        return new Instructions.Maneuver(kind, side, number, 0, Instructions.turnId(kind, side), road);
    }

    @Test
    public void turnWithDistance() {
        Instructions.Maneuver left = m(Instructions.Kind.TURN, -1, 0, "Via Roma");
        assertEquals("In 300 metres, turn left onto Via Roma", NavText.of("en").spoken(left, 310, false));
        assertEquals("Tra 300 metri, svolta a sinistra in Via Roma", NavText.of("it").spoken(left, 310, false));
        assertEquals("In 300 Metern links abbiegen auf Via Roma", NavText.of("de").spoken(left, 310, false));
        assertEquals("Turn left onto Via Roma", NavText.of("en").spoken(left, 20, false));
        assertEquals("fr falls back to English", "Turn left onto Via Roma", NavText.of("fr").action(left));
    }

    @Test
    public void roundaboutsRampsArrival() {
        Instructions.Maneuver rb = m(Instructions.Kind.ROUNDABOUT, 0, 3, "");
        assertEquals("At the roundabout, take the 3rd exit", NavText.of("en").action(rb));
        assertEquals("Alla rotonda prendi la terza uscita", NavText.of("it").action(rb));
        assertEquals("In 1 Kilometer im Kreisverkehr die dritte Ausfahrt nehmen", NavText.of("de").spoken(rb, 1000, false));
        Instructions.Maneuver exit = m(Instructions.Kind.EXIT, 1, 0, "");
        assertEquals("In 500 Metern Ausfahrt rechts nehmen", NavText.of("de").spoken(exit, 480, false));
        assertEquals("Sei arrivato a destinazione", NavText.of("it").spoken(m(Instructions.Kind.ARRIVE, 0, 0, ""), 500, false));
        assertEquals("11th", NavText.of("en").ordinal(11));
        assertEquals("22nd", NavText.of("en").ordinal(22));
    }

    @Test
    public void distances() {
        NavText it = NavText.of("it"), en = NavText.of("en");
        assertEquals("1,5 chilometri", it.distance(1530, false));
        assertEquals("1 chilometro", it.distance(1020, false));
        assertEquals("12 chilometri", it.distance(12_400, false));
        assertEquals("80 metri", it.distance(78, false));
        assertEquals("250 metres", en.distance(240, false));
        assertEquals("1 kilometre", en.distance(990, false));
        assertEquals("500 feet", en.distance(160, true));
        assertEquals("1.9 miles", en.distance(3000, true));
    }

    @Test
    public void curvesAndCameras() {
        assertEquals("Sharp bend left, slow down, then bend right", NavText.of("en").curveWarning(3, true, 2));
        assertEquals("Curva stretta a sinistra, rallenta, poi curva a destra", NavText.of("it").curveWarning(3, true, 2));
        assertEquals("Kurve links, dann scharfe Kurve rechts", NavText.of("de").curveWarning(1, false, 4));
        assertEquals("Tornante a destra", NavText.of("it").curve(8));
        assertEquals("Autovelox più avanti, limite 50", NavText.of("it").camera("fixed", 50));
        assertEquals("Rotlichtblitzer voraus", NavText.of("de").camera("red_light", 0));
        // the English curve phrase matches the one the warnings always said
        for (int t = 1; t <= 8; t++) assertEquals(CurveAlerts.phrase(t), NavText.of("en").curve(t));
    }
}
