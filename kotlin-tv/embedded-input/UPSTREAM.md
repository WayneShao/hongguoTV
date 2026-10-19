# Embedded dual-eye input module

App-local input UI for replacing system-IME requests. It does not register an
InputMethodService, change Android IME settings, or replace system components.

The module owns its Dialog, one-eye layout, two-eye rendering, fixed keyboard
buttons, pinyin candidates, editor operations and explicit local dictation.
It has no BBLL class/resource dependency. `EmbeddedInput.Host` attaches the
host's already established physical-input routing; it does not render the keyboard.
Ordinary DPAD and mouse handling belong to the module's native buttons/surface.
The small keyboard window uses a software layer; it does not alter a host player.

## Integration contract

- Show/close/status APIs run on the UI thread. `show` rejects API <23 and a
  finishing/destroyed Activity, and cleans up its own failed session on bad-token
  or invalid-display errors. It returns false on these failures; it does not retry.
- User BACK/collapse dismisses the keyboard and calls `EditorTarget.cancel()`.
  `NativeEditor` delivers pre-IME BACK DOWN/UP to the original editor, without an
  editor action. Programmatic `close`, session replacement, detach and failed
  creation only dismiss. A custom target can override the optional cancel callback.
- Wire original hide requests too. `closeWindow(view)` closes the session belonging
  to that view's window, even if another control requested hiding it. `close(target)`
  remains identity-specific, so stale logical editors cannot close newer sessions.
  `isShowing(owner)` lets a host deduplicate requests before waiting for window focus.
  Hosts own cancellation of any deferred show they create; this module does not queue them.
- Pass `NativeEditor(EditText)` to `EmbeddedInput.show(target, host)` when the
  host would request a system keyboard. Disable `showSoftInputOnFocus` for
  those fields. Preserve original text filters, watchers, selection and action
  listeners. Do not additionally inject Enter after an editor action.
- Determine each host's actual input paths. In BBLL only, the original `main.J`
  T9 page is tagged to suppress automatic replacement-keyboard requests; explicit
  editor click/confirm still opens the module. This rule is not a generic host assumption.
- For HTML fields, opt in an `EmbeddedWebView` and call
  `EmbeddedWebView.pageFinished(view)` from the original client's page callback.
  Retain the original WebViewClient and its login/navigation/cookie behavior.
  DOM fields have separate logical identities even inside one WebView.
  Completion sends change and cancellable Enter DOWN/UP. For non-textarea form
  controls, the fallback is `requestSubmit()` only when neither Enter event was
  cancelled and no synchronous submit event was observed. This retains constraint
  validation and avoids duplicating observable synchronous form submission.
  A page handling Enter with asynchronous/custom network actions must cancel default
  behavior; those actions cannot be inferred from DOM submit events. Real-page
  compatibility still needs a WebView test. No `form.submit()` or button guessing.
- `Host.attachInput(dialog, surface)` may attach the host's gesture dispatcher.
  It must not wrap this window in another stereo renderer. The supplied surface
  accepts a single logical pointer and maps physical right-eye mouse events
  using copies. The host retains its current mouse/navigation mode.
- If the host consumes raw events before the Dialog, call `EmbeddedInput.motion`
  and `EmbeddedInput.key` first for this keyboard window only. Consumed motion
  must cancel the host's pending gesture output. The module intercepts right-temple
  multi-touch and associated media keys; other host windows keep their original MENU mapping.
- Declare `android.permission.RECORD_AUDIO` in the app for explicit dictation.
  The module asks for permission on the first microphone action, records at most
  20 seconds, then recognizes locally. Dismiss/detach/focus loss cancels recording.
  It does not record automatically, save audio files, log transcripts, or call a
  speech network service. Voice is disabled for password fields.

## Keyboard-only two-finger gestures

Two fingers held for 400 ms start dictation. First finger release ends recording
and submits recognition to the current editor. Results produced at the 20-second
limit stay buffered while fingers are held, so the user can still cancel them.

An upward swipe before the hold threshold clears the whole editor via
`EditorTarget.clear()`, cancels pending dictation and resets pinyin composition.
An upward swipe after the hold threshold cancels only this dictation and preserves
the text that existed before it. Third-finger entry, focus loss and cancellation
cancel the gesture. Remaining releases are drained; new hover input can resume.

The module owns these actions; the adapter merely routes the keyboard window's
raw events. Existing menus outside the keyboard remain in the host. The default
X3 Pro filter accepts `cyttsp5_mt`, with thresholds based on its motion ranges;
physical event delivery and gesture direction still require glasses validation.

## Dependencies and rebuild

Own code: `src/embedded-input/java`. Pinned third-party inputs:
`config/keyboard-vendor.json` and `config/keyboard-voice.json`.

Run `python scripts/keyboard_assets.py` to fetch and verify Feelime/Rime data
and native libraries, the sherpa-onnx 1.13.6 native library and matching Java
API, and the bilingual Zipformer model. All bytes stay in ignored `_local/vendor`.
`python scripts/build.py` compiles both this module and BBLL's thin adapter,
applies guarded entry patches and preserves all undeclared APK payloads.

The selected native dependencies target ARM64 Android; the current target is
Android 12. First use copies dictionaries/models into the app's private directory.
Original APK native libraries for other ABIs are retained, but the new keyboard
engines have not been provided or validated for those ABIs. A future host must
choose and verify its own matching native artifacts.

Upstream references: <https://github.com/feelime/feelime> and
<https://github.com/k2-fsa/sherpa-onnx>. Pinned Feelime LICENSE/NOTICE are included
in the APK assets. This branch is an APK adaptation project, not upstream BBLL source.

## Acceptance boundary

Desktop Android-widget tests cover editing/filter/listener semantics, Unicode
deletion, one action submission, single-tree geometry, right-eye event copies,
first mouse click and old-session/new-session isolation. Native engines are
not executed by those widget tests. JNI exports and native dependencies are
checked statically; on-device pinyin/voice, optical alignment, physical temple
input and real login-page HTML remain pending on this initial test package.

The first preview was delivered locally on October 16, then installed October 19.
The user reported that the keyboard could not be opened. A narrower explicit-entry
fix and then the gesture candidate were installed. A later device screenshot captured
the stereo keyboard window, without proving every entry or native/physical behavior.
The subsequent shared-module fix build was initially local only. After explicit
user authorization it was installed once with data preserved, matching APK hash
and unchanged boot ID. It was not launched or physically tested.
It passes 84 Android desktop tests, 13 Python guards and 11 source-derived web event
cases; see `docs/shared-input-fixes-2026-10-19.md` in the project root. Source hashes
for cross-app import are in `src/embedded-input/module-version.json`.
Do not describe physical behavior as accepted. The accepted input-repair APK
`aa132e5c327b7058ea18e52826abb378254b8ed19f14e4d56d656d36be5cce31`
remains the rollback. Keep the experimental package on `rayneo/in-app-keyboard`
until device acceptance; do not merge it into the accepted baseline automatically.
