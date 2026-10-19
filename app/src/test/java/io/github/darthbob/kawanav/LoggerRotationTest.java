package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The log file's 2 MB cap. Replacing the previous {@code .1} happens in the same step as the move, so a
 * rotation that cannot be done costs nothing: both files stay, and the line still goes into the log.
 */
public class LoggerRotationTest {

    /** One byte past the cap, so the next append has to rotate. */
    private static final int PAST_THE_CAP = 2_000_001;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** The log as it stands after the cap was passed. */
    private File fullLog() throws Exception {
        File f = new File(tmp.getRoot(), "kawanav.log");
        byte[] b = new byte[PAST_THE_CAP];
        java.util.Arrays.fill(b, (byte) 'x');
        Files.write(f.toPath(), b);
        return f;
    }

    @Test
    public void aLogPastTheCapIsRotatedAndTheLineGoesToTheNewFile() throws Exception {
        File log = fullLog();
        Logger.appendToFile(tmp.getRoot(), "hello");
        File old = new File(tmp.getRoot(), "kawanav.log.1");
        assertTrue("the full log is kept as .1", old.exists());
        assertEquals(PAST_THE_CAP, old.length());
        assertEquals("hello\n", new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void aFailedRotationLosesNothing() throws Exception {
        File log = fullLog();
        // a non-empty directory where .1 has to go: the move cannot succeed
        File blocker = new File(tmp.getRoot(), "kawanav.log.1");
        assertTrue(blocker.mkdir());
        Files.write(new File(blocker, "keep").toPath(), new byte[]{1});
        Logger.appendToFile(tmp.getRoot(), "hello");
        assertEquals("the line still landed in the log", PAST_THE_CAP + "hello\n".length(), log.length());
        assertTrue("the old .1 was not thrown away", new File(blocker, "keep").exists());
    }
}
