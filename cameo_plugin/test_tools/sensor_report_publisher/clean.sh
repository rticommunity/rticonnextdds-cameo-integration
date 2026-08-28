#!/usr/bin/env bash
# Removes all generated/build artifacts, keeping only the entry point files.
set -euo pipefail

cd "$(dirname "$0")"
rm -f *.class
echo "Clean complete"
