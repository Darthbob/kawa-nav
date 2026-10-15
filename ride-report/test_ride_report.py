#!/usr/bin/env python3
"""Tests for ride_report.py: plain asserts, no pytest, no fixtures on disk.

Run it with `python test_ride_report.py` (or `python3`). Every fixture is built here, never a real
ride: an exported ride holds the GPS track of whoever was riding.
"""

import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ride_report as rr

FAILURES = []


def check(name, condition, detail=""):
    if condition:
        print("  ok   %s" % name)
    else:
        print("  FAIL %s %s" % (name, detail))
        FAILURES.append(name)


def near(a, b, tol=0.02):
    return a is not None and abs(a - b) <= tol


# ------------------------------------------------------------------ fixtures built here

def full_csv(header, rows, name="Ride test", comment=True):
    """A "CSV (all values)" file: optional comment, tab-separated header, one row per second."""
    lines = []
    if comment:
        lines.append("# %s 2026-10-15 17:28 · 1.00 km · 1 min · every value KawaNav recorded" % name)
    lines.append("\t".join(header))
    for row in rows:
        lines.append("\t".join("" if v is None else str(v) for v in row))
    return "\n".join(lines) + "\n"


def write(directory, filename, text, encoding="utf-8"):
    path = os.path.join(directory, filename)
    with open(path, "wb") as f:
        f.write(text.encode(encoding))
    return path


def ride_with(directory, samples, filename="ride.csv"):
    """A ride from a list of dicts; the header is whatever keys the samples use."""
    header = []
    for s in samples:
        for k in s:
            if k not in header:
                header.append(k)
    rows = [[s.get(k) for k in header] for s in samples]
    return rr.load(write(directory, filename, full_csv(header, rows)))


def seconds(n, **cols):
    """n samples with the same values, plus t_ms counting up."""
    return [dict(t_ms=i * 1000, **cols) for i in range(n)]


# ------------------------------------------------------------------ reading


def test_reading(tmp):
    print("reading")

    ride = ride_with(tmp, [{"t_ms": 0, "kmh": 50, "rpm": 3000}, {"t_ms": 1000, "kmh": 60, "rpm": 4000}])
    check("full CSV: rows", len(ride) == 2)
    check("full CSV: a value", ride.col("kmh") == [50.0, 60.0])

    # an empty cell is "the bike did not send it", not a zero
    empty = ride_with(tmp, [{"t_ms": 0, "kmh": 0, "rpm": None}], "empty.csv")
    check("empty cell is None, not 0", empty.col("rpm") == [None] and empty.col("kmh") == [0.0])
    check("empty cell is not 'sent'", not empty.has("rpm") and empty.has("kmh"))

    # the app's own file: a magic line, then the same header
    raw = write(tmp, "raw.tsv", rr.MAGIC + "\n" + full_csv(["t_ms", "kmh"], [[0, 42]], comment=False), "utf-8")
    check("raw file: magic line", rr.load(raw).col("kmh") == [42.0])

    # Rideology's export: seven metadata rows, quoted header, Shift-JIS, N for neutral
    rodeology = ('UserAccount Name,\nTitle,Ride 15 ott 17:28\nComment,\nVehicle Nickname,KawaNav\n'
                 'Distance(km),13.0\nTime(min),24\nR.Score,\n'
                 '"elapsed_msec","gps_latitude","gps_longitude","instant_fuel_consumption",'
                 '"water_temperature","boost_temperature","engine_RPM","wheel_speed","acceleration",'
                 '"throttle_position","boost_pressure","gear_position","lean_angle","rideology_score"\n'
                 '0,45.001,7.001,4.8,85,,4000,60,0.10,30,,N,-12.0,\n'
                 '1000,45.002,7.002,5.0,86,,4100,62,0.12,31,,3,-14.0,\n')
    path = write(tmp, "Rideology.csv", rodeology, "shift_jis")
    rideology = rr.load(path)
    check("Rideology CSV: name from the Title row", rideology.name == "Ride 15 ott 17:28", rideology.name)
    check("Rideology CSV: aliased columns", rideology.col("kmh") == [60.0, 62.0]
          and rideology.col("lean_deg") == [-12.0, -14.0])
    check("Rideology CSV: gear N is neutral", rideology.col("gear") == [0.0, 3.0])
    check("Rideology CSV: an absent boost column is no value", rideology.col("boost_temperature") == [None, None])

    # anything else is refused
    other = write(tmp, "notes.txt", "hello\nworld\n")
    try:
        rr.load(other)
        check("a file that is not a ride is refused", False, "no exception")
    except ValueError:
        check("a file that is not a ride is refused", True)


