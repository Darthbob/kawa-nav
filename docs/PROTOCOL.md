# Kawasaki Rideology – BLE5 meter protocol (TFT dashes, e.g. Z900 2025+)

Source: Rideology The App Motorcycle `jp.co.khi.mce.rideologytheappV2`, decompiled with jadx.
Obfuscated names below refer to that build; **line numbers refer to the 2.11.9 tree**
(`research/rideology-2.11.9/sources/…`, not in the repository) unless a section says 2.11.13 (the
older decompile the nav/indication sections were first written from). Every claim is tagged
**verified** (read in the decompiled code), **inferred** or **unknown**.

## Transport

- Bluetooth Low Energy, GATT. Security = OS-level bonding only (`createBond`), MTU requested: 300
  (`ne/x.java:264`). **No app-level encryption/auth** on the write path (`ne/p.f(byte[])` writes raw
  bytes). All writes are with response (no `WRITE_TYPE_*` is ever set; writer sleeps 50 ms between
  frames, `ne/i.java:29-39`). (verified)
- Two queues: request tasks (`h2.a`, `ne/p.java:159`) and frame writes (a second "send queue",
  `ne/p.java:237`).
- If the services were still not discovered after 3 tries, the app disconnects and reports
  `CONNECTION_FAILED` (`ne/p.java:213-218`). (verified)
