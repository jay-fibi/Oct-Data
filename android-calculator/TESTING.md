# Validation

## Recorded build validation (6 October 2026)

- JDK 17, Gradle wrapper 8.9, Android Gradle Plugin 8.7.3, compile/target SDK 35.
- Core Java suite: **206 assertions in 8 groups passed**.
- Debug build and lint: **passed; no lint issues**.
- Unsigned release build and lint: **passed; no lint issues**.
- Debug APK signature verification: **passed**, APK Signature Scheme v2.
- Android 15 / API 35 x86_64 emulator: initial full smoke flow **18 checks passed** with no application crashes.
- APK manifest inspection: minimum API 26, target API 35, **no requested permissions**; runtime dependency report: **no dependencies**.

These checks do not claim exhaustive device coverage, cryptographic certification, or physical-device testing. Manual stress cases below remain useful before release.

## Automated checks

Run these commands from the project directory:

```sh
./tests/run-tests.sh
./gradlew assembleDebug lintDebug
```

The Java tests run independently of Android. Android compilation and lint additionally validate the manifest, resources, API compatibility, and application code.

## Automated device smoke test

The Python standard-library harness drives the actual UI with `adb`/UIAutomator. On a **disposable Android 15 emulator** with the debug APK installed:

```sh
python3 tests/android-smoke.py --adb /absolute/path/to/android-sdk/platform-tools/adb \
  --serial emulator-5554 --allow-clear-data
```

**This deletes all calculator app data on the selected emulator.** The explicit flag is required. It checks keypad arithmetic, editing, process restart, themes, wrong/correct PIN handling, background relocking, locked calculation behavior, and the actual encrypted file. It uses only synthetic calculations and a test PIN; no screenshots are captured. UI dumps/extracted history are never committed.

## Device checklist

Use an Android 8.0+ phone or emulator. Install with `./gradlew installDebug`.

1. **Calculator:** evaluate `2 + 3 × 4` (14), `(2 + 3) × 4` (20), `−5 + 2` (−3), `0.1 + 0.2` (0.3), and `200 × 10%` (20). `1 / 0` and unfinished parentheses must show errors without adding history. Try hardware/software keyboard input, selecting/replacing text, backspace, and chaining from a result.
2. **Persistence:** make several calculations, force-stop and reopen the app. Check the same expressions, results, and dates in History.
3. **Edit:** edit an entry to `(12 + 8) / 4`; it should show 5 after saving and after restart. An invalid edit must leave the original unchanged. Cancel must not write a change. Try Use, Delete, and Clear history, including cancellation.
4. **Themes:** select System, Light, Dark, and Ocean. Reopen to check persistence. In System mode, switch the device between dark/light mode. Test large fonts, narrow/landscape screens, scrolling, and keyboard/system-bar insets.
5. **PIN:** reject fewer than four digits and mismatching confirmation. Set a PIN, lock, and verify entries are hidden. A wrong PIN must not reveal or alter data. After five failed attempts, verify the 30-second delay. Correct unlock must restore all entries.
6. **Background lock:** unlock, navigate to History, then Home/reopen, rotate, and change theme. Each must require unlocking again. Repeat while an edit or PIN derivation is in progress; no history should appear before unlocking. Protected content must not appear in the recent-apps preview or screenshots.
7. **Locked calculation:** calculate while history is locked. Verify the explicit “not saved” message and no new history entry. Unlock and press `=` again to save it.
8. **Protected mutations:** edit/delete/clear while unlocked; restart and unlock to check persistence. Clear must retain PIN protection. Change the PIN; only the new PIN should work after restart. Remove the PIN; history should remain available after restart without a PIN.
9. **Privacy:** no network/storage permission prompts. App data backups/device transfers must exclude history. Never test on important history without another copy: this app intentionally has no PIN recovery or export feature.

For a debuggable build, `adb shell run-as com.jayfibi.calculator ls files` can confirm the private history file exists. After setting a PIN, extracting that debug-only file must not expose the saved expression strings. Do not commit extracted personal history, device dumps, or credentials.