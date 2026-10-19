# Embedded input integration (RayNeo only)

The host uses native Android Views. The search page owns the sole EditText that requests the system IME. Episode number entry is already a button panel and is unchanged. No Compose or WebView adapter is installed.

`SearchKeyboard.bind` wires explicit clicks, touch completion, DPAD/Enter/A to `NativeEditor`; then disables the system keyboard for that field. Focus alone never opens the keyboard. The original Editable, length filter, search action, history and phone input remain owned by MainActivity. Detach closes the module dialog; onStop also closes the search session. The module owns stereo rendering; Host only installs RayNeoInput on its dialog window.

## Rebuild

Sources copied from BBLL-RayNeo embedded-input at 659599e57c4c08175c47ba47bbc8ad66e2a59fa2. BBLL-specific uncommitted bridge changes were read but neither copied nor modified. Own module source is in embedded-input/src/main/java. Third party versions and SHA-256 values are in config/keyboard-vendor.json and config/keyboard-voice.json.

From repository root:

    python scripts/prepare-embedded-input.py --fetch

Or reuse a verified cache:

    python scripts/prepare-embedded-input.py --vendor PATH_TO_VENDOR

Configure JDK 17, Android SDK 36 and the existing signing key. On this host use JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=C:/temp/hongguo-build for all JVM children and -Pkotlin.incremental=false. Build :app:assembleRelease. Keep native JNI names using library consumer rules. Only ARM64 engine payloads are supplied. Generated dependencies and models are ignored. Feelime notices are packaged alongside its dictionaries; sherpa-onnx is pinned to v1.13.6.

## Acceptance

Compilation/install are separate from physical input and optical acceptance. Verify search field activation, stereo keyboard, Chinese candidates, English/numbers, deletion/selection, original search submit, back dismissal, repeated opening, background cancellation, microphone denial/retry and local speech on glasses. No automatic recording, system IME replacement or global display settings are involved.

Rollback: reinstall outputs/hongguoTV-rayneo-x3pro-device-id-recovery-compatible-release.apk from the parent workspace using adb install -r -d. Both packages retain the previous Android certificate. Do not uninstall or clear data.

## Device evidence, 2026-10-19

Release assembled successfully with R8, matching previous certificate 03a937d17b2d6a88181d35e686809dc1a3640ee237a22d85a250b5e92e522292. Installation succeeded without clearing data. Fixed the pre-existing identity generator crash: Android 12 lacks SecureRandom.nextLong(bound); digits now use nextInt.

On the actual search page, ADB tap opened the keyboard. Rime produced ni candidates including 你, selection updated the original EditText. Back dismissed only the keyboard. DPAD_CENTER reopened it. Complete/Search invoked the original search, whose content request returned a parsing error (not a successful result). Reopening and deletion worked; DPAD_RIGHT moved focus. Screenshot shows both eye halves. Test text was cleared and keyboard left open. No microphone capture or video playback was started.

Not accepted yet: physical temple gestures, optical comfort, speech recognition/permission retries, extended lifecycle stress. Release lint was excluded due the previously observed dependency-download TLS issue; no full unit-test success is claimed.

Artifact: ../outputs/hongguoTV-rayneo-embedded-input-preview.apk (relative to repository parent workspace), 152788077 bytes.
SHA-256: 971210CF26AB926A1D6055232C533D145E6E229E08E9AA3FA83341C45D9342FB
