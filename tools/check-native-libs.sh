#!/usr/bin/env bash
#
# Headway -- an open-source Android Auto replacement.
# Copyright (C) 2026 the Headway contributors.
#
# This program is free software: you can redistribute it and/or modify it under
# the terms of the GNU General Public License as published by the Free Software
# Foundation, either version 3 of the License, or (at your option) any later
# version.
#
# This program is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License along with
# this program. If not, see <https://www.gnu.org/licenses/>.
#
# ---------------------------------------------------------------------------
#
# Asserts that every native library voice needs is actually inside the APK.
#
# Both of these are loaded by name at runtime and neither failure is visible at
# build time. A real drive found the second one missing: JNA calls
# `System.loadLibrary("jnidispatch")`, which searches only `lib/<abi>/` inside
# the APK, and vosk-android's POM asks for the plain jar whose copy of that
# library is an ordinary resource. The build succeeded; voice did not exist.

set -euo pipefail

apks=("$@")
if [ ${#apks[@]} -eq 0 ]; then
    apks=(
        app/build/outputs/apk/host/debug/app-host-debug.apk
        app/build/outputs/apk/compat/debug/app-compat-debug.apk
    )
fi

# libvosk is the decoder; libjnidispatch is the only way Java reaches it.
required=(libvosk.so libjnidispatch.so)

status=0
for apk in "${apks[@]}"; do
    if [ ! -f "$apk" ]; then
        echo "missing APK: $apk" >&2
        status=1
        continue
    fi
    listing=$(unzip -Z1 "$apk")
    for lib in "${required[@]}"; do
        # Any ABI: the published APKs are arm64-only, but CI's instrumentation
        # job builds x86_64 as well and both are legitimate.
        if printf '%s\n' "$listing" | grep -qE "^lib/[^/]+/${lib}$"; then
            found=$(printf '%s\n' "$listing" | grep -E "^lib/[^/]+/${lib}$" | tr '\n' ' ')
            echo "ok  $(basename "$apk"): ${found% }"
        else
            echo "FAIL $(basename "$apk"): no lib/*/${lib}" >&2
            status=1
        fi
    done
done

if [ "$status" -ne 0 ]; then
    cat >&2 <<'MSG'

A native library voice depends on is not in the APK.

libjnidispatch.so comes from net.java.dev.jna:jna packaged as an *aar*; the
plain jar carries it as a resource that never reaches lib/<abi>/, and JNA's
extract-and-load fallback has been blocked by Android since 10. See the
dependency block in app/build.gradle.kts.
MSG
fi
exit "$status"
