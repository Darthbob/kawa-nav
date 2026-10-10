# Kawasaki Rideology – TFT turn-by-turn protocol (BLE5 meters, e.g. Z900 2025+)

Source: Rideology The App Motorcycle `jp.co.khi.mce.rideologytheappV2` v2.11.13 (versionCode 150),
decompiled with jadx 1.5.6. Obfuscated names below refer to that build.

## Transport

- Bluetooth Low Energy, GATT. Security = OS-level bonding only (`createBond`), MTU requested: 300.
- **No app-level encryption/auth** on the write path (`ne/p.f(byte[])` writes raw bytes).

| Role | UUID |
|---|---|
| Scan filter (service) | `C5664E44-D60B-4342-8B7B-35BD6AE28E3F`, `4fbb7609-f50a-40c6-89a9-9be7ea880e95`, `99eb13ac-42c9-4745-8b76-c30141401ce5` |
| BLE5 service | `92faec07-c075-4b7c-a6c2-bbd1d1a150f5` |
| Write characteristic | `acf1b15c-10f9-4942-a32d-f9e019b95402` |
| Notify characteristics (enable via CCCD 0x2902) | `3aabbb34-eac0-40f5-9d50-3a1ee6787136`, `02fad1bd-358e-441c-b296-fe874af38a7e`, `5e119eba-35a7-4463-a7af-7fa40a302350` |

Connect sequence in the app (`ne/p.e()` → `ne/c` case 2): enable the 3 notifies, then a queue of
requests (e.g. `03 00 00`, `45 00 00`, `40 00 00`, `47 00 00`, `1D 00 00`, `41 00 00`, …) that read
vehicle info/settings. Unknown yet whether the meter requires any of these before accepting nav frames.

The app skips nav frames if the vehicle's capability byte `Ble5Value.b.f11421l == 15` (not supported).

## Turn-by-turn frame ("NaviControlPoint", `oh/b.java` case NAVI_CONTROL_POINT) – 35 bytes

```
idx  0  1  2  3  4 | 5  6  7     8..12  13        14 | 15 16 17    18 19 20      21..24 | 25..34
     14 08 00 FF FF | 05 70 FLAG<<4 FF×5 UNIT<<4   FF | 05 71 TURN  DIST(24-bit BE) FF×4  | FF×10
```

- FLAG (`Ble5Value.n`): 0 navigating, 1 rerouting, 2 arrived, 3 no navigation, 15 n/a
- UNIT (`Ble5Value.p`): 0 km, 1 m, 2 mile, 3 ft, 4 yd, 15 n/a. App sends 1 (m) for metric, 3 ft / 4 yd for imperial.
- DIST: integer in the base unit (m/ft/yd), rounded to 10 when ≥10, capped (9 999 000 m). Meter likely formats km itself (verify).
- TURN (`Ble5Value.o`):
  0 straight · 1 left · 2 right · 3 sharp L · 4 sharp R · 5 slight L · 6 slight R · 7 U-turn L · 8 U-turn R ·
  9 fork L · 10 fork R · 11 fork middle · 12 ramp on L · 13 ramp on R · 14 ramp off (exit) L · 15 ramp off R ·
  16 merge L · 17 merge R · 18–29 roundabout clockwise exit 1–12 (left-hand traffic) ·
  30–41 roundabout counter-clockwise exit 1–12 (right-hand traffic, e.g. DE) ·
  42 departure · 43 destination · 44–55 waypoint 1–12 · 0xFF reserved/none

HERE → turn mapping lives in `mh/o.h()`; HERE `RIGHT_ROUNDABOUT_EXITn` → CCWn, roundabout pass → straight.

