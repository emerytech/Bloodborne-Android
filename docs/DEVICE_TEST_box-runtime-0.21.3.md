# Device test: box-built native runtime on the AYN Thor (2026-11-02)

Build under test: `Bloodborne-Thor-2.2.0-thor.0.21.3-boxtest.apk` (not committed; sha256 `145b1823…`) = the 0.21.3 companion app
with the pinned upstream 2.2.0 runtime payload, except `bbport/bionic/{bin,lib}` replaced by the gandalf LXC 101
build (`tools/android/build_bionic.sh` at `codex/integrated-thor-companion` 0.21.3 with the #16 fixes), stripped like
`build_release_runtime.py` (`llvm-strip --strip-debug`). Per-file provenance: `showcase/box-runtime-swap.json`.
Signed with the release key (cert `f5caf93b…`), so `adb install -r` kept saves and settings.

Device: AYN Thor (Snapdragon 8 Gen 2 / Adreno 740), adb `5d7c27e7`, app `com.emerytech.bloodbornethor`,
previously 2.2.0-thor.0.21.3 (installed 2026-11-01 23:41).

## Byte comparison against upstream's shipped payload (after --strip-debug)

| file | upstream | box | identical |
|---|---|---|---|
| bin/bb-prepare | 348632 | 348632 | yes |
| bin/bb-probe | 431144 | 435648 | no — built from this fork's `src/` (live stats publisher); expected |
| bin/bb-texpack | 444024 | 444024 | yes |
| bin/kgsl-chip-id | 6560 | 6560 | yes |
| bin/ps4-pkg-tool | 970576 | 970576 | same size, different bytes (build path / BuildID) |
| lib/libbbcpu.so | 123536 | 123536 | same size, different bytes |
| lib/libbbgpu.so | 32628456 | 32623648 | −0.015 % |
| lib/libbbnet20.so | 11756616 | 11756440 | −0.001 % |
| lib/libbloodframe.so | 1271496 | 1271496 | same size, different bytes |
| lib/libc++_shared.so | 1813976 | 1813976 | yes |

The fork has no commits in `gpu/`, `cpu/` or `net/` since the upstream pin (`f241353e`), so these libraries are built
from the same source as upstream's; the test is whether the gandalf toolchain's output is a faithful drop-in.

## Done

| check | result |
|---|---|
| Pre-run backup | newest app autosave pulled: `device-backup/Bloodborne-autosave-20261102-000458/` (15 files, 14 MB) |
| Install over 0.21.3 (`adb install -r`) | Success; versionName now `2.2.0-thor.0.21.3-boxtest`, signatures unchanged |
| Launch (`am start --display 0 … ThorLaunchActivity`) | runtime re-extracted (checksum accepted); `bb-probe` running (RSS 2.26 GB) ~35 s after start |
| Title screen | rendered by the box-built libs: Play Online / Play Offline; overlay 65.5 FPS presented, 40 FPS game @ 25.1 ms, GSR 2 (`showcase/01-title-screen-box-built-runtime.png`) |
| Companion (display 4) | "Awaiting your location" — correct before a save is loaded (`showcase/02-companion-awaiting-location-at-title.png`) |
| logcat | no `STOP:`, no FATAL / SIGSEGV, no "Runtime archive checksum mismatch"; only the `bb:MemWatch` `/proc/pressure/memory` avc notes (permissive, benign) |
| In-world (user loaded a save, 09:50) | Central Yharnam renders on the box-built libs; game HUD shows 4304 echoes; badge 40 FPS game / 25.2 ms / GSR 2, 66.5 FPS presented (`showcase/03-in-world-drawer-shows-boxtest-version.png`); the app drawer reads "Thor integrated 2.2.0-thor.0.21.3-boxtest" |
| Process stability | same `bb-probe` pid across title → in-world → Kit check (no restart), RSS 3.8 GB in-world; logcat re-scans clean |
| **Kit HUD (display 4) — the fork's bb-probe publisher under the box build** | **PASS.** THE HUNTER: Vitality 594/594, stamina full, Blood Echoes **4,304**, Blood Vials **20**, Quicksilver **16**, insight "—" (known-unvalidated field, game shows 0), inventory enumerated (Hunter Mark 1, Notebook 1, Iosefka Vial 1, Molotov 2, Oil Urn 2, Pebble 19, Coldblood 4 + 1). Every value matches the game's own HUD in the same instant (20 / 16 / 4304 / 0) — `showcase/06-companion-kit-hud-live-stats.png` + `showcase/05-gameplay-central-yharnam-35fps.png` (side by side: `showcase/07-dual-screen-gameplay-plus-kit-hud.png`) |
| Gameplay in motion | Central Yharnam, moving: 35 FPS game / 28.7 ms / GSR 2, 62.9 FPS presented |

**Verdict: the gandalf-built runtime is a working drop-in on the Thor** — boot, title, gameplay, and the fork's own
bb-probe (the only binary that differs from upstream's) publishing correct live stats. Hook work can start from this
as a known-good baseline.

## Not done (optional)

~10–15 min of sustained play with menus / Quit → Continue was left to the player; one PKG mod install (box
`ps4-pkg-tool`), frame-gen x2 with Lossless.dll (box `libbloodframe`), Play Online (box `libbbnet20`).

## Showcase images

`showcase/` — captions in `showcase/CAPTIONS.md`.

## Restore

`adb -s 5d7c27e7 install -r build/thor-release-0.21.3/<0.21.3 apk>` (same key; data kept).
