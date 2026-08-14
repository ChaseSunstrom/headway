#!/usr/bin/env bash
#
# This file is part of Headway.
# Copyright (C) 2026 The Headway Authors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Asserts that the two published APKs differ in exactly the way that matters.
#
# Headway's car-app host has to claim two names that are global to the device:
# the permission `android.car.permission.TEMPLATE_RENDERER` and the content
# provider authority `androidx.car.app.connection`. A permission name has one
# definer per device and an authority one owner, so on a phone where anything
# else holds either, an APK declaring them does not install at all -- and the
# platform reports that as the bare string "App not installed", with every
# unrelated feature going down alongside. That happened, to a real user, on a
# real phone.
#
# The fix is two variants: `host` claims both, `compat` claims neither. The
# failure mode of that fix is silent in both directions -- a merge mistake that
# leaves the claims in `compat` makes the fallback uninstallable on exactly the
# phones it exists for, and one that drops them from `host` disables the car-app
# host with no error anywhere. Neither shows up in a build log, a unit test, or
# an emulator install. So it is checked here, on the built APKs, where the
# answer is a fact rather than an intention.
#
# Matching is on the permission name and on the provider's *class* name, not on
# the authority string: androidx.car.app's own manifest contributes a
# `<queries><provider android:authorities="androidx.car.app.connection" />` to
# every app that depends on it, which names the authority without owning it and
# is present in both variants.

set -euo pipefail

HOST_APK="${1:-app/build/outputs/apk/host/debug/app-host-debug.apk}"
COMPAT_APK="${2:-app/build/outputs/apk/compat/debug/app-compat-debug.apk}"

PERMISSION="TEMPLATE_RENDERER"
PROVIDER="CarConnectionProvider"

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
aapt2="$(find "$sdk/build-tools" -name aapt2 -type f 2>/dev/null | sort | tail -1)"
if [ -z "$aapt2" ]; then
    echo "check-install-claims: no aapt2 under $sdk/build-tools" >&2
    exit 2
fi

for apk in "$HOST_APK" "$COMPAT_APK"; do
    if [ ! -f "$apk" ]; then
        echo "check-install-claims: missing $apk" >&2
        echo "  build them with: ./gradlew :app:assembleHostDebug :app:assembleCompatDebug" >&2
        exit 2
    fi
done

manifest_of() {
    "$aapt2" dump xmltree --file AndroidManifest.xml "$1"
}

host_tree="$(manifest_of "$HOST_APK")"
compat_tree="$(manifest_of "$COMPAT_APK")"

failed=0

if ! grep -q "$PERMISSION" <<<"$host_tree"; then
    echo "FAIL: the host APK does not declare $PERMISSION." >&2
    echo "      Every car app will answer \"Unknown host\" and nothing will say why." >&2
    failed=1
fi
if ! grep -q "$PROVIDER" <<<"$host_tree"; then
    echo "FAIL: the host APK does not declare $PROVIDER." >&2
    echo "      Car apps cannot learn they are being projected; several then refuse." >&2
    failed=1
fi

if grep -q "$PERMISSION" <<<"$compat_tree"; then
    echo "FAIL: the compat APK declares $PERMISSION." >&2
    echo "      It will fail to install on the phones it exists to serve." >&2
    failed=1
fi
if grep -q "$PROVIDER" <<<"$compat_tree"; then
    echo "FAIL: the compat APK declares $PROVIDER." >&2
    echo "      It will fail to install on the phones it exists to serve." >&2
    failed=1
fi

if [ "$failed" -ne 0 ]; then
    exit 1
fi

echo "Install claims OK: host claims both names, compat claims neither."
