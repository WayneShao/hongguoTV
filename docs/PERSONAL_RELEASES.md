# Personal Android releases

Maintained at https://github.com/WayneShao/hongguoTV.

Every release from 0.9.3 includes three signed release APKs:

- TV: `main`, package `com.hongguotv.nativeapp`, standard Android TV UI.
- X3Pro Keyboard: `rayneo/x3pro`, package `com.hongguotv.rayneo`, stereo embedded input without speech assets, speech native library or microphone permission.
- X3Pro Full: the same glasses branch and package, with embedded input and offline speech.

Both glasses variants use the same versionCode and signing certificate so users can switch within a release without removing app data. Their launcher label remains 红果TV. Keep each product's existing signing key; do not regenerate keys for an update. Keys remain outside this repository.

## Playback source and recovery policy

0.9.3 imports the registration, signature and playback protocol from BearQu/hongguoTV `fix/registered-device-playback`, commit `dd840fe99a26e8fb7905419352f3defb5a98d705`. Original N3urda main is still the base; the repair is from BearQu's separate fix branch. Attribution is in `kotlin-tv/NOTICE.md`. Premium entitlement activation is excluded.

Our own recovery policy takes precedence over the imported rotation logic:

1. Reuse the persisted registered identity. During migration, first try the previous release's current device/installation IDs.
2. Only a device rejection causes registration of one candidate. A timeout or a missing playable format does not.
3. Retry the original playback resolution using that candidate.
4. Save it only after resolution succeeds. On failure, leave the old persistent identity and the next request unchanged.
5. Keep the prior preload protocol as a fallback, without a second random-identity rotation in that playback request.

Playback URL resolution is the validation boundary; it is not proof that the media CDN or decoder will play the entire episode. Registration IDs are returned by the server, rather than invented locally. Search retains its existing recovery path.

## Build

Use JDK 17 and the Android SDK. Prepare the pinned input payload with `scripts/prepare-embedded-input.py` as described in `kotlin-tv/embedded-input/UPSTREAM.md` before building the glasses branch.

Set `HONGGUOTV_KEYSTORE`, `HONGGUOTV_STORE_PASSWORD`, `HONGGUOTV_KEY_ALIAS`, `HONGGUOTV_KEY_PASSWORD`, `HONGGUOTV_VERSION_CODE` and `HONGGUOTV_VERSION_NAME` per product. SDK configuration goes in ignored `kotlin-tv/local.properties` or `ANDROID_HOME`.

Build `:app:assembleRelease` with `-PembeddedVoice=false` for Keyboard and `-PembeddedVoice=true` for Full. TV is built from main without the embedded-input module. Copy the resulting APK after each build. The Sync task removes stale voice payloads when switching from Full to Keyboard.

The local keyboard adapter intentionally adds a VOICE_ENABLED build gate to the pinned module: it hides the speech button and prevents speech startup, while preserving keyboard input and clear gestures. Other windows keep their existing two-finger menu behavior.

For 0.9.3: TV versionCode 12; both glasses APKs versionCode 18. Verify manifest metadata, debuggable flag, certificates, native/assets contents and SHA-256 hashes before uploading. Publish all three APKs and SHA256SUMS.txt together. If the verified X3Pro is online, cover-install Full with `adb install -r --no-incremental`, preserving data; do not auto-launch or send input.

Focused playback and recovery JVM tests pass. Android lint was omitted from local delivery builds because dependency retrieval had failed; physical temple gestures, voice and live playback remain separate device acceptance checks.
