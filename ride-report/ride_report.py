#!/usr/bin/env python3
"""KawaNav ride report.

Reads a ride exported by KawaNav (the "CSV (all values)" export, the app's own file, or Rideology's
CSV) and writes an HTML report beside it: the summary of every channel, the time the electronics spent
working, the notable moments of the ride, charts, and the ride on an interactive map.

Standard library only: the tool needs nothing but Python 3.9+ installed. The charts are drawn as SVG
by this program and the map by ride_map.js, which sits beside it and is put whole into every report:
no library, nothing from a CDN. Only the background under the track comes from a tile server, when
the report is opened (--no-tiles leaves it out, --no-map the whole map).

KawaNav itself is Java: this tool repeats the parts of it that matter offline (the column names, the
summary, the event rules) rather than reusing them. Where the two must agree, the rule is written here
in the same words as in the app and in docs/PROTOCOL.md.
"""

import argparse
import bisect
import html
import json
import math
import os
import subprocess
import sys

# ------------------------------------------------------------------ what a ride records

# key, label, section, unit, decimals — the same channels and wording as the app's ride summary
CHANNELS = [
    ("rpm", "Engine speed", "Engine", "rpm", 0),
    ("thr_pct", "Throttle", "Engine", "%", 0),
    ("susp_f_stroke_mm", "Front suspension", "Chassis and suspension", "mm", 1),
    ("susp_r_stroke_mm", "Rear suspension", "Chassis and suspension", "mm", 1),
    ("acc_g", "Acceleration (the bike's own)", "Chassis and suspension", "g", 2),
    ("wheelie_deg", "Wheelie angle", "Chassis and suspension", "°", 1),
    ("tyre_f_kpa", "Front tyre", "Chassis and suspension", "bar", 2),
    ("tyre_r_kpa", "Rear tyre", "Chassis and suspension", "bar", 2),
    ("brake_f_bar", "Front brake", "Brakes and electronics", "bar", 1),
    ("battery_v", "Battery", "Brakes and electronics", "V", 2),
    ("water_c", "Coolant", "Temperatures", "°C", 0),
    ("oil_c", "Oil", "Temperatures", "°C", 0),
    ("intake_c", "Intake air", "Temperatures", "°C", 0),
    ("outside_c", "Outside", "Temperatures", "°C", 0),
    ("gps_acc_m", "GPS accuracy", "Data quality", "m", 1),
    ("alt_m", "Altitude", "Data quality", "m", 0),
    # derived from the IMU pair (see imu_forward/up/lateral below)
    ("imu_forward", "Acceleration, forward", "Chassis and suspension", "g", 2),
    ("imu_up", "Acceleration, up", "Chassis and suspension", "g", 2),
    ("imu_side", "Acceleration, sideways", "Chassis and suspension", "g", 2),
]

# Some values are stored in one unit and read in another, as in the app.
SCALE = {"tyre_f_kpa": 0.01, "tyre_r_kpa": 0.01}

SECTION_ORDER = ["Engine", "Chassis and suspension", "Brakes and electronics", "Temperatures",
                 "Data quality"]

# The four "levels, not measurements": what the app counts is the time they were working.
FACTS = [
    ("tcs", "Traction control (unverified reading)", ["tcs", "tcs_hi", "tcs_hb", "tcs_lb"]),
    ("abs", "ABS", ["abs"]),
    ("wheelie", "Front wheel up", ["wheelie_flag"]),
    ("cruise", "Cruise control", ["cruise_on"]),
]

# Rideology's CSV uses other names for the same values.
ALIASES = {
    "elapsed_msec": "t_ms", "gps_latitude": "lat", "gps_longitude": "lon",
    "instant_fuel_consumption": "fuel_inst", "water_temperature": "water_c",
    "engine_RPM": "rpm", "wheel_speed": "kmh", "acceleration": "acc_g",
    "throttle_position": "thr_pct", "gear_position": "gear", "lean_angle": "lean_deg",
}


MAGIC = "#kawanav-ride 1"
COS45 = math.sqrt(0.5)


def imu_forward(x, y):
    """The bike's forward acceleration from the IMU pair, which is mounted at 45 degrees."""
    return None if x is None or y is None else (x - y) * COS45


def imu_up(x, y):
    """The bike's upward acceleration from the same pair; it reads 1 g at a standstill."""
    return None if x is None or y is None else -(x + y) * COS45


# ------------------------------------------------------------------ reading a ride


class Ride:
    """One ride: the values per second, and what can be worked out from them."""

    def __init__(self, path, name, sample_rows, header):
        self.path = path
        self.name = name
        self.rows = sample_rows
        self.header = header
        self.warnings = []

    def __len__(self):
        return len(self.rows)

    def col(self, key):
        """One column, with each cell as a float or None (None = the bike did not send it)."""
        out = []
        for r in self.rows:
            v = r.get(key)
            if v is not None and key in SCALE:
                v = v * SCALE[key]
            out.append(v)
        return out

    def has(self, key):
        return any(r.get(key) is not None for r in self.rows)

    def first(self, key):
        for r in self.rows:
            if r.get(key) is not None:
                return r[key]
        return None

    def last(self, key):
        for r in reversed(self.rows):
            if r.get(key) is not None:
                return r[key]
        return None

    # --- derived values

    def forward(self):
        """Forward acceleration: the bike's own when it sends one, else the turned IMU pair."""
        own = self.col("acc_g")
        if any(v is not None for v in own):
            return own
        x, y = self.col("imu_ax_g"), self.col("imu_ay_g")
        return [imu_forward(a, b) for a, b in zip(x, y)]

    def t_ms(self):
        return self.col("t_ms")

    def seconds(self):
        """The time of every row in ms, as recorded (a row with no time is a second after the one before)."""
        t = self.t_ms()
        if not t or t[0] is None:
            return [i * 1000.0 for i in range(len(self.rows))]
        out = []
        for v in t:
            out.append(v if v is not None else out[-1] + 1000.0)
        return out

    def distance_m(self):
        """The distance the app would report: the bike's odometer when there is one, else the GPS."""
        d = self.odo_delta_m()
        if d > 0:
            return d, "odometer"
        return self.gps_distance_m(), "GPS"

    def odo_delta_m(self):
        odo = self.col("odo_km")
        total, prev = 0.0, None
        for v in odo:
            if v is None:
                continue
            if prev is not None and v >= prev:
                total += (v - prev) * 1000
            prev = v
        return total

    def gps_distance_m(self):
        total, prev = 0.0, None
        for r in self.rows:
            lat, lon = r.get("lat"), r.get("lon")
            if lat is None or lon is None:
                continue
            if prev is not None:
                d = haversine_m(prev[0], prev[1], lat, lon)
                acc = r.get("gps_acc_m")
                if d < 500 and (acc is None or acc < 25):
                    total += d
            prev = (lat, lon)
        return total

    def duration_ms(self):
        t = [v for v in self.t_ms() if v is not None]
        if len(t) >= 2:
            return t[-1] - t[0]
        return (len(self.rows) - 1) * 1000.0

    def fuel_l(self):
        fuel = self.col("fuel_total_l")
        total, prev = 0.0, None
        for v in fuel:
            if v is None:
                continue
            if prev is not None and v >= prev:
                total += v - prev
            prev = v
        return total


