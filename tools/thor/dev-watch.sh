#!/usr/bin/env bash
# Dev loop: rebuild the signed release and install it on the connected Thor whenever a frontend
# source file changes. The release is signed with the same key the installed app uses, so it
# upgrades IN PLACE (adb install -r) and keeps the game folder, settings and saves.
#
# Usage:  tools/thor/dev-watch.sh [--no-launch] [--once]
#   --no-launch   install only; don't relaunch the app after each build
#   --once        build + install a single time, then exit (no watching)
#
# Needs: a Thor on adb (`adb devices`), JDK 17, Android SDK, and the signing files under
# ~/.local/share/hunters-companion-signing. Uses fswatch when present, else polls.
set -euo pipefail
cd "$(dirname "$0")/../.."

LAUNCH=1; ONCE=0
for a in "$@"; do
  case "$a" in
    --no-launch) LAUNCH=0 ;;
    --once) ONCE=1 ;;
    *) echo "unknown option: $a" >&2; exit 2 ;;
  esac
done

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
ADB="$ANDROID_HOME/platform-tools/adb"
APK="build/outputs/apk/release/BloodborneThorIntegrated-release.apk"
PKG="com.emerytech.bloodbornethor"
LAUNCH_ACT="$PKG/ro.cobrabm.fexdroid.ThorLaunchActivity"

SIGN_DIR="$HOME/.local/share/hunters-companion-signing"
if [ ! -f "$SIGN_DIR/github-release.jks" ] || [ ! -f "$SIGN_DIR/store-password" ]; then
  echo "signing files not found under $SIGN_DIR — needed to install in place (same key)." >&2
  exit 1
fi
export SIGNING_KEYSTORE="$SIGN_DIR/github-release.jks"
export SIGNING_STORE_PASSWORD="$(tr -d '\r\n' < "$SIGN_DIR/store-password")"
export SIGNING_KEY_ALIAS=preview
export SIGNING_KEY_PASSWORD="$SIGNING_STORE_PASSWORD"

if ! "$ADB" get-state >/dev/null 2>&1; then
  echo "no device on adb — connect the Thor (USB or wireless) and check 'adb devices'." >&2
  exit 1
fi

# Keep the Thor awake while USB-connected during the session; restore on exit.
"$ADB" shell svc power stayon usb >/dev/null 2>&1 || true
cleanup() { "$ADB" shell svc power stayon false >/dev/null 2>&1 || true; }
trap cleanup EXIT INT TERM

WATCH="tools/android/frontend/src tools/android/frontend/res tools/thor/assets build.gradle tools/android/frontend/AndroidManifest.xml"

build_install() {
  local t0; t0=$(date +%s)
  echo "▶ $(date +%H:%M:%S) building release…"
  if ! ./tools/thor/build.sh assembleRelease; then
    echo "✗ build failed — fix and save again"; return 1
  fi
  if "$ADB" install -r "$APK" >/tmp/thor-install.log 2>&1; then
    echo "✓ installed in $(( $(date +%s) - t0 ))s"
    [ "$LAUNCH" = 1 ] && "$ADB" shell am start -n "$LAUNCH_ACT" >/dev/null 2>&1 || true
  else
    echo "✗ install failed:"; tail -2 /tmp/thor-install.log
  fi
}

build_install || true
[ "$ONCE" = 1 ] && exit 0

echo "watching for changes (Ctrl-C to stop)…"
if command -v fswatch >/dev/null 2>&1; then
  # Low-latency: fswatch coalesces bursts with -o; the read debounces further.
  # shellcheck disable=SC2086
  fswatch -o -l 1 $WATCH | while read -r _; do build_install || true; done
else
  # Portable polling: rebuild when any watched file is newer than the last build stamp.
  STAMP="$(mktemp)"; trap 'cleanup; rm -f "$STAMP"' EXIT INT TERM
  touch "$STAMP"
  while true; do
    # shellcheck disable=SC2086
    if [ -n "$(find $WATCH -type f -newer "$STAMP" 2>/dev/null | head -1)" ]; then
      build_install || true
      touch "$STAMP"
    fi
    sleep 1.5
  done
fi
