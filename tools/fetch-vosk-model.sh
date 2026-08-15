#!/usr/bin/env bash
# This file is part of Headway.
# Copyright (C) 2026 The Headway Authors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Fetches the Vosk small English model used by the voice pipeline.
#
# The model is ~68 MB unpacked, so it is not committed. Without it the Phase 5
# acceptance test skips rather than fails, so a contributor who does not care
# about voice still gets a green build.

set -euo pipefail

MODEL_NAME="vosk-model-small-en-us-0.15"
DEST="${HEADWAY_VOSK_MODEL_DIR:-/opt/vosk}"
URL="https://alphacephei.com/vosk/models/${MODEL_NAME}.zip"

if [ -d "${DEST}/${MODEL_NAME}" ]; then
    echo "Model already present at ${DEST}/${MODEL_NAME}"
    exit 0
fi

mkdir -p "$DEST"
echo "Fetching ${MODEL_NAME} (~41 MB compressed)..."
curl -sSL -o "${DEST}/model.zip" "$URL"
unzip -q -o "${DEST}/model.zip" -d "$DEST"
rm -f "${DEST}/model.zip"

echo "Model installed at ${DEST}/${MODEL_NAME}"
echo "Run tests with: ./gradlew :core-voice:test -Pheadway.vosk.model=${DEST}/${MODEL_NAME}"
