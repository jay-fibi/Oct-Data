#!/bin/sh
set -eu

TEST_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$TEST_DIR/.." && pwd)

if [ -n "${JAVA_HOME:-}" ]; then
    JAVAC="$JAVA_HOME/bin/javac"
    JAVA="$JAVA_HOME/bin/java"
elif command -v javac >/dev/null 2>&1 && command -v java >/dev/null 2>&1; then
    JAVAC=$(command -v javac)
    JAVA=$(command -v java)
else
    JAVAC=
    JAVA=
    for candidate in "$HOME"/.local/share/calculator-tools/*; do
        if [ -x "$candidate/bin/javac" ] && [ -x "$candidate/bin/java" ]; then
            JAVAC="$candidate/bin/javac"
            JAVA="$candidate/bin/java"
            break
        fi
    done
fi

if [ -z "$JAVAC" ] || [ ! -x "$JAVAC" ] || [ ! -x "$JAVA" ]; then
    echo 'A JDK is required. Set JAVA_HOME or add javac/java to PATH.' >&2
    exit 1
fi

CLASSES=$(mktemp -d "${TMPDIR:-/tmp}/calculator-core-classes.XXXXXX")
trap 'rm -rf -- "$CLASSES"' EXIT HUP INT TERM

"$JAVAC" -encoding UTF-8 -Xlint:all -Werror -d "$CLASSES" \
    "$ROOT"/app/src/main/java/com/jayfibi/calculator/core/*.java \
    "$TEST_DIR/CoreTests.java"
"$JAVA" -ea -cp "$CLASSES" CoreTests