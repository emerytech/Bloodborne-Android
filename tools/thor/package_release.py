#!/usr/bin/env python3
"""Package the signed single APK plus tracked sources and matching native source inputs."""
import concurrent.futures, hashlib, io, json, shutil, subprocess, tarfile, urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'build/thor-release-0.7.0'; OUT.mkdir(parents=True,exist_ok=True)
CACHE=ROOT/'.local-deps/thor-source'; CACHE.mkdir(parents=True,exist_ok=True)
SOURCES={
 'ps4-pkg-tools-45baedaa.tar.gz':'https://codeload.github.com/xXJSONDeruloXx/ps4-pkg-tools/tar.gz/45baedaa29b8da42b3c6fe912800d2fa4119834b',
 'ffmpeg-7.1.5.tar.xz':'https://ffmpeg.org/releases/ffmpeg-7.1.5.tar.xz',
 'FEX-9fbdc00b.tar.gz':'https://codeload.github.com/FEX-Emu/FEX/tar.gz/9fbdc00bd6401aff3b32d79e78ff98b8a13e4dcf',
}
def fetch(item):
 name,url=item; dest=CACHE/name
 if not dest.exists():
  req=urllib.request.Request(url,headers={'User-Agent':'BloodborneThor-source-package'})
  with urllib.request.urlopen(req,timeout=90) as response,dest.with_suffix('.part').open('wb') as f:shutil.copyfileobj(response,f)
  dest.with_suffix('.part').replace(dest)
 # Parse archive now, never execute or unpack source downloads during packaging.
 with tarfile.open(dest) as t:
  if not t.getmembers():raise RuntimeError('Empty source archive: '+name)
 return name,{'url':url,'sha256':hashlib.sha256(dest.read_bytes()).hexdigest()}
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool: inputs=dict(pool.map(fetch,SOURCES.items()))
commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
if subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True).strip():raise SystemExit('Commit source changes before packaging.')
apk=ROOT/'build/outputs/apk/release/BloodborneThorIntegrated-release.apk'
name='Bloodborne-Thor-Companion-0.7.0-preview.apk'
shutil.copy2(apk,OUT/name)
# git archive only: no ignored game files, local builds, keys, caches, saves or logs.
source=OUT/'Bloodborne-Thor-Companion-0.7.0-source.tar.gz'
with tarfile.open(source,'w:gz') as out:
 def add_git(repo,prefix):
  data=subprocess.check_output(['git','archive','HEAD'],cwd=repo)
  with tarfile.open(fileobj=io.BytesIO(data)) as archive:
   for member in archive:
    member.name=prefix+member.name
    out.addfile(member,archive.extractfile(member) if member.isfile() else None)
 add_git(ROOT,'Bloodborne-Thor-Companion/')
 for path in ['gpu/third_party/fsr-vulkan','gpu/third_party/imgui','third_party/LibAtrac9','tools/recomp']:
  add_git(ROOT/path,'Bloodborne-Thor-Companion/'+path+'/')
 for filename in inputs:out.add(CACHE/filename,arcname='Bloodborne-Thor-Companion/third-party-source/'+filename)
 manifest={'source_commit':commit,'native_inputs':json.loads((ROOT/'out/thor-integrated/runtime-inputs.json').read_text()),'additional_source_archives':inputs}
 blob=(json.dumps(manifest,indent=2)+'\n').encode();info=tarfile.TarInfo('Bloodborne-Thor-Companion/RELEASE-SOURCE-INPUTS.json');info.size=len(blob);out.addfile(info,io.BytesIO(blob))
(OUT/'SOURCE-INPUTS.json').write_text(json.dumps(manifest,indent=2)+'\n')
shutil.copy2(ROOT/'out/thor-integrated/assets/THIRD_PARTY_LICENSES.txt',OUT/'THIRD_PARTY_LICENSES.txt')
shutil.copy2(ROOT/'COPYING-GPL-3.0',OUT/'COPYING-GPL-3.0.txt')
shutil.copy2(ROOT/'LICENSE',OUT/'COPYING-GPL-2.0.txt')
shutil.copy2(ROOT/'docs/THOR_INSTALL.md',OUT/'INSTALL.txt')
files=[p for p in OUT.iterdir() if p.is_file() and p.name!='SHA256SUMS.txt']
(OUT/'SHA256SUMS.txt').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name+'\n' for p in sorted(files)))
print('Packaged single APK, source archive, install instructions, licenses and SHA256SUMS under '+str(OUT))