- **The phone runs no GATT server** (no `BluetoothGattServer`/`openGattServer`/`BluetoothLeAdvertiser`,
  no ANCS `7905F431…`, no ANS `0x1811` anywhere). Dash notifications are a vendor frame, see
  [Dash notifications](#dash-notifications-callmail-icons). (verified)

| Role | UUID |
|---|---|
| Scan filter (service) | `C5664E44-D60B-4342-8B7B-35BD6AE28E3F`, `4fbb7609-f50a-40c6-89a9-9be7ea880e95`, `99eb13ac-42c9-4745-8b76-c30141401ce5` |
| BLE5 service | `92faec07-c075-4b7c-a6c2-bbd1d1a150f5` |
| Write characteristic | `acf1b15c-10f9-4942-a32d-f9e019b95402` |
| Notify characteristics (enable via CCCD 0x2902) | `3aabbb34-eac0-40f5-9d50-3a1ee6787136`, `02fad1bd-358e-441c-b296-fe874af38a7e`, `5e119eba-35a7-4463-a7af-7fa40a302350` |

### Frame layout

`[0]` frame type · `[1]` length − 3 · `[2]` counter (incremented by the meter) · `[3..4]` echoed request
or `FF FF` · then 10-byte blocks `[marker][block id][8 data bytes]`, marker `05` for data, `02` for ASCII
text, block bytes `FF` when absent. Requests are 3 bytes `TYPE 00 00`, or a block frame for the settings.

## Connect sequence

`ne/p.e()` (reached from `onMtuChanged`, `ne/x.java:224`, or after bonding, `x0.java:220`) enables the
three notifies and runs `ne/c.java` case 2, which queues in this order (verified):

`03 · 40 · 1A · 1D · 47 · 0B(phone name) · 41 · 1B(read) · 48(read) · 1E · clock sync · 08 · 45 · 42`

- 50 ms between frames; the four config frames (0x40, 0x1A, 0x1D, 0x47) are asked again when the meter
  answers "not ready" (500 ms pause, max 5 tries, then disconnect; `ne/p.java:171-194`). (verified)
- **A timeout during the sequence is fatal**: the app clears the queue and disconnects
  (`a0/s.java:65-132`, `ne/j.java:54-69`). Only the runtime 0x13/0x14 flows advance on timeout.
  (verified)
- ACKs are type `0x20` with the request type echoed in byte 3 (byte 3 ∈ {66 for 0x42, 18 for 0x12,
  19 for 0x13, 20 for 0x14, 8 for 0x08, 69 for 0x45}; the 0x0B ACK is not checked). (verified)
- 0x42 (`42 00 01`) is for EV/HEV bikes; KawaNav leaves it out. What an engine bike does with it is
  unknown.
- KawaNav's `KawaProtocol.startup(level, …)` mirrors this order; **release builds use the read-only
  level** (no 0x08, no phone name, no clock sync), debug builds can pick the full one.

## Vehicle data frames

### 0x40 – vehicle info config (`Ble5Value.i`)

- Request `40 00 00`, **connect-time one-shot** (`ne/c.java:226-230`, only caller `ne/c.java:71`).
  (verified)
- 2-bit nibbles say which values the bike has **and which scaling** (nibble 0 and 1 both mean
  "present", with two different scalings; 2 and 3 mean absent):
  wheel speed `(b9&0x0C)>>2` · engine speed `b9&0x03` · gear `(b10&0xF0)>>4` · throttle `(b10&0x0C)>>2` ·
  acceleration `b10&0x03` · lean `(b11&0xC0)>>6`; the same layout continues through bytes 7-12 and 27-31
  (fuel, temperatures, odometer, trips, …) (`Ble5Value/i.java:112-134`, stored in `Ble5McInfoConfigTable`,
  `a0/s.java:172-219`). (verified)
- The nibbles are read once per connection and reused; the same data can come per model from the cloud
  record (`oh/g.java:36-40`). (verified)

### 0x41 – vehicle info snapshot (`Ble5Value.h`)

- Request `41 00 00`, 85 bytes: total fuel used, ECU and meter battery voltage, odometer, fuel gauge,
  average mileage, trip A/B, average speed, outside temperature, range and its symbol, fuel
  consumption, total time, **and max lean left/right** (`o0.java:434-435`). KawaNav decodes everything
  except max lean (byte offsets unknown). (verified)
- Asked for again when the info screen updates, and at connect — **there is no interval poll on BLE5**
  (`oh/b.java:152-177`). (verified)

### 0x45 – riding log extension (`Ble5Value.q`)

- Request `45 00 00` in the connect sequence, then receive-only. 55 bytes: total distance
  (u32 × 0.1 km), engine fuel rate (u16 × 0.02), water/oil/intake temperature, instant fuel
  consumption, TPMS pressures and warning bits. Speed/rpm/gear/throttle/lean are **not** here.
  (verified)

### 0x4A – live riding data (`Ble5Value.s`)

- **Never requested** by the app; unsolicited meter traffic while the link is up (`qe/b.java:818`,
  parser `d(byte[])` `qe/b.java:91-178`). Frame dropped when `u8(5)==0xFF && u8(6)==0xFF`.
  (verified; "streams merely while connected" inferred)
- Values and scaling, the nibble from 0x40 selecting the branch (verified):

| Value | mode 0 | mode 1 |
|---|---|---|
| wheel speed | `u16(9,10) × 0.01220703125` (= /81.92) | `((u8(9)&1)<<8) \| u8(10)` km/h (Z500) |
| engine rpm | `((u8(11)&0x7F)<<8) \| u8(12)` | `u16(11,12) × 0.25` |
| gear | `u8(13)&0x0F` (presence only) | same |
| throttle | `u8(14) × 0.78125` (rider request) | `u8(14) × 0.3921568627` (mechanical, Z500) |
| acceleration | `(((u8(17)&1)<<8) \| u8(18)) × 0.01 − 2.0` g | `… × 0.01` g |
| lean | `(((u8(19)&7)<<8) \| u8(20)) × 0.1953125 − 100`° (11-bit) | `… × 0.1`° (12-bit) |
| fuel injection | `u16(7,8) × 1e-4` | `… × 100/140` |
| wheelie angle | `(((u8(22)&7)<<8) \| u8(23)) × 0.09765625` | `… × 0.1` |
| rider torque request (27-28), engine torque request (29-30), engine torque (31-32) | `(((hi&0x3F)<<8) \| lo) × 0.2` | `((hi&0x0F)<<8) \| lo` |

- TCS high/low nibbles at byte 24. (verified)
- Block 35 (absent when byte 35 or 36 is FF): byte 37 ABS bits (0x10 inspection mode, 0x0C system error,
  0x01 front / 0x02 rear acting); byte 38 front master pressure, 39 front caliper (`× 0.1953125`, FF =
  absent), 40 rear master, 41 rear caliper — the masters and the rear caliper are kept raw by Rideology.
  (verified for the layout, not for any brake scaling)
- Block 45: byte 47 ABS target (bits 7-5) and ABS indicator (bits 2-0), byte 48 bit 0 switching info.
- Block 75: byte 77 damping value; byte 78 bit 2 suspension calibration, bit 1 suspension status, bit 0
  suspension ECU status.
- Block 85 (frame longer than 87 bytes): byte 87 bit 1 cruise control working, bit 0 cruise mode; byte 88
  set speed. (layout verified in `qe/b.java:153-174`; no capture from a bike that has these yet)
- KawaNav `BikeFrames.Telemetry` selects the mode **from the 0x40 nibble, per value**, like the app.

### 0x4B – suspension / IMU (`Ble5Value.r`)

Streamed, 45 bytes on the Z500 (longer on bikes with the extra block): suspension strokes and stroke
speeds, IMU axes, filtered wheel speeds, TCS levels, KQS flags, wheelie flag (`qe/a.java:384-433`).
All `FF` on bikes without them (Z500, Z900?). (verified)

| Bytes | Value |
|---|---|
| 7-8, 9-10 | rear / front stroke, `u16 × 0.015625` mm (block 5) |
| 11-12, 13-14 | rear / front stroke speed, `u16 × 0.001` |
| 17-18, 27-28, 37-38 | rotation rates "psi p1/p3/p2", little-endian, `x × 163.84/32768 − 163.84`, 0 = absent |
| 21-22, 31-32, 41-42 | accelerations ay / ax / az, little-endian, `x × 4.1768/32768 − 4.1768` g, 0 = absent |
| 47-48, 49-50 | filtered front / rear wheel speed, `u16 × 400/32768` km/h |
| 51 | TCS high (bits 7-5), TCS low (4-2), KQS down (1), KQS up (0) |
| 52 | wheelie flag (bit 0) |

Block 45 counts as absent only when **both** bytes 45 and 46 are FF (the other blocks: either one).

### Units

Speed is stored and exported in km/h (converted to mph only at display), rpm in rev/min, lean in
degrees, temperatures in °C. (verified)

## Riding log stream: is there a start/stop?

- There is **no dedicated start or stop frame** for a ride log: recording is local, and the record button
  updates the app state and the RIDELOG icon in the 0x13 frame (`de/q.java:37-133`,
  `MyApplication.java:335-340`). (verified) Starting a recording does raise `START_R_LOG_MANUAL`
  (`u6/fa.java:141`), which asks the meter for 0x45 again with the same `45 00 00` request used at
  connect (`ne/c.java:89-94`): KawaNav sends it when a recording starts and no 0x45 has arrived.
- The stream (0x45/0x4A/0x4B) therefore flows while the link is up; the meter does the timing.
  (verified that no trigger frame exists; the meter's own behaviour still needs a capture)
- **0x08** is a connect-handshake frame, sent once per connection (`ne/c.java:216`,
  `08 0C 00 FF FF 0A 08 01 78 03 E8 00 C8 00 64`), never on ride-log start, with four unlabelled
  parameters 120 / 1000 / 200 / 100 (probably periods; **unknown**). Whether the meter needs it before
  streaming 0x4A is **unknown** — it is in KawaNav's "full" connect level for that reason.
- Sample rate: nothing is hardcoded; the app uploads `frequencyRidingLog` and the meter decides
  (**unknown**). Rows carry `elapsed_msec`, so the rate is whatever arrives.

## Vehicle settings (riding mode, power, KTRC, KQS, KEBC, profile name)

**Frame type 0x48, block 0x09; byte 7 = 0 read, 1 write. There is no 0x49.** (verified)

- READ `48 2A 00 FF FF 05 09 00 …` (45 bytes, `ne/c.java:177-189`).
- WRITE `48 2A 00 FF FF 05 09 01 …` (`oh/b.java:222-256`), all other bytes `FF` except:

| Byte | Content |
|---|---|
| 8 | `(KtrcWire & 0x0F) << 3 \| (presetMode & 0x07)` |
| 9 | `(KQS & 0x03) << 5 \| (KEBC & 0x03) << 2 \| (power & 0x03)` |
| 15-38 | profile name trailer: `05 04`+8 bytes, `05 05`+8, `05 06`+2 (18 UTF-8 bytes, zero padded) |

- **KTRC on the wire is not the raw level**: stored OFF (1) → field **10**; stored 2..6 (levels 1..5) →
  fields 1..5; anything else → 0 (`oh/b.java:184-199`, inverse `datamodels/b.java:60-85`). (verified)
- KEBC bits are forced to 0 for models where KEBC is unavailable (`oh/b.java:239-244`). (verified)
- Absent fields stay all-ones inside their mask (`oh/b.java:234-247`). (verified)
- **KECS, preload, load adjustment and damping are read but never written** (bytes 10-14 stay FF;
  `oh/b.java:200-210` populates them but they are not emitted). (verified)

### Values (index into the label arrays `ph/d.java`)

- Riding mode `f13051b` = [ERROR, Sport 1, Road 2, Rain 3, Rider 4]; the 7-entry variant `f13052c`
  (Rider1..4 = 4..7) is used when the meter allows it (`ph/d.java:94-96`). (verified)
- Power `f13053d` = [ERROR, Full 1, Medium 2, Low 3]. (verified)
- KTRC `f13054e` = [ERROR, OFF 1, „1"..„9" 2..10] (before the wire remap above). (verified)
- KEBC and KQS share `f13057h` = [ERROR, OFF 1, ON 2, KEBC-light 3]; KECS `f13055f` = [ERROR, Hard,
  Normal, Soft]; preload `ph/d.i` = [ERROR, Rider, Rider+L, Rider+P+L]. (verified)
- The preset buttons set three values at once: Sport → power 1 / KTRC 2 / KECS 1; Road → power 1 /
  KTRC 3 / KECS 2; Rain → power 3 / KTRC 4 / KECS 3 (`Ble5VehicleSettingViewModel.java:123-183`).
  (verified)

### Flow and the meter's answer

- The answer to the write is a 0x48 frame whose **byte 7** carries the result (`Ble5Value.x.f11418a`,
  `qe/b.java:710`), read only while the app is in its "changing" state (`ne/g.java:243-259`):
  - `0x00` → success, state cleared.
  - `0x10` → **wait**: the app cancels its own 4 s reject timeout, stays in the changing state and
    sends nothing (`ne/g.java:252-255`, `androidx/appcompat/widget/a.java:47-50`). It is not an error
    and not a resend; the app waits for a later 0x48 with result 0.
  - anything else → rejected (`ERR_REJECT_SETTING`).
- There is **no post-write re-read**: the reply carries the meter's new values. There is no 0x20/0x30
  handshake for settings. (verified)
- Write timeout: 4000 ms (`oh/b.java:218`; general settings `ne/f.java:32`), startup reads 3000 ms.

### Preconditions

- Tuning write (riding mode/power/KTRC/KQS): gate `he/j.n()` (`he/j.java:86-136`) checks, in order,
  connected, model supported (not "change-not-implemented", not "older 2835"), then **stationary** —
  maintained by `services/a.java:49-53`: the flag drops when the newest frame is older than 1 s or
  `Ble5Value.s.f11351b < 5` km/h. **The threshold is 5 km/h, not 0.** (verified)
- General-settings write (shift lamp, clock): only "connected" (`he/b.java:28`). (verified)
- **No neutral, throttle-closed, DTC or ignition check exists in either path** — KawaNav is stricter on
  purpose (see README/plan): it requires standing still, and its own checks are its own choice.
- Meter-side rejection: an error frame with `bArr[2]==3` → `ERR_REJECT_SETTING` (`ne/a0.java:327-329`).

## Meter general settings: clock and shift lamp (0x1B)

- READ: Rideology builds `1B 0C 00 FF FF 05 0A 00 …` and then **fills the whole array with FF**
  (`ne/c.java:237-238`), so what it actually sends is 15 bytes of `FF`; KawaNav sends the frame as built.
  Which of the two the meter answers needs a capture. Answer: byte 7 result, byte 8 shift lamp
  (`status = bits 7-6`, `level = bits 5-0`, level 1..61 → rpm `(level−1)×250 + 3000`), bytes 9-14
  day, month, year−2000, hour, minute, second. (verified)
- WRITE `1B 0C 00 FF FF 05 4A 01 <byte8> <day> <month> <year−2000> <hour> <min> <sec>` — the block id
  flips to `0x4A` **and** byte 7 becomes 1 (`h9/b.java:401-404`). (verified)
- **Clock sync**: runs on **every connect**, skipped only when the 0x1A clock gate nibble
  (`byte7 bits 7-6`) is not 0 (`ne/c.java:273-290`). It **preserves** the shift-lamp status/level read
  just before. (verified)
- Shift-lamp-only write: keeps the lamp byte and sets the six time fields to "leave unchanged" — five
  become `FF`, but the **year byte becomes `0x2F`** because it is sent as `(byte)(x − 2000)` and x = −1
  (`ne/f.java:34-43`). The frame is `1B 0C 00 FF FF 05 4A 01 <byte8> FF FF 2F FF FF FF`. (verified)

## Capabilities (0x1A, `Ble5Value.b`)

- Turn-by-turn supported unless the high nibble of byte 12 is 15; Rideology then sends no nav frames.
  (verified)
- Status icons supported unless byte 11 low nibble = 15 and the 2-bit fields byte 12 bits 3-2, 1-0 and
  byte 13 bits 7-6, 5-4 are all 3. (verified)
- Clock: byte 7 bits 7-6 = clock gate (0 = the app may sync the clock). Shift lamp: byte 7 bits 1-0 =
  mode (0 = OFF/tach/LED/LED+tach list, 3 = OFF/ON, 1 and 2 = not settable), byte 8 = max level,
  byte 9 = min level (settable when min ≥ 1 and max ≤ 61). Byte 10 flags day/month/year/hour, byte 11
  flags minute/second (0 = settable). (verified)
- A field whose own flag is not 0 is treated as unknown and its group is hidden in the app's UI.
  (verified)

## Dash notifications (call/mail icons)

- **Vendor frame 0x12, 15 bytes, written to the write characteristic** (`oh/b.java:301-323`,
  `ne/p.java:200`):
  `12 08 00 FF FF 0A 12 <EVENT> FF <CATEGORY> <STATE> 00 00 00 00`
  - EVENT `Ble5Value.f`: 0 = added, 2 = removed.
  - CATEGORY `Ble5Value.e`: **1 = incoming call, 6 = mail/message** (only these two exist).
  - STATE: 1 on add, 0 on remove.
- Input is a `NotificationListenerService` classifying status-bar notifications by package
  (`MyNotificationListenerService`): calls from dialer packages (`com.google.android.dialer`,
  `com.samsung.android.dialer`, `com.sonymobile.android.dialer`, `android.dialer`); mail/messages from
  `com.google.android.gm`, `jp.co.yahoo.android.ymail`, `jp.naver.line.android`,
  `com.google.android.apps.messaging`, `com.kddi.android.cmail`. Only two icons, no counts. (verified)
- The app emits **both** category frames on every change (the per-category switch only decides
  add vs remove), so a category that is off still gets a REMOVED frame. (verified)
- Clearing is app-driven, never a timer: on notification removed it re-scans the active notifications;
  on posted it only adds; switching a category off clears the icon immediately. (verified)
- The frame is dropped while the bike is not connected (`oh/b.java:293`). (verified)

## Meter indication frame (status icons, 0x13)

`13 0C 00 FF FF 05 00 (BATTERY<<4 | VOICE) (HEADSET<<4 | RIDELOG) FF FF FF FF FF FF`

- BATTERY (`Ble5Value.j`): 0 low (≤10 %) · 1 ≤30 % · 2 ≤70 % · 3 >70 % · 7-10 charging with 0-3 bars ·
  14 error · 15 n/a.
- VOICE (`Ble5Value.m`): 0 idle · 1 listening for wake word · 2 listening for command · 3 processing ·
  14 error · 15 n/a. In the base app only 0 is reachable (everything else lives in the voice
  dynamic feature).
- HEADSET (`Ble5Value.k`): 0 off · 1 on · 2 blink · 3 n/a.
- RIDELOG (`Ble5Value.l`): 0 off · 1 recording · 2 paused · 14 error · 15 n/a. Rideology sends 0, 1 or 2;
  **14 is never sent** (`MessageToMeterIndication.java:87-100`). (verified)
- Cadence: immediately and then every **5000 ms** while connected, plus an extra frame when the battery
  watcher fires (~60 s) or a voice event arrives (`MeterIndicationNotify.java:17/93/97`,
  `BatteryLevelWatcher.java:15/75`). Each frame is awaited with a 3000 ms ack (type 0x20, byte 3 = 19).
  (verified)

## Turn-by-turn frame ("NaviControlPoint", `oh/b.java`) – 35 bytes

```
idx  0  1  2  3  4 | 5  6  7     8..12  13        14 | 15 16 17    18 19 20      21..24 | 25..34
     14 08 00 FF FF | 05 70 FLAG<<4 FF×5 UNIT<<4   FF | 05 71 TURN  DIST(24-bit BE) FF×4  | FF×10
```

- FLAG (`Ble5Value.n`): 0 navigating, 1 rerouting, 2 arrived, 3 no navigation, 15 n/a. Rideology never
  sends 1; the runtime `clear()` uses **nibble 15 (NOT_AVAILABLE)**, not flag 3 — flag 3 comes from the
  turn-by-turn builder of the navigation feature (`MeterIndicationNotify.java:58-73`). Both work on the
  Z900 dash as "navigation ended" (KawaNav sends flag 3; verified on the bike).
- UNIT (`Ble5Value.p`): 0 km, 1 m, 2 mile, 3 ft, 4 yd, 15 n/a. App sends 1 (m) for metric, 3 ft / 4 yd
  for imperial.
- DIST: integer in the base unit (m/ft/yd), rounded to 10 when ≥10, capped (9 999 000 m).
- TURN (`Ble5Value.o`):
  0 straight · 1 left · 2 right · 3 sharp L · 4 sharp R · 5 slight L · 6 slight R · 7 U-turn L ·
  8 U-turn R · 9 fork L · 10 fork R · 11 fork middle · 12 ramp on L · 13 ramp on R · 14 ramp off L ·
  15 ramp off R · 16 merge L · 17 merge R · 18-29 roundabout clockwise exit 1-12 ·
  30-41 roundabout counter-clockwise exit 1-12 · 42 departure · 43 destination · 44-55 waypoint 1-12 ·
  0xFF none.
- Event-driven: one frame per navigation update, no fixed rate. KawaNav repeats it every second.
- HERE → turn mapping lives in `mh/o.h()`; HERE `RIGHT_ROUNDABOUT_EXITn` → CCWn, roundabout pass →
  straight.

Example frames (hex):
```
right in 250 m        140800FFFF057000FFFFFFFFFF10FF0571020000FAFFFFFFFFFFFFFFFFFFFFFFFFFFFF
left in 80 m          140800FFFF057000FFFFFFFFFF10FF057101000050FFFFFFFFFFFFFFFFFFFFFFFFFFFF
roundabout exit 2 400m 140800FFFF057000FFFFFFFFFF10FF05711F000190FFFFFFFFFFFFFFFFFFFFFFFFFFFF
clear / no navigation 140800FFFF057030FFFFFFFFFFF0FF0571FF000000FFFFFFFFFFFFFFFFFFFFFFFFFFFF
```

## Text to the meter (phone name, 0x0B)

The only text Rideology sends to a BLE5 meter is the phone's model name (`Build.MODEL`), once while
connecting. 35 bytes, zero-filled, UTF-8 in 8-byte parts:

```
0B 20 00 FF FF | 05 01 name[0..7] | 05 02 name[8..15] | 05 03 name[16..23]
```

## 0x03 – model info / VIN

35 bytes with ASCII blocks `02 71 <8>`, `02 72 <8>`, `02 73 <1…>`; the VIN is the concatenation
(13 or 17 characters). Sample (Z500): `ML5ER500FADA55558`. (verified — but see the privacy note:
KawaNav never logs this frame.)

## Riding log data model and CSV export (what Rideology records)

- Four tables per BLE5 bike: 0x45 → `Ble5RidingLogTable`, 0x4A → `Ble5RidingLogMidTable`, 0x4B →
  `Ble5RidingLogHighTable`, 0x41 → `VehicleInfoLog` (with max lean L/R). (verified)
- CSV export (`sf/c.java`): 7 metadata rows (`UserAccount Name`, `Title`, `Comment`,
  `Vehicle Nickname`, `Distance(km)`, `Time(min)`, `R.Score`), then a **fully quoted** header row, then
  one row per sample. BLE5 engine bikes use 14 columns (`sf/b.a()`):

```
"elapsed_msec","gps_latitude","gps_longitude","instant_fuel_consumption","water_temperature",
"boost_temperature","engine_RPM","wheel_speed","acceleration","throttle_position","boost_pressure",
"gear_position","lean_angle","rideology_score"
```

  (units: km/L, ℃, G, %, %, °; the label literals are inferred). Speed/distance label is hardwired to
  km. Encoding Shift-JIS, comma separated, LF line endings; only the header row is always quoted.
  File name `Riding_<label>_yyyyMMddhhmmss.csv` under `<filesDir>/LogExport/`. (verified)
- Known quirk in the app's BLE5 row writer: column 6 (header says `boost_temperature`) is filled with
  the boost **pressure** value, and column 11 repeats it (`sf/c.java:163-208`). KawaNav should write the
  header names but its own sane values.
- Gear is written as `N`, `1`..`6`, else `Error` (11..14 fall through to `Error`). (verified)
- **R.Score is not computed in the app**: per-sample and total scores only come from Kawasaki's cloud,
  and the log is uploaded as JSON. Formula/inputs **unknown**. (verified)
- Which channels a ride log shows is decided by per-log recorded flags plus a per-model spec
  (`assets/vehicle_settings/*.json`), not by the 0x40 flags; only the odometer flag is read for the
  mileage tile. (verified)

## Still unknown / to verify on the bike

- Semantics of the 0x08 parameters (120 / 1000 / 200 / 100) and whether the meter needs 0x08 before
  streaming 0x45/0x4A.
- Riding-log sample rate (meter/frequency controlled).
- Byte offsets of max lean L/R in 0x41.
- Whether the meter treats 0x14 nibble 15 or flag 3 as "navigation ended" (both work with KawaNav).
- R.Score formula (server side).
- Whether the meter shows an "Apply on the cluster" step: the `0x10` result suggests a two-stage apply,
  but no code or string proves a rider action on the dash.
- Behaviour of the shift-lamp-only write's `0x2F` year byte.
- Which byte of a *short* ACK (KawaNav reads byte 4) is the general accept/reject — Rideology only
  parses the settings result (byte 7 of a 0x48 reply) and its generic ACK wrapper is raw.
- Frame types not decoded by KawaNav: 0x01, 0x07, 0x12/0x0A (app→meter), and the EV/HEV family
  (0x11, 0x16, 0x23, 0x24, 0x31, 0x32, 0x35, 0x42, 0x52, 0x62, 0x0E).

## What KawaNav implements today

| Part | Frames | State |
|---|---|---|
| Turn-by-turn | 0x14, 0x13 | works on the bike (v0.1–v0.3, verified) |
| Bike data | 0x03, 0x40, 0x41, 0x45, 0x4A, 0x4B, 0x1A, 0x1B, 0x1D, 0x1E, 0x47, 0x48 | decoded and shown; the scaling comes from the 0x40 nibbles, not yet compared with a dash over a whole ride |
| Connect sequence | 03, 40, 1A, 1D, 47, 0B, 41, 1B, 48, 1E, 08, 45 | sent after connecting (read-only level in release; the full one, with 0x08 and the phone name, in debug builds) |
| Ride recording | 0x45, 0x4A + GPS | records locally; nothing on the meter drives it (the app sends no start/stop frame, and neither does KawaNav) |
| Dash icons | 0x13 (battery, headset, riding log), 0x12 (call, mail) | sent; the riding-log icon follows the recorder. Not yet seen on a dash |
| Clock | 0x1B write | optional setting, off by default; preserves the shift lamp |
| Settings write | 0x48 (block 09, byte 7 = 1) | debug builds only until tried on a bike: riding mode, power, KTRC, KQS |
| Shift lamp write | 0x1B (block 0x4A) | debug builds only; range and modes taken from the meter's 0x1A answer |

Everything else in this document is protocol knowledge KawaNav does not use yet.

## Verified on the bike (Z900 2026 "Kawasaki-ZR900S", 2026-10-10)

- Turn frames work with no init sequence: connect, MTU, enable notifies, write the frame.
- FLAG 1 (rerouting) and FLAG 2 (arrived): no visible effect. Rideology never sends 1; it sends 2 only on
  HERE `onDestinationReached` together with the last turn (normally 43 Destination).
- Turn ids 44-55 (waypoints): nothing shown. Rideology never sends them.
- => Useful set for a replacement app: turns 0-43, flags 0 (navigating) and 3 (clear).
- Status icon frames (0x13): the dash redraws them about once a second; faster changes are not shown.
- Pairing without Rideology (KawaNav's companion association + createBond) works.
