package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The Maps instruction parser: which turn id an instruction text gets, and how it is combined with
 * the arrow Maps drew in the notification. Pure Java, so every stem table can be pinned here.
 */
public class MapsParserTest {

    // ------------------------------------------------------------------ street names are not instructions

    @Test
    public void aStreetNameIsNotAMergeOrARamp() {
        // "Rampart Street" starts with "ramp", "emergent" contains "merge": neither is a maneuver, and
        // "continue" is what the instruction actually says (the straight arrow)
        assertEquals(0, MapsParser.parseTurn("Continue on Rampart Street", false));
        assertEquals(0, MapsParser.parseTurn("Weiter auf Emergent Avenue", false));
        assertEquals("no ramp, no exit, no merge in either", KawaProtocol.TURN_NONE,
                MapsParser.kindOf("Continue on Rampart Street"));
        assertEquals(KawaProtocol.TURN_NONE, MapsParser.kindOf("Weiter auf Emergent Avenue"));
    }

    @Test
    public void aStreetNameDoesNotBeatARealInstruction() {
        // "Rampart Street" is on the left: the side is what matters, the ramp word is not one
        assertEquals(1, MapsParser.parseTurn("Links abbiegen auf Rampart Street", false));
        assertEquals(2, MapsParser.parseTurn("Turn right onto Rampart Street", false));
    }

    @Test
    public void realRampsAndExitsStillMatch() {
        assertEquals(13, MapsParser.parseTurn("Take the ramp", false));               // on-ramp right
        assertEquals(12, MapsParser.parseTurn("Take the ramp on the left", false));   // on-ramp left
        assertEquals(15, MapsParser.parseTurn("Take the exit", false));               // exit right
        assertEquals(14, MapsParser.parseTurn("Links abbiegen, Ausfahrt 3", false));  // exit left
        assertEquals(15, MapsParser.parseTurn("Take exit 3", false));                 // no side given
        assertEquals(15, MapsParser.parseTurn("Nehmen Sie die Ausfahrt", false));
        // "sortie" has no exit number ("Prendre la sortie 12" would): the branch is still the exit
        assertEquals(15, MapsParser.parseTurn("Prenez la sortie", false));
        assertEquals(16, MapsParser.parseTurn("Merge", false));
        assertEquals(17, MapsParser.parseTurn("Merge right", false));
    }

    @Test
    public void anInflectedExitWordStillMatches() {
        // the anchoring of "|exit|" must not throw away the languages that inflect the word
        assertEquals(15, MapsParser.parseTurn("Take the exit ramp", false));   // "exit" + a letter after
        assertEquals(13, MapsParser.parseTurn("rampa de acesso", false));      // on-ramp, not "rampa" alone
        assertEquals(KawaProtocol.TURN_NONE, MapsParser.parseTurn("Rampart Street", false));
    }

    // ------------------------------------------------------------------ roundabout exit numbers

    @Test
    public void anOrdinalInAPlaceNameDoesNotChooseTheExit() {
        // "Prima" is a street, the roundabout comes after: the clause decides, not the whole string
        assertEquals(-1, MapsParser.roundaboutExit("Prima Straße, dann Kreisverkehr"));
        assertEquals(-1, MapsParser.roundaboutExit("Prima Straße, then roundabout"));
        assertFalse(MapsParser.hasRoundaboutExitNumber("Prima Straße, dann Kreisverkehr"));
    }

    @Test
    public void aRoundaboutExitNumberNextToTheWordStillCounts() {
        assertEquals(2, MapsParser.roundaboutExit("Im Kreisverkehr die 2. Ausfahrt nehmen"));
        assertEquals(3, MapsParser.roundaboutExit("At the roundabout take the 3rd exit"));
        assertEquals(2, MapsParser.roundaboutExit("Kreisverkehr, zweite Ausfahrt"));
        assertEquals(3, MapsParser.roundaboutExit("Rond-point, troisième sortie"));
        assertTrue(MapsParser.hasRoundaboutExitNumber("Im Kreisverkehr die 2. Ausfahrt nehmen"));
    }

