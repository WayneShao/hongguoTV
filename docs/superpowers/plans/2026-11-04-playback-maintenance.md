# Playback maintenance implementation plan

Approved by user on 2026-11-04 following the upstream review.

Goal: stop deterministic playback failures from retrying; validate a bounded video_tab fallback; improve account messages and expose session-only preload statistics.
Architecture: shared core policy/model metadata plus small existing UI hooks. Preserve single-player queue, original identity recovery, user quality preference, and all RayNeo input. TV API21 preview stays separate; RayNeo API26.

1. Reproduce missing-compatible-resource classification in core tests. Add terminal resource error and guarded cause-aware retry policy. Expose only parsed compatible quality metadata in StreamInfo, and offer lower actual tiers without hardcoded 720P or persistent preference mutation. Unknown tiers show no invented choice.
2. Add one GET video_tab fallback only after both established model endpoints are definitively empty, inside existing per-episode request budget. Parse exact requested episode key; preserve bounded identity candidate recovery; malformed/unsupported/transport errors do not invoke fallback or rotate identity. Existing successful endpoints remain first.
3. Run actual live comparison using existing JVM probe and same episode IDs. Record path, success, quality and elapsed time without identity/URL/key disclosure. If network cannot establish usefulness, gate new fallback behind explicit opt-in instead of silently defaulting on.
4. Keep QR login. Explain missing business profile at cloud-only operation boundary, preserve session/local records. Do not infer unprovisioned account solely from missing one ID or demand re-login.
5. Add process-session statistics: full cache starts, partial cache handoffs, uncached starts, natural queued transitions. Update only real playback acquisition/transition events; snapshot in settings, no tracking identifiers or disk history. Keep live cache-byte/count snapshot separate from cumulative counters.
6. Sync shared changes to TV preview checkout without replacing API21 patches. Core/app regression, build TV + Keyboard + Full release packages with existing signers and increasing codes. Inspect metadata. No physical install or stable publication unless current session authorizes it; stable latest's minSdk26 metadata parser requires an upgrade bridge before TV API21 publication.

Checks: deterministic unsupported/malformed/cancellation/timeout separation, available-quality selection, fallback exact identity and request bounds, profile absence preserving credentials, counters record only actual handoff. Existing playback instrumentation confirms queue still runs; build/install is not hardware acceptance.

Implementation and evidence: ../../PLAYBACK_MAINTENANCE_2026-11-04.md. Terminal model outcomes are tracked separately from transient/rejection outcomes, so mixed failures never authorize identity rotation. Account/cloud hint remains conditional; SMS login is not introduced.
