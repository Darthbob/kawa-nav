package io.github.darthbob.kawanav;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Names of Bluetooth SIG services / characteristics and the Kawasaki ones, for the read-only service
 * dump in the debug card. Only standard (SIG) characteristics are ever read, never vendor ones.
 */
final class GattNames {
    private GattNames() {}

    private static final String SIG_SUFFIX = "-0000-1000-8000-00805f9b34fb";
    private static final Map<String, String> NAMES = new HashMap<>();

    static {
        // services
        NAMES.put("1800", "Generic Access");
        NAMES.put("1801", "Generic Attribute");
        NAMES.put("1802", "Immediate Alert");
        NAMES.put("1803", "Link Loss");
        NAMES.put("1804", "Tx Power");
        NAMES.put("1805", "Current Time");
        NAMES.put("1806", "Reference Time Update");
        NAMES.put("180a", "Device Information");
        NAMES.put("180e", "Phone Alert Status");
        NAMES.put("180f", "Battery");
        NAMES.put("1811", "Alert Notification");
        NAMES.put("1812", "Human Interface Device");
        NAMES.put("fe59", "Nordic firmware update (DFU)");
        NAMES.put("fef5", "Dialog firmware update (SUOTA)");
        // characteristics
        NAMES.put("2a00", "Device Name");
        NAMES.put("2a01", "Appearance");
        NAMES.put("2a04", "Preferred Connection Parameters");
        NAMES.put("2a05", "Service Changed");
        NAMES.put("2a19", "Battery Level");
        NAMES.put("2a23", "System ID");
        NAMES.put("2a24", "Model Number");
        NAMES.put("2a25", "Serial Number");
        NAMES.put("2a26", "Firmware Revision");
        NAMES.put("2a27", "Hardware Revision");
        NAMES.put("2a28", "Software Revision");
        NAMES.put("2a29", "Manufacturer Name");
        NAMES.put("2a2a", "Regulatory Certification");
        NAMES.put("2a2b", "Current Time");
        NAMES.put("2a3f", "Alert Status");
        NAMES.put("2a44", "Alert Notification Control Point");
        NAMES.put("2a46", "New Alert");
        NAMES.put("2a47", "Supported New Alert Category");
        NAMES.put("2a50", "PnP ID");
        NAMES.put("2aa6", "Central Address Resolution");
        NAMES.put("2b29", "Client Supported Features");
        NAMES.put("2b2a", "Database Hash");
        // Dialog Semiconductor SUOTA (firmware update over the air), service fef5: never written or read
        NAMES.put("8082caa8-41a6-4021-91c6-56f9b954cc34", "SUOTA memory device");
        NAMES.put("724249f0-5ec3-4b5f-8804-42345af08651", "SUOTA GPIO map");
        NAMES.put("6c53db25-47a1-45fe-a022-7c92fb334fd4", "SUOTA memory info");
        NAMES.put("9d84b9a3-000c-49d8-9183-855b673fda31", "SUOTA patch length");
        NAMES.put("457871e8-d516-4ca1-9116-57d0b17b9cb2", "SUOTA patch data");
        NAMES.put("5f78df94-798c-46f5-990a-b3eb6a065c88", "SUOTA status");
        NAMES.put("64b4e8b5-0de5-401b-a21d-acc8db3b913a", "SUOTA L2CAP PSM");
        NAMES.put("42c3dfdd-77be-4d9c-8454-8f875267fb3b", "SUOTA version");
        NAMES.put("b7de1eea-823d-43bb-a3af-c4903dfce23c", "SUOTA patch data size");
        // Kawasaki
        NAMES.put(KawaProtocol.SERVICE.toString(), "Kawasaki BLE5 meter");
        NAMES.put(KawaProtocol.WRITE_CHAR.toString(), "Kawasaki write (control point)");
        for (UUID u : KawaProtocol.NOTIFY_CHARS) NAMES.put(u.toString(), "Kawasaki notify");
    }

    /** A Bluetooth SIG UUID (0000xxxx-0000-1000-8000-00805f9b34fb). */
    static boolean isStandard(UUID u) {
        String s = u.toString();
        return s.startsWith("0000") && s.endsWith(SIG_SUFFIX);
    }

    /** "180a Device Information", "92faec07… Kawasaki BLE5 meter", or the full UUID when unknown. */
    static String label(UUID u) {
        String s = u.toString().toLowerCase(Locale.ROOT);
        String key = isStandard(u) ? s.substring(4, 8) : s;
        String name = NAMES.get(key);
        String id = isStandard(u) ? key : name != null ? s.substring(0, 8) + "…" : s;
        return name != null ? id + " " + name : id;
    }

    /** Firmware update services: listed, never read. */
    static boolean isFirmwareUpdate(UUID u) {
        String s = u.toString().toLowerCase(Locale.ROOT);
        return s.startsWith("0000fe59") || s.startsWith("00001530-1212-efde") // Nordic DFU, legacy DFU
                || s.startsWith("0000fef5"); // Dialog SUOTA
    }

    /** Text when the bytes are printable ASCII, else hex. */
    static String value(byte[] v) {
        if (v == null || v.length == 0) return "(empty)";
        for (byte b : v) {
            if (b < 0x20 || b > 0x7E) return KawaProtocol.hex(v);
        }
        return "\"" + new String(v, java.nio.charset.StandardCharsets.US_ASCII) + "\"";
    }
}
