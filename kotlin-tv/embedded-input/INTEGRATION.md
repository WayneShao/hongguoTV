# Embedded input integration (RayNeo only)

The host uses native Android Views. The search page owns the sole EditText that requests the system IME. Episode number entry is already a button panel and is unchanged. No Compose or WebView adapter is installed.

`SearchKeyboard.bind` wires explicit clicks, touch completion, DPAD/Enter/A to `NativeEditor`; then disables the system keyboard for that field. Focus alone never opens the keyboard. The original Editable, length filter, search action, history and phone input remain owned by MainActivity. Detach closes the module dialog; onStop also closes the search session. The module owns stereo rendering; Host only installs RayNeoInput on its dialog window.

## Rebuild

Sources copied from BBLL-RayNeo embedded-input at 32ac674b46555ccc557887add49a00172c118dd2. BBLL-specific uncommitted bridge changes were read but neither copied nor modified. Own module source is in embedded-input/src/main/java. Third party versions and SHA-256 values are in config/keyboard-vendor.json and config/keyboard-voice.json.

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


## Keyboard gestures update, 2026-10-19

Imported the exact clean reference worktree at 32ac674b46555ccc557887add49a00172c118dd2. No BBLL host bridge or page rules are used. The native search editor remains the actual host input target.

SearchKeyboard calls RayNeoInput.installKeyboard(dialog). Its callback passes touch/generic events to EmbeddedInput.motion and keys to EmbeddedInput.key before ordinary handling. A consumed event cancels this callback's pending confirm, delivery, single-finger gesture and two-finger menu state. Ordinary install(window) leaves keyboard owner null and retains the existing TwoFingerTap menu path. The module owns recording and cancellation on focus loss/dismissal.

Keyboard only: two-finger hold (~400 ms) starts voice, first release stops/commits; direct upward swipe clears all text and composition; upward swipe after hold cancels voice while retaining previous text. Third finger cancels/drains. Timed-out voice result is buffered until release and can still be cancelled. Native input filters and actions remain unchanged.

Module tests: 28 passed (9 editor contracts, 13 gesture state-machine cases, 6 window/session cases). BBLL-specific Runtime/media-fallback test was not transplanted; these results do not validate Hongguo physical dispatch. Physical gesture/voice/menu acceptance is deferred at the user's request.

Gesture release delivery: 0.9.0-rayneo.4-input-gestures (versionCode 12), BUILD SUCCESSFUL. APK in parent workspace outputs/hongguoTV-rayneo-embedded-input-gestures.apk. SHA-256 9939B53FB988D5D0BE78DE0CDE190BA733F5E3BA1C60EF795EF1494A0CD18E0C. Matching certificate verified, adb install -r succeeded, device base.apk SHA-256 matches. Actual search EditText tap opens the keyboard and Rime-ready status is visible in hierarchy. Keyboard left open; no recording or video started. Physical testing deferred by user. Lint remains excluded as in previous build.

Rollback for this gesture update: parent workspace outputs/hongguoTV-rayneo-embedded-input-preview.apk, adb install -r -d, preserves data with matching certificate. This earlier input preview contains the Android-compatible ID generator fix.

Double-tap repair: physical logs show pending=true cancelled when Android clears button focus before the second DOWN (110-150 ms between taps). Preserve the pending sequence only for touch-mode null-focus transitions while the old target remains attached; restore/validate target on second DOWN. Shared Activity/Dialog path includes player. Far-apart tap regression passes. Version 15 / 0.9.0-rayneo.7-double-tap installed; physical acceptance pending. Shared module updated to 706241c, hashes verified; 36 module tests passed. User cancellation routes through onBackPressed, programmatic hide only closes. APK SHA256 E0019D304D85ECDF666DBBEFC482847B31333C8E38D1E2E9A0651F642D0C1600.
