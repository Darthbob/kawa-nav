package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The camera scan reads the nodes of an OpenStreetMap PBF: the usual ones, and the "dense" ones, whose
 * tags are a packed list that ends every node with a 0 — an off-by-one there would shift every camera
 * of the file. This builds a small PBF by hand and checks what comes out.
 */
public class PbfCamerasTest {

    /** A minimal protobuf writer, enough for a PrimitiveBlock with a DenseNodes group. */
    private static final class Pbw {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();

        void varint(long v) {
            while ((v & ~0x7FL) != 0) {
                o.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            o.write((int) v);
        }

        void tag(int field, int type) {
            varint((long) field << 3 | type);
        }

        void bytes(int field, byte[] b) {
            tag(field, 2);
            varint(b.length);
            o.write(b, 0, b.length);
        }

        void varintField(int field, long v) {
            tag(field, 0);
            varint(v);
        }

        /** sint64 field: zigzag, like Node.id/lat/lon in the format. */
        void sintField(int field, long v) {
            tag(field, 0);
            varint((v << 1) ^ (v >> 63));
        }

        void string(int field, String s) {
            bytes(field, s.getBytes(StandardCharsets.UTF_8));
        }

        /** Packed sint64 array (zigzag deltas, as DenseNodes uses for ids/lat/lon). */
        void packedDeltas(int field, long[] values) {
            Pbw p = new Pbw();
            long prev = 0;
            for (long v : values) {
                p.varint(((v - prev) << 1) ^ ((v - prev) >> 63));
                prev = v;
            }
            bytes(field, p.o.toByteArray());
        }

        /** Packed int32 array (the keys_vals list). */
        void packedInts(int field, int[] values) {
            Pbw p = new Pbw();
            for (int v : values) p.varint(v);
            bytes(field, p.o.toByteArray());
        }

        byte[] done() {
            return o.toByteArray();
        }
    }

    /** Blob header + blob (uncompressed) around a PrimitiveBlock. */
    private static byte[] blob(String type, byte[] block) {
        Pbw blob = new Pbw();
        blob.bytes(1, block);                 // raw
        byte[] blobBytes = blob.done();
        Pbw header = new Pbw();
        header.string(1, type);               // "OSMData"
        header.varintField(3, blobBytes.length);
        byte[] headerBytes = header.done();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{(byte) (headerBytes.length >>> 24), (byte) (headerBytes.length >>> 16),
                (byte) (headerBytes.length >>> 8), (byte) headerBytes.length}, 0, 4);
        out.write(headerBytes, 0, headerBytes.length);
        out.write(blobBytes, 0, blobBytes.length);
        return out.toByteArray();
    }

    private static byte[] stringTable(String... strings) {
        Pbw table = new Pbw();
        for (String s : strings) table.string(1, s);
        return table.done();
    }

    /** String ids of the table below. */
    private static final int S_HIGHWAY = 1, S_SPEED_CAMERA = 2, S_CAMERA_TYPE = 3, S_RED_LIGHT = 4,
            S_MAXSPEED = 5, S_50 = 6, S_ENFORCEMENT = 7, S_TRAFFIC_SIGNALS = 8;

    /** 46.52 N / 11.32 E and 46.20 N (the last one is outside the region border used here). */
    private static final long LAT_IN = 465_200_000L, LON = 113_200_000L, LAT_OUT = 462_000_000L;

