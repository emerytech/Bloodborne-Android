#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
out=build/thor-mod-tests
mkdir -p "$out"
src=tools/android/frontend/src/ro/cobrabm/fexdroid
jars=$(python3 - <<'PY'
from pathlib import Path
import os
print(os.pathsep.join(str(p) for p in Path('.local-deps/android/steam-libs/jars').glob('*.jar')))
PY
)
javac --release 8 -cp "$jars" -d "$out" "$src"/{FsUtil,SafeWrite,MiniJson,ModLoader,ModManager,ModArchive,ModCatalog,ModBrowserInstall}.java tests/thor-mod-browser/ModBrowserTests.java
java -cp "$out:$jars" ro.cobrabm.fexdroid.ModBrowserTests tools/thor/assets/mod-catalog.json "$@"
