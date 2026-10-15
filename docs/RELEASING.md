# Releasing

Pushing a version tag (`v0.9.2`, …) runs [the Release workflow](../.github/workflows/release.yml): it
builds both APKs, signs them with this fork's key, checks the signing certificate and publishes them
as a GitHub release with their checksums.

## Once: the release key on GitHub

The key is kept as two secrets of the `release` environment (Settings → Environments → release).
Only workflows started by a `v*` tag can read them; pull requests and branch pushes cannot.

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | the keystore file (`.jks`), base64-encoded |
| `KEYSTORE_PROPERTIES` | the content of `keystore.properties` (the workflow puts its own path in `storeFile`) |

In Git Bash, in the project folder (where `keystore.properties` is), replacing the path with your
keystore's:

```bash
base64 -w0 "C:/path/to/kawanav-release.jks" | gh secret set KEYSTORE_BASE64 --env release --repo jackyes/kawa-nav
gh secret set KEYSTORE_PROPERTIES --env release --repo jackyes/kawa-nav < keystore.properties
```

Neither command shows the passwords; `gh` encrypts the values on the PC before they are sent.
Keep the keystore and `keystore.properties` backed up outside GitHub as well: GitHub never gives a
secret back, and without the key no update installs over the app already on a phone.

## Each release

1. Raise `versionCode` and `versionName` in `app/build.gradle`. The tag must match: `v0.9.2` for
   `versionName '0.9'`, otherwise the workflow stops.
2. Write the notes in `docs/releases/v0.9.2.md`: what is new or changed, what has not been tried on a
   bike yet, which APK is which. The workflow adds the safety note, the checksums, the signing
   certificate and the licence line. Without this file it uses GitHub's generated list of changes.
3. Commit, push to `main`, and wait for CI.
4. Tag and push the tag:

   ```bash
   git tag -a v0.9.2 -m "KawaNav v0.9.2 (jackyes fork)"
   ```

   ```bash
   git push origin v0.9.2
   ```

5. The release appears under Releases with `KawaNav-v0.9.2.apk` and `KawaNav-v0.9.2-debug.apk` once the
   workflow is done (Actions → Release).

A failed run publishes nothing (at most a draft release is left: delete it under Releases). Fix the
cause, delete the tag (`git push origin :refs/tags/v0.9.2`, `git tag -d v0.9.2`), and tag again.
