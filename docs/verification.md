# Verification record and device checklist

## Evidence and limits (2026-10-20)

**Final host validation:** `python3 scripts/build.py --offline :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` passed. Three palette tests passed, lint has zero errors (remaining warnings include English-only strings, deliberate landscape orientation and pinned dependency versions). APK signature and manifest were inspected after packaging. `python3 scripts/audio/probe.py --network` was rerun successfully against the exact livestream. The Commons repository implementer verified an actual 1920×835 JPEG download (645,762 bytes). **No device installation, Android playback, dream activation, measured performance, touch-navigation, or remote-navigation pass is claimed.** `adb devices -l` listed no connected devices, no AVD was configured, and `/dev/kvm` was unavailable.

Current manifest, build configuration/helper, native UI/session code, and media/cache declarations were inspected for this documentation update. Existing dependency notices and the prior audio-probe record were reviewed; this is not an exhaustive code audit or final runtime dependency inventory. Package/component declarations and build versions are documented in the README. Android 12 is API 31; the app targets API 32 (12L) with min API 26 and compile SDK 36.1.

### Exact-source JVM audio check — passed and rerun

The existing verification record reports this command from the project root. The output below is preserved as prior host evidence, not a new execution or a target-device playback result:

```fish
python3 scripts/audio/probe.py --network --gradle-cache .gradle-home/caches/modules-2/files-2.1
```

This compiles production `AudioController.kt` and `ExtractorProbe.kt` with Android 36.1 stubs, Kotlin compiler 2.2.10, Media3 **1.11.1**, NewPipeExtractor **v0.26.5**, and OkHttp **4.12.0**, then invokes the production downloader on the JVM. Tested URL: `https://www.youtube.com/watch?v=rFZHOHl-L8A` (the exact app default, not a substitute stream).

Successful rerun output:

```text
COMPILE_OK Android 36.1 / Media3 1.11.1 / NewPipe v0.26.5 / OkHttp 4.12.0
EXTRACTION_OK title=lofi hip hop radio 📚 beats to relax/study to type=LIVE_STREAM
progressiveAudio=0 hls=true dash=true
STREAM_HTTP_OK code=200 nonempty=true
HLS_MANIFEST_OK code=200 audioRenditions=2
HLS_AUDIO_ONLY_VARIANTS count=0
DASH_MANIFEST_OK code=200 audio=true
CANCELLATION_OK new requests rejected after session cancellation
```

The prior record reports JDK native-access/Unsafe deprecation warnings and a successful exit; signed URLs were not printed. At the time of that probe, this verified extraction, a successful nonempty HTTP response, HLS master/DASH manifest retrieval, and rejection of new requests after cancellation. The HLS master had audio renditions but **no standalone audio-only variants**; no `HLS_AUDIO_PLAYLIST_OK` was emitted. It does **not** demonstrate decoded audio, sustained segment delivery, Media3 device playback, platform codec availability, audio-focus transitions, reconnection, or background/dream behavior. Stream availability is time-sensitive. The probe assembles its own compile classpath; it is not proof of the APK's final resolved dependency graph.

To rerun, use README setup and the command above. The helper downloads dependencies even without `--network`; that flag additionally enables YouTube/CDN requests. It needs cached Kotlin compiler artifacts described in the script; `--android-jar` and `--gradle-cache` override machine-specific defaults. It consumes bandwidth, contacts external providers, and requires no account/key. Never publish signed extraction URLs or personal network logs.

## Screensaver: inspect, select, restore

Prefer the normal Settings picker. These ADB operations are an explicit fallback, not an unattended installer. Firmware can ignore/rewrite settings; review actual output. Use the foreground Android user, not an assumed user 0. Python 3 and authorized `adb` are required.

### Android 12 AOSP reference

