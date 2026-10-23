package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/** The list of field tests: every test complete, and what the screen relies on. */
public class FieldTestsTest {

    @Test
    public void everyTestIsComplete() {
        Set<String> ids = new HashSet<>();
        for (FieldTests.Test t : FieldTests.ALL) {
            assertTrue("duplicate id " + t.id, ids.add(t.id));
            assertTrue(t.id, t.id.matches("[a-z0-9_]+"));     // part of a settings key
            for (String text : new String[]{t.group, t.title, t.why, t.steps, t.settled}) {
                assertFalse(t.id, text.trim().isEmpty());
            }
            assertTrue(t.id + ": steps are numbered", t.steps.startsWith("1. "));
        }
    }

    @Test
    public void theGroupsStayTogether() {
        // the screen starts a new header each time the group changes
        Set<String> seen = new HashSet<>();
        String last = null;
        for (FieldTests.Test t : FieldTests.ALL) {
            if (!t.group.equals(last)) assertTrue("group split: " + t.group, seen.add(t.group));
            last = t.group;
        }
    }

    @Test
    public void oneTestEachForTheTyreAndClutchNumbers() {
        int tyres = 0, clutch = 0;
        for (FieldTests.Test t : FieldTests.ALL) {
            if (t.results == FieldTests.RESULTS_TYRES) tyres++;
            if (t.results == FieldTests.RESULTS_CLUTCH) clutch++;
        }
        assertEquals(1, tyres);
        assertEquals(1, clutch);
    }
}
