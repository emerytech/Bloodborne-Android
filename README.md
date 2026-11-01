# Bloodborne · Thor Companion — integrated preview

A personal, unofficial fork of [ducvd89/Bloodborne-Android](https://github.com/ducvd89/Bloodborne-Android), based on **android-2.2.0**, that builds Hunter's Companion into the game app for the **AYN Thor**. One installed app, one launch icon, one game-folder selection: the game runs on the upper screen and a touchscreen companion runs on the lower screen.

![The lower-screen companion: tabs for the game menu, controls, artwork, a live status readout with a frame-time graph, and a Tune tab for the on-game HUD and graphics presets](https://github.com/emerytech/Bloodborne-Android/releases/download/thor-v0.10.0-preview.1/thor-0.10-panel.gif)

> **This is not a copy of the game.** No game, saves, game artwork, generated game code, or Lossless Scaling DLL is included. You provide your own game files, extracted from your own legally-owned disc. This is not an official Sony or FromSoftware product.

---

## Full disclosure

I had this companion integration written entirely with an LLM so I could play Bloodborne on my Thor quickly and easily. I'm only sharing it because others might want to do the same without repeating the work and spending their own tokens on it. This is an experimental personal project.

The Bloodborne Android port, compiler, runtime and Hunter's Atlas are the work of their respective upstream contributors. The disclosure describes this integration; it does not claim authorship of their work. See [upstream documentation and credits](README.upstream.md).

---

## What you need

| | |
|---|---|
| **Device** | An **AYN Thor** (dual-screen Android handheld). The build targets its two screens; it runs on other Android devices but the second-screen companion only appears on a display named `Screen-2`. |
| **Game files** | Your own **Bloodborne 1.09** files — a folder containing `eboot.bin`, `sce_module/` and `sce_sys/` — extracted from a disc **you own**. None are included here. |
| **Storage** | Free space for your extracted game files plus the on-device compiled code (several GB). |
| **The app** | The APK from the [latest preview release](https://github.com/emerytech/Bloodborne-Android/releases/latest). |

---

## Setup

### 1. Install the app

1. Download **[Bloodborne-Thor-Companion-0.10.0-preview.apk](https://github.com/emerytech/Bloodborne-Android/releases/download/thor-v0.10.0-preview.1/Bloodborne-Thor-Companion-0.10.0-preview.apk)** from the [latest release](https://github.com/emerytech/Bloodborne-Android/releases/latest) onto your Thor.
2. Open it and install. Android will ask you to allow installing from your browser or file manager the first time — allow it, then install.
3. **Do not install an upstream APK over this fork.** This build uses its own package (`com.emerytech.bloodbornethor`), so it sits beside any existing Bloodborne app without touching it.

> Upgrading from an earlier preview? Just install the new APK over it. It's signed with the same key, so it upgrades in place and keeps its settings and saves.

### 2. Put your game files on the device

Copy your own extracted **Bloodborne 1.09** folder somewhere on the Thor's storage, for example `Download/Bloodborne` or `Games/Bloodborne`. The folder must contain:

```
eboot.bin
sce_module/
sce_sys/          (artwork is read from here — pic1.png becomes the companion background)
```

This fork ships **no game files**. Dumping and extracting your own disc is covered by upstream; follow [ducvd89/Bloodborne-Android](https://github.com/ducvd89/Bloodborne-Android).

### 3. First launch — pick the folder

1. Open **Bloodborne · Thor Companion**.
2. Grant storage access when asked.
3. Choose the game folder from step 2 (the one with `eboot.bin`).

### 4. Build the native code (first run only)

If compatible precompiled `aot/` code is already in your folder, it's imported automatically. Otherwise the app compiles the game's code — this is a one-time step that takes a while on-device, or you can rebuild on a PC using your own files per upstream's instructions. Once it finishes, the game boots to its title screen on the upper screen.

### 5. Play — two screens at once

The game runs on the **upper** screen with your physical controls. The **lower** screen is the touchscreen companion described below.

---

## Using the two screens

The lower screen is a touchscreen. **Artwork is the home view** — tap a tab to open it, tap the same tab again to close back to the artwork. The top swipe-in drawer on the upper screen stays available too, so the game menu is reachable from either screen.

| Tab | What it does |
|---|---|
| **Menu** | The full in-game menu — settings, cheats, mods, saves, online, graphics, controls — mirrored onto the bottom touchscreen. Dialogs open on the upper screen. |
| **Controls** | A quick reference for the Bloodborne button layout (combat, parry/rally, movement, menus, tips). The glyphs are a reference, not touch buttons. |
| **Artwork** | The home view: the game's own key art, read from your `sce_sys` folder. |
| **Status** | A live readout, refreshed every second: FPS with a rolling average and 1% low, a frame-time graph (with 60 and 30 FPS guide lines), CPU, RAM, battery, and SoC temperature — which turns amber, then red, and warns when the chip runs hot — plus a clock and a brightness slider for the lower screen. |
| **Tune** | The on-game HUD toggle and one-tap graphics presets. The HUD is a small FPS/temperature overlay on the **upper** (game) screen — off, FPS only, or full (adds average, 1% low and temperature). The presets — **Performance**, **Balanced**, **Quality** — apply resolution, upscaler, FPS cap and frame-generation in one tap and restart the game. |
| **Help** | Full disclosure, a short how-it-works note, and a one-tap link to Couchside. |

| Live status readout + frame-time graph | Tune tab: on-game HUD + graphics presets |
|---|---|
| ![FPS with average and 1% low, CPU, RAM, battery, SoC temperature and a live frame-time graph](https://github.com/emerytech/Bloodborne-Android/releases/download/thor-v0.10.0-preview.1/thor-0.10-status.png) | ![The Tune tab: on-game HUD toggle and Performance / Balanced / Quality graphics presets](https://github.com/emerytech/Bloodborne-Android/releases/download/thor-v0.10.0-preview.1/thor-0.10-tune.png) |

| Game menu on the lower screen | On-game HUD on the upper screen |
|---|---|
| ![Game menu mirrored to the bottom touchscreen](https://github.com/emerytech/Bloodborne-Android/releases/download/thor-v0.10.0-preview.1/thor-0.10-menu.png) | ![A small FPS, average, 1% low and temperature overlay in the upper-right of the game screen](https://github.com/emerytech/Bloodborne-Android/releases/download/thor-v0.10.0-preview.1/thor-0.10-game-hud.png) |

Upstream's **Hunter's Atlas** second-screen map is preserved. The game menu's **Thor companion** entry offers hide/show and the Atlas map; turning the map on hands the lower screen to Atlas until you turn it off again.

---

## Saves and backups

This preview uses **`com.emerytech.bloodbornethor`**, a separate package from upstream and the standalone companion, so existing apps and their private saves remain installed and untouched. **It does not automatically import your existing save**, and save migration is not part of this preview — don't remove your working game app to make room for it.

To back up this app's own saves, open the game menu (top drawer or the lower-screen **Menu** tab) → **Back up saves**. It copies them to a timestamped `Bloodborne-saves-…` folder in your **Download** folder, leaving the live saves untouched. **Restore saves** replaces them from a backup folder.

See the [step-by-step install guide](docs/THOR_INSTALL.md) for more detail.

---

## Updating

The game menu's **Check for updates** (top drawer, or the lower-screen **Menu** tab) checks GitHub for a newer preview, shows its release notes, downloads the APK with a progress bar, verifies its SHA-256, and installs it **in place** through Android's package installer. Every preview is signed with the same key, so it upgrades over your current build and keeps your game folder, settings and saves. Android asks once to allow this app to install updates.

---

## Mods

Open **Mods → Browse / download mods** to search the starter catalog. Direct downloads are checksum-checked, preview their shared files, and install switched off. Author-page entries use the website's normal download flow and the browser's Import archive action. All starter mods are marked not gameplay-tested on Thor. [Details, credits and limits](docs/THOR_MOD_BROWSER.md).

---

## Troubleshooting

- **The lower screen is blank / shows nothing.** The companion only appears while the game activity is in front and only on a display named `Screen-2`. Leave and re-enter the game to bring it back. If Hunter's Atlas is enabled, it takes over the lower screen — turn it off from the game menu's **Thor companion** entry.
- **"No game folder" or a black game screen.** Re-pick the folder from the game menu → **Game folder**, and make sure it's the folder that directly contains `eboot.bin`.
- **The first launch sits on a build/compile step.** That's the one-time native build. Leave it to finish, or rebuild on a PC per upstream and copy the `aot/` code back.
- **Don't install over the upstream APK.** If you installed an upstream build on top, uninstall it and install this fork's APK (your separate saves aren't affected).

---

## Build from source

Requires JDK 17 or 21, Python 3, Android SDK platform/build-tools 35, network access and an arm64 Android device. Set `JAVA_HOME` and `ANDROID_HOME`, then:

```sh
git clone --recurse-submodules https://github.com/emerytech/Bloodborne-Android.git
cd Bloodborne-Android
./tools/thor/build.sh assembleDebug
```

To iterate on a device, `tools/thor/dev-watch.sh` rebuilds the signed release and installs it on a connected Thor (`adb install -r`) on every frontend source change — in place, keeping your saves. It uses `fswatch` when available, otherwise polls; pass `--once` for a single build or `--no-launch` to skip the relaunch.

The build recompiles all frontend Java from this repository. It reuses only checksum-pinned native runtime assets/libraries from official upstream 2.2.0, and verifies all 38 upstream JVM dependency JARs against a tracked SHA-256 lock. It does not extract upstream Java classes or resources. See [build inputs and licensing](docs/THOR_INTEGRATION.md).

A signed release requires `SIGNING_KEYSTORE`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, and `SIGNING_KEY_PASSWORD`, then `./tools/thor/build.sh assembleRelease`. Without signing variables Gradle produces an unsigned release. Keep keys and passwords out of Git.

---

## Support and Couchside

This fork is free. If the upstream port helps you, support its author **ducvd89** via the primary **Buy me a Claude token** badge by the title (PayPal). A smaller secondary badge offers an optional **[Ko-fi](https://ko-fi.com/ets3d)** tip for this Thor integration. **[Couchside](https://couchside.tv)** — the ultimate living-room gaming PC remote — opens in one tap from the game **Menu** (and from the lower-screen **Help** tab). No purchase is required and nothing is gated. Links open in your browser.

---

## License and credits

The combined application is **GPL-3.0-or-later** as described upstream; new integration files use that license. The original [GPL-2.0 license text](LICENSE) is retained, alongside [GPL-3.0](COPYING-GPL-3.0) and upstream [third-party notices](tools/android/frontend/THIRD_PARTY_LICENSES.txt). Original source headers, notices and credits remain intact. This fork is not affiliated with the upstream authors or game publishers.
