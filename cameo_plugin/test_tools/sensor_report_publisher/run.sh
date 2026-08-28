#!/usr/bin/env bash
# Runs SensorReportPublisher. Requires build.sh to have been run first.
#
# NDDSHOME defaults to this machine's actual RTI install
# (confirmed present: C:\Program Files\rti_connext_dds-7.5.0) if not already
# set in the environment -- checked live in this session: neither NDDSHOME
# nor the RTI native library directory is on PATH here, so this script is
# self-contained rather than requiring rtisetenv to be sourced first (that
# script is a .zsh, meant for macOS/Linux -- RTI's Windows equivalent is a
# .bat, awkward to source from bash). -Djava.library.path is passed
# explicitly below for the same reason: without it, this would fail with
# UnsatisfiedLinkError since Windows can't find nddscore.dll/nddsjava.dll
# without either PATH or java.library.path pointing at them (same problem
# RTIConnextPlugin.java's own loadNativeLibraries() exists to solve for the
# CAMEO plugin itself).
set -euo pipefail

cd "$(dirname "$0")"

: "${NDDSHOME:=/c/Program Files/rti_connext_dds-7.5.0}"

if [[ ! -f "$NDDSHOME/lib/java/nddsjava.jar" ]]; then
  echo "Error: nddsjava.jar not found under NDDSHOME=$NDDSHOME" >&2
  echo "  Set NDDSHOME to your real RTI Connext DDS install root if it's not" >&2
  echo "  C:\\Program Files\\rti_connext_dds-7.5.0" >&2
  exit 1
fi

# RTI Connext requires a license file to create any entity, including a
# DomainParticipant -- without RTI_LICENSE_FILE set, create_participant_from_
# config() fails with "No source for License information" (CAMEO's own JVM
# already has this set some other way; a fresh standalone `java` process
# does not). Defaults to this project's own existing convention (same path
# setup_rti_config.bat already uses) if not already set in the environment.
: "${RTI_LICENSE_FILE:=/c/Users/MKG11/Documents/rti_workspace/7.5.0/rti_license.dat}"
export RTI_LICENSE_FILE

if [[ ! -f "$RTI_LICENSE_FILE" ]]; then
  echo "Error: RTI license file not found at RTI_LICENSE_FILE=$RTI_LICENSE_FILE" >&2
  echo "  Set RTI_LICENSE_FILE to your real license file path." >&2
  exit 1
fi

# Detect the Windows native-library arch directory the same way
# RTIConnextPlugin.java's detectWindowsArchDir() does (first x64Win64* dir
# under lib\).
ARCH_DIR=$(find "$NDDSHOME/lib" -maxdepth 1 -type d -iname "x64Win64*" | head -1)
if [[ -z "$ARCH_DIR" ]]; then
  echo "Error: no x64Win64* native library directory found under $NDDSHOME/lib" >&2
  exit 1
fi

java -Djava.library.path="$ARCH_DIR" -cp ".:$NDDSHOME/lib/java/nddsjava.jar" SensorReportPublisher
