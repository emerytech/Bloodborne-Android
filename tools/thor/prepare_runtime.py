#!/usr/bin/env python3
"""Use only unchanged, checksum-pinned upstream 2.2.0 native payloads; recompile all Java from source."""
import hashlib, io, json, os, shutil, tarfile, urllib.request, zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
SHA='e97bed80872e2552ae175d9e1189def61c9a9f086bf64fd7d065602340a6ebc0'
URL='https://github.com/ducvd89/Bloodborne-Android/releases/download/android-2.2.0/Bloodborne-2.2.0.apk'
UPSTREAM='f241353e36f46bf183806acd7b79222afff2fd2f'
cache=ROOT/'.local-deps/thor-integrated'; cache.mkdir(parents=True,exist_ok=True)
apk=Path(os.environ.get('THOR_UPSTREAM_APK',str(cache/'Bloodborne-2.2.0.apk')))
if not apk.exists():
 req=urllib.request.Request(URL,headers={'User-Agent':'HuntersCompanion-Thor-build'})
 with urllib.request.urlopen(req,timeout=60) as response, apk.with_suffix('.part').open('wb') as out: shutil.copyfileobj(response,out)
 apk.with_suffix('.part').replace(apk)
if hashlib.sha256(apk.read_bytes()).hexdigest()!=SHA: raise SystemExit('Upstream APK checksum mismatch')
out=ROOT/'out/thor-integrated'; out.mkdir(parents=True,exist_ok=True)
# Only assets and unchanged native libraries. Never copy upstream resources, icons or compiled Java.
manifest={}
with zipfile.ZipFile(apk) as z:
 for entry in z.infolist():
  name=entry.filename
  if entry.is_dir(): continue
  if name.startswith('assets/'):
   rel=Path(name); target=out/rel
  elif name.startswith('lib/arm64-v8a/'):
   rel=Path('jniLibs')/Path(name).relative_to('lib'); target=out/rel
  else: continue
  if '..' in rel.parts or rel.is_absolute(): raise SystemExit('Unsafe archive member')
  data=z.read(entry); target.parent.mkdir(parents=True,exist_ok=True); target.write_bytes(data); manifest[str(rel)]=hashlib.sha256(data).hexdigest()
 payload=z.read('assets/runtime.payload')
 with tarfile.open(fileobj=io.BytesIO(payload),mode='r:gz') as t:
  for m in t.getmembers():
   n=m.name.lower()
   if any(word in n for word in ['eboot.bin','eboot.elf','libbbaot.so','lossless.dll','/savedata/']):raise SystemExit('Unexpected game-derived file: '+n)
(out/'runtime-inputs.json').write_text(json.dumps({'upstream_commit':UPSTREAM,'apk_sha256':SHA,'files':manifest},indent=2)+'\n')
print(f'Prepared {len(manifest)} pinned runtime assets/libraries; no upstream Java/resources copied.')

# AGP owns package/version/SDK attributes; retain the canonical manifest for upstream aapt scripts.
import xml.etree.ElementTree as ET
ns="{http://schemas.android.com/apk/res/android}"
ET.register_namespace("android", "http://schemas.android.com/apk/res/android")
tree=ET.parse(ROOT/'tools/android/frontend/AndroidManifest.xml')
r=tree.getroot()
for key in ['package',ns+'versionCode',ns+'versionName']: r.attrib.pop(key,None)
for child in list(r):
 if child.tag=='uses-sdk':r.remove(child)
tree.write(out/'AndroidManifest.xml',encoding='utf-8',xml_declaration=True)
