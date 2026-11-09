# 0.12.6 maintenance integration

## Approved scope

1. Update checks and APK downloads support automatic, direct and selected HTTPS mirror routes. Automatic tries GitHub then the verified gh-proxy.com mirror. Explicit routes do not silently switch. Retain release-body metadata, package/version/minSDK checks, exact size, SHA-256 and certificate matching. Route changes cancel owned work and make another check eligible.
2. Each playback-address acquisition has an eight-second monotonic deadline covering network bodies, parsing, identity-lock waits, registration and retries. Later acquisitions renew the deadline while retaining the episode's eight-request/two-retry limits. Route health is bounded to eight identities; skipped routes do not count as complete empty rounds.
3. On API28+, request modern and legacy certificate fields. Independently fall back for installed/archive packages only when modern signers are absent or empty. Nonempty modern signers remain authoritative; older Android uses legacy fields.
4. Browse complete related-work lists and provide cancellable pre-end season hints. Automatic transitions require explicit adjacent numbered titles, unique candidates and detail revalidation. Website bookType0 may use authoritative related-API playable metadata; unknown types must not be guessed. Cancel, pause, seek, Back, navigation, sleep and late callbacks must not cause unwanted continuation. Cross-season autoplay has a separate default-OFF setting and also requires normal autoplay.

## Platform constraints

TV retains API21, HttpTransport compatibility, guarded API24 minSDK access and Android5–7 installer fallback. X3Pro retains API26 and stereo UI/input. Preserve each product's package and signing identity; release codes advance to TV27 and X3Pro32. Upgrade in place without clearing data.

## Sources

- [wanwdbb update routes](https://github.com/wanwdbb/hongguoTV/commit/57ea8dabc8fa1c7309ccd4f6876af0f530a9e114)
- [tsukiyo-zero playback deadline and route health, PR84](https://github.com/tsukiyo-zero/hongguoTV/pull/84)
- [tsukiyo-zero signing compatibility, PR83](https://github.com/tsukiyo-zero/hongguoTV/pull/83)
- [tsukiyo-zero related works, PR82](https://github.com/tsukiyo-zero/hongguoTV/pull/82)

These GPL-3.0 references inform adaptations to this fork's signed request, identity-recovery, updater validation and lifecycle ownership.

## Verification status

Final code and specification reviews approved. TV: 309 core and 67 app tests; Keyboard and Full: 317 core and 68 app tests each. All passed with zero failures, errors or skips, together with lintDebug and assembleDebug. Coverage includes per-episode bounded preparation failures, separately gated ended fallback, strict season parsing, cancellation and stale update callbacks.

Release acceptance remains pending: original-signature artifacts, muted physical TV upgrade/history/playback, related-season and update-route UI, candidate/live updater fixtures and remote digests. Physical observations are recorded separately from deterministic tests.

Real PC probes verified the release metadata and complete previous TV APK through direct and mirror routes. The previous Full APK returned HTTP504 during probes; small downloads do not establish full-size mirror availability.
