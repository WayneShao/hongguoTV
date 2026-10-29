# 0.10.0: playback-first shared-cache preload

## Delivered behavior

The Activity now delegates speculative work to a shared core `EpisodePreloader`.
Foreground and background still share `RemoteVideo` parsing/decryption/cache bytes.
Preload remains opt-in; time/count settings and independent thresholds/intervals
are preserved. Existing app label 红果TV, signing keys, packages, input and stereo
rendering stay intact. No binary engine or decompiled friend APK code was imported.

- Batch models select the exact requested episode key. `video_model.video_id` is
  a different, opaque media identifier and MUST NOT be compared to that numeric
  episode key. A real-network test caught this erroneous assumption during this
  implementation; production and regressions now use the observed shape.
- A direct supported resource matching requested quality avoids fallback and the
  second model call; lower-quality results retain the compatibility search.
- When both model routes end in empty-content evidence, or explicitly reject the
  identity, recover with one normally registered candidate. Validate the original
  operation before persisting. A five-minute monotonic cooldown covers failed
  attempts too; sources sharing the same store merge recovery. Network/parser/
  unsupported-codec failures do not independently trigger this transaction.
- Recovery lock waiting is cancel-aware (100ms polling); ordinary healthy calls
  never wait on the candidate-validation lock. Search remains opt-out from this
  new empty-content/cooldown policy. No switch to an unsigned default protocol.
- Model budgets remain bounded. Each foreground episode's automatic recovery
  shares its eight-model-request/two-local-retry budget. Background preparation
  now has enough budget (eight/two) to validate a candidate after old empty
  responses. It is a separate preparation budget, not one universal lifetime
  budget across all speculative rebuilds and explicit foreground fallbacks.
- Account list object wrappers now return the parsed rows to the caller instead
  of discarding them via `return@let`.

## Cache ownership and scheduling

- Claim notification promotes a prepared video atomically, cancels outstanding
  background range reads, and prevents late speculative reads/cancellations from
  touching the new foreground owner. Verified bytes remain in the same file.
- The Activity claims before revoking background permission. Claimed initial
  preparation is protected from a generic background yield. If model/header work
  is still unfinished after 1,500ms, it is cancelled and foreground prepares fresh;
  this bounded fallback can cause an additional address request.
- Partial completed tasks resume on the SAME object/file before the scheduler
  counts/expands its window. Three consecutive genuine chunk failures stop refill;
  any committed chunk resets that counter. Preemption is not a genuine failure.
- Size/disk-ineligible entries are terminal for background refill; no retry loop.
  Unknown-duration terminal partials conservatively stop a TIME window rather than
  inventing duration. Completed episodes count toward time or episode targets.
- Low buffered duration or pause immediately revokes background IO, with callback
  outside the gate lock. Existing high/low watermarks, foreground quiet period,
  chunk spacing, network gates and HTTP429/other cooldowns remain.
- Pending work owns retained partial results even before queued resume starts.
  Cancellation/disposal cannot delete a resource already transferred to playback.
- Byte/memory/TTL limits and lifecycle cancellation remain. Cached raw media is
  decrypted using original absolute offsets, not decoded or rendered twice.

## Why retain incremental foreground reads

The friend APK's traced reader waits for a 1MiB block. A real encrypted episode
was replayed using production eager and streaming readers at the same uncached
position. At 256KiB/s, eager first delivery was 4088ms vs streaming33ms; at64KiB/s,
16119ms vs126ms. Throughput was equivalent. Cutting a request at128KiB made eager
repeat that portion; streaming resumed from delivered bytes. All output matched.
This measures byte delivery after preparation, not Android first-frame latency.
The evidence supports incremental foreground reads with stable player retries,
while buffering ahead is achieved by the shared cache, not larger eager reads.

## Verification

Local artifacts live under outputs/preload-0100-review (outside the repository).
Private media keys and signed media URLs are not release assets.

- Real final production resolver/read path fetched three distinct public episode
  identifiers at480P, with valid keys and complete 5,331,718 /4,261,958 /4,010,436B
  outputs. Each fully decoded with FFmpeg -xerror exit0, empty error logs.
- Core and Android regressions cover identity cooldown/concurrency/cancel, exact
  batch-key match, opaque media IDs, direct-result request count, account wrappers,
  partial refill, stalled-body claim, late cancellation, disposed callbacks, pause
  revocation, zero disabled work, full five-minute window and data-source reopen
  at the last delivered position after a forced network error.
- Six synthetic70-second episodes per mode reuse one real encrypted media file.
  Actual EpisodePreloader/RemoteVideo/gate run on a local HTTP server with a shared
  1MiB/s cap and8x playback/gate clock. Both groups receive one foreground cutoff.
  OFF: startup waits10922ms simulated,0 playback stalls,6 resolves,67 ranges,
  32,562,588 server-written bytes. ON:5722ms,0 stalls,6 resolves,89 ranges,
  33,004,956B;5 partial caches transferred. All12 full outputs match the decoded
  reference. Modeled wait falls47.6%, bytes rise1.36%; OFF was already stall-free.
- This is repeatable JVM/socket/crypto evidence, not six distinct real episodes,
  Media3 frame rendering, physical glasses gestures, or universal CDN reliability.
  The replay uses linear byte-rate playback and compressed clocks. No system or
  device network settings changed. No extra Android test application was installed.

Upstream reference: wanwdbb/hongguoTV31856915c36f informed empty-content recovery,
direct-model handling and account wrapper repair. Its registration-before-playback
persistence order was NOT copied: our candidate validates before persistence.

Build delivery uses0.10.0-local/code19;0.10.0-rayneo-keyboard and
0.10.0-rayneo-full/code25. Three release APKs retain signature continuity.
The existing local lintVital dependency issue means lint is not counted as passed.

### Near-capacity replay

Final production core jar c31d5cb89a4714d370430961ce0ff1d987f85ac30d064d97bf82ff20d1b32780
was replayed at shared700KiB/s, with four70-second synthetic episodes per group.
OFF and ON both had0 playback stalls,4/4 correct full decrypted outputs,4 resolves,
45 ranges,21,593,704 written bytes and one recovered foreground cutoff. ON could
only retain88,940B headers for three transitions. Startup waits8730ms OFF vs9186ms
ON simulated (+456ms,5.2% slower); excluding cold first episode,6244 vs7085ms.
Thus preload is not universally faster: at this capacity there was no meaningful
spare download window. Single-run simulated timings are not hardware frame times.
Both positive and negative performance results are retained; defaults remain OFF.

Final pre-build tests: RayNeo222 core +37 Android tests, zero failures/errors.
The TV checkout is tested separately after transplantation.
