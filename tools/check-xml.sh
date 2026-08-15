#!/usr/bin/env bash
#
# This file is part of Headway.
# Copyright (C) 2026 The Headway Authors
#
# Headway is free software: you can redistribute it and/or modify it under the
# terms of the GNU General Public License as published by the Free Software
# Foundation, either version 3 of the License, or (at your option) any later
# version.
#
# Headway is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
# A PARTICULAR PURPOSE. See the GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License along with
# Headway. If not, see <https://www.gnu.org/licenses/>.
#
# Every checked-in XML file parses.
#
# Worth its own check because the failure is expensive and the cause is
# invisible: a manifest that does not parse fails at
# `:app:processHostDebugMainManifest`, several minutes into a CI run, with
# "Error parsing AndroidManifest.xml" and no line number. The one that prompted
# this was a `--` inside an `<!-- -->` comment, which XML forbids and which
# reads as perfectly ordinary prose.

set -euo pipefail
cd "$(dirname "$0")/.."

failed=0
while IFS= read -r file; do
  if ! python3 -c "import sys, xml.dom.minidom as m; m.parse(sys.argv[1])" "$file" 2>/tmp/headway-xml-err; then
    echo "XML does not parse: $file"
    sed 's/^/    /' /tmp/headway-xml-err
    failed=1
  fi
done < <(git ls-files '*.xml')

rm -f /tmp/headway-xml-err

if [ "$failed" -ne 0 ]; then
  echo
  echo "A '--' inside an <!-- XML comment --> is the usual cause; XML forbids it."
  exit 1
fi

echo "XML OK."
