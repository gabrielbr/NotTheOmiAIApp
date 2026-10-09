# Releasing GVoice

Every push to `main` (each merged PR) runs `.github/workflows/release.yml`. It:

1. downloads and hash-checks the pinned models and whisper.cpp source,
2. builds the native Whisper library and runs the host checks,
3. builds the release APKs with versionCode `100 + run number` and versionName `0.5.<run number>`,
4. signs both apps with the persistent release key and runs `tests/hybrid/verify_apk.py` (GVoice: no INTERNET) and `tests/sentient/verify_apk.py` (GMind: exact permission allow-list, same signer),
5. publishes GitHub Release `v0.5.<run number>` with `GVoice-<version>-arm64-v8a.apk`, `GMind-<version>.apk` and `SHA256SUMS.txt`.

Both apps must share the signing key: GMind (module `sentient`) reads GVoice transcripts through a signature-level permission.

Install the newest release's arm64 APK on the phone. Because the versionCode always grows and the signing key never changes, it installs as an update and keeps your recordings.

## One-time setup: the signing key

Android only installs an update if it is signed with **the same key** as the installed app. The workflow refuses to run without that key. It will never create a new key.

Add two repository secrets under GitHub → Settings → Secrets and variables → Actions:

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | the PKCS12 keystore, base64-encoded on one line |
| `RELEASE_KEYSTORE_PASSWORD` | its password (store and key share it; key alias `release`) |

To create a key yourself (Java's `keytool` required):

```sh
keytool -genkeypair -keystore release.p12 -storetype PKCS12 -alias release \
  -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=GVoice"
base64 -w0 release.p12   # paste the output into RELEASE_KEYSTORE_BASE64
```

**Back up `release.p12` and its password somewhere safe.** If the key is lost, the next release cannot update the installed app. You would have to uninstall it, which deletes its encryption key and recordings. Export anything important first.

After adding the secrets, re-run the latest "Release APK" workflow from the Actions tab (or push to `main`).

## Local signed build

`scripts/sign_release.py --key-dir <dir with release.p12 and password>` signs the outputs of `./gradlew assembleRelease` into `dist/` (both apps by default; `--modules app` or `--modules sentient` for one).

## Obtainium

- **GVoice:** add `https://github.com/gabrielbr/NotTheOmiAIApp` as a GitHub source, with APK filter `^GVoice-.*-arm64-v8a\.apk$`.
- **GMind:** Obtainium won't add the same repository URL twice ("App already added"), so install `GMind-<version>.apk` from the release by hand.
