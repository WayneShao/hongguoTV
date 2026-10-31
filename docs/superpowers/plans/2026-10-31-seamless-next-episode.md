# Seamless next episode implementation plan

Approved design: ../specs/2026-10-31-seamless-next-episode-design.md. User authorized implementation and autonomous tests; goal implements new near-gapless next-episode release.

1. Core cache: atomically claim only complete prepared disk resources; retain pinned queued keys in desired horizon and byte/memory budgets; explicit unpin/consumption. Cache-only read must fail without network on missing/truncated cache. Regression tests cancellation, no duplicate work, limits.
2. App source ownership/controller: create owned MediaSource wrapper which releases RemoteVideo only after underlying source/period release. Single ExoPlayer current+next queue, 3s sample preload, cached-only next, safe drop/remove and dispose. Tests source ownership and queue state.
3. MainActivity: stable listener/session rather than captured episode ID; add complete next while playing, handle AUTO transition using old episode snapshot, completion dedup, sleep-at-end callback, manual queued-next seek, lifecycle and quality changes. Preserve labels/input/stereo surface and old recovery fallback.
4. Android validation: provision local emulator if needed, use production app/Media3 components with separate instrumentation (no user-facing test app). Real decrypted media fixtures, frame metadata and video/audio counters; compare prepare-after-end baseline vs queue; exercise pauses, disabled auto-next, source failure, cleanup. No device UI interference outside authorized testing.
5. Full core/app regression, targeted real playback/instrumentation, reviewer pass. Fix findings, then build TV/Keyboard/Full with existing signatures and higher version codes; publish verified assets; verify identity before optional online-glasses Full install. Leave app current page intact if device used.

Serialize Gradle per checkout. Keep current+queued resources accounted and never compare media video_id to episode IDs. No claim of universal zero hardware gap; report measured pipeline and device boundaries.
