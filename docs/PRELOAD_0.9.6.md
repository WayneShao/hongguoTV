# 0.9.6 playback repair and configurable rolling preload

## User behavior

Settings → 预加载 contains three native, focusable controls:
- Mode: time or episode count.
- Threshold: 1/2/3/5/10/15/20/30 minutes or episodes.
- Check frequency: every 1/2/3/5/10 minutes of actual playback, or completed episodes.
Defaults: time = 5 minutes / check every minute; count = 10 episodes / check every
episode. Each mode retains its own values in private preferences across restarts.
Switching modes changes both unit labels and restores the saved values. Dialogs
select the current value and restore focus to their settings anchor on dismiss.

Initial stable playback triggers the first fill. Thereafter check clocks decide
when to refill; filling itself is serial and advances only with sufficient current
playback buffer. Paused/stalled/background wall time and seeks do not count as
played minutes; speed scales the time clock. Only natural episode completion
advances the episode clock. Next-episode changes keep the completed count/time.

Time mode downloads complete episodes in order, stopping after the episode that
brings the total to/exceeds the threshold. Duration comes from MP4 mvhd v0/v1.
Unknown duration holds the prepared item but stops further time-based expansion.
Count mode caps the prepared window at the chosen episode count. No UI category
or account change is involved. Existing auto-next and unmetered-network gating
remain in force. Settings changes invalidate the old window.

## Resource ownership and limits

Raw encrypted video is stored in disposable app-private cache files, not decoded
frames. The speculative window is limited to 128 MiB disk data and 32 MiB retained
index/raw memory, one network preparation at a time, at most 64 MiB per full video.
Leave at least 16 MiB free storage. Very large videos may retain only their index;
capacity limits take precedence over the requested threshold. The current playing
episode is separate from the speculative window. A 100-entry safety cap applies
to unusually short/invalid catalogs. Cache expires after one hour.

Claiming an in-flight item transfers its existing task. After the current 256 KiB
download chunk, speculative loading yields to playback, keeping partial cache.
The UI thread never waits for that task. Cancellation closes late results; changing
series/quality, stopping/backgrounding or memory pressure clears the window.
Only files in the app-owned episode-prefetch directory are cleaned up. Exiting
playback deletes its claimed file; a later first preload removes crash leftovers.

Foreground data reads now consume cached bytes first and stream HTTP ranges as
they arrive instead of waiting for an entire 512 KiB chunk. Startup reads 32 KiB
and completes the MP4 index, rather than waiting for a fixed 256 KiB download.
Decoder rendering and stereo Surface handling remain unchanged. This does not
claim gapless decoder transitions or remedy all decoder-related stutter.

## Other repairs

Resume first matches episode ID, then a valid recorded episode index. Explicit
cloud-history selection uses that record; ordinary account resume keeps the
existing newest-record policy and account isolation, not furthest-progress-wins.

Playback model empty HTTP bodies retry at most three attempts with 700/1500 ms
backoff. Transient connection/500/502/503/504 failures get at most one retry within
that budget. Business errors and malformed JSON are not reclassified as empty
bodies. Existing registered candidate validation remains transactional. Requests
and waits respect cancellation.

Each video HTTP range has an 18-second total deadline. Thirty seconds of continuous
foreground buffering enters the existing three-attempt recovery budget and
preserves the position; pauses/background gaps reset the watchdog. Safe error
categories remain visible during recovery.

## Delivery

Version 0.9.6: TV versionCode 15, both RayNeo variants versionCode 21. All launcher
labels are 红果TV; package IDs/signing keys and the no-voice/full split are retained.
Fixture tests cover duration, disk reads/deletion, transfer/cancellation, thresholds,
independent clocks, persistence, dialog selection and streamed seek/read behavior.
Hardware temple/optics/decoder/CDN acceptance is separate from these checks.
