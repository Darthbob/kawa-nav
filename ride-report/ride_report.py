#!/usr/bin/env python3
"""KawaNav ride report.

Reads a ride exported by KawaNav (the "CSV (all values)" export, the app's own file, or Rideology's
CSV) and writes an HTML report beside it: the summary of every channel, the time the electronics spent
working, the notable moments of the ride, charts, and the track on a map.

Standard library only: the tool needs nothing but Python 3.9+ installed. Charts are drawn as SVG by
this program; the map is Leaflet, loaded from a CDN, so the report needs internet when you open it
(use --no-map to leave it out).

KawaNav itself is Java: this tool repeats the parts of it that matter offline (the column names, the
summary, the event rules) rather than reusing them. Where the two must agree, the rule is written here
in the same words as in the app and in docs/PROTOCOL.md.
"""

import argparse
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
        """Seconds since the start, one per row."""
        t = self.t_ms()
        if t and t[0] is not None:
            return t
        return [i * 1000.0 for i in range(len(self.rows))]

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
    """A cell as a number, or None: an empty cell means the bike did not send that value."""
    cell = cell.strip()
    if not cell:
        return None
    try:
        return float(cell)
    except ValueError:
        return None


def gear_value(cell):
    """Rideology writes the gear as N, a number, or Error."""
    if cell in ("", "Error"):
        return None
    if cell.upper() == "N":
        return 0.0
    try:
        return float(cell)
    except ValueError:
        return None


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

EVENT_KINDS = ["wheelspin", "braking", "wheelie", "shift", "tcs", "abs", "stop", "heat"]


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
            bits.append("%d rpm" % rpm[i])
        if i < len(gear) and gear[i] is not None:
            bits.append("gear %s" % ("N" if gear[i] == 0 else int(gear[i])))
        if i < len(lean) and lean[i] is not None:
            bits.append("%.0f° lean" % lean[i])
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


