# Integrated Thor preview: implementation and build inputs

## Source baseline

Upstream android-2.2.0: `f241353e36f46bf183806acd7b79222afff2fd2f`.
The repository retains upstream runtime, compiler submodule, native build scripts, dependency fetch scripts, patches and third-party notices. `README.upstream.md` preserves its usage/credit documentation.

## What changed

- A single exported `ThorLaunchActivity` puts the game on display 0.
- `ThorCompanionScreen` is an in-process, non-focusable `Presentation` on the Thor’s Screen-2. It shows controls/artwork and reads `sce_sys/pic1.png` from the game’s selected folder.
- The game activity owns resume/stop/destroy. The panel dismisses when backgrounded and restores on resume. No accessibility service, usage-access permission, background app watcher or separate companion package is used.
- Existing Atlas remains responsible for live-map rendering. The companion yields when Atlas is installed and enabled on screen 2. Menu choices make switching deliberate.
- A new package isolates old saves/settings. No migration or deletion is performed.
- Fork releases/issues replace upstream update/report destinations. Automatic cross-package APK installation is removed.

## Pinned native runtime

Official APK: https://github.com/ducvd89/Bloodborne-Android/releases/download/android-2.2.0/Bloodborne-2.2.0.apk

SHA-256: `e97bed80872e2552ae175d9e1189def61c9a9f086bf64fd7d065602340a6ebc0`

`prepare_runtime.py` extracts only 21 assets/native-library entries and records their individual SHA-256 values in ignored `out/thor-integrated/runtime-inputs.json`. All are reused byte-for-byte. No game-derived AOT library or executable is allowed in the runtime payload. The frontend and neutral app icon are built from source.

The native rebuild compiler identifies itself as 1.3.0, commit `6ebb33b0c729`, ABI 2. Its matching source is the pinned `tools/recomp` submodule. Runtime/dependency build scripts remain under `tools/android`; GPU driver provenance/notices are in upstream fetch scripts and bundled notices. Reusing binaries is not a claim that they were rebuilt or independently audited here.

`prepare_dependencies.py` runs the upstream Maven resolver on first use, then requires an exact set/hash match against `frontend-dependencies.json`. JARs, downloaded APKs, keys, caches, game files and build outputs are ignored by Git.

API 28 targeting is preserved from upstream because native executable loading relies on it. This preview is intended for sideloading, not Google Play. Keep the upstream native/JNI Java package names: changing the Android application ID does not change JNI symbol bindings.

## Validation limits / release gate

Device evidence must distinguish app startup, native game rendering, lower-screen switching, background restoration, Atlas UI, and actual in-game live-map/controller testing. Title/menu startup does not prove combat or save migration. Do not distribute generated game code or personal saves.

The preview release ships a tracked-source archive including the four pinned submodules, matching FFmpeg 7.1.5, ps4-pkg-tools 45baedaa, and FEX 9fbdc00b source archives, native dependency fetch/build scripts and patches. RELEASE-SOURCE-INPUTS.json records source URLs/checksums and unchanged native input hashes. Original notices are also provided alongside the APK. The preview remains separate from a stable gameplay-validated release.

## Device check — 2026-10-31

Private signed release preview installed on an AYN Thor. Observed: original folder selected once; existing AOT imported; native title/attract screen rendered; controls/artwork switched on lower display; lower panel remained with standalone companion force-stopped; upper game retained focused-display ownership; home dismissed the lower panel to Cocoon and resume restored it. Atlas enabled on display 4, replacing the companion with its “Awaiting your location” screen, then disabling Atlas restored controls. No save was loaded and live in-game map tracking/combat have not been verified. Original game and companion remain installed. Full release lint passes after guarding upstream's API-30-only audio pipe sizing on older Android; intentional API-28 target and existing warnings remain.

The integrated game menu and Help link Couchside (https://couchside.tv, promoted as the ultimate living room gaming PC remote) and an optional Ko-fi tip for this Thor integration (https://ko-fi.com/ets3d), secondary to the upstream author’s existing PayPal badge by the title. No feature is gated; no payment SDK or purchase requirement is introduced.
