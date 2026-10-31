# Thor mod browser

Open the game menu → Mods → Browse / download mods. Search matches names, authors, categories and descriptions. “Direct downloads only” hides entries that require a website visit. The bundled catalog works offline; Refresh catalog explicitly fetches a bounded, schema-checked copy from this fork. A failed refresh retains the existing list.

## Download and installation

A direct entry must have a pinned GitHub release URL, exact byte count (maximum 512 MB), and SHA-256. Redirects are limited to HTTPS GitHub asset hosts. Downloads can be cancelled and incomplete/mismatched files are deleted. There is no embedded login, personal API key, scraping or download-site bypass.

The app unpacks into its private preview cache using upstream’s archive checks (maximum 1 GB unpacked / 20,000 entries), rejects unsupported/system-code mods through ModManager, then lists counts of shared files by installed mod. Install disabled unpacks into a hidden staging folder, validates it, adds only the new name to the disabled configuration, then renames the folder into place. Existing mod files, order and enabled choices are retained. An interrupted install cannot publish an enabled mod; hidden interrupted staging is ignored by the loader. The user enables the mod and applies/restarts through the existing Mods screen. No save or base-game file is edited by this installation flow.

Website entries open the author’s page in a browser. Download there, then return to Browse → Import archive. ZIP/7z imports use the same preview and disabled install, without claiming a known publisher checksum. The original upstream Install mod action is retained separately, including its PKG support/behavior. The browser preview does not support PKG/executable patches or auto-resolve dependency/load-order conflicts.

## Starter catalog and credits

All starter entries are labeled **not gameplay-tested on Thor**. File-layout validation is not a gameplay compatibility guarantee. Do not label an entry tested without recording device/version/gameplay evidence.

- Dommo (goomab), Xbox (and switch) UI for Bloodborne: https://www.nexusmods.com/bloodborne/mods/30. Direct entry is Elden Ring style Xbox 360 prompts v0.3.2 as published by Jgamrot, windows-v1.6.4. The author permits uploads with creator credit; the app links to the existing publisher and does not bundle/rehost the mod. SHA-256 `f0448791e5201d7523ce309adc8c57bf7200218fb776b82fca179ec178ef54a0`, 9,701,069 bytes. Contains SOURCE.txt and menu/common.tpf.dcx; data layout inspected. Artwork changes do not remap the Thor’s controls.
- CocaGeladinhaHmm, More Options At Lamps: https://www.nexusmods.com/bloodborne/mods/107. Author-page entry only. Current PC-port version and optional patch dependencies are not verified for Android.
- Kyoski, Reduced Physics: https://www.nexusmods.com/bloodborne/mods/114. Author-page entry only; an emulator workaround is not assumed beneficial on native Android.

Catalog metadata lives in tools/thor/assets/mod-catalog.json. Adding a direct entry requires inspecting the archive, recording the exact publisher URL/hash/size, crediting the author, and keeping gameplay status honest. No third-party mod binaries are included in the app or source release. The existing Nexus account/manual flow is preserved; a full public API integration would require Nexus application registration.

## Checks

`tests/thor-mod-browser/run.sh` exercises catalog rejection/filtering, checksum/size/cancellation cleanup, preview conflicts, archive rejection, preserving existing mod files/configuration, duplicate-name handling and disabled publication. An optional ZIP path also checks a real archive in an isolated temporary directory. It never points tests at a live game folder.
