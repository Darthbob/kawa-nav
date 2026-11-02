package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The log entry of a crash: everything a stack trace tells, on one entry, so the exported log holds
 * the crash next to the lines that led up to it.
 */
public class LoggerCrashTest {

    @Test
    public void theEntryNamesTheThreadTheExceptionAndTheCause() {
        Throwable cause = new IllegalStateException("meter answered nothing");
        Throwable e = new RuntimeException("tick failed", cause);
        String line = Logger.crashLine("main", e);
        assertTrue(line, line.contains("CRASH on thread \"main\""));
        assertTrue(line, line.contains("java.lang.RuntimeException: tick failed"));
        assertTrue(line, line.contains("Caused by: java.lang.IllegalStateException: meter answered nothing"));
        assertTrue("the frames are there", line.contains("at " + getClass().getName()));
    }

    @Test
    public void theEntryStartsWithTheLogsTimeStamp() {
        String line = Logger.crashLine("nav-route", new Exception("x"));
        // yyyy-MM-dd HH:mm:ss.SSS, then two spaces, like every other line
        assertTrue(line, line.matches("(?s)\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}  CRASH.*"));
        assertEquals("no trailing newline: appendToFile adds the line's own", line, line.trim());
    }
}
