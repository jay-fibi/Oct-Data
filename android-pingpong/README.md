# Ping Pong

A small, offline Android ping-pong (Pong) game. Everything for the application lives in this directory. No account, ads, network permission, or third-party runtime dependencies.

## Features

- **One-thumb controls:** drag anywhere on the screen to move the bottom paddle; tap to start or restart a match.
- **Faithful Pong physics:** the ball angle depends on where it strikes the paddle, speed increases slightly with each rally hit up to a cap, and serves alternate sides and travel toward the player who lost the point.
- **Beatable opponent:** the top paddle tracks the ball at a capped speed, so hard-angled shots beat it. The first player to **11** points wins the match.
- **Stable simulation:** the game loop clamps long frames and sub-steps the physics so the ball cannot tunnel through a paddle on slow devices.

The game is portrait-locked and keeps the screen on while playing.

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

The game logic lives in a pure-Java core with a plain JVM test suite that needs no Android SDK or downloaded test framework:

```sh
./tests/run-tests.sh
```

It covers the ready/serve/match state machine, serve timing and direction, paddle clamping and NaN input, invalid frame deltas, point scoring, the full match win condition for both sides, and physics invariants (ball and paddle stay in bounds over tens of thousands of simulated frames).

For static analysis and Android compilation:

```sh
./gradlew assembleDebug lintDebug
```

## Project structure

```text
app/src/main/java/com/jayfibi/pingpong/
  MainActivity.java   Single activity hosting the game view
  GameView.java       Canvas rendering, touch input, Choreographer loop
  core/PongGame.java  Pure-Java simulation: physics, AI, scoring
app/src/main/res/     App theme, launcher icon, app name
tests/                Dependency-free JVM regression suite
```

`core/PongGame` uses a fixed 100x160 logical field and never touches Android APIs, so the entire simulation is covered by the JVM suite. `GameView` only scales those units to the screen.
