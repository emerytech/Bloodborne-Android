#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
python3 tools/thor/prepare_runtime.py
python3 tools/thor/prepare_dependencies.py
if [ "$#" -eq 0 ]; then set -- assembleDebug; fi
./gradlew --no-daemon "$@"
