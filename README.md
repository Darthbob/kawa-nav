# KawaNav

Android app that shows **Google Maps turn-by-turn directions** or the **upcoming curves of the road
you are on** on the TFT dash of a Kawasaki motorcycle. It talks to the dash (Kawasaki calls it the
"meter", hence the name in the code and protocol notes) the same way Kawasaki's
Rideology app does (Bluetooth LE, see [docs/PROTOCOL.md](docs/PROTOCOL.md)) and replaces Rideology's
link to the bike.

Independent project, not affiliated with or endorsed by Kawasaki or Google. Tested on a Kawasaki
Z900 (2026, "Kawasaki-ZR900S") with a Pixel 9. Free software under the
[GNU GPL v3](LICENSE).

> **Safety:** KawaNav is a riding aid, not a safety system. Directions and curves can be late,
> missing or wrong. Always ride by what you see on the road and keep your eyes on it. Use at your
> own risk; there is no warranty (see [License](#license)).

## Features

- **Google Maps directions on the dash**: KawaNav reads Google Maps' navigation notification and sends
  arrow + distance to the dash. The arrow is recognised from Maps' own icon (language independent);
  the text decides roundabout exit numbers and highway ramps/exits.
- **Free ride: upcoming curves**: without navigation, the dash shows the next curve of the road you
  are on (slight / normal / sharp / hairpin) with its distance, roundabouts with the exit that
  continues the road, and on straights the straight arrow with the distance to the next curve.
  Works from offline OpenStreetMap data, no mobile data needed while riding.
- **Starts by itself** when the bike is switched on (or the helmet intercom connects), also with the
  phone locked in a pocket, and stops 5 minutes after the bike is switched off.
- **Offline maps**: download regions (country, state, district) from Geofabrik, see their data date,
  update and delete them one by one.

## Supported motorcycles

Any Kawasaki whose dash shows Rideology's turn-by-turn navigation (BLE5 meter). From Kawasaki's
model pages (details and sources in [docs/MODELS.md](docs/MODELS.md)):

| Status | Models |
|---|---|
| Tested | Z900 2026 |
| Turn-by-turn confirmed by Kawasaki | Z900 / Z900 SE (2025+), Z1100 / Z1100 SE (2026+), Ninja ZX-10R / ZX-10RR (2026+) |
| Unclear | Ninja 1100SX, Versys 1100 (2025+), Z650 S (2026), Ninja 650 / Ninja H2 (2027) |
| Not supported | Z900RS 2026 (analog dials), Rideology bikes 2019–2024 (older dashes without turn-by-turn) |

KawaNav does not check the model. On a bike without turn-by-turn support it connects, but the arrows
never appear.

## Supported languages (Google Maps text)

Google Maps writes its directions in the phone's language (or Maps' own language: Android settings →
Apps → Maps → Language). The arrow works in every language because it comes from Maps' icon; the
language matters for roundabout exit numbers, highway ramps/exits/merges and as fallback when an
icon is not recognised.

| Language | Status |
|---|---|
| German, English | Verified on real Maps notifications |
| French, Spanish, Italian, Dutch, Portuguese, Polish, Czech, Swedish, Danish, Norwegian, Finnish, Greek, Russian | Supported, wording based on Google's usual phrasing, not yet checked on real notifications |

Distances are read in m/km, mi/ft/yd and Cyrillic/Greek units (м/км, μ/χλμ). The word lists are in
`MapsParser.java`. If a language misbehaves, ride with Maps in that language and check the log
(`Maps: "<instruction>" -> <turn>` lines).

The app's own screen is in English.

## Install

Download the APK from the [Releases](../../releases) page and open it on the phone (allow installing
apps from your browser or file manager when Android asks). Then follow [First-time setup](#first-time-setup).

**Notification access greyed out?** Android 13 and newer protect notification access ("Restricted
setting") for apps installed from a downloaded APK instead of an app store. Unlock it once:

1. In the notification access screen, tap KawaNav and its greyed-out switch once. Android refuses
   ("App was denied access"), but only after this attempt does the menu entry in step 2 appear.
2. Settings → Apps → KawaNav → **⋮** (top right) → **Allow restricted settings**, and confirm.
3. Back in KawaNav, tap "Open" for Google Maps directions and turn the switch on.

KawaNav shows these steps itself ("Switch greyed out?") when it notices the situation.

<img src="docs/restricted-settings.png" alt="App info with the ⋮ menu open: Allow restricted settings" width="360">

## Requirements

- Android 13 or newer (minSdk 33), with Bluetooth LE and GPS
- Google Maps (for directions)
- The bike paired with the phone once (Android-level bonding): in Rideology (tested) or with
  KawaNav's "Pair your bike" (new, not yet tested on a bike). Rideology must be closed
  (**force-stopped**) while KawaNav is used: the bike talks to one app at a time
- To build: JDK 17+, Android SDK with platform 36, Gradle 8.13 or newer (tested with Gradle 9.3.1),
  or simply Android Studio. The repository has **no Gradle wrapper**.

## Build from source

```sh
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # path to your Android SDK
JAVA_HOME=/path/to/jdk-17 gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Package name: `io.github.darthbob.kawanav`.

**Debug builds** are signed with the building machine's debug key and are debuggable (needed for
the `adb run-as` commands below). Installing over a build signed with another key (another machine,
or the release APK) fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; uninstalling deletes the
downloaded maps and settings, so back up a debug build first:
`adb exec-out run-as io.github.darthbob.kawanav tar -cf - files shared_prefs > backup.tar`.

**Release builds** (`gradle assembleRelease`) are signed with the key named in `keystore.properties`
in the project root; without that file they are built unsigned. The file and the key stay local and
are never committed:

```properties
storeFile=/path/to/kawanav-release.jks
storePassword=…
keyAlias=kawanav
keyPassword=…
```

Every update of the published APK must be signed with the same key, otherwise users have to
uninstall first. Keep the key and its password backed up.

## First-time setup

1. Pair the bike with the phone once, ignition on:
   - **in KawaNav:** "Pair your bike" in the orange banner (or "Set up" autostart) shows Android's
     dialog with nearby Kawasaki bikes; pick yours and confirm the pairing (on the dash too, if it
     asks). This also sets up autostart. New and not yet tested on a bike; if it fails, use Rideology.
   - **or in Rideology** (tested), then force-stop Rideology.
2. Open KawaNav. On the first start it walks you through everything once, explaining each step:
   Bluetooth, location and notification permissions, location "Allow all the time" (curves after an
   automatic start), notification access ("Read Google Maps directions") and unrestricted battery
   use (so Android does not pause KawaNav in your pocket).
   Anything declined or switched off later shows up in an **orange banner** at the top of the app,
   each item with a button that fixes it (also Bluetooth or location switched off). When Android no
   longer shows a permission prompt, the button opens KawaNav's settings page instead.
3. "Start automatically" → "When the bike is switched on" → **Set up** (ignition on, Rideology
   closed). Optionally pick your helmet intercom under "When my helmet connects" as a fallback.
4. "Offline maps" → download the region(s) you ride in, over Wi-Fi.

The **Start/Stop** button is only a manual fallback (autostart missed, testing).

## What the dash shows

Once a second KawaNav decides what goes on the dash:

1. **Google Maps is navigating** (and "Google Maps directions" is on): Maps' maneuver and distance.
   With "Curve warnings during navigation" on, curves before the next maneuver are shown from 300 m.
2. **Otherwise, "Show upcoming curves" is on**: the next curve or roundabout within the look-ahead;
   beyond it, the straight arrow with the distance to the next curve (searched up to 3 km).
3. Otherwise nothing.

| Setting ("On the road") | Default | Effect |
|---|---|---|
| Google Maps directions | On | Off: Maps is ignored, free ride also runs during navigation |
| Show upcoming curves | On | Free ride when Maps is not navigating |
| Curve warnings during navigation | Off | Off / sharp only / all curves between Maps directions |
| Which curves count | Normal | Only tight ones / Normal / Also gentle ones / All over 15° |
| Look ahead | 1000 m | 500 / 1000 / 1500 / 2000 m |
| Distances in feet | Off | Imperial distances |
| Left-hand traffic | Off | For countries driving on the left (UK, Ireland, …) |

### Settings in detail

**Google Maps directions**: while Google Maps is navigating, the dash shows Maps' next maneuver
and its distance, and free ride pauses. Switched off, KawaNav ignores Maps completely: free ride
keeps showing curves during navigation, and notification access is no longer needed.

**Show upcoming curves** (free ride): when Maps is not navigating, KawaNav follows the road you are on
(at junctions: the road with the same name or number, else the straightest one) and shows what comes
next:
- the next curve with its distance to where the bend starts; once you are in a bend, the one after it;
- a roundabout as roundabout symbol with the exit that continues your road;
- on a straight, the straight arrow with the distance to the next curve (searched up to 3 km ahead,
  or to the end of the downloaded roads).

**Which curves count**: how much a bend has to turn to be shown. The angle is the total change of
direction through the whole bend: 90° is a right-angle turn, 180° a hairpin. A bend also has to be
tight enough; long sweeping bends of a highway are not curves.

| Option | Shown from | Bends up to (radius) | Use |
|---|---|---|---|
| Only tight ones | 45° | ~170 m | Only bends you really have to brake for |
| Normal | 30° | ~250 m | Default |
| Also gentle ones | 20° | ~350 m | More bends, but two bends in the same direction close together can merge into one |
| All over 15° | 16° | ~570 m | Everything except the faintest bends; bends close together stay separate. Recommended |

Each bend is shown with one of four arrows:

| Arrow | When |
|---|---|
| Slight | Under 55° and wider than 60 m radius |
| Normal | 55° or more, or tighter than 60 m |
| Sharp | 100° or more, or tighter than 30 m |
| Hairpin (U-turn arrow) | 150° or more and tighter than 35 m |

**Look ahead**: how far ahead a bend or roundabout appears as its own arrow. Further away, the dash
shows the straight arrow with the distance to it. A longer look-ahead announces bends earlier; a
shorter one keeps the straight arrow longer on roads with few bends. 1000 m is about 36 s at
100 km/h.

**Curve warnings during navigation**: between Google Maps' directions, bends on the way to the next
maneuver can interrupt Maps' arrow. A bend is shown from 300 m before it, and only if it starts at
least 50 m before Maps' next maneuver; afterwards the dash goes back to Maps' arrow. "Sharp only"
shows sharp bends and hairpins, "All" every bend that counts under "Which curves count". Off by
default: the dash then shows only Google Maps. Needs downloaded roads like free ride.

**Distances in feet**: sends distances to the dash in feet instead of metres (not yet tried on the
bike).

**Left-hand traffic**: only matters when KawaNav reads a Maps instruction from its text instead of its
icon (rare): a roundabout is then shown clockwise, a roundabout exit without a number ("turn right at
the roundabout") counts from the other side, and a U-turn without a direction turns right. Free ride
reads the direction of a roundabout from the road itself and needs no setting.

## Offline maps

Curves only work where a region is downloaded; nothing is fetched while riding. The app says
"No offline roads…" when the current area is missing.

- Regions come from [Geofabrik](https://download.geofabrik.de) (OpenStreetMap extracts, rebuilt
  daily). The download is resumable and asks first on mobile data. Converting needs temporary free
  storage of about 1.25 × the file size + 500 MB.
- The extract is converted on the phone (`PbfImporter`) into road tiles of 0.05° (~5.5 × 3.7 km)
  and then deleted. Examples on a Pixel 9: Oberbayern 258 MB → 22 MB stored in ~35 s;
  Germany 4.8 GB → 383 MB stored in ~23 min (9 min download, 14 min conversion).
- Each region is listed with its data date and size. **Update** appears when Geofabrik's data is
  30 days newer; **Delete** removes the region's tiles, except tiles another downloaded region also
  uses (regions overlap, e.g. a state inside its country, and share border tiles).

## Autostart

The bike is a BLE-only device: it never connects to the phone by itself, it only advertises while
the ignition is on. KawaNav links it with Android's companion device manager; Android then watches
for the bike in a low-power scan and wakes `BikePresence`, which starts the bridge, also with the
phone locked. The helmet trigger (`HelmetTrigger`) starts the bridge when the chosen intercom
connects. After an automatic start the bridge stops itself when the bike has been gone for 5 min.

## How it works

| Class | Role |
|---|---|
| `BridgeService` | Foreground service: BLE link to the bike, decides once a second what the dash shows |
| `KawaBle`, `KawaProtocol` | GATT client and frame format of the Kawasaki BLE5 meter ([PROTOCOL.md](docs/PROTOCOL.md)) |
| `MapsListener` | Notification listener for Google Maps' navigation notification |
| `MapsIcons`, `IconMemory` | Recognise the maneuver from Maps' icon (Maps' own 64 maneuver drawables, rendered from the installed Maps app) |
| `MapsParser` | Maps text → turn and distance (15 languages) |
| `CurveEngine` | GPS → road match → follow the road ahead → next curve / roundabout |
| `RoadNetwork`, `CurveDetector` | Road graph and road following; curve detection and classification |
| `RoadStore`, `OfflineRegions` | Road tiles on the phone; downloaded regions and their tiles |
| `OfflineService`, `Geofabrik`, `PbfImporter` | Region download and on-phone conversion |
| `BikePresence`, `HelmetTrigger` | Autostart |
| `MainActivity` | The app screen (plain Android views, no libraries) |
| `Hub`, `Logger` | Shared state and settings; log file |

Plain Java, no third-party libraries.

## Data and log

On the phone (app storage): `files/tiles` (road tiles), `files/regions` (downloaded regions),
`files/icons` (Maps icons seen, for checking the icon library), `files/kawanav.log` (log, rotated at
2 MB), `shared_prefs/` (settings).

```sh
adb shell run-as io.github.darthbob.kawanav cat files/kawanav.log   # debug builds only
```

The log has every Maps instruction and how it was recognised, every frame sent to the bike, and
autostart and connection events.

## Development and testing

adb switches (the app screen has no test tools):

```sh
adb shell 'am start -n io.github.darthbob.kawanav/.MainActivity --ez bridge true --ez free_mode true'
adb shell 'am start -n io.github.darthbob.kawanav/.MainActivity --es offline_region bayern'   # Geofabrik id
adb shell 'am start -n io.github.darthbob.kawanav/.MainActivity --ez offline_cancel true'
adb shell 'am start -n io.github.darthbob.kawanav/.MainActivity --ez bridge false'
```

Autostart without the bike (after the link exists):

```sh
adb shell cmd companiondevice list 0
adb shell cmd companiondevice simulate-device-appeared <ASSOCIATION_ID>
```

The maintainer's test tools (desktop tests of curve detection and the Maps parser, the importer test,
a dash test app) live in `tools/`, which is git-ignored and not part of this repository.
`PbfImporter`, `RoadNetwork`, `CurveDetector` and `MapsParser` use no Android classes, so they can be
tested with plain `javac`/`java` on a PC.

## Known limitations

- Only German and English Maps wording is verified (see Supported languages).
- Pairing the bike from KawaNav ("Pair your bike") is not yet tested on a bike; pairing in Rideology
  is.
- KawaNav does not read the dash's "turn-by-turn supported" flag yet.
- Rerouting / arrived flags and waypoint icons have no visible effect on the Z900 dash.
- Free ride needs downloaded regions; it follows the road you are on and does not know your route.

## Repository layout

- `app/` – the KawaNav app
- `docs/` – [PROTOCOL.md](docs/PROTOCOL.md) (dash protocol), [MODELS.md](docs/MODELS.md) (supported models)
- `tools/`, `research/` – local only, git-ignored (test tools; decompiled Rideology as personal
  reverse-engineering reference, never commit or distribute)

## License

KawaNav © 2026 Darthbob. This program is free software: you can redistribute it and/or modify it
under the terms of the GNU General Public License as published by the Free Software Foundation,
version 3 (GPL-3.0-only). It is distributed in the hope that it will be useful,
but **without any warranty**; without even the implied warranty of merchantability or fitness for a
particular purpose. See [LICENSE](LICENSE) for the full text.

Road data: © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors, available under
the [Open Database License (ODbL)](https://opendatacommons.org/licenses/odbl/), downloaded by the app
from [Geofabrik](https://download.geofabrik.de). The repository and the APK contain no map data.

Kawasaki, Rideology, Ninja, Versys and the model names are trademarks of Kawasaki Motors, Ltd.;
Google Maps is a trademark of Google LLC. They are used here only to describe compatibility.
