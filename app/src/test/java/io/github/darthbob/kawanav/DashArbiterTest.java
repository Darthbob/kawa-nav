package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * What the dash shows once a second: who wins between KawaNav's navigation, Google Maps, a speed
 * camera, a curve on the way to the next maneuver and free ride, as the README promises under
 * "What the dash shows", "Curve warnings during navigation" and "Speed cameras".
 */
public class DashArbiterTest {

    private static final int STRAIGHT = 0, LEFT = 1, RIGHT = 2, SHARP_LEFT = 3, SLIGHT_RIGHT = 6, HAIRPIN_RIGHT = 8;

    private static CurveDetector.Curve curve(int turn, double startM) {
        return new CurveDetector.Curve(turn, startM, startM + 40, turn % 2 == 1 ? -60 : 60, 80, 0, startM);
    }

    private static Hub.Nav nav(int turn, int distanceM, Navigator.State state) {
        return new Hub.Nav(turn, distanceM, "turn", 5000, 300, state);
    }

    private static Hazards.Ahead camera(double distanceM) {
        return new Hazards.Ahead(new Hazards.Camera(42, 460000000, 110000000, "fixed", 50), distanceM);
    }

    private static DashArbiter.Inputs inputs() {
        DashArbiter.Inputs in = new DashArbiter.Inputs();
        in.freeRide = new Hub.Output(RIGHT, 120, "Curve");
        return in;
    }

    // ------------------------------------------------------------------ the order of precedence

    @Test
    public void nothingNavigatesAndFreeRideIsOn_theNextCurveShows() {
        DashArbiter.Inputs in = inputs();
        assertSame(in.freeRide, DashArbiter.decide(in));
    }

    @Test
    public void freeRideOff_nothingShows() {
        DashArbiter.Inputs in = inputs();
        in.freeMode = false;
        assertNull(DashArbiter.decide(in));
    }

    @Test
    public void mapsNavigates_itsManeuverReplacesFreeRide() {
        DashArbiter.Inputs in = inputs();
        in.maps = new Hub.MapsTurn(LEFT, 350, "Turn left");
        Hub.Output out = DashArbiter.decide(in);
        assertEquals("Maps", out.source);
        assertEquals(LEFT, out.turn);
        assertEquals(350, out.distanceM);
    }

    @Test
    public void mapsDirectionsSwitchedOff_mapsIsIgnoredAndFreeRideGoesOn() {
        DashArbiter.Inputs in = inputs();
        in.mapsEnabled = false;
        in.maps = new Hub.MapsTurn(LEFT, 350, "Turn left");
        assertSame(in.freeRide, DashArbiter.decide(in));
    }

    @Test
    public void mapsStartingUpWithoutATurnOrDistance_showsNothingNotFreeRide() {
        DashArbiter.Inputs in = inputs();
        in.maps = new Hub.MapsTurn(KawaProtocol.TURN_NONE, -1, "Navigation wird gestartet…");
        assertNull("free ride must not flash while Maps starts", DashArbiter.decide(in));
    }

    @Test
    public void mapsTurnUnknownButDistanceKnown_isStillSent() {
        // the icon was not recognised: the dash shows the distance with Maps' turn id as it is
        DashArbiter.Inputs in = inputs();
        in.maps = new Hub.MapsTurn(KawaProtocol.TURN_NONE, 200, "Something new");
        Hub.Output out = DashArbiter.decide(in);
        assertEquals("Maps", out.source);
        assertEquals(200, out.distanceM);
    }

    @Test
    public void kawaNavsOwnNavigationComesBeforeMaps() {
        DashArbiter.Inputs in = inputs();
        in.maps = new Hub.MapsTurn(LEFT, 350, "Turn left");
        in.nav = nav(RIGHT, 123, Navigator.State.NAVIGATING);
        Hub.Output out = DashArbiter.decide(in);
        assertEquals("Nav", out.source);
        assertEquals(RIGHT, out.turn);
        assertEquals("rounded to 10 m like Rideology", 120, out.distanceM);
    }

