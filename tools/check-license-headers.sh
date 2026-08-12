#!/usr/bin/env bash
# This file is part of Headway.
# Copyright (C) 2026 The Headway Authors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Fails if any source file is missing a GPLv3 notice.
#
# CLAUDE.md §5: "License: GPLv3 for the whole project (aasdk is GPLv3; anything
# linking it is too). Put proper headers on every file."

set -euo pipefail

cd "$(dirname "$0")/.."

missing=()

# Source files we require a notice on.
#
# Excluded:
#  - references/            third-party clones, never committed
#  - build/                 generated protobuf output, regenerated each build
#  - */proto/aap_protobuf/  schemas vendored verbatim from aasdk (GPLv3). Stamping
#                           our own copyright on someone else's files would
#                           misstate authorship; provenance is recorded in
#                           THIRD-PARTY.md and in the directory's own README.
while IFS= read -r file; do
    if ! grep -qE 'GNU General Public License|SPDX-License-Identifier: GPL-3\.0' "$file"; then
        missing+=("$file")
    fi
done < <(
    find . \
        -path ./references -prune -o \
        -path ./build -prune -o \
        -path '*/build' -prune -o \
        -path ./.git -prune -o \
        -path ./.gradle -prune -o \
        -path '*/proto/aap_protobuf' -prune -o \
        -type f \( -name '*.kt' -o -name '*.kts' -o -name '*.java' -o -name '*.proto' -o -name '*.sh' \) \
        -print
)

if [ ${#missing[@]} -gt 0 ]; then
    echo "ERROR: ${#missing[@]} source file(s) missing a GPLv3 licence notice:" >&2
    printf '  %s\n' "${missing[@]}" >&2
    echo >&2
    echo "Add the standard header (see any existing .kt file) before committing." >&2
    exit 1
fi

echo "License headers OK."
