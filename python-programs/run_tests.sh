#!/usr/bin/env bash
# Run the full python-programs test suite.
set -euo pipefail

# Move to this script's directory so module imports resolve correctly.
cd "$(dirname "$0")"

echo "Running tests with: $(python3 --version 2>&1)"
echo

python3 -m unittest discover -s tests -v