    @Test
    public void ownNavigationWithoutATurnYet_showsNothing() {
        DashArbiter.Inputs in = inputs();
        in.nav = nav(KawaProtocol.TURN_NONE, 0, Navigator.State.OFF_ROUTE);
        assertNull(DashArbiter.decide(in));
    }

    // ------------------------------------------------------------------ speed cameras

    @Test
    public void cameraWarningsOff_aCameraAheadChangesNothing() {
        DashArbiter.Inputs in = inputs();
        in.camera = camera(100);
        assertSame(in.freeRide, DashArbiter.decide(in));
    }

    @Test
    public void inFreeRideACameraWithinTheWarningDistanceTakesTheDash() {
        DashArbiter.Inputs in = inputs();
        in.camerasEnabled = true;
        in.cameraWarnM = 500;
        in.camera = camera(437);
        Hub.Output out = DashArbiter.decide(in);
        assertEquals("Camera", out.source);
        assertEquals(KawaProtocol.CAMERA_TURN, out.turn);
        assertEquals(440, out.distanceM);
        assertEquals(42, out.cameraId);
        assertEquals("Speed camera in 440 m (50 km/h)", out.alert);
    }

    @Test
    public void aCameraBeyondTheWarningDistanceIsLeftAlone() {
        DashArbiter.Inputs in = inputs();
        in.camerasEnabled = true;
        in.cameraWarnM = 300;
        in.camera = camera(437);
        assertSame(in.freeRide, DashArbiter.decide(in));
    }

    @Test
    public void whileNavigatingACameraOnlyTakesOverWithin250m() {
        DashArbiter.Inputs in = inputs();
        in.camerasEnabled = true;
        in.cameraWarnM = 800;
        in.maps = new Hub.MapsTurn(LEFT, 2000, "Turn left");
        in.camera = camera(400);
        assertEquals("the maneuver matters more than a camera 400 m away", "Maps", DashArbiter.decide(in).source);
        in.camera = camera(240);
        assertEquals("Camera", DashArbiter.decide(in).source);
        in.nav = nav(RIGHT, 900, Navigator.State.NAVIGATING);
        in.camera = camera(400);
        assertEquals("the same with KawaNav's own navigation", "Nav", DashArbiter.decide(in).source);
    }

    @Test
    public void aShorterWarningDistanceThanTheNavigationCapCountsWhileNavigating() {
        DashArbiter.Inputs in = inputs();
        in.camerasEnabled = true;
        in.cameraWarnM = 200;
        in.maps = new Hub.MapsTurn(LEFT, 2000, "Turn left");
        in.camera = camera(230);
        assertEquals("Maps", DashArbiter.decide(in).source);
    }

    @Test
    public void aCameraComesEvenBeforeAnOwnNavigationThatHasNoTurn() {
        DashArbiter.Inputs in = inputs();
        in.camerasEnabled = true;
        in.nav = nav(KawaProtocol.TURN_NONE, 0, Navigator.State.OFF_ROUTE);
        in.camera = camera(100);
        assertEquals("Camera", DashArbiter.decide(in).source);
    }

    // ------------------------------------------------------------------ curves between the maneuvers

    @Test
    public void curveWarningsDuringNavigationOff_mapsArrowStays() {
        DashArbiter.Inputs in = inputs();
        in.maps = new Hub.MapsTurn(LEFT, 2000, "Turn left");
        in.curves = Arrays.asList(curve(SHARP_LEFT, 150));
        assertEquals("Maps", DashArbiter.decide(in).source);
    }

    @Test
    public void aBendWithin300mAndClearlyBeforeTheManeuverInterruptsMaps() {
        DashArbiter.Inputs in = inputs();
        in.navCurves = DashArbiter.NAV_CURVES_ALL;
        in.maps = new Hub.MapsTurn(LEFT, 2000, "Turn left");
        in.curves = Arrays.asList(curve(RIGHT, 284));
        Hub.Output out = DashArbiter.decide(in);
        assertEquals("Curve", out.source);
        assertEquals(RIGHT, out.turn);
        assertEquals(280, out.distanceM);
    }

