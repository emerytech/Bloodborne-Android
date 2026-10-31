Bloodborne · Thor Companion 0.5.0 — integrated preview

Install ONE file: Bloodborne-Thor-Companion-0.5.0-preview.apk.
The source archive and license files are for reference; do not install/unpack those on your Thor.

1. Download the APK onto your AYN Thor and open it. Allow installation from that browser/file manager if Android asks.
2. Launch “Bloodborne · Thor Companion”. Grant storage permission and choose YOUR OWN extracted Bloodborne 1.09 folder (eboot.bin, sce_module and sce_sys).
3. Compatible native game code in aot/ is imported automatically. If absent, use the app’s “Build on this phone” flow or upstream’s PC Rebuild Tool with your own game files. The on-phone build can take several minutes and use substantial memory, battery and produce heat. See README.upstream.md for the full guide.
4. The game opens above. Hunter’s Companion controls/artwork appear below. Tap Controls, Artwork or Help on the lower screen.
5. To use the built-in map instead: game side menu → Thor companion → Hunter’s Atlas map → enable Map. Its optional map pack downloads separately. The companion yields to Atlas. Choose Show controls below / Show artwork below in Thor companion to switch back.
6. Mods → Browse / download mods adds search, direct downloads and archive previews. New browser installs stay OFF until enabled in Mods and applied/restarted. Website entries require the author’s download flow, then Browse → Import archive. All starter mods have unverified Thor gameplay compatibility.
7. Game menu / Help link Couchside (couchside.tv, the ultimate living room gaming PC remote) and an optional Ko-fi tip (ko-fi.com/ets3d). The title badge links ducvd89’s PayPal. Nothing is gated; no purchase required.

IMPORTANT
- No game files, game-derived AOT library, saves, or game artwork are included in this APK. Supply your own game. No separate companion APK is needed.
- Package: com.emerytech.bloodbornethor. It installs alongside upstream Bloodborne and the old companion. Existing private saves/settings are NOT imported. Keep your working app installed; do not delete its data.
- This is an experimental, unofficial arm64 Android/AYN Thor preview. Native title startup, lower screen switching, Atlas handoff, background/resume and sponsor navigation were checked. Live in-game Atlas tracking, combat and save migration were not verified in this release.
- No Play Store release. Check SHA256SUMS.txt if verifying a download. Updates must come from this fork and use its signing key; upstream APKs are separate apps.

FULL DISCLOSURE
I had this companion integration written entirely with an LLM so I could play Bloodborne on my Thor quickly and easily. I am sharing it because others might want to do the same without repeating the work and spending their own tokens. The Bloodborne Android port/compiler/runtime and Hunter’s Atlas are the work of their respective upstream contributors; this disclosure does not claim authorship of their work.

Source and releases: https://github.com/emerytech/Bloodborne-Android
Upstream: https://github.com/ducvd89/Bloodborne-Android
The combined app is GPL-3.0-or-later; source, license texts and third-party notices accompany the APK.
