# Waze: what the test on the phone showed

**Result (14 Oct 2026, Waze 5.25.5.0, Android 16 phone): Waze's notification carries no
turn-by-turn.** The capture of a test run holds exactly one `com.waze` notification, and it is the
one that keeps the app alive:

```
com.waze  id=1 channel=CLOSE_WAZE_CHANNEL flags=0x62 ongoing=true
    TITLE: Waze
    TEXT: In funzione. Clicca per aprire.      ("Waze is running, tap to open")
    ACTIONS 1 [Spegni]                        (turn Waze off)
    ICON small=resource com.waze/2131232483   (the app's own logo, 54x54)
```

No distance, no maneuver, no street name, and no bitmap icon that could be an arrow; the channel is
even called `CLOSE_WAZE_CHANNEL` ("Waze is running"). Every update would have been written down as a
changed block, so a turn instruction appearing there would have been caught.

**Caveat on this run:** the phone was standing still and never reached the first turn, and the screen
was not locked. A turn only appears once there is one to announce, and Waze's "Lock screen navigation"
(the surface most likely to hold it) only shows while the phone is locked. A run that actually moves
past a turn with the screen locked is therefore still missing before the result is final.

That matches what the research found before the test: Waze does not use notifications for turns, and
the apps that do show Waze turns (wazeology, wazetripper) patch the Waze APK instead. **So KawaNav
cannot read Waze, and there is no honest way to add it:** a patched Waze would break its terms of use
and cannot be distributed.

The capture tool, the procedure below and `docs/` stay in the project: they are the way to check any
other navigation app (or a later Waze version, should it ever post turns) with the same ten minutes
of work.

## How the test was run (for reference)

KawaNav reads Google Maps' turn-by-turn notification. Waze is different: everything found so far
says it does **not** put the next maneuver into its notification at all — its notification only
keeps the app alive and gives a way back into it (a competing motorcycle-dash app states this
outright and reads Waze by machine-learning on the screen instead). Three independent checks of the
research agreed. What is *not* known is what Waze does show on the lock screen (its "Lock screen
navigation", on by default since 2024) and in Android 16's live updates.

So before writing any Waze support, look at what Waze really posts, on a real phone, while riding.
The debug build can write it down.

## What to do

1. Install the **debug APK** (`KawaNav-vX.Y-debug.apk`). It installs over the normal one and keeps
   rides and settings; the signed release key is the same.
2. Settings → tap the title "Settings" **seven times** → the hidden cards appear.
3. In the **Notifications** card:
   - **Capture notifications** on (this is what writes the file);
   - **Every app** on (this is only for this test: it also writes other apps, to catch whatever
     surface Waze uses).
4. Start a route in Waze and ride (or drive) for ten minutes with a few turns. Leave the phone in
   the pocket, and lock the screen at least once: the lock screen is one of the surfaces to check.
   A closed Waze (only in the background) and Waze in the foreground are both worth trying.
5. Stop. Switch **Capture notifications** off again — the capture copies the text of *all*
   notifications, messages included.
6. **Export the log and the capture** (the button in the same card, or
   `adb shell am start -n io.github.darthbob.kawanav/io.github.darthbob.kawanav.MainActivity --ez export_log true`).
   The files land in `Download/KawaNav` (`adb pull /sdcard/Download/KawaNav/`).
7. Send `....-notif-*.txt` and the `icons/` PNGs (they are in the same folder, named
   `...-icons-<hash>.png`).

## What we are looking for

In the capture file, blocks from `com.waze`:

- a line with a title like "Turn right" and a distance, changing at every turn → Waze *can* be read
  like Google Maps, and the icons in `icons/` are the arrows to map onto the dash's turn ids;
- only "Waze is running" and never a changed line → as expected, no turn-by-turn from the
  notification. Then the only ways left are a modified Waze app (which the project wazeology does,
  at the price of patching Waze and breaking its terms) or nothing.

## Privacy

The capture file contains the text of other apps' notifications, including messages. It stays on
the phone until it is exported. Read it before sending it, delete what you do not want to share, and
clear the folder afterwards (Android/data/io.github.darthbob.kawanav/files/notifications/ and
Download/KawaNav). The switch is off by default, and release builds cannot capture at all.
