# Manual Progress Merge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the approved manual account-page merge to both products, importing farther cloud progress and uploading farther eligible local progress.

**Architecture:** Keep episode comparison in a pure core planner. An Activity-owned merge controller reads both cloud stores and resolves catalogs off the main thread, then rechecks live local state on the main thread. Manual uploads use the existing serial durable history outbox with a persisted farther-progress policy and endpoint-specific acknowledgement state.

**Tech Stack:** Kotlin/JVM, Android native views, SharedPreferences, existing AccountRepository/AccountIntents, JUnit and Robolectric. Keep API21 TV and API26 glasses configuration unchanged.

---

All paths below are relative to each repository. Implement in `hongguoTV`, mirror shared changes into `hongguoTV-random-test`, and retain each product's UI/defaults and test SDK annotations. Only one Gradle lane runs at a time. No device installation or real-account writes are part of this implementation.

### Task 1: Pure progress comparison

**Files:** Create `kotlin-tv/core/src/main/kotlin/com/hongguotv/core/ProgressMergePlanner.kt` and matching `ProgressMergePlannerTest.kt` under core tests.

- [x] Write tests covering catalog order rather than numeric IDs or timestamps, position/completion, equal and absent sides, conflicting/unknown episode IDs, duplicate catalogs, different series and idempotence.
- [x] Run `:core:test --tests com.hongguotv.core.ProgressMergePlannerTest`; confirm failure before implementation.
- [x] Implement `ProgressMergePlanner` with `Direction { IMPORT, UPLOAD, EQUAL, SKIP }`, `Decision(direction, progress)`, `normalize(progress, episodes)`, `compare(left, right, episodes): Int?`, and `plan(local, remote: List<BackupProgress>, episodes)`. Normalize by unique episode ID in the actual catalog. Reject invalid inputs; never guess from numeric ID or a default index. Compare episode index, completed status and position, independent of timestamps. Remote inputs retain their separate history/progress provenance until validated. A missing side is different from an invalid side.
- [x] Run the focused planner tests: 9 tests per product, zero failures/errors/skips. Mirror identical production/tests and preserve platform differences. Cloud checkpoints are incomplete/unknown; only local explicit completion is authoritative.

### Task 2: Durable manual upload policy

**Files:** Modify `kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/AccountHistorySync.kt`; add `AccountProgressUploadTest.kt` under app tests. Core checkpoint wire shape remains compatible.

- [x] Add failing network fixtures for local farther with older timestamp, remote farther/equal, one endpoint ahead, partial acknowledgement and recreation, deletion/account switch at both writes, and normal playback regression.
- [x] Add a serial-worker manual merge boundary that pauses ordinary draining until the operation finishes. Add durable per-series reconciliation for both imported/equal winners and uploads, retiring older ordinary entries before applying the winner. Provide `offerMerge` with the actual catalog and main-thread durable acceptance callback. Persist manual policy, catalog, separate submission time and per-endpoint acknowledgement alongside the checkpoint/revision. Ordinary offers preserve pending manual policy unless farther or a new explicit playback revision supersedes it.
- [x] On manual drain, strictly read both stores and resolve actual episode order before writes. Never let a newer timestamp erase farther local work. Treat each endpoint independently: skip an endpoint already at least as far, send only to a behind/missing endpoint, retain unknown/invalid cases. Remote advancement supersedes obsolete work without claiming it was uploaded. Persist each acknowledgement before moving on; retry must not duplicate an already acknowledged write unless newer local work replaced it.
- [x] Recheck captured context, durable deletion revision and current stored row before each write and after each response. Persist submission timestamps separately and use them on wire copies only; preserve the original viewing timestamp on disk. If either cloud endpoint is farther, cancel the obsolete row without uploading to either endpoint or claiming a submission. Failed reads/writes leave valid durable work pending. Existing suppression, legacy-owner binding and passive guest behavior remain authoritative.
- [x] Reserve bounded manual drain capacity so old viewing timestamps cannot starve behind normal work. Run new fixtures plus `AccountHistoryIntentTest` serially in both products, archive XML and inspect counts.

### Task 3: Merge controller and local persistence

