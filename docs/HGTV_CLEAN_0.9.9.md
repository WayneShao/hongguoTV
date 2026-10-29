# 0.9.9: HGTV Clean comparison and confirmed playback corrections

The user reports that 0.9.8 still disconnects, including first playback, and that
HGTV Clean 0.2.2 is clearly more stable on the same TV and network.

## Reference provenance

GitHub repository/code/issue searches for HGTV-Clean, HGTV Clean, com.hgtv.clean
and platform-126.dex did not find an identifiable source repository on 2026-10-29.
pan8664716/HGTV is a related release/documentation repository, not verified source
for this APK. The supplied APK has package com.hgtv.clean, version 0.2.2, code 4,
SHA-256 fe9480c1d4d9eacee8b9838c3e9ff736e590b34b5d7dca190e1501f276598340.

Static analysis: its Java frontend uses assets/platform-126.dex via a compatibility
loader. PlatformApi.play -> E0.W.X -> E0.C1.r prepares the current episode;
PlaybackDataSource delegates to I0.s. That reader consumes up to 1 MiB per decoded
block. PlayerScreen uses a default ExoPlayer and default ProgressiveMediaSource
retry policy. The traced frontend does not schedule a multi-episode byte preload.
The engine retains short-lived model/URL caches; these are distinct from video
prefetch. No binary engine or decompiled implementation was copied into our app.
JADX reported 24 errors; ambiguous decompiled branches are not used as proof.

## Confirmed defects and fixes

1. Our 0.9.8 explicitly set DefaultLoadErrorHandlingPolicy(0). This removes the
   player's local loader recovery, exposing transient read failures immediately
   to the outer failure UI. Restore the default policy. The existing bounded
   outer recovery and address-request budget remain.
2. Our arrayRows assumed a clear legacy format and always returned key=null.
   New live models place dimensions/codec in video_meta and the content key in
   encrypt_info.spade_a. The parser read quality as zero and treated encrypted
   media as clear. Object and array variants now share nested metadata/key
   parsing; explicitly encrypted variants with missing/invalid keys are rejected
   rather than supplied as clear video. Legacy clear arrays remain supported.

The preload switch/default and saved settings are unchanged. No new preload
scheduler tuning or media-read chunk rewrite is bundled with these corrections.
The preload implementation is not claimed to have passed the user's acceptance.

## Live evidence, 2026-10-29

Probes used the current production core classes from installDist, not a separate
Android test app. Requests explicitly used the local HTTP proxy in the probe only;
no system/device network settings or installed app state were changed.

- A sampled 70.17-second episode resolved on the existing 0.9.8 route and produced
  a complete 5,331,718-byte 854x480 HEVC/AAC file. FFmpeg full decode exited 0.
  Therefore this experiment does not prove the old endpoint is universally dead.
- A registered anonymous identity plus the unsigned phoenix endpoint returned
  code=0 and five renditions: 360/480/540/720/1080. Old array parsing selected 0P
  with key=null. Its fully downloaded media failed FFmpeg decode (invalid AAC).
- Normalizing nested fields in the diagnostic produced 480P with a content key
  and full decode exit 0. After the production parser fix, the same live path
  through production StreamModels produced 480P and full decode exit 0.
- The unsigned endpoint was exercised as a diagnostic comparison, not silently
  installed as a new production identity/recovery policy in this release.

Related source: miaoxiaohu/hongguoTV commit 1b4a6df561ed and
[its endpoint investigation](https://github.com/miaoxiaohu/hongguoTV/blob/5d2f1806fb0c2fab2621937925a4acfe24cb842c/docs/research/2026-10-28-hongguo-playback-endpoint.md).
Its observations informed the comparison, but its parallel signature retries and
identity lifetime were not transplanted. Our own probe results take precedence
over universal claims in third-party notes.

Regression: the four new nested-format tests failed 3/4 before the parser fix
and passed afterward. Complete core/app suites and APK signing verification are
required for delivery. TV code 18; RayNeo code 24. The established three variants,
app labels, package names, signing keys and embedded-input split are preserved.
Local full-file decoding is not proof of Android playback under user-network
interruptions. Real TV acceptance remains outstanding; lintVital is not included.
