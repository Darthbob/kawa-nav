package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Ride settings kept on the phone under a name: stored, read back, saved over and deleted. */
public class SettingsProfilesTest {

    private static SettingsProfiles.Profile p(String name, int mode, int power, int ktrc, int kqs) {
        return new SettingsProfiles.Profile(name, mode, power, ktrc, kqs);
    }

    @Test
    public void profilesComeBackAsTheyWereStored() {
        List<SettingsProfiles.Profile> list = Arrays.asList(p("Passo", 4, 1, 3, 2), p("Pioggia", 3, 3, 3, 1));
        List<SettingsProfiles.Profile> back = SettingsProfiles.parse(SettingsProfiles.format(list));
        assertEquals(2, back.size());
        assertEquals("Passo", back.get(0).name);
        assertEquals(4, back.get(0).mode);
        assertEquals(1, back.get(0).power);
        assertEquals(3, back.get(0).ktrcLevel);
        assertEquals(2, back.get(0).kqs);
        assertEquals("Pioggia", back.get(1).name);
        assertEquals(3, back.get(1).mode);
    }

    @Test
    public void aNameKeepsNoTabsOrLineBreaksAndFitsTheWrite() {
        assertEquals("Passo dello Stelvi", SettingsProfiles.clean("Passo\tdello\nStelvio"));
        List<SettingsProfiles.Profile> back = SettingsProfiles.parse(
                SettingsProfiles.format(Arrays.asList(p("a\tb", 1, 1, 1, 1))));
        assertEquals("a b", back.get(0).name);
        assertEquals("", SettingsProfiles.clean(null));
    }

    @Test
    public void linesThatAreNoProfileAreLeftOut() {
        String text = "4\t1\t3\t2\tPasso\n"
                + "garbage\n"
                + "9\t1\t1\t1\tNo such mode\n"   // riding mode 9 does not exist
                + "1\t1\t7\t1\tKTRC 7\n"          // KTRC 7 cannot be written
                + "1\t1\t1\t1\t\n"                // no name
                + "1\tx\t1\t1\tNot a number\n"
                + "2\t1\t2\t2\tpasso\n";          // the same name again
        List<SettingsProfiles.Profile> back = SettingsProfiles.parse(text);
        assertEquals(1, back.size());
        assertEquals("Passo", back.get(0).name);
        assertTrue(SettingsProfiles.parse(null).isEmpty());
        assertTrue(SettingsProfiles.parse("").isEmpty());
    }

    @Test
    public void savingANameAgainReplacesThatProfile() {
        List<SettingsProfiles.Profile> list = Arrays.asList(p("Passo", 4, 1, 3, 2), p("Pioggia", 3, 3, 3, 1));
        List<SettingsProfiles.Profile> next = SettingsProfiles.put(list, p("PASSO", 4, 2, 4, 2));
        assertEquals(2, next.size());
        assertEquals(0, SettingsProfiles.indexOf(next, "passo"));
        assertEquals(2, next.get(0).power);
        next = SettingsProfiles.put(next, p("Pista", 1, 1, 1, 2));
        assertEquals(3, next.size());
        assertEquals("Pista", next.get(2).name);
        // the list given is not changed
        assertEquals(2, list.size());
    }

    @Test
    public void thereIsAMostThatIsKept() {
        List<SettingsProfiles.Profile> list = new ArrayList<>();
        for (int i = 0; i < SettingsProfiles.MAX; i++) list.add(p("P" + i, 4, 1, 1, 1));
        assertNull(SettingsProfiles.put(list, p("One too many", 4, 1, 1, 1)));
        // saving over one that is there still works
        assertEquals(SettingsProfiles.MAX, SettingsProfiles.put(list, p("P3", 1, 1, 1, 1)).size());
        // a stored text with more is cut at the most
        assertEquals(SettingsProfiles.MAX, SettingsProfiles.parse(
                SettingsProfiles.format(list) + "\n4\t1\t1\t1\tExtra").size());
    }

    @Test
    public void deletingRemovesOnlyThatOne() {
        List<SettingsProfiles.Profile> list = Arrays.asList(p("Passo", 4, 1, 3, 2), p("Pioggia", 3, 3, 3, 1));
        List<SettingsProfiles.Profile> next = SettingsProfiles.remove(list, "passo");
        assertEquals(1, next.size());
        assertEquals("Pioggia", next.get(0).name);
        assertEquals(list, SettingsProfiles.remove(list, "Not there"));
    }
}
