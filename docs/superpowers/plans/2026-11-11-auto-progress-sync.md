# Auto progress sync implementation plan

**Goal:** Add persistent opt-in, load-triggered progress synchronization with a default five-minute interval to both products.

**Architecture:** Reuse AccountProgressMerge and the existing history worker/outbox. Add one Activity-owned rate controller and an explicit automatic eligibility predicate; retain all manual behavior. No background timer or new dependency.

- [x] Tests first: AutoProgressSync settings, per-owner attempt clock, restart, interval boundaries, rejection, failure throttling, clock rollback, manual completion and lifecycle.
- [x] Tests first: AccountProgressMerge automatic eligibility excludes unassigned local/foreign/deleted progress and keeps cloud-only imports and manual guest opt-in.
- [x] Implement AutoProgressSync and the narrow startAutomatically engine entry point in both products.
- [x] Tests first: Activity history-load eligibility and ownership policy; preferences persistence and shared lifecycle are covered by the focused fixtures.
- [x] Wire home / resume collection / recent history load and readiness/resume hooks; refresh visible history without seeking or changing pages. Add two native settings controls and existing remote focus links.
- [x] Review behavior and fix compilation; TV / Keyboard each pass 38 focused cases. Per the user's follow-up, omit another full matrix and physical automatic-sync run.
- [ ] Commit bounded changes, build TV code 30 / X3Pro code 35, replace the existing 0.12.8 draft assets and publish with exact updater metadata. Do not repeat the completed installations or playback checks.
- [x] Finish myHotpot feature inspection and source-backed comparison without porting additional features without a user request; detailed report remains in the enclosing workspace outputs.
