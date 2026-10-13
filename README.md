# 🏍️ KawaNav

Android app that shows **Google Maps turn-by-turn directions** or the **upcoming curves of the road
you are on** on the TFT dash of a Kawasaki motorcycle. It talks to the dash (Kawasaki calls it the
"meter", hence the name in the code and protocol notes) the same way Kawasaki's
Rideology app does (Bluetooth LE, see [docs/PROTOCOL.md](docs/PROTOCOL.md)) and replaces Rideology's
link to the bike.

Independent project, not affiliated with or endorsed by Kawasaki or Google. Tested on a Kawasaki
Z900 (2026, "Kawasaki-ZR900S") with a Pixel 9. Free software under the
[GNU GPL v3](LICENSE).

> ⚠️ **Safety:** KawaNav is a riding aid, not a safety system. Directions and curves can be late,
> missing or wrong. Always ride by what you see on the road and keep your eyes on it. Use at your
> own risk; there is no warranty (see [License](#-license)).

**Contents:** [Features](#-features) · [Compatibility](#-compatibility) · [Install](#-install) ·
[Using KawaNav](#-using-kawanav) ·
[Known limitations](#-known-limitations) · [License](#-license)

## ✨ Features

- 🧭 **Google Maps directions on the dash**: KawaNav reads Google Maps' navigation notification and sends
  arrow + distance to the dash. The arrow is recognised from Maps' own icon (language independent);
  the text decides roundabout exit numbers and highway ramps/exits.
- 🌀 **Free ride: upcoming curves**: without navigation, the dash shows the next curve of the road you
  are on (slight / normal / sharp / hairpin) with its distance, roundabouts with the exit that
  continues the road, and on straights the straight arrow with the distance to the next curve.
  Works from offline OpenStreetMap data, no mobile data needed while riding.
- 🔋 **Phone battery and headset on the dash**: the dash's phone battery icon shows the phone's charge
  in 4 bars, like with Rideology, and that it is charging; the headset icon shows while your helmet
  intercom is connected.
- 🔑 **Starts by itself** when the bike is switched on (or the helmet intercom connects), also with the
  phone locked in a pocket, and stops 5 minutes after the bike is switched off.
- 🌍 **Offline maps**: download regions (country, state, district) from Geofabrik, see their data date,
  update and delete them one by one.

## 📱 Compatibility

### 📋 Requirements

- Android 13 or newer, with Bluetooth LE and GPS
- Google Maps (for directions)
- The bike paired with the phone once (Android-level bonding): with KawaNav's "Pair your bike" or in
  Rideology (both tested). If Rideology is installed, it must be closed (**force-stopped**) while
  KawaNav is used: the bike talks to one app at a time.

### 🏍️ Supported motorcycles

Any Kawasaki whose dash shows Rideology's turn-by-turn navigation (BLE5 meter). From Kawasaki's
model pages (details and sources in [docs/MODELS.md](docs/MODELS.md)):

| Status | Models |
|---|---|
| ✅ Tested | Z900 2026 |
| 👍 Turn-by-turn confirmed by Kawasaki | Z900 / Z900 SE (2025+), Z1100 / Z1100 SE (2026+), Ninja ZX-10R / ZX-10RR (2026+) |
| ❓ Unclear | Ninja 1100SX, Versys 1100 (2025+), Z650 S (2026), Ninja 650 / Ninja H2 (2027) |
| ❌ Not supported | Z900RS 2026 (analog dials), Rideology bikes 2019–2024 (older dashes without turn-by-turn) |

KawaNav does not check the model. After connecting it asks the dash what it can show; a dash that
reports no turn-by-turn support gets a warning in the app (KawaNav keeps sending, the arrows will
probably not appear).

### 🌐 Google Maps languages

Google Maps writes its directions in the phone's language (or Maps' own language: Android settings →
Apps → Maps → Language). The arrow works in every language because it comes from Maps' icon; the
language matters for roundabout exit numbers, highway ramps/exits/merges and as fallback when an
icon is not recognised.

| Language | Status |
|---|---|
| German, English | ✅ Verified on real Maps notifications |
| French, Spanish, Italian, Dutch, Portuguese, Polish, Czech, Swedish, Danish, Norwegian, Finnish, Greek, Russian | 🟡 Supported, wording based on Google's usual phrasing, not yet checked on real notifications |

Distances are read in m/km, mi/ft/yd and Cyrillic/Greek units (м/км, μ/χλμ). The word lists are in
`MapsParser.java`.

The app's own screen is in English.

## 📦 Install

1. Download the APK from the [Releases](../../releases) page and open it on the phone (allow
   installing apps from your browser or file manager when Android asks).
2. Open KawaNav. On the first start it walks you through pairing, permissions and notification
   access, explaining each step. Anything missing later shows up in an **orange banner** at the top
   of the app, each item with a button that fixes it.

🔒 **Notification access greyed out?** Android 13 and newer protect notification access ("Restricted
setting") for apps installed from a downloaded APK instead of an app store. Unlock it once:

1. In the notification access screen, tap KawaNav and its greyed-out switch once. Android refuses
   ("App was denied access"), but only after this attempt does the menu entry in step 2 appear.
2. Settings → Apps → KawaNav → **⋮** (top right) → **Allow restricted settings**, and confirm.
3. Back in KawaNav, tap "Open" for Google Maps directions and turn the switch on.

KawaNav shows these steps itself ("Switch greyed out?") when it notices the situation.

<img src="docs/restricted-settings.png" alt="App info with the ⋮ menu open: Allow restricted settings" width="360">

## 🏁 Using KawaNav

### 📟 What the dash shows

Once a second KawaNav decides what goes on the dash:

1. **Google Maps is navigating** (and "Google Maps directions" is on): Maps' maneuver and distance.
   With "Curve warnings during navigation" on, curves before the next maneuver are shown from 300 m.
2. **Otherwise, "Show upcoming curves" is on**: the next curve or roundabout within the look-ahead;
   beyond it, the straight arrow with the distance to the next curve (searched up to 3 km).
3. Otherwise nothing.

### 🔧 Settings

| Setting ("On the road") | Default | Effect |
|---|---|---|
| [Google Maps directions](#google-maps-directions) | On | Off: Maps is ignored, free ride also runs during navigation |
| [Show upcoming curves](#show-upcoming-curves) | On | Free ride when Maps is not navigating |
| [Curve warnings during navigation](#curve-warnings-during-navigation) | Off | Off / sharp only / all curves between Maps directions |
| Which curves count | Normal | Only tight ones / Normal / Also gentle ones / All over 15° (explained in the app) |
| [Look ahead](#look-ahead) | 1000 m | 500 / 1000 / 1500 / 2000 m |
| [Phone battery](#phone-battery) | On | Phone battery icon on the dash |
| [Distances in feet](#distances-in-feet) | Off | Imperial distances |
| [Left-hand traffic](#left-hand-traffic) | Off | For countries driving on the left (UK, Ireland, …) |

#### Google Maps directions

While Google Maps is navigating, the dash shows Maps' next maneuver and its distance, and free ride
pauses. Switched off, KawaNav ignores Maps completely: free ride keeps showing curves during
navigation, and notification access is no longer needed.

#### Show upcoming curves

Free ride: when Maps is not navigating, KawaNav follows the road you are on (at junctions: the road
with the same name or number, else the straightest one) and shows what comes next:

- the next curve with its distance to where the bend starts; once you are in a bend, the one after it;
- a roundabout as roundabout symbol with the exit that continues your road;
- on a straight, the straight arrow with the distance to the next curve (searched up to 3 km ahead,
  or to the end of the downloaded roads).

Needs [offline maps](#-offline-maps) for the area.

#### Look ahead

How far ahead a bend or roundabout appears as its own arrow. Further away, the dash shows the
straight arrow with the distance to it. A longer look-ahead announces bends earlier; a shorter one
keeps the straight arrow longer on roads with few bends. 1000 m is about 36 s at 100 km/h.

#### Curve warnings during navigation

Between Google Maps' directions, bends on the way to the next maneuver can interrupt Maps' arrow. A
bend is shown from 300 m before it, and only if it starts at least 50 m before Maps' next maneuver;
afterwards the dash goes back to Maps' arrow. "Sharp only" shows sharp bends and hairpins, "All"
every bend that counts under "Which curves count". Off by default: the dash then shows only Google
Maps. Needs downloaded roads like free ride.

#### Phone battery

While connected, KawaNav sends the phone's battery level to the dash every 5 s, as Rideology does.
The dash shows 4 steps (Rideology's thresholds): up to 10 %, up to 30 %, up to 70 %, above 70 %.
While the phone is on a charger, the icon's charging version is sent (same bars). Switched off, the
icon is removed. Not yet tried on the bike.

#### Headset icon

The dash shows its headset icon while the helmet picked under "Start automatically" → "When my
helmet connects" is connected to the phone (as headset or for audio). With "Off" there, the icon
stays off. Not yet tried on the bike.

#### Distances in feet

Sends distances to the dash in feet instead of metres (not yet tried on the bike).

#### Left-hand traffic

Only matters when KawaNav reads a Maps instruction from its text instead of its icon (rare): a
roundabout is then shown clockwise, a roundabout exit without a number ("turn right at the
roundabout") counts from the other side, and a U-turn without a direction turns right. Free ride
reads the direction of a roundabout from the road itself and needs no setting.

### 🌍 Offline maps

Curves only work where a region is downloaded; nothing is fetched while riding. The app says
"No offline roads…" when the current area is missing. Download the regions you ride in under
"Offline maps", preferably over Wi-Fi.

- Regions come from [Geofabrik](https://download.geofabrik.de) (OpenStreetMap extracts, rebuilt
  daily). The download is resumable and asks first on mobile data. Converting needs temporary free
  storage of about 1.25 × the file size + 500 MB.
- The extract is converted on the phone into road tiles and then deleted. Examples on a Pixel 9:
  Oberbayern 258 MB → 22 MB stored in ~35 s; Germany 4.8 GB → 383 MB stored in ~23 min (9 min
  download, 14 min conversion).
- Each region is listed with its data date and size. **Update** appears when Geofabrik's data is
  30 days newer; **Delete** removes the region's tiles, except tiles another downloaded region also
  uses (regions overlap, e.g. a state inside its country, and share border tiles).

### 🔑 Autostart

Set it up under "Start automatically" → "When the bike is switched on" → **Set up** (ignition on,
Rideology closed). Optionally pick your helmet intercom under "When my helmet connects" as a
fallback.

- KawaNav then starts when the bike is switched on or the chosen intercom connects, also with the
  phone locked.
- After an automatic start it stops itself when the bike has been gone for 5 min.
- **Stop** by hand (button or notification) pauses autostart until the bike has been switched off
  (gone for at least 20 s) or for at most 1 hour; **Start** ends the pause.

The Start/Stop button is only a manual fallback (autostart missed, testing).

## 🚧 Known limitations

- Only German and English Maps wording is verified (see [Google Maps languages](#-google-maps-languages)).
- Rerouting / arrived flags and waypoint icons have no visible effect on the Z900 dash.
- Free ride needs downloaded regions; it follows the road you are on and does not know your route.

## 📜 License

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
