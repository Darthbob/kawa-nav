# Ride report — a desktop look at an exported ride

KawaNav's app shows one ride at a time on a phone screen. This tool reads the ride you exported and
writes an HTML report beside it: every value the ride recorded with its lowest, average and highest, how
long the electronics worked, the notable moments with the values that caused them, charts, and the ride
on an interactive map.

It needs **nothing but Python 3.9 or newer** — no packages to install. The report is one HTML file that
carries everything it draws: the charts are SVG made by the tool, and the map is drawn by `ride_map.js`,
which sits beside the tool and goes whole into every report — no map library, nothing from a CDN.

## The map

- **Drag** to move it; **Ctrl + scroll** (or a touchpad pinch, or **+** and **−**) to zoom; **⤢** shows
  the whole ride again, **⛶** fills the screen. A plain scroll keeps scrolling the page. On a phone one
  finger moves the map and two zoom it.
- **Point at the track** for that second: speed, gear, engine speed, throttle, lean, forward
  acceleration, altitude.
- **Colour the track** by speed, lean angle, throttle, engine speed, gear, acceleration (braking red,
  accelerating blue) or altitude — whichever the ride has.
- **A marker for every notable moment**, its letter saying the kind (**B** hard braking, **S** stopped,
  **W** wheel slip, **T** traction control, **A** ABS, **F** front wheel up, **H** hot coolant, **G** gear
  change). Point at one, or click it, for its time and values. The chips above the map show or hide each
  kind; gear changes start hidden, a ride has dozens of them.
- **Under the map, the ride over time**: point at it or drag along it to move the cursor; **▶** replays the
  ride at 10 to 120 times its speed.
- **Tied to the rest of the report**: click a moment in the table and the map goes there; point at a chart
  and the map, the line of values under it and every other chart show that second.

The background under the track comes from the internet, when the report is opened, and you pick it in
the map: **OpenStreetMap** (the openstreetmap.de tile server), **satellite** imagery from Esri,
**OpenTopoMap**, or **None**. These were chosen because they answer a report opened from disk:
OpenStreetMap's own servers refuse one with "Access blocked" (a file sends no referer) and CARTO's now
ask for an API key. Each is asked only for the squares in view — so it learns roughly where the ride
was. With no internet the track is drawn on a plain background and everything else works.
`--no-tiles` makes a report that names no server at all; `--no-map` leaves the map out.

A report holds the track of the ride just as the CSV does: `*_report.html` is ignored by git here, like
`Ride_*.csv`.

## Using it

Export the ride from the app: open the ride, **Keep or share → CSV (all values)**. Then, in this folder:

```
python ride_report.py Ride_15_ott_17_28_full.csv
```

On Windows you can instead drag the CSV onto **`ride-report.bat`**. On any system:

```
python ride_report.py Ride_*.csv -o reports --open     # several rides, into a folder, opened at the end
python ride_report.py Ride_*.csv --events              # only list the moments, no HTML
python ride_report.py Ride_*.csv --no-tiles            # a report that asks no server for anything
```

| Option | What it does |
|---|---|
| `-o, --out DIR` | where the report goes (default: beside the CSV) |
| `--events` | list the notable moments in the terminal and write nothing |
| `--no-map` | leave the map out of the report |
| `--no-tiles` | keep the map, with no background under the track: the report asks no server for anything |
| `--open` | open the report when it is written |
| `--max-points N` | a chart draws at most this many points; a longer ride keeps the lowest and the highest of every stretch (default 4000) |
| `--slip`, `--brake`, `--wheelie`, `--stop`, `--hot` | the thresholds, see below |

It reads the app's **CSV (all values)**, the app's **own ride file** (`#kawanav-ride 1`, what is inside
`files/rides/` on the phone), and **Rideology's CSV export** (its column names are mapped; only the
values its layout has are used). A file that is not a ride is refused with a message.

## What it works out

- **The channels**, with the lowest, average and highest of every second the bike sent them: engine
  speed, throttle, suspension travel, acceleration, wheelie angle, tyre pressures, front brake, battery,
  the four temperatures, GPS accuracy and altitude — plus the three **IMU axes turned into the bike's
  directions** (forward, up, sideways), which the app does too because the sensor is mounted at 45°, and
  the **yaw, roll and pitch rates** in °/s, turned the same way. The rates swing both ways around zero,
  so, as in the app, they show only their lowest and highest.
  The instant fuel economy's average is the distance over the litres it burned, as in the app, not the
  average of its seconds.
- **The fuel used** and the L/100 km: from the bike's fuel counter, or, on a bike without one (the
  Z900), estimated from the instant fuel economy (each second burns its distance over its km/L) and
  marked "≈".
- **The four times**: seconds the traction-control reading moved, ABS worked, the front wheel was up,
  and cruise control was on. A channel the bike does not send is reported as **not checked**, never as
  zero, and a channel that is there but never fired says **never**.
- **The notable moments**, each with the values around it:
  | Moment | Rule (change it with the option) |
  |---|---|
  | Wheel slip | the two filtered wheel speeds at least **3 km/h** apart (`--slip`) |
  | Hard braking | forward acceleration, slope-corrected from the GPS altitude, at or below **−0.35 g** (`--brake`) |
  | Front wheel up | the wheelie flag, or **5°** of wheelie angle (`--wheelie`) |
  | Gear changes | the gear is not the one of the second before (with the throttle telling a shift from a clutch) |
  | Traction control | any of the four traction-control readings above zero |
  | ABS | the ABS byte's front or rear bit |
  | Stopped | standing still for **5 s** or more (`--stop`) |
  | Hot engine | coolant at **100 °C** or more (`--hot`) |

## What it deliberately does not claim

The two traction-control readings are **not understood**: the app and this tool report "the reading moved",
not a proven intervention — Kawasaki's own names for them say "level" and its app never interprets them.
See [docs/PROTOCOL.md](../docs/PROTOCOL.md). The pitch rate is reported as the bike sends it: which of its
signs is nose up is not established yet (the yaw and roll rates' signs are: + is to the right).

A value the bike did not send is an empty cell in the export, and it is treated as *absent* everywhere —
never as a zero. A real zero (no throttle, upright, no braking) is a value and is reported as one.
A temperature at the very bottom of its scale (−40 °C coolant or intake, −60 °C oil) is absent too: it
is what a sensor that reads nothing sends, which KawaNav 1.0 and earlier wrote down as a reading.

## Tests

```
python test_ride_report.py
```

Plain asserts, no pytest, and every fixture is built inside the test: a real exported ride holds the GPS
track of whoever was riding it, so no ride file is ever committed here. `ride_map.js` is checked with
`node --check` when Node is installed.
