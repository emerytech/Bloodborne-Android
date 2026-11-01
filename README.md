# Bloodborne · Thor Companion — integrated preview

A personal, unofficial fork of [ducvd89/Bloodborne-Android](https://github.com/ducvd89/Bloodborne-Android), based on **android-2.2.0**, with Hunter’s Companion built into the game app for the AYN Thor.

**One installed app, one launch icon, one game-folder selection.** The game runs on the upper screen. The lower screen is a touchscreen companion with tabs: the full game **Menu**, a **Controls** reference, **Artwork**, live **Status** (CPU, RAM, battery, temperature and a brightness slider) and **Couchside**. The top swipe-in drawer stays available too, so the game menu is reachable from either screen. Upstream Hunter’s Atlas on the second screen is preserved, and the companion panel yields to it when enabled. The listed control glyphs are a reference, not touch buttons.

## Full disclosure

I had this companion integration written entirely with an LLM so I could play Bloodborne on my Thor quickly and easily. I’m only sharing it because others might want to do the same without repeating the work and spending their own tokens on it. This is an experimental personal project.

The Bloodborne Android port, compiler, runtime and Hunter’s Atlas are the work of their respective upstream contributors. The disclosure describes this integration; it does not claim authorship of their work. See [upstream documentation and credits](README.upstream.md).

## Setup

1. Install **[Bloodborne-Thor-Companion-0.8.0-preview.apk](https://github.com/emerytech/Bloodborne-Android/releases/download/thor-v0.8.0-preview.1/Bloodborne-Thor-Companion-0.8.0-preview.apk)** from the [integrated preview release](https://github.com/emerytech/Bloodborne-Android/releases/tag/thor-v0.8.0-preview.1). Do not install an upstream APK over this fork.
2. Open **Bloodborne · Thor Companion**. Grant storage access and choose your own extracted **Bloodborne 1.09** folder containing `eboot.bin`, `sce_module`, and `sce_sys`.
3. Existing compatible `aot/` code is imported automatically. Otherwise follow upstream’s on-device or PC rebuild instructions using your own game files.
4. The lower screen is a touchscreen. **Artwork is the home view**; tap a tab (**Menu**, **Controls**, **Status** or **Couchside**) to open it and tap the same tab again to close back to the artwork. **Help** opens a dialog. The game menu’s **Thor companion** entry still offers hide/show and the **Hunter’s Atlas map**; enabling the map switches the panel off until you turn the map off again.

No game, saves, game artwork, generated game code, or Lossless Scaling DLL is included. Artwork comes directly from your selected game folder. This is not an official Sony or FromSoftware product.

### Existing installations and saves

This preview uses **com.emerytech.bloodbornethor**, a separate package from upstream and the standalone companion. Existing apps and their private saves remain installed and untouched. **It does not automatically import your existing save.** Do not remove the working game app to make room for it. Save migration is not part of this preview.

See the [step-by-step install guide](docs/THOR_INSTALL.md). Only the APK is installed; source and license downloads are optional reference files.

## Build from source

Requires JDK 17 or 21, Python 3, Android SDK platform/build-tools 35, network access and an arm64 Android device. Set `JAVA_HOME` and `ANDROID_HOME`, then:

```sh
git clone --recurse-submodules --branch codex/integrated-thor-companion https://github.com/emerytech/Bloodborne-Android.git
cd Bloodborne-Android
./tools/thor/build.sh assembleDebug
```

The build recompiles all frontend Java from this repository. It reuses only checksum-pinned native runtime assets/libraries from official upstream 2.2.0, and verifies all 38 upstream JVM dependency JARs against a tracked SHA-256 lock. It does not extract upstream Java classes or resources. See [build inputs and licensing](docs/THOR_INTEGRATION.md).

A signed release requires `SIGNING_KEYSTORE`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, and `SIGNING_KEY_PASSWORD`, then `./tools/thor/build.sh assembleRelease`. Without signing variables Gradle produces an unsigned release. Keep keys and passwords out of Git.

## Mods

Open **Mods → Browse / download mods** to search the starter catalog. Direct downloads are checksum-checked, preview shared files, and install switched off. Author-page entries use the website’s normal download flow and the browser’s Import archive action. All starter mods are marked not gameplay-tested on Thor. [Details, credits and limits](docs/THOR_MOD_BROWSER.md).

## Support and Couchside

This fork is free. If the upstream port helps you, support its author ducvd89 via the primary **Buy me a Claude token** badge by the title (PayPal). A smaller secondary badge offers an optional **[Ko-fi](https://ko-fi.com/ets3d)** tip for this Thor integration. The second-screen companion panel has a **[Couchside](https://couchside.tv)** tab that shows a scannable QR on demand — the ultimate living-room gaming PC remote — and, because the lower screen is a touchscreen, the card also opens couchside.tv on tap. The app menu and companion Help link it too. No purchase is required and nothing is gated. Links open in your browser.

## License and credits

The combined application is **GPL-3.0-or-later** as described upstream; new integration files use that license. The original [GPL-2.0 license text](LICENSE) is retained, alongside [GPL-3.0](COPYING-GPL-3.0) and upstream [third-party notices](tools/android/frontend/THIRD_PARTY_LICENSES.txt). Original source headers, notices and credits remain intact. This fork is not affiliated with the upstream authors or game publishers.
