# Bloodborne · Thor Companion — integrated preview

A personal, unofficial fork of [ducvd89/Bloodborne-Android](https://github.com/ducvd89/Bloodborne-Android), based on **android-2.2.0**, with Hunter’s Companion built into the game app for the AYN Thor.

**One installed app, one launch icon, one game-folder selection.** The game runs above; controls or artwork appear below. Upstream already supports Hunter’s Atlas on the second screen: that feature is preserved, and the companion panel yields to it when enabled. Controls here are a reference, not touch buttons.

## Full disclosure

I had this companion integration written entirely with an LLM so I could play Bloodborne on my Thor quickly and easily. I’m only sharing it because others might want to do the same without repeating the work and spending their own tokens on it. This is an experimental personal project.

The Bloodborne Android port, compiler, runtime and Hunter’s Atlas are the work of their respective upstream contributors. The disclosure describes this integration; it does not claim authorship of their work. See [upstream documentation and credits](README.upstream.md).

## Setup

1. Install the integrated APK when available from this fork’s releases. Do not install an upstream APK over this fork.
2. Open **Bloodborne · Thor Companion**. Grant storage access and choose your own extracted **Bloodborne 1.09** folder containing `eboot.bin`, `sce_module`, and `sce_sys`.
3. Existing compatible `aot/` code is imported automatically. Otherwise follow upstream’s on-device or PC rebuild instructions using your own game files.
4. Use the lower screen’s **Controls**, **Artwork**, and **Help** buttons. The game menu’s **Thor companion** entry also offers hide/show and **Hunter’s Atlas map**. Choose controls/artwork in that menu to switch Atlas off; re-enable the map through Hunter’s Atlas whenever you want it.

No game, saves, game artwork, generated game code, or Lossless Scaling DLL is included. Artwork comes directly from your selected game folder. This is not an official Sony or FromSoftware product.

### Existing installations and saves

This preview uses **com.emerytech.bloodbornethor**, a separate package from upstream and the standalone companion. Existing apps and their private saves remain installed and untouched. **It does not automatically import your existing save.** Do not remove the working game app to make room for it. Save migration is not part of this preview.

## Build from source

Requires JDK 17 or 21, Python 3, Android SDK platform/build-tools 35, network access and an arm64 Android device. Set `JAVA_HOME` and `ANDROID_HOME`, then:

```sh
git clone --recurse-submodules --branch codex/integrated-thor-companion https://github.com/emerytech/Bloodborne-Android.git
cd Bloodborne-Android
./tools/thor/build.sh assembleDebug
```

The build recompiles all frontend Java from this repository. It reuses only checksum-pinned native runtime assets/libraries from official upstream 2.2.0, and verifies all 38 upstream JVM dependency JARs against a tracked SHA-256 lock. It does not extract upstream Java classes or resources. See [build inputs and licensing](docs/THOR_INTEGRATION.md).

A signed release requires `SIGNING_KEYSTORE`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, and `SIGNING_KEY_PASSWORD`, then `./tools/thor/build.sh assembleRelease`. Without signing variables Gradle produces an unsigned release. Keep keys and passwords out of Git.

## Sponsor

The app menu and companion Help include a **Sponsor · Couchside** link to [couchside.tv](https://couchside.tv) for optional Couchside unlock purchases. Checkout opens in your browser.

## License and credits

The combined application is **GPL-3.0-or-later** as described upstream; new integration files use that license. The original [GPL-2.0 license text](LICENSE) is retained, alongside [GPL-3.0](COPYING-GPL-3.0) and upstream [third-party notices](tools/android/frontend/THIRD_PARTY_LICENSES.txt). Original source headers, notices and credits remain intact. This fork is not affiliated with the upstream authors or game publishers.
