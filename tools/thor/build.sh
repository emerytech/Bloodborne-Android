#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
python3 tools/thor/prepare_runtime.py
# Recompile bb-probe from our src/ (adds the companion's live HP/echoes to the Atlas shm) and splice it
# into the staged payload, refreshing runtime.sha256. Set THOR_SKIP_PROBE=1 to ship the payload as-is.
if [ "${THOR_SKIP_PROBE:-0}" != "1" ]; then tools/thor/integrate_probe.sh; fi
python3 tools/thor/prepare_dependencies.py
if [ "$#" -eq 0 ]; then set -- assembleDebug; fi
./gradlew --no-daemon "$@"