def haversine_m(lat1, lon1, lat2, lon2):
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = math.radians(lat2 - lat1), math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def read_text(path):
    """A ride file as text: ours are UTF-8, Rideology's export is Shift-JIS."""
    raw = open(path, "rb").read()
    for enc in ("utf-8", "shift_jis"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", "replace")


def load(path):
    """The ride in a file, or ValueError when it is not one."""
    text = read_text(path)
    lines = text.splitlines()
    if not lines:
        raise ValueError("%s is empty" % path)

    first = lines[0].strip()
    if first.startswith(MAGIC) or (first.startswith("#") and "\t" in lines[1] if len(lines) > 1 else False):
        # our own file, or the full CSV: an optional comment line, then the header
        start = 1 if first.startswith("#") else 0
        header = [h.strip() for h in lines[start].split("\t")]
        rows = parse_rows(lines[start + 1:], header, "\t")
        name = first.lstrip("# ").split(" 20")[0].strip() if first.startswith("#") else os.path.basename(path)
        return Ride(path, name or os.path.basename(path), rows, header)

    if first.startswith("UserAccount Name") or first.startswith('"elapsed_msec"'):
        # Rideology's export: seven metadata rows, then the quoted header
        head_at = next((i for i, l in enumerate(lines) if '"elapsed_msec"' in l
                        or l.startswith("elapsed_msec")), None)
        if head_at is None:
            raise ValueError("%s looks like Rideology's CSV but has no header" % path)
        header = [h.strip().strip('"') for h in lines[head_at].split(",")]
        header = [ALIASES.get(h, h) for h in header]
        rows = []
        for line in lines[head_at + 1:]:
            if not line.strip():
                continue
            cells = line.split(",")
            row = {}
            for i, key in enumerate(header):
                raw = cells[i].strip().strip('"') if i < len(cells) else ""
                row[key] = gear_value(raw) if key == "gear" else number(raw)
            rows.append(row)
        title = next((l.split(",", 1)[1] for l in lines if l.startswith("Title,")), "")
        return Ride(path, title.strip() or os.path.basename(path), rows, header)

    raise ValueError("%s is not a KawaNav or Rideology ride file (the first line is %r)"
                     % (os.path.basename(path), first[:60]))


def parse_rows(lines, header, sep):
    rows = []
    for line in lines:
        if not line.strip():
            continue
        cells = line.split(sep)
        rows.append({k: number(cells[i]) if i < len(cells) else None for i, k in enumerate(header)})
    return rows


def number(cell):
    """A cell as a number, or None: an empty cell means the bike did not send that value (and so does
    one that is not a finite number, which no channel can be)."""
    cell = cell.strip()
    if not cell:
        return None
    try:
        v = float(cell)
    except ValueError:
        return None
    return v if math.isfinite(v) else None


def gear_value(cell):
    """Rideology writes the gear as N, a number, or Error."""
    if cell in ("", "Error"):
        return None
    if cell.upper() == "N":
        return 0.0
    return number(cell)


# ------------------------------------------------------------------ what the ride came to


class Metric:
    def __init__(self, key, label, section, unit, decimals):
        self.key, self.label, self.section, self.unit, self.decimals = key, label, section, unit, decimals
        self.min = self.mean = self.max = None
        self.n = 0
        self.sum = 0.0

    def add(self, v):
        if v is None:
            return
        self.min = v if self.min is None else min(self.min, v)
        self.max = v if self.max is None else max(self.max, v)
        self.n += 1
        self.sum += v
        self.mean = self.sum / self.n

    @property
    def known(self):
        return self.n > 0

    def value(self, which):
        return getattr(self, which) if self.known else None


def metrics_of(ride):
    out = []
    for key, label, section, unit, decimals in CHANNELS:
        m = Metric(key, label, section, unit, decimals)
        if key == "imu_forward":
            x, y = ride.col("imu_ax_g"), ride.col("imu_ay_g")
            for a, b in zip(x, y):
                m.add(imu_forward(a, b))
        elif key == "imu_up":
            x, y = ride.col("imu_ax_g"), ride.col("imu_ay_g")
            for a, b in zip(x, y):
                m.add(imu_up(a, b))
        elif key == "imu_side":
            for v in ride.col("imu_az_g"):
                m.add(v)
        else:
            for v in ride.col(key):
                m.add(v)
        out.append(m)
    return out


def facts_of(ride):
    """Seconds the electronics worked, and whether the ride has the channels at all."""
    out = {}
    for key, label, cols in FACTS:
        present = [c for c in cols if ride.has(c)]
        if not present:
            out[key] = (label, None, False)      # not evaluated: the bike does not send it
            continue
        seconds = 0
        for r in ride.rows:
            if any((r.get(c) or 0) > 0 for c in cols):
                seconds += 1
        out[key] = (label, seconds, True)
    return out


# ------------------------------------------------------------------ the notable moments

# Every kind of moment, in the order of its colour in the report's palette (the CSS gives each one
# as --k-<kind>): its name on the map, the letter on its marker, and whether the map shows it before
# the reader asks. Gear changes come by the dozen, so they are there but hidden until asked for.
EVENT_STYLE = [
    ("shift", "Gear change", "G", False),
    ("braking", "Hard braking", "B", True),
    ("stop", "Stopped", "S", True),
    ("wheelspin", "Wheel slip", "W", True),
    ("tcs", "Traction control", "T", True),
    ("wheelie", "Front wheel up", "F", True),
    ("abs", "ABS", "A", True),
    ("heat", "Coolant hot", "H", True),
]
GLYPHS = {kind: letter for kind, _, letter, _ in EVENT_STYLE}


class Event:
    def __init__(self, kind, index, seconds, title, detail, lat=None, lon=None):
        self.kind, self.index, self.seconds = kind, index, seconds
        self.title, self.detail = title, detail
        self.lat, self.lon = lat, lon

    @property
    def time(self):
        m, s = divmod(int(round(self.seconds)), 60)
        return "%d:%02d" % (m, s)


def find_events(ride, opts):
    """The moments worth looking at, each with the values that triggered it."""
    events = []
    rows = ride.rows
    t = ride.seconds()
    kmh = ride.col("kmh")
    rpm = ride.col("rpm")
    gear = ride.col("gear")
    lean = ride.col("lean_deg")
    front, rear = ride.col("wheel_f_kmh"), ride.col("wheel_r_kmh")
    forward = ride.forward()

    def where(i):
        return (rows[i].get("lat"), rows[i].get("lon"))

    def context(i):
        bits = []
        if i < len(kmh) and kmh[i] is not None:
            bits.append("%.0f km/h" % kmh[i])
        if i < len(rpm) and rpm[i] is not None:
            bits.append("{:,.0f} rpm".format(rpm[i]))
        if i < len(gear) and gear[i] is not None:
            bits.append("gear %s" % ("N" if gear[i] == 0 else int(gear[i])))
        if i < len(lean) and lean[i] is not None:
            bits.append("%d° lean" % round(lean[i]))        # round(), so a hair to the left is not "-0°"
        return " · ".join(bits)

    def run(kind, hits, title, describe):
        """Groups consecutive seconds into one event, so a 3 s slide is one line, not three."""
        i = 0
        while i < len(hits):
            if not hits[i]:
                i += 1
                continue
            j = i
            while j + 1 < len(hits) and hits[j + 1]:
                j += 1
            lat, lon = where(i)
            events.append(Event(kind, i, t[i] / 1000.0, title(j - i + 1),
                                context(i) + (" · " + describe(i, j) if describe else ""), lat, lon))
            i = j + 1

    # slip between the two filtered wheel speeds (0x4B): the sign of a wheel breaking traction
    if ride.has("wheel_f_kmh") and ride.has("wheel_r_kmh"):
        diff = [None if a is None or b is None else b - a for a, b in zip(front, rear)]
        hits = [d is not None and abs(d) >= opts.slip for d in diff]
        run("wheelspin", hits, lambda n: "Wheel slip for %d s" % n,
            lambda a, b: "rear %+.1f km/h against the front" % max(diff[a:b + 1],
                                                                  key=lambda v: abs(v) if v else 0))
    else:
        ride.warnings.append("no filtered wheel speeds: wheel slip not checked")

    # braking, from whichever forward acceleration the ride has
    if any(v is not None for v in forward):
        hits = [v is not None and v <= -opts.brake for v in forward]
        run("braking", hits, lambda n: "Hard braking for %d s" % n,
            lambda a, b: "%.2f g" % min(v for v in forward[a:b + 1] if v is not None))
    else:
        ride.warnings.append("no acceleration: braking not checked")

    # the front wheel coming up, as the bike reports it
    flags = ride.col("wheelie_flag")
    angles = ride.col("wheelie_deg")
    if any(v is not None for v in flags) or any(v is not None for v in angles):
        hits = [((flags[i] or 0) > 0) or ((angles[i] or 0) >= opts.wheelie) for i in range(len(rows))]
        run("wheelie", hits, lambda n: "Front wheel up for %d s" % n,
            lambda a, b: "up to %.0f°" % max([v for v in angles[a:b + 1] if v is not None] or [0]))
    else:
        ride.warnings.append("no wheelie flag: wheelies not checked")

    # gear changes, with the throttle telling whether it was a shift or a clutch
    if ride.has("gear"):
        for i in range(1, len(rows)):
            g0, g1 = gear[i - 1], gear[i]
            if g0 is None or g1 is None or g0 == g1:
                continue
            thr = rows[i].get("thr_pct")
            how = "on the throttle" if (thr or 0) > 5 else "throttle closed"
            lat, lon = where(i)
            events.append(Event("shift", i, t[i] / 1000.0,
                                "Gear %s → %s" % ("N" if g0 == 0 else int(g0), "N" if g1 == 0 else int(g1)),
                                context(i) + " · " + how, lat, lon))
    else:
        ride.warnings.append("no gear: gear changes not checked")

    # the traction-control readings (unverified) and the ABS state
    tcs_cols = [c for c in ("tcs", "tcs_hi", "tcs_hb", "tcs_lb") if ride.has(c)]
    if tcs_cols:
        hits = [any((r.get(c) or 0) > 0 for c in tcs_cols) for r in rows]
        run("tcs", hits, lambda n: "Traction control reading active for %d s" % n,
            lambda a, b: "reading: " + ", ".join("%s=%g" % (c, rows[a].get(c) or 0) for c in tcs_cols))
    else:
        ride.warnings.append("no traction-control column: not checked")

    abs_col = ride.col("abs")
    if any(v is not None for v in abs_col):
        hits = [v is not None and (int(v) & 0x03) != 0 for v in abs_col]
        run("abs", hits, lambda n: "ABS active for %d s" % n,
            lambda a, b: "bits %02X" % int(abs_col[a]))
    else:
        ride.warnings.append("no ABS byte: not checked")

    # standing still for a while
    if any(v is not None for v in kmh):
        hits = [v is not None and v <= 0.5 for v in kmh]
        i = 0
        while i < len(hits):
            if not hits[i]:
                i += 1
                continue
            j = i
            while j + 1 < len(hits) and hits[j + 1]:
                j += 1
            if (j - i + 1) >= opts.stop:
                lat, lon = where(i)
                events.append(Event("stop", i, t[i] / 1000.0, "Stopped for %d s" % (j - i + 1),
                                    "engine %s rpm" % (int(rpm[i]) if rpm[i] is not None else "?"), lat, lon))
            i = j + 1
    else:
        ride.warnings.append("no speed: stops not checked")

    # the engine getting hot
    cool = ride.col("water_c")
    if any(v is not None for v in cool):
        hot = [v is not None and v >= opts.hot for v in cool]
        run("heat", hot, lambda n: "Coolant hot for %d s" % n,
            lambda a, b: "up to %.0f °C" % max(v for v in cool[a:b + 1] if v is not None))
    else:
        ride.warnings.append("no coolant temperature: heat not checked")

    return sorted(events, key=lambda e: e.seconds)


# ------------------------------------------------------------------ the report


def fmt(v, decimals, unit=""):
    if v is None:
        return "–"
    out = format(v, ",.%df" % decimals)
    return out + (" " + unit if unit else "")


def hhmmss(ms):
    s = int(round(ms / 1000.0))
    h, rem = divmod(s, 3600)
    m, sec = divmod(rem, 60)
    return "%d h %02d min" % (h, m) if h else ("%d min %02d s" % (m, sec) if m else "%d s" % sec)


def stretch(n, limit):
    """How many seconds one stretch of a chart covers when a ride has more seconds than a chart draws."""
    return max(1, math.ceil(n / max(1, limit // 2)))


def chart_points(values, limit):
    """The seconds a chart draws: every one, or on a long ride the lowest and the highest of each
    stretch, so thinning the line never loses a peak."""
    known = [i for i, v in enumerate(values) if v is not None]
    if len(known) <= limit:
        return known
    per = stretch(len(known), limit)
    out = []
    for s in range(0, len(known), per):
        chunk = known[s:s + per]
        out.extend(sorted({min(chunk, key=values.__getitem__), max(chunk, key=values.__getitem__)}))
    return out


def svg_chart(ride, metric, t_ms, limit, width=980, height=170):
    """One value over the ride, with the traction-control and ABS seconds marked along the bottom.

    The svg carries where its plot is (data-left, data-w) so the map's script can draw the cursor on it
    and read the value under the pointer."""
    values = series_of(ride, metric.key)
    if not any(v is not None for v in values):
        return ""
    known = [v for v in values if v is not None]
    lo, hi = min(known), max(known)
    if hi == lo:
        hi, lo = lo + 1, lo - 1
    pad = (hi - lo) * 0.08
    lo, hi = lo - pad, hi + pad
    total = max(1.0, t_ms[-1] - t_ms[0]) if t_ms else 1.0
    left, top, w, h = 62, 12, width - 78, height - 34

    def x(i):
        return left + w * ((t_ms[i] - t_ms[0]) / total)

    def y(v):
        return top + h - (v - lo) / (hi - lo) * h

    points = [(x(i), y(values[i])) for i in chart_points(values, limit)]
    path = " ".join("%s%.1f,%.1f" % ("M" if k == 0 else "L", px, py) for k, (px, py) in enumerate(points))
    marks = tcs_abs_marks(ride, t_ms, x, top, h)
    label = "%s  (%s)" % (metric.label, metric.unit) if metric.unit else metric.label
    return f'''<figure class="chart">
<figcaption><b>{html.escape(label)}</b> <span class="dim">lowest {fmt(metric.min, metric.decimals)} ·
average {fmt(metric.mean, metric.decimals)} · highest {fmt(metric.max, metric.decimals)}
{html.escape(metric.unit)}</span><span class="at"></span></figcaption>
<svg viewBox="0 0 {width} {height}" preserveAspectRatio="none" data-key="{html.escape(metric.key)}"
 data-left="{left}" data-w="{w}" data-vw="{width}" data-decimals="{metric.decimals}" data-unit="{html.escape(metric.unit)}">
<rect x="{left}" y="{top}" width="{w}" height="{h}" class="plot"/>
{gridlines(left, top, w, h)}
<text x="{left - 8}" y="{top + 10}" class="axis" text-anchor="end">{fmt(hi, metric.decimals)}</text>
<text x="{left - 8}" y="{top + h}" class="axis" text-anchor="end">{fmt(lo, metric.decimals)}</text>
{marks}
<path d="{path}" class="line"/>
<line class="cursor" x1="{left}" y1="{top}" x2="{left}" y2="{top + h}" visibility="hidden"/>
<text x="{left}" y="{height - 4}" class="axis">{hhmmss(0)}</text>
<text x="{left + w}" y="{height - 4}" class="axis" text-anchor="end">{hhmmss(total)}</text>
</svg></figure>'''


def series_of(ride, key):
    if key == "imu_forward":
        x, y = ride.col("imu_ax_g"), ride.col("imu_ay_g")
        return [imu_forward(a, b) for a, b in zip(x, y)]
    if key == "imu_up":
        x, y = ride.col("imu_ax_g"), ride.col("imu_ay_g")
        return [imu_up(a, b) for a, b in zip(x, y)]
    if key == "imu_side":
        return ride.col("imu_az_g")
    return ride.col(key)


def gridlines(left, top, w, h):
    out = []
    for k in range(1, 4):
        y = top + h * k / 4
        out.append('<line x1="%d" y1="%.1f" x2="%d" y2="%.1f" class="grid"/>' % (left, y, left + w, y))
    return "\n".join(out)


def tcs_abs_marks(ride, t_ms, x, top, h):
    """Along the bottom: orange where the traction-control reading moved, red where ABS did."""
    out = []
    tcs_cols = [c for c in ("tcs", "tcs_hi", "tcs_hb", "tcs_lb") if ride.has(c)]
    for i, r in enumerate(ride.rows):
        tcs = any((r.get(c) or 0) > 0 for c in tcs_cols)
        absv = r.get("abs")
        abs_on = absv is not None and (int(absv) & 0x03) != 0
        if not tcs and not abs_on:
            continue
        colour = "marker-abs" if abs_on else "marker-tcs"
        out.append('<line x1="%.1f" y1="%.1f" x2="%.1f" y2="%.1f" class="%s"/>'
                   % (x(i), top + h - 8, x(i), top + h, colour))
    return "\n".join(out)


def charted(metric):
    """Every value the ride has gets a chart, except GPS accuracy: it is about the track, not the ride."""
    return metric.known and metric.key != "gps_acc_m"


# ------------------------------------------------------------------ the map

# The backgrounds the map can draw under the track. They were chosen because they answer a report
# opened from disk, which sends no referer: OpenStreetMap's own tile servers refuse that ("Access
# blocked") and CARTO's now ask for an API key. Each is asked only for the squares in view, when the
# report is opened; with --no-tiles the report names no server at all.
MAP_LAYERS = [
    {"id": "map", "name": "Map", "source": "OpenStreetMap's openstreetmap.de tile server",
     "url": "https://tile.openstreetmap.de/{z}/{x}/{y}.png", "max": 19, "dark": False,
     "credit": '© <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">'
               'OpenStreetMap</a> contributors'},
    {"id": "satellite", "name": "Satellite", "source": "Esri's World Imagery",
     "url": "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
     "max": 19, "dark": True,
     "credit": "Imagery © Esri, Maxar, Earthstar Geographics and the GIS User Community"},
    {"id": "topo", "name": "Topographic", "source": "OpenTopoMap",
     "url": "https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png", "sub": "abc", "max": 17, "dark": False,
     "credit": '© <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">'
               'OpenStreetMap</a> contributors, SRTM · style © <a href="https://opentopomap.org" '
               'target="_blank" rel="noopener">OpenTopoMap</a> (CC-BY-SA)'},
]

# The script that draws the map: it lives beside this file and goes whole into every report.
MAP_SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ride_map.js")

# A fix the phone itself says is this many metres off or worse is left out of the track.
MAX_FIX_ERROR_M = 50.0


def map_data(ride, events, opts, metrics):
    """What ride_map.js draws: the track, the values of every second, the moments, the backgrounds.

    None when the ride has no track. Every series has one value per row (None where the bike sent
    nothing), so a second is the same index on the map, on the strip under it and in every chart."""
    rows = ride.rows
    fixes = [i for i, r in enumerate(rows)
             if r.get("lat") is not None and r.get("lon") is not None
             and (r.get("gps_acc_m") is None or r["gps_acc_m"] <= MAX_FIX_ERROR_M)]
    if len(fixes) < 2:
        return None
    t = ride.seconds()
    lat, lon = [None] * len(rows), [None] * len(rows)
    for i in fixes:
        lat[i], lon[i] = round(rows[i]["lat"], 6), round(rows[i]["lon"], 6)

    series = {}

    def keep(key, values, decimals):
        if any(v is not None for v in values):
            series[key] = [None if v is None else (round(v, decimals) if decimals else int(round(v)))
                           for v in values]

    keep("speed", ride.col("kmh" if ride.has("kmh") else "gps_kmh"), 0)
    for key in ("gear", "rpm", "thr_pct", "lean_deg", "alt_m"):
        keep(key, ride.col(key), 0)
    keep("forward", ride.forward(), 2)
    for m in metrics:
        if charted(m):
            keep(m.key, series_of(ride, m.key), m.decimals)

    # what the track can be coloured by, and what the cursor reads, as far as this ride has them
    colours = [dict(key=key, label=label, unit=unit, decimals=d, scale=scale, **extra)
               for key, label, unit, d, scale, extra in (
                   ("speed", "Speed", "km/h", 0, "seq", {"lo": 0}),
                   ("lean_deg", "Lean angle", "°", 0, "seq", {"lo": 0, "abs": True}),
                   ("thr_pct", "Throttle", "%", 0, "seq", {"lo": 0}),
                   ("rpm", "Engine speed", "rpm", 0, "seq", {}),
                   ("gear", "Gear", "", 0, "gear", {}),
                   ("forward", "Acceleration", "g", 2, "div", {"signed": True}),
                   ("alt_m", "Altitude", "m", 0, "seq", {}),
               ) if key in series]
    readout = [dict(key=key, label=label, unit=unit, decimals=d, **extra)
               for key, label, unit, d, extra in (
                   ("speed", "speed", "km/h", 0, {}),
                   ("gear", "gear", "", 0, {}),
                   ("rpm", "engine", "rpm", 0, {}),
                   ("thr_pct", "throttle", "%", 0, {}),
                   ("lean_deg", "lean", "°", 0, {"abs": True}),
                   ("forward", "forward", "g", 2, {"signed": True}),
                   ("alt_m", "altitude", "m", 0, {}),
               ) if key in series]

    def place(i):
        """Where a moment goes: its own second's fix, or the nearest one within 10 s."""
        k = bisect.bisect_left(fixes, i)
        near = min(fixes[max(0, k - 1):k + 1], key=lambda f: abs(f - i))
        return near if abs(t[near] - t[i]) <= 10000 else None

    moments = []
    for e in events:
        f = place(e.index)
        moments.append({"i": e.index, "kind": e.kind, "time": e.time, "title": e.title, "detail": e.detail,
                        "lat": None if f is None else lat[f], "lon": None if f is None else lon[f]})
    present = {e.kind for e in events}
    return {
        "t": [int(round(v - t[0])) for v in t],
        "lat": lat, "lon": lon,
        "series": series, "colours": colours, "readout": readout,
        "events": moments,
        "kinds": [{"id": kind, "label": label, "glyph": letter, "hidden": not shown}
                  for kind, label, letter, shown in EVENT_STYLE if kind in present],
        "layers": MAP_LAYERS if opts.tiles else [],
    }


def script_json(value):
    """JSON to put inside a <script> element: nothing in it can end the element early."""
    text = json.dumps(value, separators=(",", ":"), allow_nan=False)
    return text.replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")


def map_script():
    with open(MAP_SCRIPT, encoding="utf-8") as f:
        js = f.read()
    if "</script" in js.lower():
        raise ValueError("ride_map.js cannot go inside the report: it contains </script")
    return js


def map_parts(ride, events, opts, metrics):
    """The map's place in the page, and the two scripts that fill it, which go at the end of the page."""
    data = map_data(ride, events, opts, metrics)
    if data is None:
        return '<p class="dim">No GPS track in this ride.</p>', ""
    try:
        js = map_script()
    except OSError:
        return '<p class="dim">No map: ride_map.js should be beside ride_report.py.</p>', ""
    panel = ('<section class="map-panel" id="ride-map" aria-label="The ride on a map">\n'
             '<noscript><p class="dim">The map needs JavaScript; everything else in the report is here '
             'without it.</p></noscript>\n</section>')
    scripts = ('<script type="application/json" id="ride-data">%s</script>\n<script>\n%s</script>'
               % (script_json(data), js))
    return panel, scripts


# ------------------------------------------------------------------ the page

REPORT_CSS = """
:root {
  color-scheme: light dark;
  --page: #fff; --ink: #0b0b0b; --ink-2: #52514e; --muted: #898781; --line: #e1e0d9;
  --surface: #fcfcfb; --map-bg: #eef0f1; --focus: #2a78d6;
  /* one colour per kind of moment, in the palette's order (see EVENT_STYLE) */
  --k-shift: #2a78d6; --k-braking: #eb6834; --k-stop: #1baf7a; --k-wheelspin: #eda100;
  --k-tcs: #e87ba4; --k-wheelie: #008300; --k-abs: #6250d6; --k-heat: #e34948;
}
[hidden] { display: none !important; }
body { font: 15px/1.5 system-ui, sans-serif; margin: 0 auto; max-width: 1040px; padding: 24px;
       background: var(--page); color: var(--ink); }
h1 { font-size: 24px; margin: 0 0 4px; }
h2 { font-size: 18px; margin: 28px 0 8px; }
h3 { font-size: 15px; margin: 18px 0 6px; color: #666; }
.dim { color: #777; font-weight: normal; }
.tiles { display: flex; flex-wrap: wrap; gap: 10px; margin: 16px 0; }
.tile { border: 1px solid #ddd; border-radius: 10px; padding: 10px 14px; min-width: 130px; }
.tile b { display: block; font-size: 20px; }
table.data { border-collapse: collapse; width: 100%; }
table.data th { text-align: left; font-weight: 600; }
table.data td, table.data th { padding: 4px 10px 4px 0; border-bottom: 1px solid #eee; }
table.data td { text-align: right; font-variant-numeric: tabular-nums; }
table.data th:first-child { width: 40%; }
table.events th:first-child { width: 4em; }
table.events td { text-align: left; }
.ev-tcs td, .ev-wheelspin td { background: #fff6e5; }
.ev-braking td, .ev-abs td, .ev-heat td { background: #fdecea; }
tr[data-ev] { cursor: pointer; }
tr[data-ev]:hover td, tr[data-ev]:focus td { box-shadow: inset 0 0 0 999px rgba(42, 120, 214, .08); }
tr[data-ev]:focus { outline: none; }
tr.is-on td { box-shadow: inset 0 -2px 0 var(--focus); }
figure.chart { margin: 14px 0; }
figcaption { font-size: 13px; margin-bottom: 4px; }
svg { width: 100%; height: 170px; }
figure.chart svg { touch-action: pan-y; }
.plot { fill: none; stroke: #ddd; }
.grid { stroke: #eee; }
.line { fill: none; stroke: #2e86de; stroke-width: 1.6; }
.axis { font-size: 10px; fill: #888; }
.marker-tcs { stroke: #f39c12; stroke-width: 2; }
.marker-abs { stroke: #c0392b; stroke-width: 2; }
.cursor { stroke: var(--ink); stroke-width: 1; vector-effect: non-scaling-stroke; }
.at { color: var(--ink-2); font-variant-numeric: tabular-nums; }

/* the map: built by ride_map.js inside #ride-map */
.map-panel { margin: 10px 0 4px; }
.map-tools { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 16px; margin-bottom: 8px;
             font-size: 13px; }
.map-tools .push { margin-left: auto; }
.map-tools select, .map-bar select { font: inherit; font-size: 13px; padding: 3px 6px; border-radius: 6px;
                                     border: 1px solid var(--line); background: var(--surface); color: var(--ink); }
.legend { display: inline-flex; flex-wrap: wrap; align-items: center; gap: 6px; color: var(--ink-2);
          font-variant-numeric: tabular-nums; }
.legend .ramp { display: inline-flex; border-radius: 3px; overflow: hidden; }
.legend .ramp i { width: 12px; height: 10px; }
.legend .lg { display: inline-flex; align-items: center; gap: 4px; }
.legend .lg i { width: 12px; height: 10px; border-radius: 2px; }
.map-chips { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }
.chip { display: inline-flex; align-items: center; gap: 6px; padding: 2px 10px 2px 3px; border-radius: 999px;
        border: 1px solid var(--line); background: var(--surface); color: var(--ink); font: inherit;
        font-size: 12px; cursor: pointer; }
.chip .count { color: var(--muted); font-variant-numeric: tabular-nums; }
.chip[aria-pressed="false"] { color: var(--muted); }
.chip[aria-pressed="false"] .glyph { background: transparent; color: var(--muted);
                                     box-shadow: inset 0 0 0 1.5px var(--muted); }
.glyph { display: inline-grid; place-items: center; width: 18px; height: 18px; border-radius: 50%;
         background: var(--k); color: #0b0b0b; font: 700 10px/1 system-ui, sans-serif; vertical-align: -3px; }
.k-shift { --k: var(--k-shift); } .k-braking { --k: var(--k-braking); } .k-stop { --k: var(--k-stop); }
.k-wheelspin { --k: var(--k-wheelspin); } .k-tcs { --k: var(--k-tcs); } .k-wheelie { --k: var(--k-wheelie); }
.k-abs { --k: var(--k-abs); } .k-heat { --k: var(--k-heat); }
.glyph.k-wheelie, .glyph.k-abs { color: #fff; }
.map-view { position: relative; height: 480px; overflow: hidden; border-radius: 10px; background: var(--map-bg);
            touch-action: none; user-select: none; -webkit-user-select: none; cursor: grab; outline: none; }
.map-view:focus-visible { box-shadow: 0 0 0 2px var(--focus); }
.map-view.dragging { cursor: grabbing; }
.map-tiles { position: absolute; inset: 0; z-index: 0; }
.map-canvas { position: absolute; left: 0; top: 0; z-index: 1; }
.map-tiles img { position: absolute; left: 0; top: 0; max-width: none; opacity: 0; transition: opacity .25s;
                 pointer-events: none; }
.map-tiles img.on { opacity: 1; }
.map-ctl { position: absolute; left: 10px; top: 10px; z-index: 3; display: flex; flex-direction: column; gap: 6px; }
.map-ctl button { width: 32px; height: 32px; padding: 0; border-radius: 8px; border: 1px solid rgba(11, 11, 11, .12);
                  background: var(--surface); color: var(--ink); font: 600 17px/1 system-ui, sans-serif;
                  box-shadow: 0 1px 3px rgba(0, 0, 0, .18); cursor: pointer; }
.map-ctl button:hover { background: var(--line); }
.map-scale { position: absolute; left: 10px; bottom: 10px; z-index: 3; box-sizing: border-box; height: 16px;
             padding: 0 4px; border: 2px solid var(--ink); border-top: 0; font-size: 11px; line-height: 12px;
             color: var(--ink); background: color-mix(in srgb, var(--surface) 70%, transparent); white-space: nowrap; }
.map-credit { position: absolute; right: 0; bottom: 0; z-index: 3; max-width: 75%; padding: 1px 6px;
              border-top-left-radius: 6px; font-size: 10px; color: var(--ink-2);
              background: color-mix(in srgb, var(--surface) 80%, transparent); }
.map-credit a { color: inherit; }
.map-note { position: absolute; left: 50%; top: 10px; z-index: 4; transform: translateX(-50%); max-width: 80%;
            padding: 6px 12px; border-radius: 8px; font-size: 12px; text-align: center; background: var(--surface);
            color: var(--ink); box-shadow: 0 2px 8px rgba(0, 0, 0, .2); pointer-events: none; }
.map-tip { position: absolute; z-index: 5; max-width: 280px; padding: 7px 10px; border-radius: 8px; font-size: 12px;
           line-height: 1.4; background: var(--surface); color: var(--ink); border: 1px solid var(--line);
           box-shadow: 0 4px 14px rgba(0, 0, 0, .18); pointer-events: none; }
.tip-head { font-weight: 600; margin-bottom: 3px; }
.tip-grid { display: grid; grid-template-columns: auto auto; gap: 1px 10px; font-variant-numeric: tabular-nums; }
.tip-grid b { text-align: right; }
.tip-grid span, .tip-detail { color: var(--ink-2); }
.map-bar { display: flex; align-items: center; gap: 8px; margin-top: 8px; }
.map-bar .play { width: 34px; height: 30px; border-radius: 8px; border: 1px solid var(--line);
                 background: var(--surface); color: var(--ink); font-size: 13px; cursor: pointer; }
.map-strip { flex: 1; min-width: 0; height: 52px; cursor: col-resize; touch-action: pan-y; }
.map-readout { display: flex; flex-wrap: wrap; gap: 2px 14px; min-height: 22px; margin-top: 6px; font-size: 13px;
               color: var(--ink-2); font-variant-numeric: tabular-nums; }
.map-readout b { color: var(--ink); font-weight: 600; }
.map-caption { font-size: 12px; margin: 6px 0 0; }
.map-panel:fullscreen { display: flex; flex-direction: column; box-sizing: border-box; padding: 14px;
                        background: var(--page); }
.map-panel.is-max { position: fixed; inset: 0; z-index: 1000; display: flex; flex-direction: column; margin: 0;
                    padding: 14px; overflow: auto; background: var(--page); }
.map-panel:fullscreen .map-view, .map-panel.is-max .map-view { flex: 1; height: auto; min-height: 240px; }
@media (max-width: 640px) {
  body { padding: 16px; }
  .map-view { height: 62vh; }
  .map-tools .push { margin-left: 0; }
}
@media (prefers-color-scheme: dark) {
  :root {
    --page: #111; --ink: #f0efec; --ink-2: #c3c2b7; --line: #2c2c2a; --surface: #1a1a19; --map-bg: #1a1d21;
    --focus: #3987e5;
    --k-shift: #3987e5; --k-braking: #d95926; --k-stop: #199e70; --k-wheelspin: #c98500;
    --k-tcs: #d55181; --k-wheelie: #008300; --k-abs: #9085e9; --k-heat: #e66767;
  }
  .tile, table.data td, table.data th { border-color: #333; }
  .grid { stroke: #262626; } .plot { stroke: #333; } .line { stroke: #4aa3ff; }
  .ev-tcs td, .ev-wheelspin td { background: #2a2317; }
  .ev-braking td, .ev-abs td, .ev-heat td { background: #2a1a19; }
  .glyph.k-abs { color: #0b0b0b; }
  .map-ctl button { border-color: rgba(255, 255, 255, .12); }
  /* the street and the topographic maps are light: dimmed a little, so they do not glare */
  .map-view[data-layer="map"] .map-tiles, .map-view[data-layer="topo"] .map-tiles { filter: brightness(.82); }
}
"""


def html_report(ride, opts, events=None):
    if events is None:
        events = find_events(ride, opts)
    metrics = metrics_of(ride)
    facts = facts_of(ride)
    t_ms = ride.seconds()
    dist_m, dist_src = ride.distance_m()
    km = dist_m / 1000.0
    fuel = ride.fuel_l()

    tiles = []
    tiles.append(("Time", hhmmss(ride.duration_ms())))
    if km > 0:
        avg = km / (ride.duration_ms() / 3_600_000.0) if ride.duration_ms() > 0 else 0
        tiles.append(("Average", "%.0f km/h" % avg))
    top = max([v for v in ride.col("kmh") if v is not None] or [0])
    if top > 0:
        tiles.append(("Top speed", "%.0f km/h" % top))
    lean = [v for v in ride.col("lean_deg") if v is not None]
    if lean:
        tiles.append(("Max lean", "%.0f° / %.0f°" % (abs(min(lean)), max(lean))))
    rpms = [v for v in ride.col("rpm") if v is not None]
    if rpms:
        tiles.append(("Max engine speed", "{:,.0f} rpm".format(max(rpms))))
    if fuel > 0:
        tiles.append(("Fuel used", "%.1f L" % fuel))

    # --- the table, one section at a time
    table = []
    for section in SECTION_ORDER:
        mine = [m for m in metrics if m.section == section and m.known]
        if not mine:
            continue
        rows_html = "\n".join(
            "<tr><th>%s</th><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
            % (html.escape(m.label), fmt(m.min, m.decimals), fmt(m.mean, m.decimals),
               fmt(m.max, m.decimals), html.escape(m.unit))
            for m in mine)
        table.append("<h3>%s</h3><table class=\"data\"><tr><th>Value</th><th>Lowest</th>"
                     "<th>Average</th><th>Highest</th><th>Unit</th></tr>\n%s</table>" % (section, rows_html))

    fact_lines = []
    for key, _, _ in FACTS:
        label, seconds, present = facts[key]
        if not present:
            continue
        fact_lines.append("<tr><th>%s</th><td>%s</td></tr>"
                          % (html.escape(label),
                             "never" if seconds == 0 else hhmmss(seconds * 1000)))

    map_panel, map_scripts = map_parts(ride, events, opts, metrics) if opts.map else ("", "")
    on_map = bool(map_scripts)

    def event_row(k, e):
        if not on_map:
            return ("<tr class=\"ev-%s\"><td>%s</td><td><b>%s</b></td><td>%s</td></tr>"
                    % (e.kind, e.time, html.escape(e.title), html.escape(e.detail)))
        return ("<tr class=\"ev-%s\" data-ev=\"%d\" tabindex=\"0\" title=\"Show it on the map\"><td>%s</td>"
                "<td><b><span class=\"glyph k-%s\">%s</span> %s</b></td><td>%s</td></tr>"
                % (e.kind, k, e.time, e.kind, GLYPHS.get(e.kind, ""), html.escape(e.title),
                   html.escape(e.detail)))

    event_rows = "\n".join(event_row(k, e) for k, e in enumerate(events))
    if not event_rows:
        event_rows = "<tr><td colspan=\"3\" class=\"dim\">Nothing notable found.</td></tr>"

    charts = [svg_chart(ride, m, t_ms, opts.max_points) for m in metrics if charted(m)]

    notes = []
    if not ride.has("kmh"):
        notes.append("recorded from the phone alone: no values from the bike")
    if not ride.has("lat"):
        notes.append("recorded without GPS: no track")
    if ride.warnings:
        notes.append("not checked: " + "; ".join(ride.warnings))
    if len(ride) > opts.max_points:
        notes.append("the charts keep the lowest and highest of every %d s"
                     % stretch(len(ride), opts.max_points))

    return f'''<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<title>{html.escape(ride.name)} — KawaNav ride report</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>{REPORT_CSS}</style></head><body>
<h1>{html.escape(ride.name)}</h1>
<p class="dim">{html.escape(os.path.basename(ride.path))} · {len(ride)} seconds ·
distance {km:,.2f} km ({dist_src}) · {hhmmss(ride.duration_ms())}{(' · ' + '; '.join(notes)) if notes else ''}</p>

<div class="tiles">
{''.join('<div class="tile"><b>%s</b>%s</div>' % (html.escape(v), html.escape(k)) for k, v in tiles)}
</div>
{f'<p>{fuel / km * 100:.1f} L/100 km over {km:,.1f} km</p>' if fuel > 0 and km > 0 else ''}

{('<h2>Map</h2>' + map_panel) if opts.map else ''}

<h2>Notable moments</h2>
<p class="dim">Traction-control readings are unverified (see docs/PROTOCOL.md): what is listed is
the reading moving, not a proven intervention.{' Click a moment to see it on the map.' if on_map else ''}</p>
<table class="data events"><tr><th>Time</th><th>What</th><th>Values</th></tr>{event_rows}</table>

<h2>What the ride recorded</h2>
{''.join(table)}
<h3>Times</h3>
<table class="data">{''.join(fact_lines)}</table>

<h2>Charts</h2>
{'<p class="dim">Point at a chart to read that second, on the chart and on the map.</p>' if on_map else ''}
{''.join(charts)}

<p class="dim">Made by KawaNav's ride-report tool from {html.escape(os.path.basename(ride.path))}.
The values are what the bike sent; an empty cell there means it sent nothing.</p>
{map_scripts}
</body></html>
'''


# ------------------------------------------------------------------ command line


def parse_args(argv):
    p = argparse.ArgumentParser(description="Report on a KawaNav ride exported as CSV.")
    p.add_argument("files", nargs="+", help="the exported CSV (or the app's own ride file)")
    p.add_argument("-o", "--out", default=None, help="where to write the report (default: beside the CSV)")
    p.add_argument("--events", action="store_true", help="only list the notable moments, no HTML")
    p.add_argument("--no-map", dest="map", action="store_false", help="leave the map out of the report")
    p.add_argument("--no-tiles", dest="tiles", action="store_false",
                   help="no background under the track: the report asks no server for anything")
    p.add_argument("--max-points", type=int, default=4000,
                   help="a chart draws at most this many points (a longer ride keeps each stretch's extremes)")
    p.add_argument("--open", action="store_true", help="open the report when it is written")
    p.add_argument("--slip", type=float, default=3.0, help="wheel slip, km/h (default 3)")
    p.add_argument("--brake", type=float, default=0.35, help="hard braking, g (default 0.35)")
    p.add_argument("--wheelie", type=float, default=5.0, help="wheelie angle, degrees (default 5)")
    p.add_argument("--stop", type=float, default=5.0, help="a stop lasts at least this many seconds")
    p.add_argument("--hot", type=float, default=100.0, help="hot coolant, °C (default 100)")
    return p.parse_args(argv)


def main(argv=None):
    # the Windows console is not UTF-8 by default and the report uses a few symbols
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass
    opts = parse_args(argv if argv is not None else sys.argv[1:])
    failed = False
    for path in opts.files:
        try:
            ride = load(path)
        except (OSError, ValueError) as e:
            print("error: %s" % e, file=sys.stderr)
            failed = True
            continue
        events = find_events(ride, opts)
        dist_m, dist_src = ride.distance_m()
        print("%s — %d seconds, %.2f km (%s), %s"
              % (ride.name, len(ride), dist_m / 1000, dist_src, hhmmss(ride.duration_ms())))
        for e in events:
            print("  %-7s %s  %s  [%s]" % (e.time, e.title, e.detail, e.kind))
        if not events:
            print("  nothing notable found")
        if not opts.events:
            out_dir = opts.out or os.path.dirname(os.path.abspath(path))
            os.makedirs(out_dir, exist_ok=True)
            base = os.path.splitext(os.path.basename(path))[0]
            out = os.path.join(out_dir, base + "_report.html")
            with open(out, "w", encoding="utf-8") as f:
                f.write(html_report(ride, opts, events))
            print("  report: %s" % out)
            if opts.open:
                open_in_browser(out)
    return 1 if failed else 0


def open_in_browser(path):
    try:
        if sys.platform.startswith("win"):
            os.startfile(path)              # noqa: S606 - opening our own file
        elif sys.platform == "darwin":
            subprocess.run(["open", path], check=False)
        else:
            subprocess.run(["xdg-open", path], check=False)
    except Exception as e:                  # opening a browser is a convenience, never a failure
        print("  (could not open the browser: %s)" % e, file=sys.stderr)


if __name__ == "__main__":
    sys.exit(main())
