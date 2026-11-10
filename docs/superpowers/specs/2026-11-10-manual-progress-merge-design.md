# Manual cloud/local progress merge

Date: 2026-11-10. Applies to the TV and RayNeo applications.

## Approved behavior

The user requested cloud progress be merged into the local library, while farther local progress must be retained and uploaded. After reviewing the comparison rules, the user selected **manual merge** rather than automatic merge or a combination of both. This feature adds a signed-in account-page action, `合并观看进度`. Starting the application, signing in, or opening a cloud list must not implicitly import guest history or start this operation.

For each series, compare actual episode order first, then position within the same episode. Explicit local completion wins over an incomplete checkpoint of that same episode. The cloud APIs do not supply completion: never infer it from position or duration. A newer timestamp alone never makes a less advanced checkpoint win. Resolve episode IDs against the series episode list when comparing different episodes; missing, contradictory, or unresolvable episode information is skipped and reported rather than guessed from numeric IDs or default index zero.

- Cloud ahead, or cloud-only: import into local history so it appears in `最近观看` and resumes from the imported position.
- Local ahead, or local-only: keep the local checkpoint and queue it for upload to both existing history/progress endpoints.
- Equal: do not rewrite progress or upload duplicate checkpoints.
- Preserve existing exact completed-episode marks. Do not infer that intervening episodes were watched. An imported checkpoint must not mark an entire series watched.
- This explicit action may include previously unassigned guest records. Records owned by another account are excluded, including conflicting legacy account bindings. Existing history deletions suppress both local import and upload and are never undone by merging.

## Integration

Keep comparison/planning as pure core logic. Use the existing account-history worker, durable outbox, account context/epoch and per-series deletion revisions for orchestration. Extend the local library with a narrowly scoped history-merge operation; its existing bounded retention and batched progress/completion persistence remain authoritative. TV/API21 and RayNeo/API26 retain their platform UI, package, signing and build configuration.

Read both cloud stores before applying a merge. Use history rows as presentation metadata and progress rows as a separate source of checkpoints; choose the farther valid checkpoint rather than the newer timestamp. A progress-only series may use local metadata or a detail lookup. The current history API returns bounded lists: describe results as the records read, and never interpret an absent row as a deletion or proof that all remote history was fetched. Failure to read a required cloud store leaves the local library unchanged and starts no merge uploads. Failure to resolve one series skips that series without discarding valid peers.

Capture the account and action generation at the click. Revalidate them after every network boundary and before applying local changes. Re-read the current local checkpoint before applying each result so a blocked pull cannot overwrite newer playback. A changed account, same-credential logout/login, deletion generation, library teardown, or superseded merge invalidates the old result. Do not change an active player's position as a side effect of merging.

Manually queued records carry a persisted farther-progress policy distinct from normal playback checkpoints. Retries re-read remote progress before sending and discard merge work that is already satisfied; a newer remote timestamp must not discard farther local work. If either cloud store has advanced further meanwhile, cancel the obsolete merge record as superseded, never send it to either endpoint and never describe this cancellation as a successful upload. Preserve the policy, catalog and captured action revision through process recreation. Persist a separate submission timestamp chosen no earlier than either observed remote timestamp; use it on wire copies and preserve the original local viewing time.

Before applying any selected winner, reconcile the single outbox slot for that series on the existing serial history worker. Retire older ordinary or manual checkpoints covered by this merge, including a newer-timestamp ordinary row behind an imported cloud winner. A later ordinary offer must not erase a pending merge policy or move its checkpoint backwards; only a farther checkpoint or a newly accepted explicit playback/rewatch revision may supersede it. Unrelated ordinary playback and rewatch semantics remain unchanged.

Before each of the two upload endpoints, recheck the captured owner/session and per-series deletion revision. Clear a queued record only after both endpoints acknowledge it, or both cloud stores demonstrably satisfy it. Partial failure retains the remaining work for retry. Never claim remote read-back confirmation solely from a successful submission response.

## User feedback and lifetime

The account page explains `按观看进度合并，本地领先时上报云端`. Clarify the existing guest-history copy: ordinary sign-in does not upload guest records, while this explicit action may include them. Prevent overlapping manual runs. Show progress followed by counts of local updates, submissions, pending retries and skipped records, with a clear failure message when reads fail. Detaching a page must not deliver stale UI callbacks or discard durable upload work; activity destruction cancels in-flight merge application while preserving accepted outbox entries. Account switching must not display another account's result.

## Verification

Add focused core tests for episode/position/completion ordering, timestamp independence, equal/idempotent merges, cloud-only/local-only rows and invalid episode mappings. Add application fixtures for actual local persistence and resumed selection, explicit guest adoption versus other-account exclusion, deletion suppression, a blocked read followed by account change/deletion/local playback, duplicate clicks, endpoint partial failure, pending merge restoration, and cloud advancement before retry. Re-run existing account-history/intent/library fixtures to protect normal rewatch and guest-passive-sync behavior.

Then run serial TV, RayNeo Keyboard and Full core/app tests, lint and debug assembly. Record evidence separately from prior 0.12.7 results. Device installs, live account writes, release publication and version changes are separate delivery actions. If later testing can produce sound, mute first; installation or read-only checks alone must not change volume.
