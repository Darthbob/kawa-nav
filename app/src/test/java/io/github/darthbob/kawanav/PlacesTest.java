package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** Recent and favourite places: no doubles, newest first, names kept whatever they hold. */
public class PlacesTest {

    @Test
    public void newestFirstWithoutDoubles() {
        List<Places.Place> l = new ArrayList<>();
        l = Places.addFirst(l, new Places.Place("Andorra la Vella", 42.5063, 1.5218), Places.MAX_RECENT);
        l = Places.addFirst(l, new Places.Place("Pas de la Casa", 42.5424, 1.7333), Places.MAX_RECENT);
        // 50 m from the first one: the same place, moved to the top
        l = Places.addFirst(l, new Places.Place("Andorra", 42.5067, 1.5220), Places.MAX_RECENT);
        assertEquals(2, l.size());
        assertEquals("Andorra", l.get(0).name);
        assertEquals("Pas de la Casa", l.get(1).name);
    }

    @Test
    public void atMostTen() {
        List<Places.Place> l = new ArrayList<>();
        for (int i = 0; i < 15; i++) l = Places.addFirst(l, new Places.Place("P" + i, 40 + i * 0.1, 10), Places.MAX_RECENT);
        assertEquals(Places.MAX_RECENT, l.size());
        assertEquals("P14", l.get(0).name);
        assertEquals("P5", l.get(9).name);
    }

    @Test
    public void namesSurviveTabsBreaksAndBackslashes() {
        List<Places.Place> l = new ArrayList<>();
        l.add(new Places.Place("Bar \"Sole\"\tvia Roma 1\nTorino \\ centro", 45.07, 7.68));
        l.add(new Places.Place("", -33.9, 151.2));
        List<Places.Place> back = Places.decode(Places.encode(l));
        assertEquals(2, back.size());
        assertEquals(l.get(0).name, back.get(0).name);
        assertEquals(45.07, back.get(0).lat, 1e-6);
        assertEquals("", back.get(1).name);
        assertEquals(151.2, back.get(1).lon, 1e-6);
    }

    @Test
    public void brokenLinesAreSkipped() {
        assertEquals(1, Places.decode("garbage\n1.0\t2.0\tok\nx\ty\tz\n").size());
        assertNull(Places.place("1.0\t2.0"));
        assertEquals(0, Places.decode("").size());
    }
}
