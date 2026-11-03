package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The phrases in the app's languages. */
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
        assertEquals("a language the app does not have falls back to English", "Turn left onto Via Roma",
                NavText.of("cs").action(left));
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
        // still ahead: named, not reached
        assertEquals("Destinazione", NavText.of("it").upcoming(m(Instructions.Kind.ARRIVE, 0, 0, "")));
        assertEquals("Waypoint 2", NavText.of("en").upcoming(m(Instructions.Kind.VIA, 0, 2, "")));
        assertEquals("Exit and turns keep their action", "Take the exit on the right",
                NavText.of("en").upcoming(m(Instructions.Kind.EXIT, 1, 0, "")));
        assertEquals("11th", NavText.of("en").ordinal(11));
        assertEquals("22nd", NavText.of("en").ordinal(22));
    }

    @Test
    public void askedForTheNextManeuver() {
        Instructions.Maneuver left = m(Instructions.Kind.TURN, -1, 0, "Via Roma");
        assertEquals("In 300 metres, turn left onto Via Roma", NavText.of("en").ahead(left, 310, false));
        Instructions.Maneuver dest = m(Instructions.Kind.ARRIVE, 0, 0, "");
        assertEquals("Destination in 2 kilometres", NavText.of("en").ahead(dest, 2000, false));
        assertEquals("Destinazione tra 2 chilometri", NavText.of("it").ahead(dest, 2000, false));
        assertEquals("Zwischenziel 1 in 500 Metern", NavText.of("de").ahead(m(Instructions.Kind.VIA, 0, 1, ""), 480, false));
        assertEquals("close: just named", "Destination", NavText.of("en").ahead(dest, 20, false));
    }

    @Test
    public void theManeuverAfterTheNext() {
        Instructions.Maneuver left = m(Instructions.Kind.TURN, -1, 0, "Via Roma");
        assertEquals("Then turn left onto Via Roma", NavText.of("en").thenShown(left));
        assertEquals("Poi svolta a sinistra in Via Roma", NavText.of("it").thenShown(left));
        assertEquals("Dann links abbiegen auf Via Roma", NavText.of("de").thenShown(left));
        Instructions.Maneuver dest = m(Instructions.Kind.ARRIVE, 0, 0, "");
        assertEquals("Poi destinazione", NavText.of("it").thenShown(dest));
        assertEquals("a German noun keeps its capital", "Dann Ziel", NavText.of("de").thenShown(dest));
    }

    @Test
    public void distancesAndTimesOnTheScreen() {
        assertEquals("850 m", NavText.shown(847, false));
        assertEquals("990 m", NavText.shown(994, false));
        assertEquals("1.0 km", NavText.shown(996, false));
        assertEquals("4.2 km", NavText.shown(4240, false));
        assertEquals("37 km", NavText.shown(36_800, false));
        assertEquals("not 0 km near the end", "420 m", NavText.shown(423, false));
        assertEquals("660 ft", NavText.shown(200, true));
        assertEquals("0.8 mi", NavText.shown(1300, true));
        assertEquals("23 mi", NavText.shown(37_000, true));
        assertEquals("1 min", NavText.duration(10));
        assertEquals("48 min", NavText.duration(48 * 60 + 10));
        assertEquals("1 h 05 min", NavText.duration(65 * 60));
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

    @Test
    public void theOtherLanguages() {
        Instructions.Maneuver left = m(Instructions.Kind.TURN, -1, 0, "Rue de Rivoli");
        assertEquals("Dans 300 mètres, tournez à gauche sur Rue de Rivoli", NavText.of("fr").spoken(left, 310, false));
        assertEquals("En 300 metros, gira a la izquierda hacia Rue de Rivoli", NavText.of("es").spoken(left, 310, false));
        assertEquals("Over 300 meter, sla linksaf naar Rue de Rivoli", NavText.of("nl").spoken(left, 310, false));
        assertEquals("Em 300 metros, vire à esquerda na Rue de Rivoli", NavText.of("pt").spoken(left, 310, false));
        assertEquals("Za 300 metrów, skręć w lewo na Rue de Rivoli", NavText.of("pl").spoken(left, 310, false));
        assertEquals("Om 300 meter, sväng vänster in på Rue de Rivoli", NavText.of("sv").spoken(left, 310, false));
        Instructions.Maneuver rb = m(Instructions.Kind.ROUNDABOUT, 0, 3, "");
        assertEquals("Au rond-point, prenez la troisième sortie", NavText.of("fr").action(rb));
        assertEquals("Na rondzie zjedź trzecim zjazdem", NavText.of("pl").action(rb));
        assertEquals("Je hebt tussenstop 2 bereikt", NavText.of("nl").action(m(Instructions.Kind.VIA, 0, 2, "")));
        assertEquals("Virage serré à gauche, ralentissez, puis virage à droite", NavText.of("fr").curveWarning(3, true, 2));
        assertEquals("Agrafka w prawo", NavText.of("pl").curve(8));
        assertEquals("Fartkamera framöver, gräns 70", NavText.of("sv").camera("fixed", 70));
    }

    @Test
    public void pluralFormsOfDistances() {
        NavText pl = NavText.of("pl"), fr = NavText.of("fr");
        assertEquals("1 kilometr", pl.distance(1020, false));
        assertEquals("2 kilometry", pl.distance(2000, false));
        assertEquals("5 kilometrów", pl.distance(5000, false));
        assertEquals("12 kilometrów", pl.distance(12_000, false));
        assertEquals("1,5 kilometra", pl.distance(1530, false));
        assertEquals("1,5 kilomètre", fr.distance(1530, false));
        assertEquals("2 kilomètres", fr.distance(2000, false));
        assertEquals("1 milla", NavText.of("es").distance(1609, true));
    }
}