Example frames (hex):
```
right in 250 m        140800FFFF057000FFFFFFFFFF10FF0571020000FAFFFFFFFFFFFFFFFFFFFFFFFFFFFF
left in 80 m          140800FFFF057000FFFFFFFFFF10FF057101000050FFFFFFFFFFFFFFFFFFFFFFFFFFFF
roundabout exit 2 400m 140800FFFF057000FFFFFFFFFF10FF05711F000190FFFFFFFFFFFFFFFFFFFFFFFFFFFF
clear / no navigation 140800FFFF057030FFFFFFFFFFF0FF0571FF000000FFFFFFFFFFFFFFFFFFFFFFFFFFFF
```

## Meter indication frame (status icons, sent every 5 s) – 15 bytes

`13 0C 00 FF FF 05 00 (BATTERY<<4 | VOICE) (HEADSET<<4 | RIDELOG) FF FF FF FF FF FF`

- BATTERY (`Ble5Value.j`): phone battery, 0 low (≤10 %) · 1 ≤30 % · 2 ≤70 % · 3 >70 % (lj/a.b) ·
  7–10 charging with 0–3 bars · 14 error · 15 n/a. Rideology only ever sends 7 when charging;
  KawaNav sends 7 + bars while the phone is plugged in (not yet verified on the bike).
- VOICE (`Ble5Value.m`): 0 idle · 1 listening for wake word · 2 listening for command · 3 processing · 14 error · 15 n/a
- HEADSET (`Ble5Value.k`): 0 off · 1 on · 2 blink · 3 n/a
- RIDELOG (`Ble5Value.l`): 0 off · 1 recording · 2 paused · 14 error · 15 n/a
- KawaNav sends it every 5 s with VOICE 0, RIDELOG 1 (test: is that the "recording" icon?), HEADSET 1 while the chosen helmet is connected,
  and BATTERY 15 to remove the battery icon (not yet verified on the bike).

## Capabilities (request `1A 00 00`, answer starts with `1A`; Rideology qe/a.java case 2, oh/b.java)

- Turn-by-turn supported unless the high nibble of byte 12 is 15 (`Ble5Value.b.l`); Rideology then
  sends no navigation frames.
- Status icons supported unless byte 11 low nibble = 15 and the 2-bit fields byte 12 bits 3-2, 1-0 and
  byte 13 bits 7-6, 5-4 are all 3; Rideology then sends no meter indication frames.
- KawaNav requests it after connecting, logs it ("Bike display: …"), shows it in the debug card and
  warns (does not stop sending) when turn-by-turn is not supported. Same parsing as
  [wazeology](https://github.com/agstrc/wazeology), an independent Z900 SE project.

## Text to the meter (checked 2026-10-11 in Rideology 2.11.13, ne/c.java case 20)

The only text Rideology sends to a BLE5 meter is the phone's model name (`Build.MODEL`), once while
connecting. 35 bytes, zero-filled, UTF-8 split in 8-byte parts:

```
0B 20 00 FF FF | 05 01 name[0..7] | 05 02 name[8..15] | 05 03 name[16..23]
```

Where the meter shows it is unknown (probably its Bluetooth / connected-phone info). KawaNav's debug
card (debug builds) can send any text here ("Phone name on the dash"); not yet tried on the bike. The
turn-by-turn and indication frames carry no text. The text frame `12 LEN text…` (20 bytes) is only
in the protocol classes of older Rideology bikes (other GATT UUIDs, ne/s.java), not BLE5.

## Verified on the bike (Z900 2026 "Kawasaki-ZR900S", 2026-10-10)

- Turn frames work with no init sequence: connect, MTU, enable notifies, write the frame.
- FLAG 1 (rerouting) and FLAG 2 (arrived): no visible effect. Rideology never sends 1; it sends 2 only on
  HERE `onDestinationReached` (gh/b.java) together with the last turn (normally 43 Destination).
- Turn ids 44–55 (waypoints): nothing shown. Rideology never sends them (no mapping in mh/o.h()).
- => Useful set for a replacement app: turns 0–43, flags 0 (navigating) and 3 (clear).
- Status icon frames (0x13): the dash redraws them about once a second; faster changes are not shown.
- Pairing without Rideology (KawaNav's companion association + createBond) works.
