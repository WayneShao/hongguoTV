# 0.9.8 playback recovery and speculative IO

The preload switch remains OFF by default. Explicit user choices and independent
TIME/EPISODES thresholds and intervals persist. This revision addresses mechanisms
that can amplify retries; real TV/CDN playback still needs user acceptance.

## Foreground

- Remove the 18-second total deadline from streaming response-body consumption.
  OkHttp idle socket reads remain bounded at 15 seconds (or a smaller supplied
  timeout). Eager header/range reads have a 30-second total bound; background reads
  have an 18-second total bound. Player backpressure is not a stalled socket read.
- The 30-second buffering watchdog restarts when delivered bytes or buffered
  position advance. Lifecycle/poll gaps reset it as before.
- Ordinary automatic recovery stops/reprepares the same player source at the saved
  position, retaining the prepared header and any disk cache. HTTP 401/403/404/410
  or a changed resource length discard that source and resolve a fresh address.
- Keep the visible, bounded three-attempt automatic recovery flow. Disable Media3's
  additional loader retries so it does not multiply the outer retry loop.
- Each foreground episode shares a model-resolution budget of eight logical HTTP
  requests and two local read retries, across endpoints, fallback and automatic
  recovery. A manual retry or a different episode starts a new budget. Background
  resolution has its own four-request/one-retry budget. These limits cover model
  and fallback fetches, not video byte ranges or the separate finite registration
  protocol. Model requests disable OkHttp implicit connection retries.
- Normal device-backed requests no longer hold the identity lock during network
  IO. Rejected-device registration/validation/persistence remains transactional.

## Background

- Start/resume after 20 seconds buffered; pause below eight seconds. Thresholds
  clamp to the remaining duration of short episodes. Require active playback,
  unmetered network, auto-next and the enabled switch.
- Foreground network reads cancel the active background range, preserving verified
  chunks. Wait four seconds after foreground network demand and 300 ms between
  background chunks. Check the gate again before header preparation.
- Failures pause speculative work for 30 seconds. HTTP 429 pauses at least 120
  seconds, honoring numeric Retry-After up to ten minutes. Foreground recovery
  never clears this cooldown. One failed chunk may retry after cooldown; a second
  failure hands back the verified partial cache instead of deleting it.
- A current-episode failure pauses upcoming work without clearing unrelated
  completed entries. Explicit disable, leaving playback/lifecycle stop, a changed
  desired list and memory pressure still cancel/dispose as before.
- Existing disk/memory/resource size caps and cache TTL remain in force. A partial
  episode with unknown completed duration does not expand the time-mode window.

## References and verification

The design selectively follows foreground priority / cooldown ideas from
[evil1127/HGTV 41f18d0635](https://github.com/evil1127/HGTV/commit/41f18d0635).
The identity work in [tsukiyo-zero PR 30](https://github.com/tsukiyo-zero/hongguoTV/pull/30)
is recorded in PRELOAD_0.9.7.md; it is not imported as a replacement identity policy.
OkHttp 4.12 callTimeout semantics include response body consumption.

Regression coverage includes real local HTTP slow responses beyond the inherited
call timeout, socket inactivity, preserved/retried partial chunks, progress-based
watchdog deadlines, high/low watermarks, foreground demand, cooldown and chunk
spacing, shared budgets across repository endpoints/recovery, and concurrent
ordinary device reads. App regression suites and three release builds are required.

Delivery: TV code 17 / 0.9.8-local; glasses code 23 / 0.9.8-rayneo-keyboard and
0.9.8-rayneo-full. Existing signing keys, package names, app label 红果TV and
embedded input variants remain unchanged. Build/install are not real-device
long-playback acceptance. Android lint tasks are excluded from this local build
because the existing dependency resolution issue is unrelated to these edits.
