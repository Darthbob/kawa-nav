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

- 50 ms between frames; the config frames are asked again when the meter answers "not ready" (500 ms
  pause, max 5 tries, then disconnect; `ne/p.java:171-194`). (verified)
- **What "not ready" means**, per frame (`i.java:174-181`, `y.java:71-75`, `qe/a.java:348`,
  `u.java:97-101`): a segment is still being prepared when its header bytes are not both `FF` **and** the
  low bit of the segment's last byte is 0. The frame is usable when at least one segment is ready, and
  Rideology only retries the frames that carry a config payload — **0x40 and 0x47**; 0x1A and 0x1D go
  through the same routine but are not re-asked on their own. (verified)
- KawaNav keeps its own queue instead: three attempts per frame id, no blocking sleep, and it now retries
  0x1A, 0x40, 0x1D **and 0x47**. Its 0x1D test is stricter than Rideology's (it wants the `05` block
  marker, Rideology only that the header is not `FF FF`); on every real frame both agree.
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
  "present", with two different scalings; 2 and 3 mean absent). The full map, byte by byte
  (`Ble5Value/i.java:112-183`, stored in `Ble5McInfoConfigTable`, `a0/s.java:172-219`). (verified)

| Byte | Bits 7-6 | Bits 5-4 | Bits 3-2 | Bits 1-0 |
|---|---|---|---|---|
| 7 | total distance travelled | total fuel consumed | engine fuel rate | ECU 12 V battery (3 = unsupported) |
| 8 | water temperature | oil temperature | intake air temperature | boost temperature (never read by any parser) |
| 9 | boost pressure (never read) | fuel injection | wheel speed | engine speed |
| 10 | gear position | throttle position | acceleration | — |
| 11 | lean angle | wheelie flag | wheelie angle | TCS level high |
| 12 | TCS level low | rider torque request | engine torque request | engine torque |
| 13-26 | — | — | — | — (unused) |
| 27 | odometer (3 = unsupported) | fuel gauge | average fuel mileage (3 = unsupported) | meter 12 V battery |
| 28 | trip A | trip B | average speed | outside air temperature |
| 29 | range symbol | range | fuel consumption | total time |
| 30 | — | — | — | — (unused) |
| 31 | — | — | instant fuel mileage | — |
| 32 | max lean left | max lean right | — | — |
| 33 | odometer valid bit present | meter 12 V valid bit present | average mileage valid bit present | instant fuel valid bit present |
| 33 | trip A valid bit present | trip B valid bit present | average speed valid bit present | outside temperature valid bit present |
| 34 | — | range valid bit present | total time valid bit present | — |

  The "present" nibbles of bytes 33-34 are themselves nibbles like the others: **0 means the bike sends
  that value's valid bit**, 1 means it does not (see the 0x41 table below). Bytes 13-26 and 30 are not
  used by any parser. Boost temperature and boost pressure are configured but read from no frame, in
  Rideology either (`Ble5Value/i.java` is their only user).
- The nibbles are read once per connection and reused; the same data can come per model from the cloud
  record (`oh/g.java:36-40`). (verified)

### 0x41 – vehicle info snapshot (`Ble5Value.h`)

- Request `41 00 00`, 85 bytes: total fuel used, ECU and meter battery voltage, odometer, fuel gauge,
  average mileage, trip A/B, average speed, outside temperature, range and its symbol, fuel
  consumption, total time, **and max lean left/right** (`o0.java:434-435`). KawaNav decodes everything
  including max lean: bytes 53/54 (7 bits, 127 = none), with the gate in 0x40 byte 32 and the "not
  current" bits in byte 47 (bits 3 and 2). (layout from i.java / o0.java, not yet compared with a dash)