The four secure keys are defined in [Android 12 Settings.java](https://android.googlesource.com/platform/frameworks/base/+/android-12.0.0_r1/core/java/android/provider/Settings.java):

| Key | Meaning |
| --- | --- |
| `screensaver_components` | Selected dream service component(s) |
| `screensaver_enabled` | Whether dreaming is enabled |
| `screensaver_activate_on_sleep` | Automatic activation at screen timeout when not on battery |
| `screensaver_activate_on_dock` | Automatic activation on docking |

Inspected [Android 12 DreamManagerService.java](https://android.googlesource.com/platform/frameworks/base/+/android-12.0.0_r1/services/core/java/com/android/server/dreams/DreamManagerService.java): that version has no `onShellCommand` implementation or `start-dreaming` command. Do **not** assume a command from newer Android versions exists on Android 12. Vendor forks may differ. This is source verification, not verification on a physical Android 12 TV.

### Reversible helper (fish)

```fish
adb devices -l
# Configurable network example; for USB, use the serial from adb devices instead.
set TV_SERIAL 192.168.4.47:5555
# Inspect first; save a NEW backup file outside the source tree.
python3 scripts/screensaver.py --serial $TV_SERIAL --state $HOME/ambientflow-dream-before.json inspect
# Review the JSON before proceeding. Existing backup files are never overwritten.
python3 scripts/screensaver.py --serial $TV_SERIAL --state $HOME/ambientflow-dream-before.json select
# Prints cmd dreams help, then opens the normal Settings screen; use Preview/Start now there.
python3 scripts/screensaver.py --serial $TV_SERIAL test
# Restore the original selection and enabled value, including deleting formerly absent keys.
python3 scripts/screensaver.py --serial $TV_SERIAL --state $HOME/ambientflow-dream-before.json restore
```

Use `--adb /actual/path/to/adb` if needed and `--user ID` for an explicitly chosen Android user. A backup is bound to the reported device serial and user. Keep it until restoration is verified; it is not a release/source-ZIP input. Selection refuses stale snapshots. It sets only `screensaver_components=org.ambientflow.tv/.AmbientDreamService` and `screensaver_enabled=1`, reads back both, and attempts rollback if selection fails. Restore refuses to overwrite unexpected later selection changes. Multi-setting writes are not atomic: retain the backup if a device disconnects.

**Neither select nor restore changes the sleep/dock activation triggers.** Their exact original values are saved for inspection, but restore leaves any later user trigger changes alone. If automatic dreaming does not start with the original triggers, choose activation behavior deliberately through Settings; the helper intentionally does not “fix” it. It does not alter timeout, power, launcher, or package-enabled settings.

Equivalent read-only raw inspection (replace `0` with the user ID reported by the second command):

```fish
adb -s $TV_SERIAL shell am get-current-user
adb -s $TV_SERIAL shell settings --user 0 list secure
adb -s $TV_SERIAL shell settings --user 0 get secure screensaver_components
adb -s $TV_SERIAL shell settings --user 0 get secure screensaver_enabled
adb -s $TV_SERIAL shell settings --user 0 get secure screensaver_activate_on_sleep
adb -s $TV_SERIAL shell settings --user 0 get secure screensaver_activate_on_dock
adb -s $TV_SERIAL shell cmd dreams help
adb -s $TV_SERIAL shell am start -a android.settings.DREAM_SETTINGS
```

Use `list secure` to distinguish an **absent entry** from a literal string `null`; `get` alone cannot do so. The helper preserves that distinction as JSON `null` (absent) versus JSON `"null"` (present), and quotes previous component lists for the remote shell. Prefer helper restore for arbitrary saved values.

### Explicit manual selection and rollback

These are **optional changes**, not prerequisites for normal app use. First run the helper's `inspect --state` above and review/save its JSON. Never run selection without a recoverable prior snapshot. Set the user to the ID you actually inspected:

```fish
read -P 'Android user ID from am get-current-user: ' ANDROID_USER
# Literal selection: changes only these two secure keys.
adb -s $TV_SERIAL shell settings --user $ANDROID_USER put secure screensaver_components org.ambientflow.tv/.AmbientDreamService
adb -s $TV_SERIAL shell settings --user $ANDROID_USER put secure screensaver_enabled 1
adb -s $TV_SERIAL shell settings --user $ANDROID_USER get secure screensaver_components
adb -s $TV_SERIAL shell settings --user $ANDROID_USER get secure screensaver_enabled
# Restore the EXACT saved values (recommended, including after manual selection):
python3 scripts/screensaver.py --serial $TV_SERIAL --user $ANDROID_USER --state $HOME/ambientflow-dream-before.json restore
```

For manual rollback, choose **one branch per key**, independently, using the snapshot. The `put` lines below are command **templates**, not commands to paste unchanged: replace `EXACT_OLD_COMPONENTS`/`EXACT_OLD_ENABLED` with the corresponding saved strings. The embedded single quotes are for the device's shell; arbitrary values containing quotes require proper shell escaping, which the helper handles.

```fish
# Originally present: restore the precise saved string, even if it was "0" or "null".
adb -s $TV_SERIAL shell "settings --user $ANDROID_USER put secure screensaver_components 'EXACT_OLD_COMPONENTS'"
adb -s $TV_SERIAL shell "settings --user $ANDROID_USER put secure screensaver_enabled 'EXACT_OLD_ENABLED'"
# Originally ABSENT (JSON null): delete that key instead of putting a string "null".
adb -s $TV_SERIAL shell settings --user $ANDROID_USER delete secure screensaver_components
adb -s $TV_SERIAL shell settings --user $ANDROID_USER delete secure screensaver_enabled
```

Do not run both branches for a key, guess an OEM default, or overwrite a subsequent user's intentional selection. Verify restoration with `list secure` and the normal picker, and keep the backup until confirmed. If selection fails midway or ADB disconnects, reconnect to the same device/user and restore from the snapshot; two writes cannot be atomic.

The helper's `test` action checks `cmd dreams help` but never invokes a dream-start shell command, even if advertised. It only opens `android.settings.DREAM_SETTINGS`. **Do not prescribe `cmd dreams start-dreaming` for stock Android 12**; no such command was verified there. If Settings lacks a preview/start control, use ordinary configured idle/dock behavior or manual `AmbientActivity` for a visual preview, not an unsupported shell command. A manual activity preview does not test DreamService.

## Device acceptance checklist — not yet performed

Record device model, firmware/API, Android user, APK SHA-256, build log, and test date. Preserve previous screensaver settings first.

- Install/upgrade the same APK on a USB tablet and a TV. Confirm ordinary `LAUNCHER` and `LEANBACK_LAUNCHER` entries respectively, landscape/full-screen activity presentation, and any OEM orientation overrides. The ambient activity requests immersive bars; menus only use a full-screen theme, and DreamService orientation/bars are firmware-controlled.
- Tablet: tap all controls/dialogs, swipe native vertical pages and the horizontal ambient button strip, tap the canvas to reveal controls, test Back, keyboard/URL input, and narrow landscape layouts. TV: banner, D-pad focus and scrolling to every control, dialogs, Back, OK, media keys, and seven-second control auto-hide.
- Test manual `AmbientActivity`, pause/resume/Next/settings/exit; confirm no work/audio remains after exit, Home, screen-off, dream stop, or focus loss.
- Select `AmbientDreamService` through Settings; test Preview and genuine idle/dock entry under the user's chosen triggers. Confirm the original dream returns after restore.
- Flow: palettes, Still/reduced motion, clock/date/placement, animation smoothness, long-run memory/CPU, and burn-in considerations. “30 fps” is scheduling intent, not a measured benchmark or burn-in guarantee.
- Cityscapes: all selected cities, intervals, no-network/empty-cache Flow fallback, cache limits and clear-after-exit, decode bounds, crossfades, full Image details/source/license links, and attribution legibility. Try long credits and no browser.
- Audio: exact default live stream decoded on TV, direct HTTPS HLS/DASH/progressive audio and local document URI; volume/mute; another app already playing; permanent/transient focus loss; no unrequested resume; network loss/retry; stop during extraction; no video decoder. Check Android 12 and any other supported devices separately.
- Check no cleartext traffic, bundled media, analytics, account prompts, or unintended system-setting changes. Disable debugging after testing.

Suggested maintainer build checks (not executed in this documentation task):

```fish
python3 scripts/build.py :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
python3 scripts/build.py :app:dependencies --configuration debugRuntimeClasspath
```

Review task outcomes: `NO-SOURCE` is not a passing test suite. Archive the actual runtime dependency resolution and obtain matching dependency sources/notices before packaging a release; caches contain obsolete versions and build-only artifacts too.

## Source-review findings for the app maintainer (not fixed here)

These are source-level risks, not reproduced device failures; app code is intentionally unchanged:

1. Pause now invalidates in-flight photo completion using a session generation token; a stale decoded result is discarded. On returning from Settings a fresh initial city image may be loaded while paused because prior bitmaps are released on visibility loss. Scheduling remains paused. Verify pause/resume semantics across Settings/focus transitions on hardware.
2. The credit overlay uses `maxLines=3`; full creator/credit text exists in Image details, but long multi-author/crossfade credits can be visually truncated. Verify attribution presentation against individual image terms before redistribution.
3. The independent audio probe does not mirror the full runtime graph: the existing inventory identifies jsoup 1.22.2's RE2/J 1.8 dependency as absent from the probe's explicit download list. The prior exact-stream success is not a general extraction/runtime compatibility guarantee.
4. Menu full-screen styling is not the same as immersive navigation-bar hiding; DreamService landscape behavior is not explicitly requested by an activity manifest entry. Confirm tablet navigation bars and OEM dream orientation on hardware.

No application code, Gradle configuration, build helper, or screensaver helper was changed for this documentation update. Only README, LICENSE, third-party notices, and this record were written. Android 12 AOSP key definitions and absence of a dream-start shell handler were checked online; the full GPLv3 license text was fetched from GCC's upstream mirror after GNU's text endpoint was unavailable. Existing full dependency-license appendices were retained, with tagged NewPipe and OkHttp licensing cross-checked online. These documentation/source checks are not builds, tests, or device verification. The parent maintainer is handling build/test validation; record actual results separately.
