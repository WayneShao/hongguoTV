# TV Android 5 compatibility preview — 2026-11-03

Branch: `tv/android5-preview`, based on TV `58e1a67`.
TV only: minSdk 21 (Android 5.0), targetSdk 36, version 0.12.1-local/code 22.
RayNeo Keyboard/Full remain API 26 on their existing branch; no glasses install.
App name, TV package and TV release signing identity remain unchanged.

## Changes

- Guard API 23/24/26 network, service lookup and installer APIs; preserve Wi-Fi /
  Ethernet phone-input and backup LAN discovery on API 21.
- Use the older PriorityQueue constructor and a versioned status-bar style.
- Retain Java Base64 and NIO through the existing desugaring configuration.
- Supplement old system TLS roots inside the application only on API 21–23.
  Standard certificate-chain and hostname checks remain. Initialization failure
  retains system TLS instead of crashing application startup. Root provenance and
  fingerprints are in `kotlin-tv/app/src/main/assets/legacy-roots/README.md`.
- Accept API 21 TV update metadata. Preserve package, hash, version and signer
  checks. Android 5's installer checks the APK's declared minimum SDK; reading
  ApplicationInfo.minSdkVersion directly requires API 24.
- No playback-interface, cache, preload-default or RayNeo behavior changes.

## Runtime evidence

Official Android API 21 x86 emulator, Android 5.0. Production application plus
`LegacyTvInstrumentation`, not a standalone replacement application:

- Activity starts; network is recognized.
- Final signed/minified Release APK installs on API 21 and starts successfully;
  the home catalog and poster images render. No crash-buffer entry was produced.
- Packaged Base64, NIO update-file write and SHA-256 verification pass.
- HTTPS catalog search returns results; personal GitHub latest release parses as
  `personal-0.12.0` with certificate verification enabled.
- Three 3-second 854x480 HEVC/AAC fixtures pass through production RemoteVideo,
  SeamlessPlaybackQueue and ExoPlayer. Two natural transitions, then STATE_ENDED.
  Audio track remained enabled. This verifies decoding/queue completion, not
  audible output, physical panel gaps or vendor hardware decoder performance.
- Update dialog opens. Full download-to-system-installer upgrade on an old
  physical TV is not yet accepted.
- The emulator exposes cellular transport, so LAN-only QR is correctly absent;
  API 21 Wi-Fi/Ethernet/disconnected cases are separately tested with Robolectric.

Test-only DNS overrides used host-resolved IPs for api.github.com and
hongguoduanju.com because this emulator's DNS failed. No IP override enters
production. Initial TLS missing-root failures were reproduced before the fix.
An initial playback timeout at position 0 was accompanied by repeated Windows
DirectSound buffer-loss errors. Relaunching the isolated emulator with
`-audio none` fixed progress without disabling the player's audio track or
changing production playback code. Physical TV audio remains a test gap.

Evidence outside Git: `../outputs/api21-lab/instrumentation7.txt`, build/lint/test
logs and the signed release verification report in the same directory.
Final verification: 233 core tests + 22 Android unit tests pass; Release lint and
assembleRelease pass. APK minSdk=21, targetSdk=36, label=红果TV, v1/v2 signature
verified against the existing TV certificate. APK SHA-256:
`672adfbc40d6e3f9290db1463d36860eeab3cb180bab6b252673e151b11aa4e1`.

## Distribution boundary

This is an opt-in preview, not a replacement for stable latest. Existing 0.12.0
TV updater rejects minSdk below 26 in release metadata; therefore do not publish
an API 21 asset as stable latest without first deciding an upgrade bridge.
The preview supports manual cover installation with the existing TV signer.
Do not lower RayNeo metadata or ship a speculative Android 4 build: API 21 is the
current tested dependency floor. Vendor ROMs/codecs still need real TV feedback.

## Upstream review

- Original N3urda repo has no new implementation since the previous review;
  personal fork has no open issue/PR requiring repair.
- https://github.com/tsukiyo-zero/hongguoTV/pull/46 introduces the single-episode
  `/reading/bookapi/video_tab/multi_video_model/v1/` path with older-path fallback.
  Worth a separate live comparison, not a wholesale replacement of our adapter.
- https://github.com/tsukiyo-zero/hongguoTV/pull/48 recognizes unsupported bytevc2
  rather than retrying it eight times. Preview sampling may skip to episodes 2/3;
  normal sequential viewing must not silently skip episodes.
- https://github.com/tsukiyo-zero/hongguoTV/issues/50 is a new November 3 report
  of playback-interface failure, with no diagnostic detail at review time. The
  new interface is consequently not established as universally stable.
- fyworld's November 2 work follows the same video_tab direction. evil1127's
  preload statistics and bounded handling of rejected CDN requests are useful
  follow-up ideas; infinite retry and blind CDN substitution are not adopted.

These upstream interface changes were researched, not included in this OS-only
preview, so failures remain attributable and the accepted playback path is kept.
