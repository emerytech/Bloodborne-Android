#!/usr/bin/env python3
"""Resolve upstream's JVM dependencies and require exact reviewed SHA-256 bytes."""
import hashlib, json, os, subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
jars = ROOT / '.local-deps/android/steam-libs/jars'
expected = json.loads((ROOT / 'tools/thor/frontend-dependencies.json').read_text())
if not jars.exists() or not any(jars.glob('*.jar')):
    (ROOT / '.local-deps/android').mkdir(parents=True, exist_ok=True)
    subprocess.run(['bash', 'tools/android/fetch_steam_libs.sh'], cwd=ROOT, check=True)
actual = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in jars.glob('*.jar')}
if actual != expected:
    raise SystemExit('Frontend dependency set or checksum differs from reviewed lock; refusing build.')
print(f'Verified {len(actual)} upstream JVM dependencies.')
