# Ride report — a desktop look at an exported ride

KawaNav's app shows one ride at a time on a phone screen. This tool reads the ride you exported and
writes an HTML report beside it: every value the ride recorded with its lowest, average and highest, how
long the electronics worked, the notable moments with the values that caused them, charts, and the track
on a map.

It needs **nothing but Python 3.9 or newer** — no packages to install. The charts are drawn by the tool
itself; only the map uses the internet (Leaflet from a CDN), and `--no-map` leaves it out.

## Using it

Export the ride from the app: open the ride, **Keep or share → CSV (all values)**. Then, in this folder:

```
python ride_report.py Ride_15_ott_17_28_full.csv
```

On Windows you can instead drag the CSV onto **`ride-report.bat`**. On any system:

```
python ride_report.py Ride_*.csv -o reports --open     # several rides, into a folder, opened at the end
python ride_report.py Ride_*.csv --events              # only list the moments, no HTML
python ride_report.py Ride_*.csv --no-map              # no internet needed
```

| Option | What it does |
|---|---|
| `-o, --out DIR` | where the report goes (default: beside the CSV) |
| `--events` | list the notable moments in the terminal and write nothing |
| `--no-map` | leave the map out of the report |
| `--open` | open the report when it is written |
| `--max-points N` | charts use at most this many seconds (long rides) |
| `--slip`, `--brake`, `--wheelie`, `--stop`, `--hot` | the thresholds, see below |

It reads the app's **CSV (all values)**, the app's **own ride file** (`#kawanav-ride 1`, what is inside
`files/rides/` on the phone), and **Rideology's CSV export** (its column names are mapped; only the
values its layout has are used). A file that is not a ride is refused with a message.

## What it works out

- **The channels**, with the lowest, average and highest of every second the bike sent them: engine
  speed, throttle, suspension travel, acceleration, wheelie angle, tyre pressures, front brake, battery,
  the four temperatures, GPS accuracy and altitude — plus the three **IMU axes turned into the bike's
  directions** (forward, up, sideways), which the app does too because the sensor is mounted at 45°.
- **The four times**: seconds the traction-control reading moved, ABS worked, the front wheel was up,
  and cruise control was on. A channel the bike does not send is reported as **not checked**, never as
  zero, and a channel that is there but never fired says **never**.
- **The notable moments**, each with the values around it:
  | Moment | Rule (change it with the option) |
  |---|---|
  | Wheel slip | the two filtered wheel speeds at least **3 km/h** apart (`--slip`) |
  | Hard braking | forward acceleration at or below **−0.35 g** (`--brake`) |
  | Front wheel up | the wheelie flag, or **5°** of wheelie angle (`--wheelie`) |
  | Gear changes | the gear is not the one of the second before (with the throttle telling a shift from a clutch) |
  | Traction control | any of the four traction-control readings above zero |
  | ABS | the ABS byte's front or rear bit |
  | Stopped | standing still for **5 s** or more (`--stop`) |
  | Hot engine | coolant at **100 °C** or more (`--hot`) |

## What it deliberately does not claim

The two traction-control readings are **not understood**: the app and this tool report "the reading moved",
not a proven intervention — Kawasaki's own names for them say "level" and its app never interprets them.
See [docs/PROTOCOL.md](../docs/PROTOCOL.md). The same goes for which of the three rotation rates is
which axis: they are not reported here for that reason.

A value the bike did not send is an empty cell in the export, and it is treated as *absent* everywhere —
never as a zero. A real zero (no throttle, upright, no braking) is a value and is reported as one.

## Tests

```
python test_ride_report.py
```

Plain asserts, no pytest, and every fixture is built inside the test: a real exported ride holds the GPS
track of whoever was riding it, so no ride file is ever committed here.
