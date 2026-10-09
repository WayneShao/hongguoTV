# RayNeo X3 Pro native port

User selected the previously proposed hongguoTV/kotlin-tv port on 2026-10-09. Scope: independent package, single content tree per window, all app-owned windows in stereo, native four-way focus, single video decoder. Official account login remains out of scope.

## Architecture

- Keep upstream checkout baseline eea73e6 and work on rayneo/x3pro. This fresh clean clone is already an isolated workspace; no additional worktree is necessary.
- Use the explicitly user-selected launcher/application name 红果TV (latest correction); do not add RayNeo/X3 Pro/adapted edition labels. Package com.hongguotv.rayneo; retain namespace for source compatibility, version 0.9.0-rayneo.2, stable local signing, no upstream auto-update downloads.
- RayNeoLayout measures one logical UI at half physical width. Render UI once to a reusable software bitmap and composite the same bitmap into both eyes. No repeated child hardware draw; transparent video overlay, no video frame readback. Invalidation driven, no permanent UI polling loop.
- Activity has a full-window video surface below the stereo UI host. Media3 PlayerView uses surface_type=none; a single ExoPlayer feeds an OES SurfaceTexture renderer, drawing the same frame into both viewports. Fit/zoom/stretch, size/rotation, lifecycle and late surface callbacks must be correct.
- Central RayNeoDialogs factory wraps complete dialog content including title, list and buttons using public Window/View APIs. EpisodePanel uses the same wrapper after its own sizing. Replace app Toasts with window-owned stereo notices. System IME/installer are outside app ownership; phone input remains available.
- Window-owned input uses exact right cyttsp5_mt, consumes left cyttsp6_mt and CapSense inputs before children. Physical mouse and DPAD remain normal. One swipe -> one direction; delayed single tap bound to target, double tap -> original back, long press -> original menu; two-finger tap -> one original MENU event. Cancel pending work at window/content changes. Focus recovery uses current visible focus and lists and bounded fallback, no replayed confirmation.
- Default missing/invalid quality to 480P while preserving explicit saved values. Use logical width for layout calculations, compact card columns, scrollable top navigation, preserve business networking and local Library semantics.

## Alternatives considered

1. Selected: software UI snapshot plus independent single-decoder OES video. A little UI bitmap work, but avoids known repeated hardware draw/device stability issues and excludes video readback.
2. TextureView/full-window capture: easier initial composition but copies every video frame and risks unnecessary CPU/thermal overhead.
3. Two UI/player trees: rejected because duplicate state, effects and decoders are unnecessary.

## Coverage and verification

MainActivity catalog/detail/settings/player; TvTools info/confirm/list/numeric input/phone search/backup/restore; speed/quality/license dialogs; EpisodePanel and nested jump; AppUpdater local version notice; transient notices; surface-less PlayerView loading/error/subtitles.

Run baseline JVM tests/build, meaningful navigation/gesture/geometry tests, lint and APK metadata/signature checks. On selected verified glasses only: preserve boot ID and current installed-package state, install independent package, launch, capture stereo UI/dialog/player screenshots and logs, exercise DPAD and playback lifecycle. Real temple input and optical comfort remain user physical acceptance. No reboot, app-data clearing or changes to other apps.
