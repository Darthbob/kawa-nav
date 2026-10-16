# Releasing

A push to `main` whose `versionName` has no tag yet runs [the Release workflow](../.github/workflows/release.yml):
it builds both APKs, signs them with the release key, checks the signing certificate, tags
`v<versionName>` and publishes a GitHub release with the changes from `changelog.json` and the
checksums. Installed apps (1.0 and newer) then offer the update themselves (see `Updater.java`).

## Each release

In one commit or pull request:

1. Raise `versionCode` by 1 and set the new `versionName` in `app/build.gradle`.
2. Add an entry **on top** of `changelog.json` with `version` = the new versionName and its
   `changes`: short sentences for riders. The app shows them in the update dialog, the release notes
   list them. CI fails when the current versionName has no entry.
3. Optional: longer notes in `docs/releases/v<versionName>.md` (what is new in detail, what has not
   been tried on a bike yet). They follow the list of changes in the release notes. The workflow
   adds the safety note, install steps, checksums, signing certificate and licence line itself.

Merge or push to `main`. The release appears under Releases with `KawaNav-v<versionName>.apk` and
`KawaNav-v<versionName>-debug.apk` once the workflow is done (Actions → Release). The updater
downloads exactly `KawaNav-v<versionName>.apk`.

Pushes that do not change the version are only built and tested (CI). A failed release run
publishes nothing (at most a draft release is left: delete it under Releases); fix the cause and
push again.

## The release key on GitHub

Every version is signed with the same key (certificate SHA-256
`7e9575b361224e88803f075caa6ee7373066901f701d31a619e47cc2f7cce08b`): Android installs an update only
when it is signed like the installed app. The key is kept as secrets of the `release` environment
(Settings → Environments → release), which only `main` may use:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | the keystore file (`.jks`), base64-encoded |
| `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | the values from the local `keystore.properties` |

Keep the keystore and `keystore.properties` backed up outside GitHub as well: GitHub never gives a
secret back, and without the key no update installs over the app already on a phone.
