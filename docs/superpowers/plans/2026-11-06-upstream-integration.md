# Upstream maintenance integration

User approved recommendations 1–4 and their order on 2026-11-06. Execute in the existing rayneo/x3pro and tv/android5-preview worktrees; preserve their platform-specific behavior and existing untracked logs. No release or device installation is part of this change.

## Design and acceptance

1. Playback: sign the exact single-episode GET fallback query with the existing PlayerSigner (null body), using the existing registered device. Keep the one-GET budget, cancellation, terminal errors and identity-recovery bounds. Inspect the POST chain but do not replace working signing/identity behavior based solely on another fork's live observations. Regression tests must inspect the outgoing GET signatures and exact video ID; attempt a bounded live comparison if existing tooling permits.
2. Next season: adapt wanwdbb faf8876ac24a6040934b64a32ca1dd1ddd52f144. Extract conservative title/season matching into core; only match the adjacent numbered season of the same normalized title and content type. Reject ambiguous candidates and previews. Search at most three pages plus existing related results if available, validate detail before playback. Integrate at natural final episode end with autoNext enabled, foreground/resumed player and no sleep expiry. Cancel or discard results on navigation, lifecycle change, pause, replay or replacement; preserve completed-season history and start next season at episode zero with a title toast. Never block the UI or start multiple continuations. Tests cover matching, ambiguity and stale lifecycle results.
3. Installation: introduce a small shared installation intent helper, API21–25 INSTALL_PACKAGE then VIEW fallback, API26+ existing VIEW/per-app permission flow. Preserve FileProvider content URI, ClipData and temporary read grant on every attempt. Failure remains actionable, with no automatic settings loop. Test API21/23/25/26 intent selection, fallback and grants, including both attempts failing.
4. Diagnostics: show model, Android version, app version, bounded recovery count and safe error category on the playback failure page. Preserve existing actions/focus and avoid URLs, cookies or identifiers from raw exceptions. Test classification where meaningful.

## Implementation steps

- [x] Task 1: ContentRepository.kt GET signing and ContentRepositoryPlayerTest.kt request regression; core tests.
- [x] Task 2: core SeasonContinuation.kt + tests; MainActivity.kt continuation hooks + stale-token regressions.
- [x] Task 3: TV installation helper + AppUpdater.kt integration + Robolectric tests; glasses retain API26+ VIEW flow.
- [x] Task 4: core playback diagnostics helper + MainActivity.kt failure text + classification tests.
- [x] Record upstream attribution in kotlin-tv/NOTICE.md; synchronize shared changes to TV without replacing API21 patches.
- [x] Independent spec and code review; fix findings (pause/picker invalidation, seamless seek reset, strict numeral grammar, retry counts, ended controls and pending status).
- [x] Run core/app tests, lint and APK assembly for TV and X3 Pro variants using local build configuration; inspect diff and report real-device limits.

## Verification commands

Use the repository's installed Gradle/JDK configuration. From kotlin-tv: gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug (adjust app task names for rayneo keyboard/full product flavors after inspecting build.gradle.kts). Serialize builds across worktrees to avoid resource contention. No test may require the live content service in the normal suite.

## Integration decisions

- Season lookup uses up to three search pages and confirms detail; no related-results request is needed. Unknown types, malformed season numbers, ambiguous matches and incomplete detail fail closed.
- Main-thread epoch tokens reject stale asynchronous results after pause, navigation or player replacement. Seamless episode transitions reset the per-episode seek guard. Abandoning a pending lookup restores ended controls.
- Diagnostics show the historical bounded retry count; the existing recovery text continues to describe current recovery status.
- Legacy install handling belongs only in TV (minSdk 21); glasses already use VIEW and per-app install permission at minSdk 26.
- Outgoing GET signing is verified offline, including registered identity, exact episode, null body and one-fallback budget. Existing live tools do not force this fallback, so a successful live GET is not claimed.
- Automated tests do not establish physical-TV installer compatibility or real-service cross-season playback; those remain device acceptance checks.

## Final verification

Final serial verification passed after all review fixes: TV 262 core + 34 app tests;
Keyboard and Full each 270 core + 45 app tests, zero failures/errors/skips.
All three passed lintDebug and assembleDebug. APK metadata confirms TV minSdk 21
and both glasses variants minSdk 26. Both worktrees passed git diff --check.

Logs, test summaries, SHA-256 and debug APKs are saved in
`D:\Backup\Desktop\hongguo\outputs\review-2026-11-06`.
These are development verification builds using the repository's default debug
version/signing configuration, not a newly versioned personal release.
