package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * How many road points a conversion round takes: sized to the heap the phone gives the app, so a small
 * heap means more rounds rather than an OutOfMemory, and a big one fewer rounds.
 */
public class PbfRoundsTest {

    private static final long MB = 1024 * 1024;

    @Test
    public void aLargeHeapOf512MbGivesTheEightMillionPointsOfEarlierVersions() {
        assertEquals(8 * MB, PbfImporter.roundRefsFor(512 * MB));
    }

    @Test
    public void aSmallerHeapTakesSmallerRounds() {
        assertEquals(4 * MB, PbfImporter.roundRefsFor(256 * MB));
        assertEquals("never below a million: a round costs a pass over the file", 1_000_000, PbfImporter.roundRefsFor(16 * MB));
    }

    @Test
    public void aBiggerHeapTakesBiggerRoundsUpToACap() {
        assertEquals(16 * MB, PbfImporter.roundRefsFor(1024 * MB));
        assertEquals(32_000_000, PbfImporter.roundRefsFor(8 * 1024 * MB));
    }
}
