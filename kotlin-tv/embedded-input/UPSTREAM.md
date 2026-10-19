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

- Pass `NativeEditor(EditText)` to `EmbeddedInput.show(target, host)` when the
  host would request a system keyboard. Disable `showSoftInputOnFocus` for
  those fields. Preserve original text filters, watchers, selection and action
  listeners. Do not additionally inject Enter after an editor action.
- Keep app-owned keyboards out of this route. BBLL tags its original `main.J`
  T9 page without replacing its keyboard or suggestion/search methods.
- For HTML fields, opt in an `EmbeddedWebView` and call
  `EmbeddedWebView.pageFinished(view)` from the original client's page callback.
  Retain the original WebViewClient and its login/navigation/cookie behavior.
  DOM fields have separate logical identities even inside one WebView.
- `Host.attachInput(dialog, surface)` may attach the host's gesture dispatcher.
  It must not wrap this window in another stereo renderer. The supplied surface
  accepts a single logical pointer and maps physical right-eye mouse events
  using copies. The host retains its current mouse/navigation mode.
- Declare `android.permission.RECORD_AUDIO` in the app for explicit dictation.
  The module asks for permission on the first microphone action, records at most
  20 seconds, then recognizes locally. Dismiss/detach/focus loss cancels recording.
  It does not record automatically, save audio files, log transcripts, or call a
  speech network service. Voice is disabled for password fields.

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

The glasses were powered off by the user before delivery. Do not describe this
package as installed or physically accepted. The accepted input-repair APK
`aa132e5c327b7058ea18e52826abb378254b8ed19f14e4d56d656d36be5cce31`
remains the rollback. Keep the experimental package on `rayneo/in-app-keyboard`
until device acceptance; do not merge it into the accepted baseline automatically.
