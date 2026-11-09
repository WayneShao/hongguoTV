# 0.12.6 maintenance integration

## Approved scope

1. Add automatic/direct/selected HTTPS mirror routes for release checks and APK downloads. The default tries GitHub first and then the verified `gh-proxy.com` mirror. Explicit routes do not silently switch. Retain release-body metadata, package/version/minSDK checks, exact size, SHA-256 and installed/archive certificate matching. Changing routes cancels owned work and makes a new check eligible.
2. Bound each playback-address acquisition by an eight-second monotonic deadline, including body reads, parsing, identity-lock waits, registration and retry waits. A later acquisition renews the deadline while retaining the episode's eight-request/two-retry limits. Keep successful route preference and short failure/empty cooldowns per identity, with at most eight identity entries in memory. Skipped routes do not count as empty responses or complete empty rounds.
3. On API28+, request both modern and legacy package certificate fields. Fall back independently for installed/archive packages only when modern signers are absent or empty. Nonempty modern signers remain authoritative. On older Android, use the legacy field.
4. Add a related-season browser and cancellable pre-end continuation hint. Browsing may show related works, but automatic continuation must retain strict numbered, consecutive, unambiguous matching and detail revalidation. Cancel, pause, seek, navigation, sleep and stale callbacks must prevent unwanted continuation.

## Platform constraints

TV retains API21, `HttpTransport` compatibility configuration, guarded API24 minSDK access and Android5–7 installer fallback. X3Pro retains API26 and its stereo UI/input. Preserve each product's package and signing identity. Release codes advance to TV27 and X3Pro32; upgrade in place without clearing data.

## Sources

- [wanwdbb update-route reference](https://github.com/wanwdbb/hongguoTV/commit/57ea8dabc8fa1c7309ccd4f6876af0f530a9e114)
- [tsukiyo-zero playback deadline/route reference, PR84](https://github.com/tsukiyo-zero/hongguoTV/pull/84)
- [tsukiyo-zero signing compatibility reference, PR83](https://github.com/tsukiyo-zero/hongguoTV/pull/83)
- [tsukiyo-zero related-season reference, PR82](https://github.com/tsukiyo-zero/hongguoTV/pull/82)

These GPL-3.0 references inform adaptations to this fork's existing signed request, identity-recovery, update validation and lifecycle ownership. Their complete implementations are not imported wholesale.

## Verification status

Pending final acceptance. Required gates: core/app tests, Android lint, TV/Keyboard/Full builds, original-signature release verification, muted physical TV upgrade with history retention and playback, related-season and update-route UI, candidate/live release parser checks, and remote asset digests. Record physical observations separately from deterministic test coverage.

Intermediate TV check of scopes 1–3: 285 core tests and 50 app tests passed with no failures, errors or skips; lintDebug and assembleDebug also passed. This includes legacy certificate access on API21, 23, 25 and 26. Scope 4 and the final combined release still require validation.

Network probes are observations rather than availability guarantees: release metadata and the complete previous TV APK were verified through the mirror; the previous Full APK returned HTTP504 on direct/mirror probes. A successful small download does not establish full-size download availability.
