# 0.9.7: preload kill switch, not a buffering fix claim

Settings → 预加载 → 预加载开关. Default OFF, including upgrades from 0.9.6
that do not yet have the enabled preference. Explicit later choices persist.
Both time/count thresholds and check intervals remain saved while disabled.
The foreground scheduler and rolling window both guard the disabled state.
Disabling cancels pending speculative work and clears its retained cache but
does not close a resource already claimed by the current player. Automatic
playback error recovery and the existing network/decoder pipeline stay intact.

User reported that 0.9.6 buffering/retry frequency was worse than the previous
experience. This is an immediate isolation control, not evidence that the broader
playback regression is fixed. No account or device identity behavior is changed.

## Investigation on 2026-10-28

- All RemoteVideo calls use an 18-second total deadline, including streaming reads
  consumed by the foreground player. OkHttp's total timeout includes response-body
  consumption; player backpressure must not be mistaken for stalled network IO.
- A playback error calls cancelPrefetch(), which clears all speculative results
  and resets the initial-fill clock. Recovery may therefore refill the same window.
- Prefetch starts with only five seconds buffered, downloads 256 KiB ranges with
  a fresh request per range, and deletes partial downloads on an exception. These
  mechanisms can amplify competition and re-downloads; no user-session trace yet
  establishes which dominated the reported event.
- PlaybackReadRetry is per endpoint, then alternate endpoints and outer player
  recovery apply; three local attempts are not a global request budget.
- PlayerDeviceSource only probes a new registered candidate on explicit rejection.
  An empty model response can still fall back to the legacy identity without ever
  testing a registered candidate when none is persisted.

Latest inspected external fix: tsukiyo-zero/hongguoTV PR #30, commit
388931852ddc614db8b00775a3dc54c5ede6307f, merged and released in TV 0.1.11.
It registers/shares anonymous identities and reports current-sample empty-body
improvement, not a universal buffer fix. It keeps identity only in-process; our
transactional persistent-identity policy must not be replaced wholesale.
wanwdbb had no commits since Oct 27 at this check; HGTV 1.2.7 has start-up speed
release notes and a switching-freeze report, but no validated matching patch found.

Next proposal for discussion: distinguish model, CDN and decoder failures; use
inactivity/progress-based recovery for foreground streaming; pause speculative
IO at a low watermark and resume only at a higher watermark; preserve usable
cache after unrelated playback errors; share one bounded recovery budget and
refresh URLs/identities only when that layer's evidence supports doing so.

Delivery: TV versionCode 16 / 0.9.7-local; both glasses versionCode 22 /
0.9.7-rayneo-keyboard and 0.9.7-rayneo-full. Same names, packages and signatures.
