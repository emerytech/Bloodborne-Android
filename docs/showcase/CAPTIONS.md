# Bloodborne · Thor Companion — box-built native runtime on the AYN Thor (2026-11-02)

Captured on the AYN Thor (Snapdragon 8 Gen 2 / Adreno 740) running test build 2.2.0-thor.0.21.3-boxtest:
the companion app with libbbgpu / libbbcpu / libbbnet20 / libbloodframe / bb-probe rebuilt from source on a
Linux build box (gandalf LXC 101, NDK r29) instead of upstream's prebuilt payload.

01  Title screen rendered by the box-built libs — 40 FPS game / 65.5 FPS presented, GSR 2.
02  Companion (lower screen) at the title: "Awaiting your location" (no player position before a save loads).
03  In-world with the app drawer open — version string reads 2.2.0-thor.0.21.3-boxtest.
04  Companion Game Menu (Pause, Double-tap to pause, Game settings, Cheats, Mods, Thor companion, Hunter's Atlas).
05  Gameplay, Central Yharnam — 35 FPS game / 28.7 ms / 62.9 presented; game HUD: 20 vials, 16 bullets, 4304 echoes.
06  Companion Kit HUD — THE HUNTER: Vitality 594/594, stamina, 4,304 Blood Echoes, 20 Blood Vials, 16 Quicksilver,
    live inventory (Hunter Mark, Notebook, Iosefka Vial, Molotov ×2, Oil Urn ×2, Pebble ×19, Coldblood ×4 / ×1) —
    every number matches the game's own HUD, published by the fork's bb-probe built on the Linux box.
07  Both screens side by side (05 + 06).
