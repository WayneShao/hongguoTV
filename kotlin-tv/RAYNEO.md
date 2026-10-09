# 红果TV — X3 Pro adaptation

Current delivery: `0.9.0-rayneo.2` / versionCode `10`, package `com.hongguotv.rayneo`.
The launcher name is **红果TV**, explicitly selected by the user. The internal identifier isolates this build from upstream installs and updates.

## Architecture

One logical native View tree per window is drawn once into an ordinary UI bitmap, then shown in both eyes. A single Media3 decoder supplies an independent OES video renderer; video frames do not pass through UI bitmap copies. AlertDialog and the episode Dialog use the same full-window stereo wrapper, preserving original callbacks and focus. Upstream updates are disabled for this independently signed package.

Every app-owned Activity/Dialog attaches `RayNeoInput`. Only the right `cyttsp5_mt` becomes temple navigation; left `cyttsp6_mt` and `CapSense *` events are consumed before descendants. External mouse and DPAD retain their normal routes.

| Gesture | Action |
| --- | --- |
| Four-way swipe | Native directional focus/navigation |
| Single tap | Confirm after double-tap window |
| Double tap | Original BACK semantics |
| Two-finger tap | Original MENU semantics, once per completed gesture |
| Long press then release | Existing MENU action |

## Input fixes in revision 2

- The tested firmware exposes a framework touch slop of 65 pixels. Temple input uses a separate 12dp threshold, bounded to 12–24 pixels, so ordinary short swipes become directions.
- A window owns its focus recovery and pending actions. Each ListView stores the browsed item independently from its checked setting. Missing selection does not erase this memory. Recovery validates the same adapter and stable ID or unique item value, including reordered lists; deleted/disabled/ambiguous items are not blindly activated.
- Real DPAD and temple actions both update row memory. Touch-mode recovery includes non-touch-focusable native dialog controls. List relayout can finish before a queued key; normal row recycling does not invalidate the containing list identity.
- Hover navigation cancels the old gesture before dispatch, so it does not erase a newly queued direction. Single- and two-finger paths both process historical motion samples.
- Dialog reparenting preserves the caller's selected control/row, including default Cancel and numeric keypad focus.
- Two-finger input cancels pending single taps, tracks pointer identities through either lift order and emits MENU once. Movement, cancellation, extra fingers and window changes suppress residual actions.
- Default/missing/invalid local quality is 480P. Explicit saved 480/720/1080 preferences survive upgrades and valid backups retain all three choices. The tested device already had the user's chosen 480P and it remained 480P.

## Verification on 2026-10-09

Host: 91 core tests and 24 input tests passed. Debug APK build passed; lint reported 0 errors and 30 warnings. APK signature verified and an in-place install succeeded without clearing data. Boot ID remained unchanged during the recorded adaptation and input checks.

Actual-device callback regression uses the production `Window.Callback` and right-pad device identity with synthetic MotionEvents. This is distinct from physical touchpad acceptance. Tests use fixture dialogs and do not persist their selected quality values.

| Path | Evidence |
| --- | --- |
| Quality radio list | Repeated touch/hover 480→720→1080→720→480; selected-row loss and restoration; confirm target; mixed DPAD/temple input passed |
| Ordinary dialog list | Navigate beyond visible rows to item 22, lose selection, continue to 23 and confirm the same item passed |
| Confirmation dialog | Original default Cancel remained focused after stereo reparenting |
| Numeric episode picker | Native 1→2→5 directional movement passed |
| Episode panel | Current episode 8→9→14 with intervening touch-mode change passed |
| Home/search/ranking/favorites/history/settings | Valid focus after synthetic temple navigation and touch-mode change passed on each actual page |
| Settings grid | Quality→speed→frame→version across columns passed |
| Two-finger callback | Exactly one MENU down/up pair; no extra CENTER passed |
| Physical quality navigation | User confirmed continuous up/down movement works |
| Physical two-finger menu | User confirmed one menu opens on a focused home card |
| Stereo UI/video | Earlier device captures showed identical left/right images for home, detail, real video, player controls, episode panel and numeric dialog. No new optical/long-duration thermal claim |

Shared-window coverage also includes speed, frame/sleep options, quick actions, history management, information, phone search, backup/restore and version/license dialogs. Not every business/network branch of those windows has separate physical acceptance. System IME and framework text-selection/context popups remain outside this recorded window regression.

## Build and regression

Use JDK 17 and Android SDK 36:

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

The local lint dependency workaround under ignored `outputs/rayneo` caches unchanged upstream artifacts downloaded from official Maven URLs; it is not needed where Gradle HTTPS resolution works normally.

`InputRegressionInstrumentation` has an opt-in `leaveOpen=true` mode for interactive device debugging. Start without `-w`; it writes `files/input-regression.txt`, sends status and deliberately leaves the app in settings instead of calling `finish()` and returning to the launcher. Normal automated test mode calls `finish()` as usual. Inform the user before device regression; never leave the app at the launcher afterwards when they asked to keep their current page.

Local delivery: `../outputs/红果TV.apk`. Host and device evidence: `../outputs/rayneo/input-fix/`. Previous revision is preserved under `../outputs/rayneo/archive/`. This delivery uses the existing Android debug certificate; future in-place updates must retain its signing identity. No external release was published.