# ------------------------------------------------------------------ the values


def test_metrics(tmp):
    print("summary")

    ride = ride_with(tmp, [{"t_ms": i * 1000, "rpm": 1000 + i, "water_c": 80, "oil_c": None}
                           for i in range(4)])
    by_key = {m.key: m for m in rr.metrics_of(ride)}
    rpm = by_key["rpm"]
    check("min/mean/max", (rpm.min, rpm.mean, rpm.max, rpm.n) == (1000, 1001.5, 1003, 4),
          (rpm.min, rpm.mean, rpm.max, rpm.n))
    check("a channel the bike never sent is not known", not by_key["oil_c"].known)
    check("a real zero is a value", rr.Metric("x", "x", "Engine", "", 0).n == 0)

    zero = ride_with(tmp, [{"t_ms": 0, "thr_pct": 0}, {"t_ms": 1000, "thr_pct": 0}], "zero.csv")
    thr = {m.key: m for m in rr.metrics_of(zero)}["thr_pct"]
    check("a zero channel is known and zero", thr.known and thr.max == 0)

    # the IMU pair is mounted at 45 degrees: the numbers are the ones measured on a ride
    check("imu forward at rest", near(rr.imu_forward(-0.70, -0.71), 0.0))
    check("imu up at rest is one g", near(rr.imu_up(-0.70, -0.71), 1.0))
    check("imu forward accelerating", near(rr.imu_forward(-0.51, -1.09), 0.41))
    check("imu up accelerating", near(rr.imu_up(-0.51, -1.09), 1.13))
    check("imu forward braking", near(rr.imu_forward(-1.21, -0.24), -0.69))
    check("imu with no value stays absent", rr.imu_forward(None, -0.5) is None)


def test_facts(tmp):
    print("times")

    # the 0x4B pair moves, the 0x4A nibbles do not: either counts as the reading being active
    samples = seconds(6, tcs=0, tcs_hb=0, tcs_lb=0, abs=0, wheelie_flag=0, cruise_on=0)
    samples[3].update(tcs_hb=1, tcs_lb=7)
    facts = rr.facts_of(ride_with(tmp, samples, "facts.csv"))
    check("traction control counts the seconds it moved", facts["tcs"][1] == 1, facts["tcs"])
    check("ABS present but never: zero, not absent", facts["abs"][1] == 0 and facts["abs"][2])
    check("cruise present but never: zero", facts["cruise"][1] == 0)

    without = rr.facts_of(ride_with(tmp, seconds(3, kmh=30), "no-tcs.csv"))
    check("a channel the bike does not send is not evaluated", without["tcs"][1] is None
          and not without["tcs"][2])


# ------------------------------------------------------------------ events