    @Test
    public void theRoundaboutFollowsTheTrafficSide() {
        // right-hand traffic (id 30 + exit), left-hand traffic (18 + exit)
        assertEquals(31, MapsParser.roundaboutTurn(2, false));
        assertEquals(19, MapsParser.roundaboutTurn(2, true));
        assertEquals(2, MapsParser.roundaboutExitOf(31));
        assertEquals(2, MapsParser.roundaboutExitOf(19));
        assertEquals(-1, MapsParser.roundaboutExitOf(15));
        // and through the whole parser
        assertEquals(32, MapsParser.parseTurn("Kreisverkehr, dritte Ausfahrt", false));
        assertEquals(20, MapsParser.parseTurn("Kreisverkehr, dritte Ausfahrt", true));
    }

    // ------------------------------------------------------------------ Italian, as Maps writes it

    @Test
    public void italianAsMapsWritesIt() {
        // Maps' own wording, from its notifications on rides in October 2026 (street names changed)
        assertEquals(1, MapsParser.parseTurn("90 m · Svolta a sinistra e prendi Via Roma", false));
        assertEquals(2, MapsParser.parseTurn("Svolta a destra e prendi SP37", false));
        assertEquals(6, MapsParser.parseTurn("Svolta leggermente a destra e prendi Via Roma", false));
        assertEquals(9, MapsParser.parseTurn("Mantieni la sinistra per continuare su Via Roma", false));
        assertEquals(7, MapsParser.parseTurn("Fai una inversione a U", false));
        assertEquals(0, MapsParser.parseTurn("Prosegui dritto su Via Roma", false));
        assertEquals(42, MapsParser.parseTurn("Procedi verso Via Roma", false));
        assertEquals(43, MapsParser.parseTurn("Arrivo", false));
        assertEquals(31, MapsParser.parseTurn("Alla rotonda, prosegui dritto su Via Roma", false));
        assertEquals(30, MapsParser.parseTurn("Alla rotonda, prendi la 1ª uscita e prendi Via Roma", false));
        assertEquals(3, MapsParser.roundaboutExit("Alla rotonda prendi la 3ª uscita e prendi Via Roma"));
        assertEquals(9900, MapsParser.parseDistance("9,9 km · Svolta a destra e prendi SP37"));
    }

    @Test
    public void italianRotatoriaAndSharpTurns() {
        // Maps says "rotatoria" as well as "rotonda", and "Svolta tutto a" for a sharp turn: the text read
        // both as plain turns, and only Maps' icon drew them right
        assertTrue(MapsParser.isRoundabout("Alla rotatoria, svolta a sinistra in Via Roma"));
        assertEquals(32, MapsParser.parseTurn("Alla rotatoria, svolta a sinistra per restare su SS 1", false));
        assertEquals(3, MapsParser.parseTurn("Svolta tutto a sinistra e prendi Via Roma", false));
        assertEquals(4, MapsParser.parseTurn("Svolta tutto a destra", false));
    }

    // ------------------------------------------------------------------ text + icon

    @Test
    public void anIconIsKeptWhenTheTextNamesNoSide() {
        // "Take the ramp" says nothing about the side: Maps' arrow (ramp on the left, 12) stands
        assertEquals(12, MapsParser.chooseTurn("Take the ramp", 12, false));
        assertEquals(14, MapsParser.chooseTurn("Take the exit", 14, false));
    }

    @Test
    public void theTextKindStillWinsOnAPlainArrow() {
        // Maps draws a ramp with the plain right-turn arrow (2): the text knows it is a ramp
        assertEquals(13, MapsParser.chooseTurn("Take the ramp", 2, false));
        assertEquals(15, MapsParser.chooseTurn("Take the exit", 2, false));
        assertEquals(16, MapsParser.chooseTurn("Merge", 0, false));
    }

