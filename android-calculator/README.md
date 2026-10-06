# Pocket Calculator

A small, offline Android calculator with file-backed, editable history and optional numeric-PIN encryption. Everything for the application lives in this directory. No account, ads, network permission, or third-party runtime dependencies.

## Features

- **Calculator:** decimal arithmetic, `+`, `−`, `×`, `÷`, parentheses, unary signs, and postfix `%`. Enter expressions with the keypad or the keyboard. Results use decimal arithmetic rather than binary floating-point.
- **File-backed history:** the latest 1,000 calculations survive app restarts in private internal storage. Newest entries appear first. Each has a timestamp and **Use**, **Edit**, and **Delete** actions; **Clear history** requires confirmation.
- **Text editing:** edit a saved expression in a text field. Saving validates it, recalculates the result, and updates the history file. Invalid expressions do not overwrite the entry.
- **Themes:** System, Light, Dark, and Ocean. The selection persists across restarts.
- **Numeric lock:** set and confirm a 4–12 digit PIN, unlock, change/remove the PIN after unlocking, or lock immediately. Protected history locks when the activity leaves the foreground, including theme changes and rotation.

`%` always means division by 100: `50% = 0.5`, `200 × 10% = 20`, and `200 + 10% = 200.1`. It is not the context-sensitive “add 10 percent” behavior of some calculators. Multiplication must be explicit (`2 × (3 + 4)`, not `2(3 + 4)`).

### Using the history lock

1. Open **Settings → Set history PIN**, enter a PIN twice, and keep it somewhere safe.
2. Use **Lock now**, or leave the application to lock automatically.
3. Open **History → Unlock history** and enter the PIN to view/edit saved calculations.
4. After unlocking, **Settings** provides **Change PIN** and **Remove PIN**.

**While locked, calculations work but are not saved.** The app explicitly reports this. Unlock history and press `=` again to save a result. There is no unencrypted holding file or queue for locked calculations.

There is **no forgotten-PIN recovery**. Clearing application data or uninstalling permanently deletes history. There is intentionally no unauthenticated reset button that exposes existing history.

## Build and install

Requirements:

- Android Studio supporting Android Gradle Plugin **8.7.3**, or a command-line Android SDK
- **JDK 17**
- Android SDK Platform **35** and Android SDK Build Tools (Gradle installs the required version if SDK licenses are accepted)
- An Android **8.0 / API 26** or newer phone/emulator

Open this directory in Android Studio, let Gradle sync, and choose **Run app**. Alternatively, from this directory:

```sh
export JAVA_HOME=/absolute/path/to/jdk-17
export ANDROID_HOME=/absolute/path/to/android-sdk
./gradlew assembleDebug lintDebug
./gradlew installDebug
```

On Windows, use `gradlew.bat`. Instead of `ANDROID_HOME`, you may create an untracked `local.properties` with `sdk.dir=/absolute/path/to/android-sdk`.

The installable debug APK is at `app/build/outputs/apk/debug/app-debug.apk`, relative to this directory. A debug APK is for local installation/testing; a distributable release needs your own signing key. Build outputs, SDK paths, and signing material are excluded from Git.

The committed Gradle wrapper pins Gradle **8.9** with a distribution SHA-256 checksum. No globally installed Gradle is necessary.

## Tests

The core test suite is plain Java and needs no Android SDK or downloaded test framework:

```sh
./tests/run-tests.sh
```

It covers expression evaluation and failures, persistence/reload, history editing, PIN protection and transitions, incorrect PINs, tampering/corruption, and bounded history storage.

For static analysis and Android compilation:

```sh
./gradlew assembleDebug lintDebug
```

See [TESTING.md](TESTING.md) for device-level checks and validation results.

## Storage and security

- The only history file is `history.bin` in Android's private `Context.getFilesDir()` directory. Writes replace the file atomically; a failed write does not commit the in-memory change. With PIN protection, the temporary file contains ciphertext, not plaintext.
- Interrupted-write temporary files are removed on load and before setting a PIN. File contents are synced before replacement; atomic replacement prevents partial files, but power-loss durability of the directory rename still depends on the device filesystem.
- Without a PIN, history is unencrypted but still app-private. Enabling protection rewrites the entire history using **AES-256-GCM**, a fresh nonce per write, and a random salt with **PBKDF2-HMAC-SHA256 (210,000 iterations)**. The PIN itself is not stored.
- Unlocking authenticates the encrypted file. Locking releases decrypted entries and wipes the retained key bytes. File operations and PIN derivation run on a serial background executor, not the UI thread.
- The app clears history views when leaving the foreground, does not save protected expressions in Android instance state, and disables screenshots/recent-app previews, backups, and device-transfer backups.
- After five incorrect unlock attempts, the UI enforces a 30-second delay on each subsequent failure. A successful unlock clears the counter. This is a convenience deterrent, **not** protection against someone who extracts the file or controls the device clock/app data. Short numeric PINs have limited entropy; prefer a longer PIN.
- No application can guarantee deletion of earlier plaintext copies from flash storage or wipe every JVM/keyboard memory copy. This lock is not a substitute for a strong Android device lock and full-device encryption. The app makes no claim of protection against a rooted or compromised operating system.

## Project structure

```text
app/src/main/java/com/jayfibi/calculator/
  MainActivity.java          Native calculator/history/settings UI
  CalculatorApp.java         Shared serial disk worker and history lifecycle
  core/CalculatorEngine.java Decimal expression parser
  core/HistoryEntry.java     Immutable saved calculation
  core/HistoryStore.java     Versioned persistence and PIN encryption
app/src/main/res/            App theme, icon, backup policy
tests/                      Dependency-free JVM regression suite
```

The source intentionally uses native Android widgets and standard Java APIs to keep this application easy to build and inspect.

The UI is currently English-only and expressions use a decimal point, independent of the device locale. Device-level automation targets Android 15; the declared minimum Android 8.0 compatibility is also checked by Android lint, but older-device behavior should be verified on your target hardware before distributing a release.