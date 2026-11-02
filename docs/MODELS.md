# Supported Kawasaki models

KawaNav does not check the model. It works with any bike whose meter uses Kawasaki's BLE5 protocol
(see [PROTOCOL.md](PROTOCOL.md)) and can show turn-by-turn arrows, i.e. the bikes on which Rideology
shows turn-by-turn navigation on the dash. Autostart accepts any Bluetooth device named "…Kawasaki…".

Kawasaki publishes no single compatibility list; this is collected from model pages and press
coverage (as of October 2026), and from Rideology itself.

## Which meter a model has

Rideology (2.11.14) finds a bike's model in its model list (`hg/a.java`) in one of two ways
(`u6/ka.java:16-50`): a **BLE5** bike by a code taken from its VIN (characters 4-9 of a 17-character
VIN, `le/b.java:18-24`: `ZR900S` for a Z900 2025+, `ER500F` for a Z500), any **other** bike by the part
number of its meter (`E86D0-0456`). A model listed by meter part number therefore has a meter that is
not BLE5: Rideology talks to it with its older protocol, which has no turn-by-turn and which KawaNav
does not speak ([PROTOCOL.md](PROTOCOL.md#meters-before-ble5-not-supported)). KawaNav recognizes such a
meter by its Bluetooth services: Home says the dash is not supported, and KawaNav stops trying to
connect. (verified in the code; inferred for the bikes, none of which but the Z900 has been tried with
KawaNav)

## Turn-by-turn on the dash confirmed

| Model | Years | Notes |
|---|---|---|
| Z900 / Z900 SE | 2025+ | "A Kawasaki first". **Tested** with KawaNav: Z900 2026 ("Kawasaki-ZR900S") |
| Z1100 / Z1100 SE | 2026+ | 5" TFT with turn-by-turn |
| Ninja ZX-10R / ZX-10RR | 2026+ | New 5" TFT with voice command and turn-by-turn |

All three are BLE5 in Rideology's list (`ZR900S`/`ZR900V`/`ZR900T`, `ZRT10G`/`ZRT10H`,
`ZXT03A`/`ZXT03B`/`ZXT03C`).

## BLE5 meter, turn-by-turn not known

KawaNav should connect to these and read the bike's data; whether the dash shows the arrows is what
it says in its capability answer.

- **Z500 / Ninja 500 (2024+)**: `ER500E`/`ER500F`, `EX500G`/`EX500H`/`EX500J`. The Z500 frames in
  KawaNav's tests come from one.
- **KLX230 / KLX230 SM (2024+), KLX230 SHERPA (2025), KLX230 (2027)**: `LX232…`, `LX233…`.
- **KLE500 (2026)**: `LE500C`/`LE500D`. Rideology 2.11.14's store notes name the KLE500 2027 too.
- **KLX300 (2027)**: `LX300J`/`LX300M`/`LX300N`/`LX300P`, new in Rideology 2.11.14.
- **KLE650 (2027)**: `LE650L`/`LE650N`, new in Rideology 2.11.14.

## Not supported

- **Z900RS (2026)**: BLE5 (`ZR902A`–`ZR902E`) and Rideology voice command, but analog dials without a
  screen for directions.
- **Meters that are not BLE5** (listed by meter part number, see above):
  - **Ninja 650 (2027), Ninja H2 (2027)**: added in Rideology 2.11.14.
  - **Ninja 1100SX, Versys 1100 (2025+)** and **Z650 S (2026)**: 4.3" TFT. Kawasaki Australia notes
    turn-by-turn is "not available in Australia" on the 1100s, so they may have it elsewhere, through the
    older protocol.
  - **The older Rideology bikes (2019–2024)**: Ninja H2 / H2 SX, Versys 1000 / 1000 S, Z650 / Ninja 650
    (2020), Z900 (2020), Z900 SE (2021), Z H2 / Z H2 SE, Ninja 1000SX, Ninja ZX-10R (2021), Versys 650
    (2022), Ninja ZX-25R, Ninja 7 Hybrid / Z 7 Hybrid, Ninja e-1 / Z e-1, ELIMINATOR 400 / 500, Ninja
    ZX-4R, Ninja ZX-6R; also the bimota models Rideology lists (TESI H2, KB4, KB998 Rimini, KB399).

The meter reports what it can show (the 0x1A capability answer, see PROTOCOL.md): KawaNav asks for it
right after connecting, shows it on the bike screen and in the debug card, and warns (without stopping)
when the dash says it has no turn-by-turn. Leaving the arrows out for good is a decision for the rider,
since the flag has not been checked against a dash without turn-by-turn yet.

## Sources

- Rideology 2.11.14 (October 2026), decompiled: model list `hg/a.java`, model lookup `u6/ka.java` and
  `le/b.java` (not in the repository, see [PROTOCOL.md](PROTOCOL.md))
- [Kawasaki: remodeled Z900 with turn-by-turn](https://global.kawasaki.com/en/corp/newsroom/news/detail/?f=20241031_1292)
- [Kawasaki EU: Z900 SE 2026](https://www.kawasaki.eu/en/Motorcycles/Supernaked/Z900_se_2026.html)
- [Kawasaki EU: New Z1100 for 2026](https://www.kawasaki.eu/en/news/2025/september/new-z1100-for-2026----sugomi-awakens-.html)
- [Motorcycle.com: 2026 Z1100 first look](https://www.motorcycle.com/bikes/new-model-preview/2026-kawasaki-z1100-and-z1100-se-first-look-44650111)
- [Kawasaki USA: 2026 Ninja ZX-10RR](https://www.kawasaki.com/en-us/motorcycle/ninja/supersport/ninja-zx-10r/2026-ninja-zx-10rr-abs)
- [ADVrider: Z900RS and ZX-10R for 2026](https://www.advrider.com/kawasaki-updates-the-z900rs-and-ninja-zx-10r-for-2026/)
- [Kawasaki Australia: Ninja 1100SX](https://www.kawasaki.com.au/en-au/motorcycle/ninja/sport/ninja-1100sx)
- [Kawasaki Australia: Versys 1100](https://www.kawasaki.com.au/en-au/motorcycle/versys/adventure-touring/versys-1100)
- [RideApart: 2026 Z650 S](https://www.rideapart.com/news/776975/2026-kawasaki-z650s-specs-features-horsepower/)
- [App Store: Rideology version history](https://apps.apple.com/ca/app/rideology-the-app-motorcycle/id1515050057)
