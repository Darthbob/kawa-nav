package io.github.darthbob.kawanav;

import java.util.List;

/**
 * The tests still to be done at the bike, for the debug screen that lists them ({@link FieldTestsActivity}):
 * what each one settles, how to do it and what counts as an answer. Most come from the open questions of
 * docs/PROTOCOL.md ("Still unknown / to verify on the bike", "What would settle them"); the tyre and clutch
 * ones from {@link WheelChecks}. Pure Java, so the list can be checked by a unit test.
 */
final class FieldTests {
    private FieldTests() {}

    /** Results the screen works out from the recorded rides for a test. */
    static final int RESULTS_NONE = 0, RESULTS_TYRES = 1, RESULTS_CLUTCH = 2;

    static final String HEALTH = "Bike health", WRITES = "Written to the bike",
            MEANING = "What the recorded values mean", PROTOCOL = "Protocol";

    static final class Test {
        /** Never changes: the "done" mark is kept under it. */
        final String id;
        final String group, title, why, steps, settled;
        final int results;

        Test(String id, String group, String title, String why, String steps, String settled, int results) {
            this.id = id;
            this.group = group;
            this.title = title;
            this.why = why;
            this.steps = steps;
            this.settled = settled;
            this.results = results;
        }
    }

    static final List<Test> ALL = List.of(
            new Test("tyre_ratio", HEALTH, "Rear tyre pressure and the wheel speeds",
                    "The bike has no tyre pressure sensors, but it sends both wheel speeds. On steady riding the "
                            + "rear wheel turns a fixed amount slower than the front (0.988 on the Z900), the same to "
                            + "0.06% from ride to ride. If a soft rear tyre moves that ratio clearly, KawaNav can warn "
                            + "about a slow puncture, like cars without pressure sensors do.",
                    "1. Both tyres at their usual cold pressure: record a ride of 20 minutes or more with steady "
                            + "riding at 40–100 km/h.\n"
                            + "2. Let 0.5 bar out of the rear tyre (cold) and record the same road again. Ride gently "
                            + "while it is low.\n"
                            + "3. Put the pressure back.\n"
                            + "4. Compare the two rides below, in the same speed band.",
                    "The low ride reads clearly below the usual ratio (more than 0.1%, the spread between normal "
                            + "rides): a slow-puncture warning is worth building. No clear shift: drop the idea.",
                    RESULTS_TYRES),
            new Test("clutch_slip", HEALTH, "Clutch slip and the gearing",
                    "In a gear, engine speed per km/h of the rear wheel is fixed by the gearing while the clutch "
                            + "holds. A slipping clutch lets the engine run ahead under load, so the ratio rises above "
                            + "the same gear's light-throttle value.",
                    "1. Record a ride with a few full-throttle roll-ons in 3rd to 6th gear, from about 3000 rpm, "
                            + "3–4 s each, where it is safe and legal.\n"
                            + "2. Look at that ride's clutch line below.",
                    "The loaded seconds stay at or below the reference (1–4% below is normal on the Z900): no slip, "
                            + "and the check has its baseline. Seconds more than 3% above it in more than one roll-on: "
                            + "the clutch may be slipping, or the rule needs work.",
                    RESULTS_CLUTCH),
            new Test("profile_name", WRITES, "Profile name on the dash",
                    "Each settings write carries a profile name (bytes 15-38 of 0x48). Whether a dash shows it "
                            + "anywhere is not known.",
                    "1. Bike tab → Ride settings: type a short name and send the settings.\n"
                            + "2. Look through the dash's riding mode and settings pages for the name.",
                    "The name appears somewhere (note where), or it appears nowhere after a look through every "
                            + "page.",
                    RESULTS_NONE),
            new Test("ktrc_level", MEANING, "KTRC level and the tcs columns",
                    "Two pairs of traction control values are recorded (tcs/tcs_hi from 0x4A, tcs_hb/tcs_lb from "
                            + "0x4B). Whether either is the rider's level, an intervention or something else is "
                            + "not settled.",
                    "1. Record a short ride with KTRC at one level.\n"
                            + "2. Change the level on the dash and record another.\n"
                            + "3. Export both as CSV and compare the four tcs columns.",
                    "A column that follows the level is the setting. If none does, none of them is.",
                    RESULTS_NONE),
            new Test("tcs_cut", MEANING, "Traction control cutting in",
                    "If no tcs column is the level, one of them may step when traction control cuts in.",
                    "1. Bike data card: Capture frames on, connect again.\n"
                            + "2. On a slippery surface, slowly and only where it is safe, open the throttle until "
                            + "the dash's traction control lamp lights.\n"
                            + "3. Mark the moment (Test mark → Something else).",
                    "The column (or byte) that steps with the lamp is the intervention.",
                    RESULTS_NONE),
            new Test("abs_stop", MEANING, "ABS lamp and byte 37",
                    "abs read 2 in 8 isolated seconds of 2026-10-17, not all under braking. Whether that is an "
                            + "ABS intervention is not known.",
                    "1. Bike data card: Capture frames on, connect again.\n"
                            + "2. On an empty, safe road, a hard stop that makes the dash's ABS lamp flash.\n"
                            + "3. Mark the moment (Test mark → Front brake).",
                    "Byte 37 reads 02 with the lamp: it is the intervention. It stays 00: the 02 seconds are "
                            + "something else.",
                    RESULTS_NONE),
            new Test("imu_rate1", MEANING, "The first IMU rotation rate",
                    "Of the three 0x4B rotation rates, yaw and roll are identified; the first (imu_ay_psip1) "
                            + "follows neither the lean, its change, speed, braking nor yaw. It could be the pitch "
                            + "rate.",
                    "1. Bike data card: Capture frames on, connect again, engine on, bike standing.\n"
                            + "2. Push the front down hard 5 times, mark, wait 15 s, then the rear 5 times, mark.\n"
                            + "3. Standing on a known slope, nose up then nose down, 30 s each.",
                    "Rate 1 moves with the bounces and not the other two: it is pitch. Its size against the "
                            + "slope also gives the scale none of the three rates has yet.",
                    RESULTS_NONE),
            new Test("dash_values", MEANING, "Values against the dash over a ride",
                    "The bike data's scaling comes from the 0x40 nibbles and has not been compared with a dash over "
                            + "a whole ride.",
                    "1. Record a ride.\n"
                            + "2. At three or four stops, photograph the dash (odometer, trips, fuel, range, "
                            + "temperatures, battery) and the app's Bike tab at the same moment.",
                    "Every value matches the dash at every stop (whole units, the dash's own rounding).",
                    RESULTS_NONE),
            new Test("unknown_bytes", PROTOCOL, "Bytes nobody decodes",
                    "0x30 and the unused bytes of the other frames may carry indicators, high beam, clutch, side "
                            + "stand and the like. Rideology reads none of them.",
                    "1. Bike data card: Capture frames on, connect again.\n"
                            + "2. One thing at a time, 15 s or more apart: pick it under Test mark, do it, tap Mark "
                            + "now (indicators, high beam, clutch, side stand, a riding mode changed on the dash…).\n"
                            + "3. Pull the capture and compare the bytes before and after each mark.",
                    "A byte steady before a mark and changed right after it, every time, is that control.",
                    RESULTS_NONE),
            new Test("init_08", PROTOCOL, "What 0x08 does",
                    "Rideology sends 0x08 once per connect with 120 / 1000 / 200 / 100. They may be the periods of "
                            + "the 0x45/0x4A/0x4B streams; whether the dash needs it at all is not known.",
                    "1. Bike data card: Requests after connecting → Read-only, Capture frames on, connect, "
                            + "2 minutes with the engine running.\n"
                            + "2. The same with Full (it sends 0x08).\n"
                            + "3. Compare how often 0x45, 0x4A and 0x4B arrive in the two captures.",
                    "The same rates both times: 0x08 is not needed for the streams. Different: its numbers are "
                            + "periods, and the data could come faster than once a second.",
                    RESULTS_NONE),
            new Test("ancs", PROTOCOL, "Dash notification categories",
                    "Rideology only sends categories 1 (call) and 6 (mail) in 0x12. Whether a dash shows an icon "
                            + "for the others is not known.",
                    "1. Bike data card → Dash notification test: show one category, look at the dash, clear it.\n"
                            + "2. Repeat for each category other than 1 and 6.",
                    "The list of categories the dash shows something for, and what it shows.",
                    RESULTS_NONE));
}