    @Test
    public void aNamedSideInTheTextWins() {
        assertEquals(12, MapsParser.chooseTurn("Take the ramp on the left", 13, false));
        assertEquals(2, MapsParser.chooseTurn("Turn right onto Rampart Street", 1, false));
    }

    @Test
    public void aRoundaboutExitNumberTakesTheIconsDirection() {
        // the icon shows the exit's direction (counter-clockwise, 30 + n), the text its number
        int turn = MapsParser.chooseTurn("At the roundabout take the 4th exit", 33, false);
        assertEquals(4, MapsParser.roundaboutExitOf(turn));
        assertTrue("counter-clockwise, like the icon it came from", turn >= 30);
        // clockwise icon (18..29): the same exit number, the other rotation
        int cw = MapsParser.chooseTurn("At the roundabout take the 4th exit", 21, false);
        assertEquals(4, MapsParser.roundaboutExitOf(cw));
        assertTrue(cw >= 18 && cw <= 29);
    }

    @Test
    public void aRoundaboutIconWithNoNumberIsKeptAsItIs() {
        // the icon's id carries the exit and its rotation already: the text's vague "rechts" must not
        // rotate it and must not invent an exit
        assertEquals(31, MapsParser.chooseTurn("Im Kreisverkehr rechts", 31, false));
        assertEquals(19, MapsParser.chooseTurn("Im Kreisverkehr rechts", 19, true));
        assertEquals(35, MapsParser.chooseTurn("At the roundabout, turn left", 35, false));
        // and a plain turn icon next to a roundabout text is not turned into a roundabout id: only the
        // text's exit number can do that (there is none here)
        assertEquals(2, MapsParser.chooseTurn("Im Kreisverkehr rechts", 2, false));
    }

    @Test
    public void theIconsSideIsKeptWhenTheTextNamesOnlyAKind() {
        // a plain left arrow with "Merge" is a merge on the left, not the plain turn
        assertEquals(16, MapsParser.chooseTurn("Merge", 1, false));
        assertEquals(17, MapsParser.chooseTurn("Merge", 2, false));
        assertEquals(14, MapsParser.chooseTurn("Take the exit", 1, false));
        // an icon with no side of its own (straight, fork middle, waypoint) leaves the text's own reading
        assertEquals(16, MapsParser.chooseTurn("Merge", 0, false));
        assertEquals(13, MapsParser.chooseTurn("Take the ramp", 11, false));
    }

    @Test
    public void aSideOfTheTextBeatsTheIconsSide() {
        // Maps drew a right arrow, the text says left: the text is the one that read the instruction
        assertEquals(16, MapsParser.chooseTurn("Merge left", 2, false));
        assertEquals(14, MapsParser.chooseTurn("Exit on the left", 15, false));
    }

    @Test
    public void withoutAnIconTheTextDecides() {
        assertEquals(0, MapsParser.chooseTurn("Continue on Rampart Street", null, false));
        assertEquals(2, MapsParser.chooseTurn("Turn right onto Rampart Street", null, false));
        // a text that names nothing at all stays unknown rather than becoming a straight arrow
        assertEquals(KawaProtocol.TURN_NONE, MapsParser.chooseTurn("Maps is running", null, false));
    }

    @Test
    public void sideKnownFollowsTheLanguages() {
        assertTrue(MapsParser.sideKnown("Rechts abbiegen"));
        assertTrue(MapsParser.sideKnown("Take the left fork"));
        assertTrue(MapsParser.sideKnown("Prenez à gauche"));
        assertTrue(MapsParser.sideKnown("Skręć w prawo"));
        assertFalse(MapsParser.sideKnown("Take the ramp"));
        assertFalse(MapsParser.sideKnown("Continue on Rampart Street"));
        assertFalse(MapsParser.sideKnown(null));
    }
}
