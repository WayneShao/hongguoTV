# Playback maintenance — 2026-11-04

## User-visible changes

- Distinguish unusable video/model responses from transient network failures. Incompatible video and malformed model responses no longer enter automatic retry or identity rotation. Socket and overall call timeouts retain the existing bounded recovery.
- Replace the fixed 720P recovery action with lower compatible qualities observed in the current episode's model. No invented tiers; no permanent change to the global quality setting. Explicitly reselecting the global quality clears a temporary override and reloads the episode.
- Keep the established playback path first. After two definitively empty original endpoint results, try one video_tab GET per shared episode request budget. Match only the requested episode key. Never persist a candidate identity on failed validation.
- Explain missing cloud account identifiers without logging out or discarding local records. Keep QR login; do not infer account provisioning solely from missing profile fields.
- Settings > preloading > current-run statistics: complete/partial cache acquisition, uncached acquisition, natural queued transitions, current pending cache items/bytes. Counters are process-local, not disk telemetry; acquisition does not imply first frame.

## Verification

- RayNeo: 249 core tests and 45 app tests pass. TV: 241 core tests and 22 app tests pass. Both app debug lint checks pass. Final core regressions include malformed+timeout, malformed+rejection, exact episode mapping, one GET per budget, timeout/cancellation separation, and account session preservation.
- Live comparison uses the same three episode IDs through old paths and the new path. Old successful path remains first. To isolate the new path, the comparison harness supplies empty fixtures only for old model endpoints; video_tab and media reads use real HTTPS. All six full media downloads/decryptions succeed at 480P (854x480 HEVC/AAC, approximately 65–71 seconds each). ffmpeg -xerror decodes all six video/audio tracks with exit 0. This is one network/time sample, not long-term CDN reliability.
- Actual Android 5/API21 application instrumentation passes Activity startup, network, packaged Base64/NIO update file handling, HTTPS catalog and GitHub latest, update dialog, and a production queue playing three HEVC/AAC clips with two automatic transitions. Audio track enabled; emulator host audio backend disabled to avoid a verified Windows DirectSound issue. This does not establish physical TV audio/decoder performance or physical glasses input.
- Evidence and reproducible comparison harness: ../outputs/review-2026-11-04/. APK outputs: ../outputs/release-0.12.2-candidate/.

## Variants and rollout

TV candidate: 0.12.2-local/code23, API21, existing TV signature.
RayNeo Keyboard/Full candidates: 0.12.2-rayneo-keyboard/full/code28, API26, existing glasses signature. Existing embedded keyboard and full voice payload are retained.

TV remains on tv/android5-preview. RayNeo remains on rayneo/x3pro. Online RayNeo X3 Pro receives the Full build under the standing user instruction, preserving data; installation evidence is recorded separately from physical input/playback acceptance. Stable latest remains 0.12.0: its TV updater rejects minSdk21 release metadata, so a stable release needs an upgrade bridge decision. Do not silently lower glasses minSdk or switch the accepted preload default.

## Upstream references

- https://github.com/tsukiyo-zero/hongguoTV/pull/46 — video_tab protocol reference; adapted as fallback rather than replacing successful existing paths. Exact episode key required instead of first available map entry.
- https://github.com/tsukiyo-zero/hongguoTV/pull/48 — deterministic incompatible-format handling; no automatic episode skip adopted.
- https://github.com/tsukiyo-zero/hongguoTV/pull/53 — account profile diagnosis; adapted conservatively for our QR account flow.
- https://github.com/tsukiyo-zero/hongguoTV/pull/57 — reviewed only: its recommendation/login paths differ from ours.