def svg_chart(ride, metric, t_ms, width=980, height=170):
    """One value over the ride, with the traction-control and ABS seconds marked along the bottom."""
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

    points = [(x(i), y(v)) for i, v in enumerate(values) if v is not None]
    path = " ".join("%s%.1f,%.1f" % ("M" if k == 0 else "L", px, py) for k, (px, py) in enumerate(points))
    marks = tcs_abs_marks(ride, t_ms, x, top, h)
    label = "%s  (%s)" % (metric.label, metric.unit) if metric.unit else metric.label
    return f'''<figure class="chart">
<figcaption><b>{html.escape(label)}</b> <span class="dim">lowest {fmt(metric.min, metric.decimals)} ·
average {fmt(metric.mean, metric.decimals)} · highest {fmt(metric.max, metric.decimals)}
{html.escape(metric.unit)}</span></figcaption>
<svg viewBox="0 0 {width} {height}" preserveAspectRatio="none">
<rect x="{left}" y="{top}" width="{w}" height="{h}" class="plot"/>
{gridlines(left, top, w, h)}
<text x="{left - 8}" y="{top + 10}" class="axis" text-anchor="end">{fmt(hi, metric.decimals)}</text>
<text x="{left - 8}" y="{top + h}" class="axis" text-anchor="end">{fmt(lo, metric.decimals)}</text>
{marks}
<path d="{path}" class="line"/>
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


def track_section(ride, events, opts):
    """The track drawn here, as SVG: no map library, no tiles, no internet.

    The shape is the point: where the ride went, coloured by speed, with a marker for every notable
    moment, a scale bar and north. An equirectangular projection is plenty over a ride's few kilometres
    (and it is what the phone's own track view does).
    """
    pts = [(r.get("lat"), r.get("lon"), r.get("kmh")) for r in ride.rows
           if r.get("lat") is not None and r.get("lon") is not None]
    if len(pts) < 2:
        return "<p class=\"dim\">No GPS track in this ride.</p>"

    width, height, pad = 980, 460, 26
    lat0 = min(p[0] for p in pts)
    lat1 = max(p[0] for p in pts)
    lon0 = min(p[1] for p in pts)
    lon1 = max(p[1] for p in pts)
    lat_mid = math.radians((lat0 + lat1) / 2)
    # metres per degree, and the same scale on both axes so the shape is not stretched
    m_per_deg_lat = 111_320.0
    m_per_deg_lon = 111_320.0 * math.cos(lat_mid)
    span_x = max(1e-6, (lon1 - lon0) * m_per_deg_lon)
    span_y = max(1e-6, (lat1 - lat0) * m_per_deg_lat)
    scale = min((width - 2 * pad) / span_x, (height - 2 * pad) / span_y)
    # centre what does not fill the box
    off_x = (width - span_x * scale) / 2
    off_y = (height - span_y * scale) / 2

    def xy(lat, lon):
        x = off_x + (lon - lon0) * m_per_deg_lon * scale
        y = off_y + (lat1 - lat) * m_per_deg_lat * scale
        return x, y

    speeds = [p[2] for p in pts if p[2] is not None]
    top = max(speeds) if speeds else 0
    palette = ["#4a6fa5", "#2e86de", "#27ae60", "#f39c12", "#c0392b"]

    def colour(kmh):
        if kmh is None or top <= 0:
            return palette[0]
        f = min(0.999, max(0.0, kmh / top))
        return palette[int(f * len(palette))]

    segments = []
    for i in range(1, len(pts)):
        a, b = pts[i - 1], pts[i]
        x1, y1 = xy(a[0], a[1])
        x2, y2 = xy(b[0], b[1])
        segments.append('<line x1="%.1f" y1="%.1f" x2="%.1f" y2="%.1f" stroke="%s"/>'
                        % (x1, y1, x2, y2, colour(b[2])))

    markers = []
    for e in events:
        if e.lat is None or e.lon is None:
            continue
        x, y = xy(e.lat, e.lon)
        markers.append('<g class="ev"><title>%s · %s — %s</title>'
                       '<circle cx="%.1f" cy="%.1f" r="5" class="ev-%s" stroke="#fff"/></g>'
                       % (html.escape(e.time), html.escape(e.title), html.escape(e.detail),
                          x, y, e.kind))
    sx, sy = xy(pts[0][0], pts[0][1])
    start_dot = ('<circle cx="%.1f" cy="%.1f" r="4" class="start"/>' % (sx, sy))

    # a scale bar of a round distance that fits the drawing
    bar_m = 100.0
    for candidate in (50, 100, 200, 500, 1000, 2000, 5000, 10000):
        if candidate * scale <= width * 0.3:
            bar_m = candidate
    bar_px = bar_m * scale
    bar_label = "%d m" % bar_m if bar_m < 1000 else "%d km" % (bar_m // 1000)

    return """<figure class="track">
<svg viewBox="0 0 %d %d" role="img" aria-label="the ride's track">
<rect width="%d" height="%d" class="track-bg"/>
%s
%s
%s
<g class="scale"><line x1="%d" y1="%d" x2="%.1f" y2="%d"/><text x="%d" y="%d">%s</text></g>
<g class="north"><line x1="%d" y1="%d" x2="%d" y2="%d"/><text x="%d" y="%d">N</text></g>
</svg>
<figcaption class="dim">Blue is the slowest part of the ride, red the fastest%s. Touch a marker for the
moment it stands for; the scale bar is approximate (the shape is drawn flat, without a projection).
</figcaption></figure>""" % (
        width, height, width, height,
        "\n".join(segments), start_dot, "\n".join(markers),
        16, height - 18, 16 + bar_px, height - 18, 16, height - 26, bar_label,
        width - 28, 22, width - 28, 44, width - 34, 38,
        (", grey where the speed is unknown" if top <= 0 else ""),
    )


def html_report(ride, opts):
    metrics = metrics_of(ride)
    facts = facts_of(ride)
    events = find_events(ride, opts)
    t_ms = ride.seconds()
    dist_m, dist_src = ride.distance_m()
    km = dist_m / 1000.0
    fuel = ride.fuel_l()
    noted = [c for c, _ in [(c, None) for c in ride.header]]

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
    for key, _ in [(k, None) for k, _, _ in FACTS]:
        label, seconds, present = facts[key]
        if not present:
            continue
        fact_lines.append("<tr><th>%s</th><td>%s</td></tr>"
                          % (html.escape(label),
                             "never" if seconds == 0 else hhmmss(seconds * 1000)))

    event_rows = "\n".join(
        "<tr class=\"ev-%s\"><td>%s</td><td><b>%s</b></td><td>%s</td></tr>"
        % (e.kind, e.time, html.escape(e.title), html.escape(e.detail)) for e in events)
    if not event_rows:
        event_rows = "<tr><td colspan=\"3\" class=\"dim\">Nothing notable found.</td></tr>"

    charts = []
    for m in metrics:
        if m.known and m.key not in ("gps_acc_m",):
            charts.append(svg_chart(ride, m, t_ms))

    notes = []
    if not ride.has("kmh"):
        notes.append("recorded from the phone alone: no values from the bike")
    if not ride.has("lat"):
        notes.append("recorded without GPS: no track")
    if ride.warnings:
        notes.append("not checked: " + "; ".join(ride.warnings))
    if len(ride) > opts.max_points:
        notes.append("charts drawn from every %dth second of %d" % (max(1, len(ride) // opts.max_points), len(ride)))

    return f'''<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<title>{html.escape(ride.name)} — KawaNav ride report</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