- **Valid bits** (each carries a value only with its 0x40 nibble at 0; a bit set means "not current"):
  byte 27 bit 6 odometer, bit 5 meter 12 V, bit 4 average mileage; byte 37 bit 7 trip A, bit 6 trip B,
  bit 5 average speed (this byte also holds trip A's highest bit, bit 0); byte 47 bit 7 range, bit 6
  total time. The two remaining bits of byte 47 — **bit 3 = max lean left, bit 2 = max lean right** —
  are the only valid bits Rideology reads without a config gate (`qe/b.java:419-422`; they reach
  `setMax_lean_angle_L/R` at `qe/b.java:541-544`). (verified)
- **Total time** (bytes 51-52, 13 bits, 8191 = none) is in **minutes**, not hours: a four-month-old
  Z900 reported 4959, which as hours would be 206 days of running, as minutes 82 h 39 min. Rideology's
  own row says only "Total time" with no unit (`vehicle_list_item_totaltime`, from the APK's resources),
  so the "h" KawaNav used to append was its own guess. KawaNav shows hours and minutes now.
  (inferred from the value; not yet checked against the dash's own total-time display)
- **Average fuel mileage** (bytes 32-33, × 0.1) and **fuel consumption** (bytes 49-50, × 0.1) are in
  **km/L**, whatever the dash's own unit setting: a Z900 with the dash set to L/100 km sent 19.2,
  which is its rider's 5.2 L/100 km (100 / 19.2; issue #9). KawaNav shows them in the unit the rider
  picks, converting as Rideology does (`u6/ib.java`: 100 / v for L/100 km, × 3.78 / 1.61 for MPG US,
  × 4.54 / 1.61 for MPG UK).
- Values the meter fills in when it has none are dropped, not shown: `0xFFFFFFFF`, `0xFFFFF` (1048575),
  `0xFFFFF` (13107.1), `8191`, `1023`, `255`, `102.3`, `25.5`, `15`, `127` (`qe/b.java:520-542` and the
  `h.a()`/`q.a()` helpers). (verified)
- Asked for again when the info screen updates, and at connect — **there is no interval poll on BLE5**
  (`oh/b.java:152-177`). (verified)

### 0x45 – riding log extension (`Ble5Value.q`)

- Request `45 00 00` in the connect sequence, then receive-only. 55 bytes: total distance
  (u32 × 0.1 km), engine fuel rate (u16 × 0.02), water/oil/intake temperature, instant fuel
  consumption, TPMS pressures and warning bits. Speed/rpm/gear/throttle/lean are **not** here.
  (verified). Water/oil/intake temperature are read when their 0x40 nibble is **1**, not 0; every other
  value here wants 0 (`qe/b.java:52-62`). (verified)
- Temperatures: water `b17 − 40`, oil `b19 − 60`, intake `b20 − 40` °C. Besides `FF`, KawaNav drops `00`,
  the bottom of each scale (−40 °C, −60 °C), which no running engine reads: it is what a sensor that
  reads nothing gives. The Z500 sends `00` as its oil temperature. This is KawaNav's rule, not Rideology's.
- KawaNav also reads a temperature only once the 0x40 is in. Its other gates treat a config not read yet
  as Rideology's default-initialised one (every nibble 0); for the temperatures that default means "not
  read", since they want 1. It matters because the meter keeps streaming 0x45 before KawaNav has the
  config, and a temperature the bike does not have can come as `00` (the Z500's oil byte, which its
  config marks absent). A Z900 ride had exactly that, −60 °C oil in its first second with bike data
  (below).
- **Instant fuel consumption** (`b27` bits 1-0 and `b28`, × 0.1) is in **km/L**, not L/100 km: on the
  62 km ride of 2026-10-17 integrating it over time gives 3.6 L, 5.8 L/100 km, while read as L/100 km
  it would come to 14.3 L, i.e. 23 L/100 km, far from a Z900's. `99.9` is the top of the scale (it
  appears in seconds with the throttle nearly closed at 40–50 km/h), the value is absent at a
  standstill, and it falls as the throttle opens and as speed rises (median 22 km/L at closed
  throttle, 20–22 at 40–60 km/h, 10 above 100 km/h). (verified on 2026-10-17's rides)
- **Long form** (the frame the Z900 does not send; longer than 58 bytes) repeats what the 0x41 carries,
  with a second set of valid bits in byte 57 (`qe/b.java:70-82`). (verified)

| Bytes | Value | Read when |
|---|---|---|
| 57-59 | odometer, `((b57&0x0F)<<16) \| be16(58)` km | length > 59 and the 0x40 odometer nibble is 0 |
| 60 (low nibble) | fuel gauge bars | length > 60 and the fuel-gauge nibble is 0 or 1 |
| 62-63 | average fuel mileage, `((b62&3)<<8 \| b63) × 0.1` | length > 63 and the average-mileage nibble is 0 |
| 64 | meter 12 V battery, `b64 × 0.1` | length > 64 and the meter-battery nibble is 0 |

  Byte 57 also carries the "not current" bits for these three: bit 6 odometer, bit 5 meter battery,
  bit 4 average mileage — same meaning as in the 0x41. Byte 27 bit 4 is the instant fuel's own "not
  current" bit, read when the 0x40 inst-fuel nibble (byte 33 bit 4) is 0. The same placeholders are
  dropped (`0xFFFFF`, `15`, `102.3`, `25.5`).

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
| wheelie angle | `(((u8(22)&7)<<8) \| u8(23)) × 0.09765625` | `(((u8(22)&0x0F)<<8) \| u8(23)) × 0.1` |
| rider torque request (27-28), engine torque request (29-30), engine torque (31-32) | `(((hi&0x3F)<<8) \| lo) × 0.2` | `((hi&0x0F)<<8) \| lo` |

Wheelie angle: Rideology's nibble-1 branch masks 0x0F, KawaNav does the same; its nibble-0 branch uses 0x07
in both. Rideology adds the two bytes there (`(bArr[22] & 7) + bArr[23]`) where every neighbouring value
shifts the high byte; KawaNav keeps the shift, which the masked nibble makes equivalent for values in range.

**The acceleration is a specific force, gravity included.** `acc_g` carries the component of gravity
along the bike's forward axis, not only the bike's own acceleration: at constant speed the 2026-10-17
rides read about +0.010 g per +1 % of upgrade (+0.07…+0.08 g on +5…+18 % climbs, −0.07…−0.10 g on
descents). The slope-corrected value is `acc_corr ≈ acc_g − 0.0098 × slope_%`; the empirical
coefficient is 0.0093–0.0100 and the residual about 0.05 g, because the GPS slope adds noise. Without
the correction a −12 % descent looks like braking. (inferred from the rides)

- **Byte 24: the two TCS nibbles** (`Ble5Value.s.f11358k`/`f11359l`, written to the realm columns
  `tcs_level_hb`/`tcs_level_lb` of the mid log, `a0/s.java:301-302`). Each nibble is decoded only when its
  0x40 gate (byte 11 bits 1-0 for the high one, byte 12 bits 7-6 for the low) is exactly 0. What the
  numbers mean is **not documented by Kawasaki either**: no code in Rideology compares, thresholds,
  averages or displays them, they are in no chart, no tile and **not in the CSV export** (`sf/c.java` has
  no such column); they only reach the realm tables and the JSON backup (`u6/da.java:139-143`). The same
  two column names are also filled from two other sources (see 0x4B below). A whole recorded ride on the
  Z900 had this byte at `00` in all 1448 seconds that carried it: a real 0, not a suppressed reading.
  (verified)
- Block 35 (absent when byte 35 or 36 is FF): byte 37 ABS bits (0x10 inspection mode, 0x0C system error,
  0x01 front / 0x02 rear acting); byte 38 front master pressure, 39 front caliper (`× 0.1953125`, FF =
  absent), 40 rear master, 41 rear caliper — the masters and the rear caliper are kept raw by Rideology.
  (verified for the layout, not for any brake scaling)
- **ABS, on the four rides of 2026-10-17:** byte 37 read `00` all the time but for **8 isolated
  seconds** where it read `02`; only some of those are under braking (one at throttle closed with
  −0.15 g, one while accelerating at +0.14 g), and the front-to-rear wheel difference in those seconds
  is no larger than the ride's usual +0.5 km/h. If `02` is "rear acting", it is not an obvious rear
  cut-in, so what that byte's non-zero values say is still open. The ABS **indicator** (byte 47 bits
  2-0) read `1` only in the first minute of three rides — 19, 55 and 46 seconds of it with the bike
  stationary — which reads like the dash's ABS self-test after power-on, not a riding event.
- Block 45: byte 47 ABS target (bits 7-5) and ABS indicator (bits 2-0), byte 48 bit 0 switching info.
- Block 75: byte 77 damping value; byte 78 bit 2 suspension calibration, bit 1 suspension status, bit 0
  suspension ECU status.
- Block 85 (frame longer than 87 bytes): byte 87 bit 1 cruise control working, bit 0 cruise mode. Byte 88
  is Rideology's **`wheel_speed_setting`**: it reaches the realm column of that name (`qe/b.java:168-171`
  → `a0/s.java:323`), so it is a wheel-circumference/calibration number, **not** a cruise speed. KawaNav
  shows the raw byte on its debug card only. (layout verified; the number's meaning needs the bike)
- KawaNav reads block 1 of 0x4A as absent when either header byte is FF, where Rideology drops the frame
  only when both are; it also gates the second and third blocks on their headers, which Rideology does not.
  Deliberately stricter: a frame with a half-FF header is not something the meter sends, and reading it
  anyway risks values built from FF bytes.
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
| 51 | `tcs_level_hb` (bits 7-5), `tcs_level_lb` (bits 4-2), `kqs_down_operation_flag` (bit 1), `kqs_up_operation_flag` (bit 0) |
| 52 | wheelie flag (bit 0) |

**The speed channels do not agree with each other.** On the Z900 of 2026-10-17 the filtered front wheel
(0x4B) reads about 0.7 km/h above the 0x4A speed, the filtered rear about 1.2 % below the front and
stable with speed, and under braking the two filtered speeds differ by 0.7–0.9 km/h. GPS reads
2.6–2.9 % above the 0x4A speed at a steady speed. This is calibration, wheel circumference and
filtering, **not** wheel slip or tyre pressure; the comparison with the dash is still to be done.
(observed on the rides)

**The three accelerations are not the bike's axes.** On a Z900 at a standstill the first two read
−0.70 g and −0.71 g while the third reads 0.05 g: the gravity is split evenly between them, which is what
a sensor mounted at **45°** about the bike's lateral axis does. Turning the pair by 45° gives, at a
standstill, **0.00 g forward and 1.00 g down**, and on the move it follows the acceleration the bike
reports on its own (+0.52 g of its own against +0.41 g turned, −0.60 against −0.69): so

- forward = `(x − y) × cos 45°`, positive when accelerating;
- up = `−(x + y) × cos 45°`, which reads 1 g at rest;
- sideways = the third axis, unchanged.

KawaNav applies this (`BikeFrames.ImuSusp.longitudinal/vertical/lateral`) and names them accordingly. The
mounting angle was measured on one bike; a model that mounts its IMU differently would need its own angle,
which the same standstill check reveals. The four rides of 2026-10-17 (171 km) confirm it: at a
standstill the two accelerations read −0.70 and −0.70 g and the third +0.05, which turns into 0.00 g
forward and 0.99 g up.

**Two of the three rotation rates (17-18, 27-28, 37-38) are now identified** from the same rides:

- 37-38 (`imu_az_psip2`, Rideology's "p2") is the **yaw rate**: it follows the lean angle
  (r = +0.62 … +0.79) and the course change from the GPS track (+0.67), and it is near zero on a
  straight. The **axis** identification is sound, its **scale is not**: with R = v/ω the radius comes
  out 1.39–1.42× the lean-based R = v²/(g·tan φ) (r = 0.72–0.80 on all four rides), and the unit and
  scaling of the rotation rates are unresolved. Do not use it for an absolute radius without a
  calibration. (observed)
- 27-28 (`imu_ax_psip3`, "p3") is the **roll rate**: it follows the lean angle's change over 5 s
  (r ≈ +0.40) and nothing else.
- 17-18 (`imu_ay_psip1`, "p1") is still **unidentified**: no correlation with the lean angle, its
  change, speed, speed changes, braking or the yaw rate. The app labels 17-18 "IMU rate 1
  (unverified)" and the other two by their axis.

Block 45 counts as absent only when **both** bytes 45 and 46 are FF (the other blocks: either one). No
config nibble gates byte 51.

**The two pairs of "TCS" values are not the same thing, and neither of them is the rider's setting.**

- The rider's traction-control level is **KTRC**, read from the 0x48 settings frame (byte 18 bits 6-3, wire
  1..9, OFF = 10). It appears in no riding-log frame.
- 0x4A byte 24 carries a **4-bit** pair, 0x4B byte 51 a **3-bit** pair: different frames, different gates,
  different widths, written to different tables, and **no code relates them to each other** — nothing in
  Rideology says which one is "the" reading, and the same column names are used for both.
- Rideology is not consistent about the `hb`/`lb` suffix either: in 0x4B bits 7-5 are stored as
  `tcs_level_hb`, while in its high-rate riding log the same bits are stored as `tcs_level_lb`
  (`a0/s.java:274-275` versus `u5/d.java:258-259`). Read the suffix as a name, not as "high/low side".
- The only empirical reading so far comes from a recorded Z900 ride: the **0x4B pair moved once**, in the
  single second where the filtered rear wheel ran 4.6 km/h faster than the front (56.2 against 51.6 km/h,
  the largest slip of that ride), while the **0x4A nibbles stayed 0 all ride**. The two bits beside them in
  the same byte, `kqs_*_operation_flag`, fired 12 and 6 times and every time a gear changed in that second
  or the next (up with the throttle open, down with it closed) — so byte 51 reads like "what the
  electronics did this second", and the 0x4B pair is far more likely an intervention reading than a level.
  "Far more likely" is as far as this goes: Kawasaki's own name says *level*, and nothing proves otherwise.
- The four rides of 2026-10-17 say more about the quick-shifter bits: `kqs_up` fired on 2/16, 14/85,
  21/72 and 18/75 of the upshifts, `kqs_down` on 3/17, 12/82, 7/72 and 12/70 of the downshifts, always
  within a second of the gear change. They are the **quick shifter's operation flags**, set when the
  rider shifted without the clutch, not for every shift. (verified by correlation, not from the dash)
- The same four rides had the **0x4A TCS nibbles and the 0x4B TCS pair at 0 in all 171 km**: no
  intervention was recorded, so they still say nothing about the meaning of those columns.

What would settle it, on a bike: (1) change KTRC between two rides — if neither column follows the setting,
neither of them is the setting; (2) provoke a deliberate cut-in on a slippery surface with the dash's
traction-control lamp in view, and compare which column steps with the lamp.

### Units

Speed is stored and exported in km/h (converted to mph only at display), rpm in rev/min, temperatures
in °C (verified), and the instant fuel consumption in km/L (see 0x45; verified on the 2026-10-17 rides).
Lean is in degrees, **negative to the left, positive to the right**: checked against the GPS course
change on the 2026-10-17 rides, 713 of 749 curves agree (95.2 %), and the disagreements are chicanes or
GPS noise. (inferred from the rides, not from the app)

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
  - Its block marker is `0A`, not the `05` of the vehicle frames (0x12 uses `0A` too), then block id 08
    and byte 7 = 1. Byte 8 = 120; bytes 9-10, 11-12 and 13-14 read as big-endian 16-bit values:
    `03 E8` = 1000, `00 C8` = 200, `00 64` = 100. (Rideology's source writes `3` and `(byte) 1000`
    as two separate bytes; together they are 1000.)
  - **Hypothesis**: 1000 / 200 / 100 ms are the periods of three streams (for example 0x45, 0x4A and
    0x4B), and 120 a timeout in seconds. If so, the 0x4A/0x4B data could come faster than the once a
    second KawaNav records. Test (debug build only, after a capture): log the arrival times of
    0x45/0x4A/0x4B with and without 0x08, then with other periods.
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
  `oh/b.java:200-210` populates them but they are not emitted). (verified) Rideology's profiles still
  store them, so on a BLE5 bike they are read-only in practice. Where they would go is only a guess:
  the reply (below) carries them in bytes 10-13 of the same first block, so a write would most likely
  use those bytes with the reply's bit layout. **Untried, and no Rideology version writes them**: do not
  send them.
- **The profile name is the title the rider gives the setting in Rideology**: the edit screen passes it
  along with the values (`ae/j.java:378`, `ae/j.java:407`) and the write puts it in bytes 15-38. Nothing
  on that screen says it goes to the bike. Whether a dash shows it anywhere (the Rider mode, a settings
  page) is **unknown**. Since 1.2.1 KawaNav sends the name the rider types in the Ride settings card
  (optional, cut after the last whole character that fits in 18 bytes, `SettingsWrite.fitName`);
  before, it sent an empty one.

### The meter's 0x48 reply

The reply is laid out differently from the write: the tuning values move to the second block
(`qe/b.java:705-725`, KawaNav `BikeFrames.VehicleSettings`). Each field is only read when the 0x47
config says the bike has it.

| Byte | Content |
|---|---|
| 7 | result of the write for the meter (see below); "meter tuning capability" in Rideology's store |
| 10 | KECS preload (bits 5-3), KECS mode (bits 2-0) |
| 11 | KECS load adjustment (bits 3-0) |
| 12 | front compression (bits 7-4), front rebound (bits 3-0) |
| 13 | rear compression (bits 7-4), rear rebound (bits 3-0) |
| 17 | result for the ECU; "FI ECU tuning capability" in Rideology's store |
| 18 | KTRC wire value (bits 6-3), riding mode (bits 2-0) |
| 19 | KQS (bits 6-5), KEBC (bits 3-2), power (bits 1-0) |

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

- Turn-by-turn supported unless the high nibble of byte 12 is 15; Rideology then sends no nav frame at
  all (`oh/b.java:385`), while the notification frame 0x12 has no such gate (`ne/p.java:196-202`).
  (verified)
- Status icons supported unless byte 11 low nibble = 15 **and** the 2-bit fields byte 12 bits 3-2, 1-0
  and byte 13 bits 7-6, 5-4 are all 3 — the frame 0x13 is dropped only when **all five** say "none"
  (`oh/b.java:343`, `oh/b.java:362`). (verified)
- Both gates only apply once the capability frame is a *usable* one: a 0x1A that is missing or still
  being prepared leaves the app sending, which is what Rideology does with its default-initialised
  object. (verified)
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
- **The ids are Apple ANCS's**: CategoryID 1 = IncomingCall and 6 = Email, EventID 0 = Added and
  2 = Removed (ANCS's 1 = Modified is not used). ANCS also has 0 Other, 2 MissedCall, 3 Voicemail,
  4 Social, 5 Schedule, 7 News, 8 HealthAndFitness, 9 BusinessAndFinance, 10 Location and
  11 Entertainment. Rideology never sends those, and 0x1A declares nothing about categories, so
  **whether a dash shows anything for them is unknown**. A debug test (KawaNav debug build, card
  "Bike data" → "Dash notification test"): show category 2 or 4 and look at the dash, then clear it.
- Input is a `NotificationListenerService` classifying status-bar notifications by package
  (`MyNotificationListenerService`): calls from dialer packages (`com.google.android.dialer`,
  `com.samsung.android.dialer`, `com.sonymobile.android.dialer`, `android.dialer`); mail/messages from
  `com.google.android.gm`, `jp.co.yahoo.android.ymail`, `jp.naver.line.android`,
  `com.google.android.apps.messaging`, `com.kddi.android.cmail`. Only two icons, no counts. (verified)
- KawaNav (since 1.2.1) adds the messengers and mail apps used outside Japan to the mail icon: WhatsApp
  (and Business), Telegram, Signal, Messenger, Threema, Outlook, Samsung Messages / Email and AOSP
  Messaging; a `call`/`missed_call` notification of a messenger lights the call icon, and an ongoing
  notification of a mail or messaging app lights nothing (`DashAlerts.categoryOf`).
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

## Pairing history reset (0x0A)

- `0A 00 00` on a BLE5 dash **deletes all the pairing history the bike keeps and cancels the pairing**.
  It is Rideology's "Initialization settings" screen ("Deletes all pairing history saved in the
  motorcycle and cancels pairing"; `VehicleInitializeFragment`, event `DELETE_LIST_MANUAL`,
  `ne/c.java:100-104`). It is only offered while connected, and afterwards the app tells the rider to
  remove the bike from the phone's Bluetooth list too. (verified in the code; never sent by KawaNav)
- On BLE4 dashes the id 0x0A is a different frame: the call/mail icons (`oh/b.java:471-487`).
- **KawaNav never sends frame type 0x0A** (the block id 0x0A inside 0x1B is something else). Keep it
  that way unless a "forget this phone" feature is wanted on purpose.

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
  streaming 0x45/0x4A. The hypothesis (stream periods) and a test are in the riding-log section.
- Whether a dash shows the 0x48 profile name, and whether it shows an icon for ANCS categories other
  than 1 and 6 in 0x12.
- Whether 0x1E can be written. Rideology only reads it: `1E 2A 01 FF FF` and three blocks
  `05 0C 00`, `05 0D 00`, `05 0E 00` in bytes 5, 15 and 25, the rest `FF` (`ne/c.java:191-208`). In
  0x48 and 0x1B, byte 7 = 1 turns a read into a write, so a 1 in bytes 7/17/27 might write the service
  reminder (a reset after a service, say). **Pure speculation, and no Rideology version does it: do not
  send it.**
- The bits of 0x1A that Rideology's parser skips: byte 7 bits 5-2 and byte 13 bits 3-0
  (`qe/a.java:326-348`).
- Riding-log sample rate (meter/frequency controlled).
- Whether the meter treats 0x14 nibble 15 or flag 3 as "navigation ended" (both work with KawaNav).
- What the two TCS pairs (0x4A byte 24, 0x4B byte 51) actually report: the rider's level, an intervention,
  or something else. See the 0x4B section for the one observation there is and the two tests that settle it.
- Unit and scale of the three 0x4B rotation rates (bytes 17-18, 27-28, 37-38): the axes are identified
  by correlation, but R = v/ω gives a radius 1.39–1.42× the lean-based one, so no absolute value is
  usable yet. See the 0x4B section.
- Which *exported columns* carry a doubt of this kind, and what would settle each: the last part of the
  Z900 section below lists them by column.
- R.Score formula (server side).
- Whether the meter shows an "Apply on the cluster" step: the `0x10` result suggests a two-stage apply,
  but no code or string proves a rider action on the dash.
- Behaviour of the shift-lamp-only write's `0x2F` year byte.
- Which byte of a *short* ACK (KawaNav reads byte 4) is the general accept/reject — Rideology only
  parses the settings result (byte 7 of a 0x48 reply) and its generic ACK wrapper is raw.
- Frame ids, by what they belong to. KawaNav targets the BLE5 combustion bikes, so only the first group
  is its business:

| Group | Ids | What they are |
|---|---|---|
| BLE5, in use | 0x03 0x08 0x0A 0x0B 0x1A 0x1B 0x1D 0x1E 0x13 0x14 0x12 0x40 0x41 0x45 0x47 0x48 0x4A 0x4B | model info, meter init, pairing-history reset (never sent by KawaNav), phone name, capabilities, general settings, maintenance config/indicator, dash icons, turn-by-turn, notifications, the vehicle frames |
| BLE5, seen but not decoded | 0x01 0x20 0x30 | 0x01 is an empty "accepted" reply; 0x20 the ACK; 0x30 the meter's status frame |
| EV / HEV family | 0x07 0x0E 0x11 0x16 0x17 0x23 0x24 0x31 0x32 0x35 0x42 0x52 0x62 | battery pack, EV modes, walk mode, an energy histogram. Reached only on the e-1 and HEV bikes |
| BLE4 (older dashes) | 0x0A (the call/mail icons there), 0x12 as settings read, 0x17, and the 0x0E histogram | a different protocol entirely (see `Ble4VehicleSettingViewModel`) |

  The EV/HEV ids are the ones a 2025+ petrol bike never sends; if one appears in a capture, the
  assumption that this is a BLE5 combustion meter is wrong.

## What Rideology has but does not show (2.11.9)

A search of the app for values a BLE5 dash takes but the rider does not see, or does not see go to the
bike. (verified in the code; nothing here has been tried on a bike)

- **Everything Rideology writes to a BLE5 dash**: the `XX 00 00` requests (0x03, 0x1A, 0x1D, 0x40, 0x41,
  0x45, 0x47), 0x1B read and write, 0x48 read and write, the 0x1E read, 0x08, 0x0B, 0x12, 0x13, 0x14, the
  [pairing-history reset 0x0A](#pairing-history-reset-0x0a) and, for EV/HEV, 0x42 (`ne/c.java`, the BLE5
  branch of `oh/b.java`, `ne/p.java:200`). KawaNav knows all of them; there is no further BLE5 write in
  the app.
- **A hidden debug screen, "V2 Debug"** (`SystemInspectFragment`). The navigation graph has an action
  from the settings to it (`action_settingsFragment_to_systemInspectFragment`), but no code uses it, so
  a release build cannot open it. It shows the BLE service UUIDs and has tests for dialogs, location,
  battery optimisation and Crashlytics. Its only bike part is a **shift-lamp test** that offers all
  four statuses (off / tachometer / LED / LED + tachometer) whatever mode the 0x1A frame declares, and
  sends them through the normal general-settings write (`ce/a.java:160-185`). The normal screen only
  offers the statuses the dash declares.
- **KECS, preload, load adjustment and damping**: kept in the profiles, never written to a BLE5 bike
  (see [Vehicle settings](#vehicle-settings-riding-mode-power-ktrc-kqs-kebc-profile-name) and
  [the reply](#the-meters-0x48-reply)).
- **The profile name**: goes to the bike with every settings write, without the rider being told
  (see [Vehicle settings](#vehicle-settings-riding-mode-power-ktrc-kqs-kebc-profile-name)).
- **The dash clock**: no screen at all, only the sync on every connect (0x1B above).
- **Units and date format** (temperature, date order, distance/fuel): the units screen writes them to
  **BLE4 dashes only** (`UnitSettingFragment.java:97-143`, sent on the BLE4 path at `oh/b.java:457`).
  The BLE5 general-settings path takes only the shift-lamp/clock object (`oh/b.java:267-276`), so a
  BLE5 dash's units are set on the dash itself.
- **Notification categories**: only call and mail, although the ids are ANCS's (see
  [Dash notifications](#dash-notifications-callmail-icons)).

## What KawaNav implements today

| Part | Frames | State |
|---|---|---|
| Turn-by-turn | 0x14, 0x13 | works on the bike (v0.1–v0.3, verified) |
| Bike data | 0x03, 0x40, 0x41, 0x45, 0x4A, 0x4B, 0x1A, 0x1B, 0x1D, 0x1E, 0x47, 0x48 | decoded and shown; the scaling comes from the 0x40 nibbles, not yet compared with a dash over a whole ride |
| Connect sequence | 03, 40, 1A, 1D, 47, 0B, 41, 1B, 48, 1E, 08, 45 | sent after connecting (read-only level in release; the full one, with 0x08 and the phone name, in debug builds) |
| Ride recording | 0x45, 0x4A, 0x4B + GPS | records locally; the suspension/IMU values (0x4B: travel, accelerations, rotation rates, filtered wheel speeds, TCS/KQS flags) and the rest of 0x4A (torque, brake pressures, ABS target, damping, cruise) are recorded per second since 0.8; nothing on the meter drives it (no start/stop frame); like Rideology, KawaNav asks for 0x45 at connect and when a recording starts without one |
| Dash icons | 0x13 (battery, headset, riding log), 0x12 (call, mail) | sent; the riding-log icon follows the recorder. Not yet seen on a dash |
| Clock | 0x1B write | optional setting, off by default; preserves the shift lamp |
| Settings write | 0x48 (block 09, byte 7 = 1) | riding mode, power, KTRC, KQS; in all builds since 1.2.1 (debug only before), not yet tried on a bike |
| Shift lamp write | 0x1B (block 0x4A) | in all builds since 1.2.1 (debug only before), not yet tried on a bike; range and modes taken from the meter's 0x1A answer |

Everything else in this document is protocol knowledge KawaNav does not use yet.

## Verified on the bike (Z900 2026 "Kawasaki-ZR900S", 2026-10-10 and 2026-10-17)

- Turn frames work with no init sequence: connect, MTU, enable notifies, write the frame.
- FLAG 1 (rerouting) and FLAG 2 (arrived): no visible effect. Rideology never sends 1; it sends 2 only on
  HERE `onDestinationReached` together with the last turn (normally 43 Destination).
- Turn ids 44-55 (waypoints): nothing shown. Rideology never sends them.
- => Useful set for a replacement app: turns 0-43, flags 0 (navigating) and 3 (clear).
- Status icon frames (0x13): the dash redraws them about once a second; faster changes are not shown.
- Pairing without Rideology (KawaNav's companion association + createBond) works.

### What a Z900 actually sends (recorded rides, 2026-10-15 and 2026-10-17)

A 24-minute ride recorded by KawaNav on this bike (1450 samples, at rest and moving) shows which fields
that model fills. That matters more than any scaling question: a value a bike never sends cannot be
compared with a dash, and it is not "unverified", it is absent.

Four more rides the following day (2026-10-17: 601, 4736, 4978 and 2325 samples, 171 km in all) fill
the same fields and leave the same 21 columns empty. They were recorded with KawaNav 0.9.2, so their
`dist_m` still moves in whole-kilometre steps; the 0.9.3 rule on the same data gives 6.66, 61.66, 68.76
and 32.98 km, never running backwards.

- **Every second**: 0x4A speed, rpm, gear, throttle, acceleration, lean, the TCS nibbles (below), the ABS
  byte 37 (always `00` = ready), the wheelie flag and angle; 0x4B block 45 (the two filtered wheel speeds,
  the TCS pair and the two quick-shifter bits of byte 51, the wheelie flag); 0x4B blocks 15/25/35 (the three
  accelerations and the three rotation rates); GPS position, altitude, speed and accuracy.
- **Now and then**: 0x45 coolant and intake temperature every second; instant fuel in 80 % of the seconds
  (the dash leaves it `FF` while decelerating or stopped); 0x41 odometer (whole km) and battery voltage
  in runs of three seconds every 20–21 s (70 runs), which is KawaNav's cadence, not the bike's: the meter
  answers 0x41 only when asked, KawaNav asks every 20 s (`BridgeService.INFO_POLL_MS`), and the recorder
  writes an answer into each second for 3 s (`RideRecorder.BIKE_MS`); 0x4A blocks 45/85 (ABS target and
  indicator, cruise bits) present but always `0`.
- **Never**: the oil temperature (the ride file has one oil cell, −60 °C in the first second with bike
  data: a `00` decoded before the 0x40 was in, see 0x45 above; KawaNav now leaves it empty), TPMS (no
  tyre pressures at all: no sensors fitted), outside temperature, fuel rate, total fuel, front brake
  pressure, the three torque values, the raw brake masters and the rear caliper, ABS switching, damping,
  the suspension calibration/status/ECU bits, and — worth noting — **the strokes and stroke speeds of
  0x4B block 5**, while the IMU blocks of the same frame are present. A Z900 has the IMU (lean, traction
  control, wheelie) but no electronic suspension, and the two are independent blocks, as the 0x4B table
  says.

Consequence for KawaNav: the values its screen labels "unverified" (brake pressure, the torque numbers, the
tyre pressures, outside temperature, the suspension strokes) are simply **not sent by this bike**; they
stay empty cells in its ride file. The "unverified" wording now applies only to bikes that do send them.

Two more things this ride showed, both on KawaNav's side:

- The oil temperature's single `00` made a −60 °C cell. After 1.0 `00` is no temperature, none is read
  before the 0x40 is in, and a −40 °C coolant/intake or −60 °C oil cell written by an older version reads
  back as empty (`RideTsv`).
- The odometer counts whole kilometres, so a ride's distance taken from it alone moves in 1 km steps and
  can be nearly a kilometre off either way: this ride read 5234 → 5247 km, "13.00 km", while its GPS track
  measures 13.4 km. After 1.0 the odometer counts the kilometres and the GPS fills in between them: how
  far the ride had gone when the odometer first stepped, and how far since its last step (`Ride.Distance`).
  This ride comes to 13.2 km, and its `dist_m` column no longer moves in 1000 m steps.

**Isolated rpm spikes.** The 2026-10-17 rides have four isolated samples with the throttle closed and
the speed below 2 km/h that read 1808 / 1894 / 2859 / 2699 rpm, each back to 1070–1220 rpm the next
second, plus 2 s of 0 rpm at 1 km/h in gear 2 followed by 1347 rpm. None is a plausible throttle blip;
whether the meter or the decoder produces them is not established. Filter isolated rpm outliers before
reading anything into them. (observed)

### Which exported values are still doubtful

Two kinds of doubt are left, and they are different things: a value whose **meaning** is not settled (the
number is right, the name is a guess), and one whose meaning is understood but whose **scale or unit** was
never checked against a dash. Everything else in a ride file is either verified on a capture or an empty
cell on a bike that does not send it.

**Meaning not settled**

| Columns | The question | What there is to go on |
|---|---|---|
| `tcs`, `tcs_hi`, `tcs_hb`, `tcs_lb` | the rider's level, an intervention, or something else | two different pairs in two frames; on 2026-10-15 the 0x4A nibbles stayed `0` and the 0x4B pair moved once, in the second with the largest wheel-speed difference — where `tcs_lb` read `7`, all three bits set, which elsewhere in a 3-bit field means "no value", so even that one observation is ambiguous. All four columns were `0` in all 171 km of 2026-10-17. See the 0x4B section for the two tests that settle it |
| `imu_ay_psip1` | what the first rotation rate is | the other two are identified (yaw and roll, 0x4B section); this one has no correlation with the lean angle, its change, speed, braking or the yaw rate |
| `imu_ax_g`, `imu_ay_g`, `imu_az_g` | the IMU's mounting angle | 45°, measured at a standstill on one bike and confirmed on the 2026-10-17 rides; a model that mounts its IMU differently needs its own angle, which the same check reveals |
| `wheel_speed_setting` | what the number says | Rideology's own column name points at a wheel circumference, not a speed; `0` in every second of all five rides |
| `cruise_on`, `cruise_mode` | what the mode values mean | present but always `0` on this bike, all five rides |
| `abs`, `abs_target`, `abs_ind` | what a value other than "ready" says | `abs` read `2` in 8 isolated seconds of 2026-10-17, not all under braking, with ordinary wheel slip; `abs_ind` `1` only in the ABS self-test at the start of a ride (0x4A section); `abs_target` always `0` |
| `wheelie_flag` | the values other than the `0` it read all ride (the 2-bit field's `3` is the all-ones byte, i.e. no value) | only `0` seen, all five rides |

**Scale or unit never checked against a dash**

| Columns | The scaling used | Note |
|---|---|---|
| `fuel_rate` | `u16 × 0.02` | the unit is not established (the instant fuel, by contrast, is km/L: 0x45 above) |
| `brake_f_bar` | `u8 × 0.1953125`, bar | not sent by this bike |
| `brake_f_master`, `brake_r_master`, `brake_r_caliper` | kept raw | Rideology keeps these raw too; not sent by this bike |
| `torque_rider`, `torque_req`, `torque_actual` | `((hi & 0x3F) << 8 \| lo) × 0.2` (nibble 0) | not sent by this bike |
| `tyre_f_kpa`, `tyre_r_kpa` | `u8 × 1.373 + 100` | never compared with a gauge |
| `outside_c` | `u8 − 60` | not sent by this bike |
| `damping`, `susp_cal`, `susp_status`, `susp_ecu` | raw bits and values | no electronic suspension on this bike |

Water, oil and intake temperature are the one case where the scaling is verified but the **gate** is not:
KawaNav reads them when their 0x40 nibble is 0 or 1, Rideology only when it is 1 (0x45 above). It only
bites on a bike whose 0x40 declares one of them at 0.

**Empty on this bike, meaning known** (their scaling is still the unverified one above): total fuel, fuel
rate, both tyre pressures, the three torque values, the front brake pressure, the raw brake masters and
the rear caliper, ABS switching, damping, the suspension calibration/status/ECU bits, and the four
suspension strokes and stroke speeds.

**What would settle them, on a bike**

1. **KTRC:** change the level between two rides. If neither `tcs` pair follows the setting, neither is
   the setting.
2. **Intervention:** provoke a cut-in on a slippery surface with the dash's traction-control lamp in
   view, and see which column steps with the lamp.
3. **IMU:** at a standstill on a known slope, and on a straight at a known lean, correlate the three
   rotation rates with the lean angle and its rate of change, and the three axes with the bike's own
   acceleration. Two rates are identified; what is left is rate 1 (17-18).
4. **ABS:** a hard stop that makes the dash's ABS lamp flash, with the raw byte 37 in the capture, would
   say whether the 8 isolated `02` seconds are an intervention or something else.
