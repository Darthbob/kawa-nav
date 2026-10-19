package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

/**
 * Geofabrik's region ids contain "/" for the US states ({@code us/alabama}), and a region id becomes a
 * file name in two places: the download cache ({@code <id>.poly}, {@code <id>.osm.pbf}) and the region
 * record. A "/" there needs a directory that is not there, and {@code regions/us/alabama.json} stays
 * invisible to a listing that reads one flat folder. {@link OfflineRegions#safeKey} is the one place
 * that folds it; the real id stays inside the record, so a region is found and deleted by the id it
 * came with.
 *
 * <p>The round-trip through the record is not covered here: it goes through {@code org.json}, which the
 * JVM test runtime stubs out (the framework supplies it on a device). What can be pinned here is the
 * name the record is written under, which is what the round-trip rests on.
 */
public class OfflineRegionKeyTest {

    @Test
    public void aUsRegionBecomesASafeFileName() {
        assertEquals("us_alabama", OfflineRegions.safeKey("us/alabama"));
        assertEquals("us_new_york", OfflineRegions.safeKey("us/new_york"));
        assertEquals("bayern", OfflineRegions.safeKey("bayern"));
        assertEquals("", OfflineRegions.safeKey(null));
    }

    @Test
    public void theKeyNeverLeavesTheDirectory() {
        // what matters is that the key cannot point outside the folder it is joined to, whatever an id
        // contains: no separator, no "..", and never "." or ".." itself
        for (String id : new String[]{"us/alabama", "a/b/c", "a\\b", "../etc/passwd", "..", "a/../b",
                "..\\..\\windows", "a//b", "./x", ".", "///"}) {
            String key = OfflineRegions.safeKey(id);
            assertFalse(id + " -> " + key, key.contains("/"));
            assertFalse(id + " -> " + key, key.contains("\\"));
            assertFalse(id + " -> " + key, key.contains(".."));
            assertFalse(id + " -> " + key, key.equals("."));
        }
    }

    @Test
    public void theKeyIsWhatEveryFileIsNamedAfter() {
        // the download cache and the record have to agree, or a region is downloaded under one name and
        // looked for under another
        assertEquals("us_alabama.json", OfflineRegions.safeKey("us/alabama") + ".json");
        assertEquals("us_alabama.osm.pbf", OfflineRegions.safeKey("us/alabama") + ".osm.pbf");
        assertEquals("us_alabama.poly", OfflineRegions.safeKey("us/alabama") + ".poly");
    }

    @Test
    public void idsThatNeedNoFoldingAreLeftAlone() {
        assertEquals("bayern", OfflineRegions.safeKey("bayern"));
        assertEquals("nord-ovest", OfflineRegions.safeKey("nord-ovest"));
        assertEquals("us_new-hampshire", OfflineRegions.safeKey("us/new-hampshire"));
        assertEquals("italy_nord-est", OfflineRegions.safeKey("italy/nord-est"));
    }
}
