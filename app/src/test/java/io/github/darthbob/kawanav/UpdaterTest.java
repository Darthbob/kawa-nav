package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Which release is newer than the installed app: version names compared number by number, so 1.3.10
 * comes after 1.3.9 and a third number counts, as the in-app updater and its changelog list rely on.
 */
public class UpdaterTest {

    private static void newer(String a, String b) {
        assertTrue(a + " > " + b, Updater.compare(a, b) > 0);
        assertTrue(b + " < " + a, Updater.compare(b, a) < 0);
    }

    @Test
    public void numbersNotStringsDecide() {
        newer("1.3.10", "1.3.9");
        newer("0.10", "0.9");
        newer("2.0", "1.9.9");
    }

    @Test
    public void aThirdNumberIsNewerThanNone() {
        newer("1.3.1", "1.3");
        newer("0.4.1", "0.4");
    }

    @Test
    public void missingNumbersCountAsZero() {
        assertEquals(0, Updater.compare("1.3", "1.3.0"));
        assertEquals(0, Updater.compare("1.3.0.0", "1.3"));
    }

    @Test
    public void theSameVersionIsNotAnUpdate() {
        assertEquals(0, Updater.compare("1.3.2", "1.3.2"));
    }

    @Test
    public void aSuffixAfterTheNumberIsIgnored() {
        // a "1.4-rc1" tag would read as 1.4; letters alone count as 0
        assertEquals(0, Updater.compare("1.4-rc1", "1.4"));
        newer("1.4", "1.4-rc1".replace("1.4", "1.3"));
        assertEquals(0, Updater.compare("x", "0"));
    }
}
