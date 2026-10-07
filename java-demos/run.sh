#!/usr/bin/env bash
#
# Compiles and runs every demo in the java-demos folder.
#
# Usage:
#   ./run.sh            # run all demos with their default arguments
#   ./run.sh Fibonacci  # run a single demo (extra args are passed through)
#
set -euo pipefail

cd "$(dirname "$0")"

SRC_DIR="src"
OUT_DIR="out"

echo "==> Compiling sources into $OUT_DIR"
mkdir -p "$OUT_DIR"
javac -d "$OUT_DIR" "$SRC_DIR"/*.java

run_demo() {
    local class="$1"
    shift
    echo
    echo "=================================================="
    echo " $class $*"
    echo "=================================================="
    java -cp "$OUT_DIR" "$class" "$@"
}

if [ "$#" -gt 0 ]; then
    # Run only the requested demo, forwarding any remaining arguments.
    DEMO="$1"
    shift
    run_demo "$DEMO" "$@"
else
    run_demo HelloWorld
    run_demo FizzBuzz
    run_demo Fibonacci
    run_demo PalindromeChecker
    run_demo BubbleSort
fi
