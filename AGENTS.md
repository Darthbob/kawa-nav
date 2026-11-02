# KawaNav: notes for contributors and AI coding agents

Android app that shows Google Maps directions and upcoming curves on the TFT dash of Kawasaki
motorcycles over Bluetooth LE, and reads, records and shows the bike's data like Rideology. User
documentation: [README.md](README.md); dash protocol: [docs/PROTOCOL.md](docs/PROTOCOL.md);
release details: [docs/RELEASING.md](docs/RELEASING.md).

## Releasing a new version

Releases are published by GitHub Actions ([release.yml](.github/workflows/release.yml)) when a
commit on `main` carries a `versionName` that has no tag yet. A release needs, in the same commit
or pull request:

1. `app/build.gradle`: `versionCode` raised by 1 and the new `versionName` (e.g. `'1.1'`).
2. `changelog.json`: a new entry **on top** with `version` = the new versionName and its
   `changes`: short sentences for riders, not developers (the app shows them in the update
   dialog, the release notes list them).

Optional longer notes go in `docs/releases/v<versionName>.md`. The workflow then builds and signs
the APKs, tags `v<versionName>` and creates the GitHub release.
The in-app updater ([Updater.java](app/src/main/java/io/github/darthbob/kawanav/Updater.java))
offers the newest release to installed apps and reads the changes from `changelog.json` on `main`.
Pull requests fail when `changelog.json` has no entry for the current versionName.

User-visible changes that are not released yet can already be added to the top entry of a new,
higher version in `changelog.json`; the version in `app/build.gradle` decides when it is released.

A changelog line says only what differs from the last released version, as a rider of that version
will notice it, in one plain sentence. It is not a record of the work: when a later change reworks
something still unreleased, rewrite its line to the end result instead of adding a line about the
change, and leave out the reasons and steps in between.

## Code

- Java 17, minSdk 33, Android framework only: no AndroidX, no third-party libraries in the app
  (JUnit for the JVM tests in `app/src/test` only).
- The UI is built in code (no XML layouts): screens extend `KawaActivity`, shared styling is in
  `Ui`; UI text is English.
- Shared state between services and UI and the setting keys are in `Hub`; log with `Logger`
  (`KawaApp` sets it up and sends crashes into it).
- What the dash shows each second is decided in `DashArbiter` (plain Java, tested in
  `DashArbiterTest`); `BridgeService` only gathers its inputs and sends the frame. Keep rules about
  precedence, distances and windows there, not in the service.
- When behaviour users notice changes, update README.md too.
- Build and test: `gradle assembleDebug testDebugUnitTest lintDebug` (Gradle 9.6.x as in CI,
  JDK 17+; there is no Gradle wrapper). Lint warnings fail the build; the ones from before that rule
  are listed in `app/lint-baseline.xml` — fix them when you touch that code and remove the entry,
  never add to the baseline.

## Never commit

- `research/` (decompiled Rideology app: personal research only, never commit or distribute)
- `tools/` (local test tools)
- exported rides and reports made from them (`Ride_*.csv`, `Ride_*.gpx`, `Ride_*.tsv`, `*_report.html`): personal GPS
  tracks
- `keystore.properties`, `*.jks`, `*.keystore` (release signing; CI gets the key from the secrets
  of the `release` environment)
- exported logs and notification captures (`*-kawanav.log*.txt`, `*-notif-*.txt`, `*-icons-*.png`):
  they hold the bike's Bluetooth address, the helmet's name, street names and other apps'
  notification text. Keep them outside the checkout (they are git-ignored, but `git add -f` is not)