**Files:** Create `kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/AccountProgressMerge.kt`; modify `Library.kt`; add `AccountProgressMergeTest.kt` and `LibraryProgressMergeTest.kt`.

- [x] Add failing fixtures for two-store read failure with no mutation/upload; cloud-only/progress-only; guest adoption versus foreign ownership; bounded local persistence and genuine resume selection; deletion/relogin/teardown during reads; newer playback during a blocked pull; and overlapping clicks.
- [x] Implement an Activity-owned controller with one active manual operation. Inject main-thread local snapshot/ownership/application callbacks, existing repository/intents and the durable outbox; expose `start(context, resultCallback): Boolean` and `close()`. Use a serial worker for HTTP/catalog resolution. Capture context and global action generation at start, check after every IO boundary and before every main-thread apply. Detaching the account page only suppresses its UI result; Activity retirement cancels application while accepted durable uploads survive.
- [x] Read both stores completely as supported by current APIs before any apply/offer, using the existing history worker boundary to prevent an older drain racing these reads and local application. Retain distinct checkpoints and use actual detail catalogs. Remote checkpoints carry no completion flag; never infer it from duration or position. Resolve progress-only metadata via local rows or detail. Skip isolated failures and suppressed/foreign-owned IDs in both directions. Never interpret the bounded response as all cloud history.
- [x] Main-thread application re-reads local progress and ownership. For each series pass both history and video-progress candidates together to `ProgressMergePlanner`, so a blocked read cannot overwrite playback that advanced in the meantime and one cloud store cannot replace a farther winner from the other. Accept guest rows only from this explicit action. Claim adopted/imported rows for the captured canonical account without calling `rewatch` or changing suppression. Never seek an active player.
- [x] Add a narrow `Library.mergeProgress` operation accepting already validated winners; retain history limits and exact completed IDs, batch progress/completion/watched persistence, clear an obsolete series-wide watched flag only when needed to make imported incomplete progress resume correctly. Do not merge through timestamp-based backup restore. Use the existing asynchronous writer and verify persistence across close/reload; do not present a disk acknowledgement that the writer has not provided.
- [x] Queue local winners through `offerMerge` with exact current per-ID revisions. Report local updates, durable submissions/pending and skipped records honestly. Acknowledged HTTP submission is not cloud read-back confirmation.
- [x] Run new controller/library fixtures and existing completion/Activity intent tests. Inspect actual resumed target and stored progress after restart.

### Task 4: Account-page entry and Activity lifetime

**Files:** Modify `AccountScreen.kt` and `MainActivity.kt`; add `AccountProgressMergeScreenTest.kt` and extend focused Activity wiring tests as needed.

- [x] Write a real-view fixture for signed-in availability, explanatory text, disabled repeat click, running/result/failure labels, and stale page/account callbacks.
- [x] Append an optional merge dependency to `AccountScreen` so existing callers remain valid. Add `合并观看进度` and `按观看进度合并，本地领先时上报云端`; explain that this explicit action may include guest records while ordinary login does not. The operation must never trigger on render/login/list reads.
- [x] Wire one lazy Activity-owned controller using the existing readiness/ownership helpers; close it during Activity teardown without constructing an unused controller. Page close must not clear accepted upload work. Preserve TV native UI and glasses stereo UI.
- [x] Re-run screen and Activity fixtures in both products. Check owner and generation guards at the real integration boundary.

### Task 5: Review and final verification

**Files:** Evidence under `outputs/progress-merge-2026-11-10/` in the enclosing workspace; update the design and implementation record if review requires a clarification.

- [x] Review all changed core/app code for acceptance-rule coverage, account isolation, endpoint supersession and durable retry; fix substantive findings and re-run affected tests.
- [x] Run the existing serial runner: `& 'D:/Backup/Desktop/hongguo/outputs/review-2026-11-06/verify-integration.ps1' -OutputDirectory 'D:/Backup/Desktop/hongguo/outputs/progress-merge-2026-11-10/final-integration'`.
- [x] Independently recount archived XML, confirm lint has no errors, inspect build results and unchanged package/signing/API settings, and run `git diff --check` in both repositories.
- [x] Record fresh test counts and limitations, commit only intended feature/tests/docs in each repository, preserve original untracked TV logs, and report the local result. No push, release or installation is implied.