    @Test
    public void aBendFurtherThan300mWaits() {
        DashArbiter.Inputs in = inputs();
        in.navCurves = DashArbiter.NAV_CURVES_ALL;
        in.maps = new Hub.MapsTurn(LEFT, 2000, "Turn left");
        in.curves = Arrays.asList(curve(RIGHT, 301));
        assertEquals("Maps", DashArbiter.decide(in).source);
    }

    @Test
    public void aBendLessThan50mBeforeTheManeuverIsLeftToTheManeuver() {
        DashArbiter.Inputs in = inputs();
        in.navCurves = DashArbiter.NAV_CURVES_ALL;
        in.maps = new Hub.MapsTurn(LEFT, 200, "Turn left");
        in.curves = Arrays.asList(curve(RIGHT, 151));
        assertEquals("Maps", DashArbiter.decide(in).source);
        in.curves = Arrays.asList(curve(RIGHT, 150));
        assertEquals("Curve", DashArbiter.decide(in).source);
    }

    @Test
    public void aManeuverWithoutADistanceIsImminent_noCurveThen() {
        DashArbiter.Inputs in = inputs();
        in.navCurves = DashArbiter.NAV_CURVES_ALL;
        in.maps = new Hub.MapsTurn(LEFT, -1, "Links abbiegen");
        in.curves = Arrays.asList(curve(RIGHT, 100));
        assertEquals("Maps", DashArbiter.decide(in).source);
    }

    @Test
    public void sharpOnlySkipsGentleBendsButFindsTheSharpOneBehindThem() {
        DashArbiter.Inputs in = inputs();
        in.navCurves = DashArbiter.NAV_CURVES_SHARP;
        in.maps = new Hub.MapsTurn(LEFT, 2000, "Turn left");
        in.curves = Arrays.asList(curve(SLIGHT_RIGHT, 100), curve(HAIRPIN_RIGHT, 250));
        Hub.Output out = DashArbiter.decide(in);
        assertEquals("Curve", out.source);
        assertEquals(HAIRPIN_RIGHT, out.turn);
        in.curves = Arrays.asList(curve(SLIGHT_RIGHT, 100), curve(LEFT, 250));
        assertEquals("Maps", DashArbiter.decide(in).source);
    }

    @Test
    public void aBendAlreadyEnteredIsTheRidersBusiness_theNextOneCounts() {
        DashArbiter.Inputs in = inputs();
        in.navCurves = DashArbiter.NAV_CURVES_ALL;
        in.maps = new Hub.MapsTurn(LEFT, 2000, "Turn left");
        in.curves = Arrays.asList(curve(RIGHT, 3), curve(LEFT, 120));
        assertEquals(LEFT, DashArbiter.decide(in).turn);
    }

    @Test
    public void curvesInterruptOwnNavigationOnlyWhileOnTheRoute() {
        DashArbiter.Inputs in = inputs();
        in.navCurves = DashArbiter.NAV_CURVES_ALL;
        in.curves = Arrays.asList(curve(SHARP_LEFT, 150));
        in.nav = nav(RIGHT, 1500, Navigator.State.NAVIGATING);
        assertEquals("Curve", DashArbiter.decide(in).source);
        in.nav = nav(RIGHT, 1500, Navigator.State.OFF_ROUTE);
        assertEquals("off the route the curves are of another road", "Nav", DashArbiter.decide(in).source);
    }

    @Test
    public void anOldFixHasNoCurves_theManeuverStays() {
        DashArbiter.Inputs in = inputs();
        in.navCurves = DashArbiter.NAV_CURVES_ALL;
        in.maps = new Hub.MapsTurn(LEFT, 2000, "Turn left");
        List<CurveDetector.Curve> none = java.util.Collections.emptyList();
        in.curves = none;
        assertEquals("Maps", DashArbiter.decide(in).source);
    }

    @Test
    public void onlySharpBendsAndHairpinsCountAsSharp() {
        for (int turn = 0; turn < KawaProtocol.TURN_NAMES.length; turn++) {
            String name = KawaProtocol.TURN_NAMES[turn];
            boolean expected = name.startsWith("Sharp") || name.startsWith("U-turn");
            assertEquals(name, expected, DashArbiter.sharp(turn));
        }
    }
}
