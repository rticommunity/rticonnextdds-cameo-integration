#!/usr/bin/env bash
# Compiles SensorReportPublisher.java against nddsjava.jar.
#
# NDDSHOME defaults to this machine's actual RTI install (confirmed
# present: C:\Program Files\rti_connext_dds-7.5.0) if not already set in
# the environment -- see run.sh's header comment for why this doesn't
# require rtisetenv to be sourced first.
set -euo pipefail

cd "$(dirname "$0")"

: "${NDDSHOME:=/c/Program Files/rti_connext_dds-7.5.0}"

if [[ ! -f "$NDDSHOME/lib/java/nddsjava.jar" ]]; then
  echo "Error: nddsjava.jar not found under NDDSHOME=$NDDSHOME" >&2
  echo "  Set NDDSHOME to your real RTI Connext DDS install root if it's not" >&2
  echo "  C:\\Program Files\\rti_connext_dds-7.5.0" >&2
  exit 1
fi

javac -cp "$NDDSHOME/lib/java/nddsjava.jar" *.java
echo "Build complete"
