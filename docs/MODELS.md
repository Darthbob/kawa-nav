# Supported Kawasaki models

KawaNav does not check the model. It works with any bike whose meter uses Kawasaki's BLE5 protocol
(see [PROTOCOL.md](PROTOCOL.md)) and can show turn-by-turn arrows, i.e. the bikes on which Rideology
shows turn-by-turn navigation on the dash. Autostart accepts any Bluetooth device named "…Kawasaki…".

Kawasaki publishes no single compatibility list; this is collected from model pages and press
coverage (as of October 2026).

## Turn-by-turn on the dash confirmed

| Model | Years | Notes |
|---|---|---|
| Z900 / Z900 SE | 2025+ | "A Kawasaki first". **Tested** with KawaNav: Z900 2026 ("Kawasaki-ZR900S") |
| Z1100 / Z1100 SE | 2026+ | 5" TFT with turn-by-turn |
| Ninja ZX-10R / ZX-10RR | 2026+ | New 5" TFT with voice command and turn-by-turn |

## Unclear: Rideology works, turn-by-turn not confirmed

- **Ninja 1100SX, Versys 1100 (2025+)**: Kawasaki Australia notes turn-by-turn is "not available in
  Australia" on these, which suggests it exists in other markets. 4.3" TFT.
- **Z650 S (2026)**: 4.3" TFT with Rideology; turn-by-turn not mentioned.
- **Ninja 650 (2027), Ninja H2 (2027)**: added to Rideology recently; features not stated.

## Not supported

- **Z900RS (2026)**: Rideology voice command, but analog dials without a screen for directions.
- **Older Rideology bikes (2019–2024, e.g. Z900 2020, Ninja 650 2020)**: older meters without
  turn-by-turn.

The meter reports whether it supports turn-by-turn (capability byte, see PROTOCOL.md); Rideology
then skips nav frames. KawaNav does not read it yet: on an unsupported bike it connects, but the
arrows never appear.

## Sources

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
