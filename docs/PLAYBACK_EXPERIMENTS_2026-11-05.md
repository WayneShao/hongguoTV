# 0.12.3 playback experiments — 2026-11-05

Approved scope: compatible-codec request, device_level A/B, bounded batch metadata prefetch, accurate playback statistics. Companion computer transcoding explicitly excluded.

## Results and delivered behavior

1. Full ByteVC2/bvc2 fallback models can receive one extra codec_type=0 + force_codec=h264 request, counted in the existing episode budget. Actual returned codec is still validated. Unknown/mixed codec evidence does not trigger it, and failure never rotates identity. Preserve original User-Agent per path. Unit regression verifies successful h265, continued bvc2 rejection, URL parameter retention and once-per-budget.
2. Same-identity comparison of five episodes at device_level3 and1 found no improvement: shortplay returned four bytevc1 variants in both cases; phoenix rejected both. Exact reported season12 episodes71/72 were located (series7702705838418316313). Four complete decrypted media samples (two levels × two episodes) were HEVC hvc1 836x480/AAC; same episode files had identical hashes and ffmpeg full-video decode exited0. Existing level3 remains unchanged; tab fallback already uses1. The compatibility-codec incident did not reproduce in this sample/time.
3. Batch metadata code is implemented behind a separate DEFAULT-OFF experimental switch under preloading settings. It requires the existing preload switch and foreground buffer gate, requests at most3 upcoming IDs, retains <=6 entries for <=120 seconds, keys by identity/episode/quality, consumes once, cancels on foreground demand and never registers devices. Signed transport only parses exact IDs, accepts partial success and performs no media/fallback fan-out. Its real request with the same identity was rejected; no live benefit claimed. Ordinary playback continues the established single-episode path. Failures are cooled down. Existing media cache budget unchanged.
4. Process-session statistics distinguish acquisition from actual initial rendered frame/startup wait and eligible rebuffer durations. Startup/paused/suppressed/seek intervals excluded; seek/resume starts excluded from startup samples. Suppression callbacks split eligible intervals. Seamless per-item first-frame gap remains explicitly unavailable because existing rendered-frame callback cannot accurately attribute every continuously rendered media item; READY is never reported as rendered.

## Validation

RayNeo core260/app45 and TV core252/app22 tests pass; both debug lint checks pass. Final UA preservation core regression rerun passes. API21 actual-app instrumentation passes startup/network/Base64-NIO/update dialog/HTTPS catalog+GitHub and three HEVC/AAC clips with two auto transitions. This uses an emulator with host audio backend disabled (audio track enabled), not physical old-TV audio/decoder proof. Keyboard/Full retain API26 and embedded input; TV retains API21 preview. No model-ID rotation policy change.

Evidence outside Git: ../outputs/codec-1105/ (CodecProbe.java, live-media.txt, media-verification.json), ../outputs/review-2026-11-05/implementation/ (tests, live-batch.txt, API21 report). Release artifacts: ../outputs/release-0.12.3-candidate/.

References: tsukiyo-zero/hongguoTV PR59 and issue56; miaoxiaohu/hongguoTV a2a30eae994ad6bb845190b2209f7d761d1d621f. Independent review corrected playback-suppression accounting and preserved legacy headers. Batch experiments do not adopt fresh registration per request.
