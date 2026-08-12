#!/usr/bin/env bash
# This file is part of Headway.
# Copyright (C) 2026 The Headway Authors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Runs the instrumentation tests on a connected device via adb.
#
# Why not ./gradlew connectedAndroidTest: AGP probes each device for its API
# level and ABI before running, and on a software-emulated (no-KVM) emulator
# that probe times out, so AGP reports "Found 1 connected device(s), 0 of which
# were compatible" even though the device is perfectly usable. Driving
# `am instrument` directly skips the probe and runs the same APKs.
#
# CI has KVM and uses ./gradlew connectedDebugAndroidTest instead; this script is
# for local and no-KVM environments.
#
# Known limitation of a no-KVM emulator: the software AVC encoder is unstable
# under sustained use and will eventually take the instrumentation process down
# with "Process crashed". core-video's suite therefore wants running in two
# batches there (VideoResolutionTest, then H264EncoderTest), and a crash after a
# long MediaCodec run is the emulator giving up rather than a test failing. With
# KVM, or on a phone, the whole suite runs in one pass.

set -euo pipefail

cd "$(dirname "$0")/.."

ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
MODULE="${1:-app}"
FILTER="${2:-}"

if ! "$ADB" get-state >/dev/null 2>&1; then
    echo "No device. Start an emulator or attach a phone." >&2
    exit 1
fi

echo "Building $MODULE..."
./gradlew ":$MODULE:assembleDebug" ":$MODULE:assembleDebugAndroidTest" -q

# Library modules produce only a test APK (it is self-instrumenting); the app
# module produces both and the test APK targets it.
for apk in $(find "$MODULE/build/outputs/apk" -name '*.apk' | sort); do
    echo "Installing $(basename "$apk")"
    "$ADB" install -r -t "$apk" >/dev/null
done

# The instrumentation package is the module's namespace with .test appended.
NAMESPACE=$(grep -oP 'namespace\s*=\s*"\K[^"]+' "$MODULE/build.gradle.kts" | head -1)
RUNNER="$NAMESPACE.test/androidx.test.runner.AndroidJUnitRunner"

ARGS=(-w -r)
[ -n "$FILTER" ] && ARGS+=(-e class "$FILTER")

echo "Running $RUNNER"
OUTPUT=$("$ADB" shell am instrument "${ARGS[@]}" "$RUNNER" 2>&1)
echo "$OUTPUT" | grep -E "^(Time:|OK|FAILURES|Tests run)" || true

# am instrument exits 0 even when tests fail, so the summary decides.
if echo "$OUTPUT" | grep -q "FAILURES!!!"; then
    echo "$OUTPUT" | grep -A20 "FAILURES!!!" >&2
    exit 1
fi
if ! echo "$OUTPUT" | grep -q "^OK ("; then
    echo "$OUTPUT" | tail -40 >&2
    echo "Instrumentation did not report success." >&2
    exit 1
fi
echo "Instrumentation tests passed."