:root {{ color-scheme: light dark; }}
body {{ font: 15px/1.5 system-ui, sans-serif; margin: 0 auto; max-width: 1040px; padding: 24px; }}
h1 {{ font-size: 24px; margin: 0 0 4px; }}
h2 {{ font-size: 18px; margin: 28px 0 8px; }}
h3 {{ font-size: 15px; margin: 18px 0 6px; color: #666; }}
.dim {{ color: #777; font-weight: normal; }}
.tiles {{ display: flex; flex-wrap: wrap; gap: 10px; margin: 16px 0; }}
.tile {{ border: 1px solid #ddd; border-radius: 10px; padding: 10px 14px; min-width: 130px; }}
.tile b {{ display: block; font-size: 20px; }}
table.data {{ border-collapse: collapse; width: 100%; }}
table.data th {{ text-align: left; font-weight: 600; }}
table.data td, table.data th {{ padding: 4px 10px 4px 0; border-bottom: 1px solid #eee; }}
table.data td {{ text-align: right; font-variant-numeric: tabular-nums; }}
table.data th:first-child {{ width: 40%; }}
.ev-tcs td, .ev-wheelspin td {{ background: #fff6e5; }}
.ev-braking td, .ev-abs td, .ev-heat td {{ background: #fdecea; }}
figure.chart {{ margin: 14px 0; }}
figcaption {{ font-size: 13px; margin-bottom: 4px; }}
svg {{ width: 100%; height: 170px; }}
.plot {{ fill: none; stroke: #ddd; }}
.grid {{ stroke: #eee; }}
.line {{ fill: none; stroke: #2e86de; stroke-width: 1.6; }}
.axis {{ font-size: 10px; fill: #888; }}
.marker-tcs {{ stroke: #f39c12; stroke-width: 2; }}
.marker-abs {{ stroke: #c0392b; stroke-width: 2; }}
figure.track svg {{ height: auto; }}
.track-bg {{ fill: #f4f6f8; }}
figure.track line {{ stroke-width: 4; stroke-linecap: round; }}
.start {{ fill: #27ae60; }}
.scale line, .north line {{ stroke: #666; stroke-width: 2; }}
.scale text, .north text {{ font-size: 11px; fill: #666; }}
.ev circle {{ stroke-width: 2; }}
.ev-wheelspin circle, .ev-tcs circle {{ fill: #f39c12; }}
.ev-braking circle, .ev-abs circle, .ev-heat circle {{ fill: #c0392b; }}
.ev-shift circle, .ev-stop circle, .ev-wheelie circle {{ fill: #333; }}
@media (prefers-color-scheme: dark) {{
  body {{ background: #111; color: #eee; }}
  .tile, table.data td, table.data th {{ border-color: #333; }}
  .grid {{ stroke: #262626; }} .plot {{ stroke: #333; }} .line {{ stroke: #4aa3ff; }}
  .track-bg {{ fill: #1a1d21; }}
  .scale line, .north line {{ stroke: #999; }} .scale text, .north text {{ fill: #999; }}
  .ev-shift circle, .ev-stop circle, .ev-wheelie circle {{ fill: #ddd; }}
  .ev-tcs td, .ev-wheelspin td {{ background: #2a2317; }}
  .ev-braking td, .ev-abs td, .ev-heat td {{ background: #2a1a19; }}
}}
</style></head><body>
<h1>{html.escape(ride.name)}</h1>
<p class="dim">{html.escape(os.path.basename(ride.path))} · {len(ride)} seconds ·
distance {km:,.2f} km ({dist_src}) · {hhmmss(ride.duration_ms())}{(' · ' + '; '.join(notes)) if notes else ''}</p>

<div class="tiles">
{''.join('<div class="tile"><b>%s</b>%s</div>' % (html.escape(v), html.escape(k)) for k, v in tiles)}
</div>
{f'<p>{fuel / km * 100:.1f} L/100 km over {km:,.1f} km</p>' if fuel > 0 and km > 0 else ''}

<h2>What the ride recorded</h2>
{''.join(table)}
<h3>Times</h3>
<table class="data">{''.join(fact_lines)}</table>

<h2>Notable moments</h2>
<p class="dim">Traction-control readings are unverified (see docs/PROTOCOL.md): what is listed is
the reading moving, not a proven intervention.</p>
<table class="data"><tr><th>Time</th><th>What</th><th>Values</th></tr>{event_rows}</table>

<h2>Charts</h2>
{''.join(charts)}

{('<h2>Track</h2>' + track_section(ride, events, opts)) if opts.map else ''}

<p class="dim">Made by KawaNav's ride-report tool from {html.escape(os.path.basename(ride.path))}.
The values are what the bike sent; an empty cell there means it sent nothing.</p>
</body></html>
'''


# ------------------------------------------------------------------ command line


def parse_args(argv):
    p = argparse.ArgumentParser(description="Report on a KawaNav ride exported as CSV.")
    p.add_argument("files", nargs="+", help="the exported CSV (or the app's own ride file)")
    p.add_argument("-o", "--out", default=None, help="where to write the report (default: beside the CSV)")
    p.add_argument("--events", action="store_true", help="only list the notable moments, no HTML")
    p.add_argument("--no-map", dest="map", action="store_false", help="leave the map out (no internet needed)")
    p.add_argument("--max-points", type=int, default=4000, help="charts use at most this many seconds")
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
                f.write(html_report(ride, opts))
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