def test_events(tmp):
    print("events")

    def kinds(samples, name="events.csv", **opts):
        ride = ride_with(tmp, samples, name)
        return ride, [e.kind for e in rr.find_events(ride, rr.parse_args(["x", "--events"] +
                                                                        ["--%s" % k for k in opts]))]

    # a wheel spinning faster than the other: three seconds of it, then quiet
    spin = seconds(8, wheel_f_kmh=50, wheel_r_kmh=50, kmh=50)
    for i in (3, 4, 5):
        spin[i]["wheel_r_kmh"] = 56
    ride, events = kinds(spin, "spin.csv")
    check("wheel slip is found", events.count("wheelspin") == 1, events)
    slip = [e for e in rr.find_events(ride, rr.parse_args(["x", "--events"])) if e.kind == "wheelspin"][0]
    check("the slip says how long and how much", "for 3 s" in slip.title and "+6.0 km/h" in slip.detail,
          (slip.title, slip.detail))

    calm = seconds(6, wheel_f_kmh=50, wheel_r_kmh=51, kmh=50)   # 1 km/h apart: not a slip
    _, events = kinds(calm, "calm.csv")
    check("a small difference is not a slip", "wheelspin" not in events, events)

    braking = seconds(6, acc_g=0.1)
    braking[2]["acc_g"] = -0.5
    braking[3]["acc_g"] = -0.4
    _, events = kinds(braking, "brake.csv")
    check("hard braking is found and grouped", events.count("braking") == 1, events)
    gentle = seconds(5, acc_g=-0.2)
    _, events = kinds(gentle, "gentle.csv")
    check("a gentle stop is not hard braking", "braking" not in events, events)

    gears = seconds(5, gear=1, thr_pct=30)
    gears[2]["gear"] = 2
    gears[3]["thr_pct"] = 0
    gears[3]["gear"] = 1
    _, events = kinds(gears, "gears.csv")
    check("gear changes are found", events.count("shift") == 2, events)

    stops = seconds(8, kmh=40)
    for i in (2, 3, 4, 5, 6):
        stops[i]["kmh"] = 0
    _, events = kinds(stops, "stops.csv")
    check("a stop is found", events.count("stop") == 1, events)
    short = seconds(6, kmh=40)
    short[2]["kmh"] = 0
    _, events = kinds(short, "short-stop.csv")
    check("a moment at a standstill is not a stop", "stop" not in events, events)

    hot = seconds(4, water_c=95)
    hot[2]["water_c"] = 104
    _, events = kinds(hot, "hot.csv")
    check("hot coolant is found", events.count("heat") == 1, events)

    tcs = seconds(4, tcs=0)
    tcs[1]["tcs_hb"] = 1
    _, events = kinds(tcs, "tcs.csv")
    check("the traction-control reading is found", events.count("tcs") == 1, events)

    wheelie = seconds(4, wheelie_flag=0)
    wheelie[1]["wheelie_flag"] = 1
    _, events = kinds(wheelie, "wheelie.csv")
    check("the front wheel coming up is found", events.count("wheelie") == 1, events)

    # a ride with only the phone: nothing to check, and it says so
    empty_ride = ride_with(tmp, seconds(4, kmh=30, lat=45.0, lon=7.0), "gps-only.csv")
    rr.find_events(empty_ride, rr.parse_args(["x", "--events"]))
    check("a ride without bike values says what it could not check",
          any("not checked" in w or "traction" in w for w in empty_ride.warnings), empty_ride.warnings)


# ------------------------------------------------------------------ the report


def test_report(tmp):
    print("report")

    samples = seconds(10, kmh=50, rpm=4000, water_c=90, gear=3, thr_pct=30, acc_g=0.1,
                      lat=45.0, lon=7.0, wheel_f_kmh=50, wheel_r_kmh=50)
    samples[4]["rpm"] = 9000
    samples[4]["gas"] = None
    path = write(tmp, "report-ride.csv", full_csv(
        ["t_ms", "kmh", "rpm", "water_c", "gear", "thr_pct", "acc_g", "lat", "lon",
         "wheel_f_kmh", "wheel_r_kmh"],
        [[s.get(k) for k in ["t_ms", "kmh", "rpm", "water_c", "gear", "thr_pct", "acc_g", "lat", "lon",
                             "wheel_f_kmh", "wheel_r_kmh"]] for s in samples]))
    ride = rr.load(path)
    opts = rr.parse_args(["x", path])
    out = rr.html_report(ride, opts)
    check("the report names the ride", "Ride test" in out)
    check("the report has the tiles", 'class="tile"' in out and "Max engine speed" in out)
    check("the report has the table", "Average" in out and "Coolant" in out)
    check("the report has the charts", out.count("<figure class=\"chart\">") >= 3,
          out.count("<figure class=\"chart\">"))
    check("the report has the track", "leaflet" in out and "circleMarker" in out)
    check("the highest value is in the tile", "9,000 rpm" in out,
          [line for line in out.splitlines() if "tile" in line][:1])

    no_map = rr.parse_args(["x", path, "--no-map"])
    check("--no-map leaves the map out", "leaflet" not in rr.html_report(ride, no_map))

    # the command line: a good file writes a report, a bad one fails with a message
    with tempfile.TemporaryDirectory() as outdir:
        code = rr.main([path, "-o", outdir, "--events"])
        check("--events writes nothing", code == 0 and os.listdir(outdir) == [], os.listdir(outdir))
        code = rr.main([path, "-o", outdir])
        written = os.listdir(outdir)
        check("a report is written and named after the file",
              code == 0 and written == ["report-ride_report.html"], written)
        code = rr.main([os.path.join(tmp, "notes.txt"), "-o", outdir])
        check("a file that is not a ride fails", code == 1)


def main():
    with tempfile.TemporaryDirectory() as tmp:
        test_reading(tmp)
        test_metrics(tmp)
        test_facts(tmp)
        test_events(tmp)
        test_report(tmp)
    print()
    if FAILURES:
        print("%d failed: %s" % (len(FAILURES), ", ".join(FAILURES)))
        return 1
    print("all tests passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
