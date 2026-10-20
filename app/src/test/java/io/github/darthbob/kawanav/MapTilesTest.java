package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The map under a ride's track: where a position falls on OSM's tiles, which tiles are drawn, how long they keep. */
public class MapTilesTest {

    private static final long NOW = 1_800_000_000_000L;

    @Test
    public void positionsFallOnOsmsTiles() {
        assertEquals(0, MapTiles.x(-180), 1e-12);
        assertEquals(0.5, MapTiles.x(0), 1e-12);
        assertEquals(1, MapTiles.x(180), 1e-12);
        assertEquals(0.5, MapTiles.y(0), 1e-12);
        // Munich's Marienplatz is on tile 13/4359/2842, as the slippy-map formula gives it
        int n = 1 << 13;
        assertEquals(4359, (int) Math.floor(MapTiles.x(11.5755) * n));
        assertEquals(2842, (int) Math.floor(MapTiles.y(48.1374) * n));
    }

    @Test
    public void thePolesStayOnTheMap() {
        // Web Mercator ends at about 85° 3': beyond it the edge, not an infinity
        assertEquals(0, MapTiles.y(85.0511287798), 1e-9);
        assertEquals(1, MapTiles.y(-85.0511287798), 1e-9);
        assertEquals(0, MapTiles.y(90), 1e-9);
        assertEquals(1, MapTiles.y(-90), 1e-9);
    }

    @Test
    public void theZoomDrawnIsTheOneWhoseTilesComeClosestToTheirSize() {
        double tile = 384;
        assertEquals(12, MapTiles.zoomFor(tile * (1 << 12), tile));
        assertEquals(12, MapTiles.zoomFor(tile * (1 << 12) * 1.3, tile));   // tiles a little larger
        assertEquals(13, MapTiles.zoomFor(tile * (1 << 12) * 1.5, tile));   // closer to the next level's
        assertEquals(0, MapTiles.zoomFor(tile / 8, tile));                  // never below the world
        assertEquals(MapTiles.MAX_ZOOM, MapTiles.zoomFor(tile * (1L << 22), tile)); // nor past OSM's closest
    }

    @Test
    public void aTileKeepsAsLongAsOsmSays() {
        assertEquals(NOW + 604_800_000L, MapTiles.expiry("max-age=604800, stale-while-revalidate=604800", 0, NOW));
        assertEquals(NOW + 3_600_000L, MapTiles.expiry("public, MAX-AGE=3600", NOW + 5, NOW));
        // max-age wins over Expires, Expires counts when there is no max-age
        assertEquals(NOW + 60_000L, MapTiles.expiry("max-age=60", NOW + 999_999, NOW));
        assertEquals(NOW + 123_000L, MapTiles.expiry("public", NOW + 123_000, NOW));
        assertEquals(NOW + 123_000L, MapTiles.expiry("max-age=soon", NOW + 123_000, NOW));
    }

    @Test
    public void withoutCacheHeadersATileKeepsAWeek() {
        assertEquals(NOW + MapTiles.DEFAULT_KEEP_MS, MapTiles.expiry(null, 0, NOW));
        assertEquals(NOW + MapTiles.DEFAULT_KEEP_MS, MapTiles.expiry("", 0, NOW));
        assertEquals(7L * 24 * 3600 * 1000, MapTiles.DEFAULT_KEEP_MS);
    }

    @Test
    public void tilesAreNamedAsOsmNamesThem() {
        assertEquals("13/4359/2842", MapTiles.key(13, 4359, 2842));
    }
}
