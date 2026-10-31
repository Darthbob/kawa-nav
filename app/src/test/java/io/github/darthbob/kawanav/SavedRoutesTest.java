package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

/** Saved routes: written and read back, listed newest first, deleted. */
public class SavedRoutesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static SavedRoutes.Saved route(String name, long created, boolean roundTrip) {
        SavedRoutes.Saved s = new SavedRoutes.Saved();
        s.name = name;
        s.created = created;
        s.roundTrip = roundTrip;
        s.kind = RouteProfile.Kind.EXTRA_CURVY;
        s.avoidMotorways = true;
        s.avoidUnpaved = false;
        s.km = 27.34;
        s.points.add(new Places.Place("Andorra la Vella", 42.5063, 1.5218));
        s.points.add(new Places.Place("Coll d'Ordino", 42.5553, 1.5735));
        s.points.add(roundTrip ? new Places.Place("Andorra la Vella", 42.5063, 1.5218)
                : new Places.Place("Pas de la Casa", 42.5424, 1.7333));
        return s;
    }

    @Test
    public void readBackAsWritten() {
        SavedRoutes.Saved s = route("Ordino = bends\nand more", 1730000000000L, true);
        SavedRoutes.Saved back = SavedRoutes.decode(SavedRoutes.encode(s));
        assertEquals(s.name, back.name);
        assertEquals(s.created, back.created);
        assertTrue(back.roundTrip);
        assertEquals(RouteProfile.Kind.EXTRA_CURVY, back.kind);
        assertTrue(back.avoidMotorways);
        assertFalse(back.avoidTolls);
        assertFalse(back.avoidUnpaved);
        assertEquals(27.3, back.km, 1e-9);
        assertEquals(3, back.points.size());
        assertEquals("Coll d'Ordino", back.points.get(1).name);
        assertEquals(1.5735, back.points.get(1).lon, 1e-6);
        assertEquals(RouteProfile.Kind.EXTRA_CURVY, back.profile().kind);
    }

    @Test
    public void unknownAndBrokenValuesKeepDefaults() {
        SavedRoutes.Saved s = SavedRoutes.decode("name=x\nprofile=WIGGLY\ncolour=red\nkm=abc\n"
                + "point=1\t2\ta\npoint=3\t4\tb\n");
        assertEquals(RouteProfile.Kind.CURVY, s.kind);
        assertEquals(0, s.km, 0);
        assertTrue(s.avoidUnpaved);
        assertNull("one point is no route", SavedRoutes.decode("name=x\npoint=1\t2\ta\n"));
    }

    @Test
    public void listedNewestFirstAndDeleted() throws Exception {
        File dir = new File(tmp.getRoot(), "routes");
        SavedRoutes.save(dir, route("old", 1000, false));
        SavedRoutes.Saved same = route("same time", 2000, false);
        SavedRoutes.save(dir, same);
        SavedRoutes.save(dir, route("same time too", 2000, false));
        List<SavedRoutes.Saved> l = SavedRoutes.list(dir);
        assertEquals(3, l.size());
        assertEquals("old", l.get(2).name);

        // renamed: written to its own file again
        same.name = "renamed";
        SavedRoutes.save(dir, same);
        l = SavedRoutes.list(dir);
        assertEquals(3, l.size());
        assertTrue(l.stream().anyMatch(r -> r.name.equals("renamed")));

        assertTrue(SavedRoutes.delete(l.get(2)));
        assertEquals(2, SavedRoutes.list(dir).size());
        assertEquals(0, SavedRoutes.list(new File(tmp.getRoot(), "none")).size());
    }
}
