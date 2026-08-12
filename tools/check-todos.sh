#!/usr/bin/env bash
# This file is part of Headway.
# Copyright (C) 2026 The Headway Authors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Enforces the CLAUDE.md working rule:
#
#   "Every TODO left in code must have a matching entry in BLOCKERS.md or it
#    doesn't get committed."
#
# The convention: a TODO in code must carry a blocker id of the form B-NNN, e.g.
# a comment reading "TODO" immediately followed by "(B-017)" and an explanation.
# BLOCKERS.md must then contain a matching entry for that id.
#
# (This file deliberately avoids writing a literal tagged example, since it
# scans the tree for exactly that pattern and would flag itself.)

set -euo pipefail

cd "$(dirname "$0")/.."

fail=0

# 1. Every TODO/FIXME in tracked source must carry a B-NNN id.
untagged=$(
    grep -rnE '(TODO|FIXME)' \
        --include='*.kt' --include='*.kts' --include='*.java' \
        --include='*.proto' --include='*.sh' \
        --exclude-dir=references --exclude-dir=build --exclude-dir=.git \
        --exclude-dir=.gradle --exclude-dir=.github \
        --exclude='check-todos.sh' \
        . 2>/dev/null \
    | grep -vE '(TODO|FIXME)\(B-[0-9]{3}\)' \
    || true
)

if [ -n "$untagged" ]; then
    echo "ERROR: TODO/FIXME without a blocker id (expected TODO(B-NNN)):" >&2
    echo "$untagged" >&2
    echo >&2
    fail=1
fi

# 2. Every referenced blocker id must exist in BLOCKERS.md.
if [ -f BLOCKERS.md ]; then
    ids=$(
        grep -rhoE '(TODO|FIXME)\(B-[0-9]{3}\)' \
            --include='*.kt' --include='*.kts' --include='*.java' \
            --include='*.proto' --include='*.sh' \
            --exclude-dir=references --exclude-dir=build --exclude-dir=.git \
            --exclude-dir=.gradle --exclude-dir=.github \
            --exclude='check-todos.sh' \
            . 2>/dev/null \
        | grep -oE 'B-[0-9]{3}' | sort -u || true
    )
    for id in $ids; do
        if ! grep -q "$id" BLOCKERS.md; then
            echo "ERROR: $id is referenced in code but absent from BLOCKERS.md" >&2
            fail=1
        fi
    done
fi

if [ "$fail" -ne 0 ]; then
    exit 1
fi

echo "TODO tracking OK."
