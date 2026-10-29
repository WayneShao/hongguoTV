# 0.10.0 playback-first preload implementation plan

Approved order: stabilize model/identity recovery; compare block and streaming
reads using the same real media; implement cache handoff/resume; preserve time/count
settings; validate enabled/disabled under controlled disruptions; build and publish
TV, RayNeo Keyboard and RayNeo Full, install Full only if verified glasses online.

Acceptance:
- Empty model recovery is candidate-local until same episode validation succeeds;
  explicit rejection and empty-content evidence are distinct from generic network
  failures. Cooldown and global request bounds hold under concurrent work.
- Models match requested episode, and a usable direct result avoids fallback IO.
- Current playback never waits indefinitely for speculative work. Claim cancels a
  speculative range, keeps verified bytes and transfers ownership exactly once.
- Interrupted partial caches are retried on the same object/file after cooldown;
  no double counting, duplicate writers, deletion during active playback, or loop
  for permanently uncacheable resources. Disk/memory limits remain.
- One stable foreground source/retry path; speculative failures cannot surface as
  current-playback errors. Time/count settings remain independently persisted.
- Real resource -> model -> encrypted bytes -> clear output -> full decode, plus
  local slow/stall/cutoff, claim, cancellation, seek and preload ON/OFF scenarios.
  Report timings and hashes; network/core proof is separate from Android render
  and physical input acceptance. No test Android app or user-device UI automation.

Work streams under subagent-driven-development skill:
1. resolve_playback: model/identity/account parsing plus focused regressions.
2. read_comparison: isolated JVM same-resource diagnostics, no production edits.
3. cache_handoff: core cache ownership/resumption and network race tests.
4. root: application integration, data-source tests, reviews, live final probes,
   signing, release and conditionally authorized glasses installation.

All Gradle execution in a checkout is serialized by root. Existing signatures,
package names, 红果TV app label, stereo rendering and embedded input preserved.
