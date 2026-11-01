# Thor companion — roadmap

Planned work for the **AYN Thor** companion layer (this fork). The upstream native
port's own roadmap is [ROADMAP.md](ROADMAP.md). Guiding rule: **bring your own game
files, nothing copyrighted is bundled.**

## Achievements / QoL

- [x] **Trophy case (Phase 1)** — boss tracker reading the game's event flags via the Atlas shm; live unlock flourish + vibrate. (0.14.0)
- [x] **Decouple the trophy case from the map** — flags flow whenever the Atlas pack is *installed*, not only when the game is launched with the map on. The map display stays gated by its own toggle. (0.14.1)
- [x] **Expand to the full trophy list** — all 40 PS4 trophies (CUSA03173), read from the game's own trophy save (shadPS4 `<trophyconf>` XML) rather than Atlas boss flags, so endings, weapons, runes, chalices and every boss are tracked. Each trophy carries its official name, description and grade; tap a trophy to read what it is. Supersedes the event-flag boss tracker. (0.15.0)
- [ ] **Journal / notes tab** — scratchpad for NPC quests and item locations.
- [ ] **Save auto-backup rotation** — periodic backups keeping the last N (manual backup already ships).
- [ ] **Screenshot gallery** on the companion.
- [ ] **Native player stats** — extend `bbport` to write blood echoes / level / deaths into the Atlas shm → death counter, run stats, build tracker. (native C++ change)

## More Bloodborne

- [ ] **"More Bloodborne" links shelf** on the start screen — links to companion fan works (Bloodborne PSX by Lilith Walther, Yarntown by Max Mraz). Links only; nothing bundled.
- [ ] **Yarntown playable on the Thor (future, pending permission)** — Yarntown is a [Solarus](https://www.solarus-games.org/)-engine quest (Windows/Mac/Linux today, no Android build). Solarus is open-source (GPL) and can target Android, so a "Play Yarntown" tile is possible *with Max Mraz's permission to use his quest* (his itch source has no license yet). Path once permitted: a Solarus Android runtime + a bring-your-own-quest tile launched from the start menu, crediting Max. **Blocked on reaching out to Max Mraz.**

## App

- [x] **New app icon** — gold "HC" monogram over a blood-red moon, replacing the plain two-box mark. (0.14.0)
- [ ] Verify the 0.13.1 lid-pause persists-on-reopen on a real lid close.
