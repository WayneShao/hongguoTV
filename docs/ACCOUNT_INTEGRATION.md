# Official Red Fruit account integration

Source: wanwdbb/hongguoTV d5e4c5f800b36cbd1c907eda87da2be647adb88d (GPL-3.0-only).

Entry: Settings → 红果账号. The app displays the official web QR image; confirmation
on the phone establishes the session. Profile refresh, logout, cloud favorites,
recent cloud history and reservations are available. Unreleased reservations can
be cancelled. Favorites are read-only here; local favorite controls keep their
existing local meaning. Upstream list limits are retained (history: up to 30 per
book type; reservations: 30 per online/offline category), not advertised as a full
account archive.

AccountRepository is separate from ContentRepository: anonymous search/playback
and their persisted device recovery remain unchanged. Official account requests
reuse the validated search registration identity when available (aid 8662), never
the playback registration from a different app identity. Cookies/token are in app
private preferences, excluded from logs and existing library exports. QR redirects
are HTTPS and restricted to the expected official site/API domain families.

AccountScreen is ordinary content inside MainActivity's existing stereo root;
there is no external WebView, system input method, or new Dialog window. Existing
window-level BACK/temple processing remains in charge. Back closes QR/list first,
then account returns to settings. Leaving the account screen cancels QR work and
discards unaccepted login results. Returning from a cloud item restores its list
and requests focus on that series.

Login-time cloud history reads and per-title cloud progress contribute to resume.
Same-account local progress wins if newer; another account's local checkpoint is
not used. Captured-session upload writes both history and progress, retains failed
entries in a durable account-partitioned outbox, and never bulk-uploads pre-login
guest history. Session changes invalidate stale reads/writes. Unknown book types
are resolved only by an exact series-ID match, otherwise deferred.

Verification: inherited account protocol tests plus local anonymous-request
isolation, late profile response, UI back/logout/cancel tests. Real account login
and cross-device progress need physical acceptance. On 2026-10-26 the local
resolver returned 127.0.0.1 and a synthetic IPv6 address for hongguoduanju.com;
QR creation failed before connecting. No hosts entry was found; no system DNS,
proxy or network settings were changed. No credentials were requested or logged.

Delivery: version 0.9.5; TV versionCode 14, both RayNeo variants versionCode 20.
All use their existing package names, labels and signing keys. Full and Keyboard
variants retain the existing embedded input/voice split.

## Display name in the repackaged 0.9.5 TV build

The TV launcher/application label and in-app name are 红果TV, matching the glasses
variants. Do not append 原生版, TV edition or device names. Keep the existing TV
applicationId and signing key for data-preserving upgrades. At the user's request, the local 0.9.5 TV APK is rebuilt with this name; versionCode
14 and versionName 0.9.5-local are retained. The previous APK is kept locally for rollback.