    /** One OSMData blob: four dense nodes (untagged, camera, untagged, camera) plus one outside. */
    private static File syntheticPbf(File dir) throws Exception {
        Pbw dense = new Pbw();
        dense.packedDeltas(1, new long[]{11, 12, 13, 14, 15});                 // ids
        dense.packedDeltas(8, new long[]{LAT_IN, LAT_IN, LAT_IN, LAT_IN, LAT_OUT}); // 1e-9 degrees
        dense.packedDeltas(9, new long[]{LON, LON, LON, LON, LON});   // 11.32 E
        dense.packedInts(10, new int[]{
                0,                                                     // 11: no tags
                S_HIGHWAY, S_SPEED_CAMERA, S_CAMERA_TYPE, S_RED_LIGHT, S_MAXSPEED, S_50, 0,  // 12
                0,                                                     // 13: no tags
                S_ENFORCEMENT, S_TRAFFIC_SIGNALS, 0,                   // 14
                S_HIGHWAY, S_SPEED_CAMERA, 0,                          // 15: outside the border
        });

        // one plain node as well, with its tags as parallel arrays
        Pbw plain = new Pbw();
        plain.sintField(1, 21);
        plain.sintField(8, LAT_IN);
        plain.sintField(9, LON);
        plain.packedInts(2, new int[]{S_HIGHWAY});
        plain.packedInts(3, new int[]{S_SPEED_CAMERA});

        Pbw group = new Pbw();
        group.bytes(2, dense.done());
        group.bytes(1, plain.done());

        Pbw block = new Pbw();
        block.bytes(1, stringTable("", "highway", "speed_camera", "camera:type", "red_light", "maxspeed", "50",
                "enforcement", "traffic_signals"));
        block.bytes(2, group.done());

        File pbf = new File(dir, "test.osm.pbf");
        try (FileOutputStream out = new FileOutputStream(pbf)) {
            out.write(blob("OSMData", block.done()));
        }
        return pbf;
    }

    private static PbfImporter.Polygon border(File dir) throws Exception {
        File poly = new File(dir, "test.poly");
        Files.write(poly.toPath(), ("test\n1\n11.2999 46.4999\n11.3501 46.4999\n11.3501 46.5501\n"
                + "11.2999 46.5501\n11.2999 46.4999\nEND\nEND\n").getBytes(StandardCharsets.UTF_8));
        return PbfImporter.Polygon.read(poly);
    }

    private static PbfImporter.Progress quiet() {
        return new PbfImporter.Progress() {
            public void status(String s) {}

            public boolean cancelled() {
                return false;
            }
        };
    }

    @Test
    public void findsTheCamerasOfDenseAndPlainNodes() throws Exception {
        File dir = Files.createTempDirectory("pbf").toFile();
        File pbf = syntheticPbf(dir);
        RoadStore roads = new RoadStore(new File(dir, "tiles"));
        List<Hazards.Camera> cameras;
        try {
            PbfImporter importer = new PbfImporter(pbf, border(dir), roads, new File(dir, "tmp"), quiet());
            importer.run();
            cameras = importer.cameras();
        } finally {
            pbf.delete();
        }

        // 12 (highway=speed_camera + camera:type=red_light, 50 km/h), 14 (enforcement=traffic_signals)
        // and 21 (plain node); 15 is outside the region border, 11 and 13 have no tags
        List<Long> ids = new ArrayList<>();
        for (Hazards.Camera c : cameras) ids.add(c.id);
        assertEquals("found " + ids, 3, cameras.size());
        assertTrue(ids.containsAll(Arrays.asList(12L, 14L, 21L)));

        Hazards.Camera c12 = null, c14 = null;
        for (Hazards.Camera c : cameras) {
            if (c.id == 12) c12 = c;
            if (c.id == 14) c14 = c;
        }
        assertTrue(c12 != null && c14 != null);
        assertEquals("red_light", c12.type);        // camera:type wins over the plain speed_camera
        assertEquals(50, c12.maxspeed);
        assertEquals(46.52, c12.lat(), 1e-6);
        assertEquals(11.32, c12.lon(), 1e-6);
        assertEquals("red_light", c14.type);        // enforcement=traffic_signals
        assertEquals(0, c14.maxspeed);
    }

    @Test
    public void cancellingStopsBeforeTheCamerasAreUsed() throws Exception {
        File dir = Files.createTempDirectory("pbf-cancel").toFile();
        File pbf = syntheticPbf(dir);
        List<Hazards.Camera> cameras;
        try {
            PbfImporter importer = new PbfImporter(pbf, border(dir), new RoadStore(new File(dir, "tiles")),
                    new File(dir, "tmp"), new PbfImporter.Progress() {
                public void status(String s) {}

                public boolean cancelled() {
                    return true;
                }
            });
            boolean cancelled = false;
            try {
                importer.run();
            } catch (PbfImporter.CancelledException e) {
                cancelled = true;
            }
            assertTrue("a cancelled import must stop", cancelled);
            cameras = importer.cameras();
        } finally {
            pbf.delete();
        }
        assertTrue(cameras.isEmpty() || cameras.size() <= 3);   // never a broken list
    }
}
