#!/usr/bin/env bash
# Recompile bb-probe from THIS project's src/ and splice it into the upstream runtime.payload, then
# refresh runtime.sha256 so the in-app installer accepts the result.
#
# This is a deliberate, documented change to the upstream native payload — not a verbatim re-ship.
# bb-probe is the port's small runtime executable (src/probe.c + src/runtime_*.c). We rebuild it so the
# companion's Atlas publisher (src/runtime_atlas.c) can also write the player's live HP and blood echoes
# into the shared block the Thor companion HUD reads. Everything else in the payload — the prebuilt
# libbbgpu/libbbcpu/libbbnet20, the game data path, bb-prepare, ps4-pkg-tool, the scripts — is left
# exactly as ducvd89 shipped it; bb-probe links against those same prebuilt libraries, so the game
# itself runs identically. The rest of the native stack is NOT recompiled here (build_bionic.sh does
# that from a Linux host); this step only relinks bb-probe with the host NDK, which is enough because
# bb-probe depends only on the NDK sysroot plus the payload's own libbbgpu/libbbcpu.
#
# Run after tools/thor/prepare_runtime.py (which stages the pinned upstream payload under out/).
# Honors BB_NDK (an explicit NDK dir) and BB_ANDROID_API (default 30).
set -euo pipefail
cd -- "$(dirname -- "$0")/../.."
assets=out/thor-integrated/assets
payload=$assets/runtime.payload
[ -f "$payload" ] || { echo "No $payload — run tools/thor/prepare_runtime.py first" >&2; exit 1; }

# Host NDK that targets Android arm64.
ndk=${BB_NDK:-}
if [ -z "$ndk" ]; then
  for c in "$HOME/Library/Android/sdk/ndk" "$HOME/Android/Sdk/ndk" "${ANDROID_HOME:-}/ndk" "${ANDROID_NDK_HOME:-}"; do
    [ -n "$c" ] && [ -d "$c" ] || continue
    if [ -d "$c/toolchains" ]; then ndk=$c; break; fi
    pick=$(ls -d "$c"/* 2>/dev/null | sort -V | tail -1 || true)
    [ -n "$pick" ] && { ndk=$pick; break; }
  done
fi
[ -n "$ndk" ] && [ -d "$ndk" ] || { echo "No Android NDK found (set BB_NDK)" >&2; exit 1; }
case "$(uname -s)" in Darwin) hosttag=darwin-x86_64;; *) hosttag=linux-x86_64;; esac
api=${BB_ANDROID_API:-30}
bin=$ndk/toolchains/llvm/prebuilt/$hosttag/bin
clang=$bin/aarch64-linux-android$api-clang
[ -x "$clang" ] || { echo "No NDK clang: $clang" >&2; exit 1; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
tar xzf "$payload" -C "$work"
lib=$work/bbport/bionic/lib
probe=$work/bbport/bionic/bin/bb-probe
[ -f "$lib/libbbgpu.so" ] && [ -f "$lib/libbbcpu.so" ] || { echo "payload missing libbbgpu/libbbcpu" >&2; exit 1; }
[ -f "$probe" ] || { echo "payload missing bbport/bionic/bin/bb-probe" >&2; exit 1; }

# libatrac9.a — bb-probe decodes ATRAC9 (src/runtime_audio.c); same sources the upstream build uses.
atr=$work/.atrac9; mkdir -p "$atr"
for s in third_party/LibAtrac9/C/src/*.c; do
  "$clang" -std=c99 -O2 -fPIC -w -c "$s" -o "$atr/$(basename "${s%.c}").o"
done
"$bin/llvm-ar" rcs "$work/.libatrac9.a" "$atr"/*.o

# bb-probe: PIE, runtime_* symbols exported for libbbgpu to resolve against the executable (-rdynamic),
# the payload's libs found at run time through $ORIGIN/../lib. Same recipe as build_bionic.sh's final
# link, minus the Linux deps prefix (bb-probe needs only the NDK sysroot and these two libraries).
"$clang" -std=c11 -O2 -g -Wall -Wextra -Werror -pthread -I. -Isrc \
  src/probe.c src/runtime*.c src/vulkan_smoke.c "$work/.libatrac9.a" -lm \
  -L"$lib" -lbbgpu -lbbcpu -Wl,-rpath,'$ORIGIN/../lib' -rdynamic -pie \
  -o "$probe"
chmod 755 "$probe"
file "$probe" | grep -q "ARM aarch64" || { echo "rebuilt bb-probe is not ARM aarch64" >&2; exit 1; }

# Ship THIS fork's default settings, not upstream's copy baked into the payload. SettingsDefaults.java
# applies bbport.default.ini on a first install (and once on update), and the Thor graphics-preset defaults
# (Balanced 40, etc.) live in tools/android/thor.ini — the prepare_runtime path would otherwise keep
# ducvd89's defaults. build_release_runtime.py maps the same file; this keeps the prepare_runtime path in step.
if [ -f tools/android/thor.ini ] && [ -f "$work/bbport/bbport.default.ini" ]; then
  cp -f tools/android/thor.ini "$work/bbport/bbport.default.ini"
  chmod 644 "$work/bbport/bbport.default.ini"
  echo "  bbport.default.ini <- tools/android/thor.ini (fork defaults)"
fi

# Repack (ustar: the device extracts with toybox `tar -oxzf`). Drop the build scratch first.
rm -rf "$atr" "$work/.libatrac9.a"
( cd "$work" && tar --format=ustar -czf - bbport ) > "$payload.new"
mv -f "$payload.new" "$payload"
shasum -a 256 "$payload" | awk '{print $1}' > "$assets/runtime.sha256"
echo "Integrated rebuilt bb-probe into $payload"
echo "  bb-probe: $(cd "$work" && file bbport/bionic/bin/bb-probe | cut -d: -f2- | sed 's/^ //')"
echo "  runtime.sha256: $(cat "$assets/runtime.sha256")"
