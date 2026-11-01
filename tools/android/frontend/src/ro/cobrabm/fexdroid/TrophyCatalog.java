// SPDX-License-Identifier: GPL-3.0-or-later
package ro.cobrabm.fexdroid;

/** Bloodborne boss roster for the companion's Trophies tab, keyed by the game's area-clear event flags.
 *  The flag ids come from the Hunter's Atlas map pack (maps/features.json reveal_regions boss_flags), so the
 *  game already exposes them through bbport/atlas.shm when it is launched with the Atlas — no extra request.
 *  These are the required story + DLC bosses (one area-clear flag each, two where an area has two). Optional
 *  bosses and ending bosses (Cleric Beast, Paarl, Micolash, Gehrman, Moon Presence) have their own event
 *  flags that are not in the map pack yet; they are a later addition once those ids are requested too. */
final class TrophyCatalog {
    static final class Boss {
        final int flag; final String name, area; final boolean dlc;
        Boss(int flag, String name, String area, boolean dlc) { this.flag = flag; this.name = name; this.area = area; this.dlc = dlc; }
    }

    /** Required story + DLC bosses, in roughly the order a hunter meets them. Names are the fought boss; the area
     *  is the map region whose clear flag this is (features.json), kept for on-device cross-checking. */
    static final Boss[] BOSSES = {
        new Boss(12411800, "Father Gascoigne",                     "Central Yharnam",            false),
        new Boss(12301800, "Blood-starved Beast",                  "Old Yharnam",                false),
        new Boss(12401800, "Vicar Amelia",                         "Cathedral Ward",             false),
        new Boss(12201800, "Witch of Hemwick",                     "Hemwick Charnel Lane",       false),
        new Boss(12701800, "Shadow of Yharnam",                    "Forbidden Woods",            false),
        new Boss(13201800, "Rom, the Vacuous Spider",              "Byrgenwerth",                false),
        new Boss(12801800, "The One Reborn",                       "Yahar'gul, Unseen Village",  false),
        new Boss(12601800, "Mergo's Wet Nurse",                    "Nightmare of Mensis",        false),
        new Boss(12501800, "Martyr Logarius",                      "Forsaken Castle Cainhurst",  false),
        new Boss(13301800, "Amygdala",                             "Nightmare Frontier",         false),
        new Boss(12421700, "Celestial Emissary",                   "Upper Cathedral Ward",       false),
        new Boss(12421800, "Ebrietas, Daughter of the Cosmos",     "Upper Cathedral Ward",       false),
        new Boss(13401800, "Ludwig the Accursed",                  "Hunter's Nightmare",         true),
        new Boss(13501800, "Living Failures",                      "Research Hall",              true),
        new Boss(13501850, "Lady Maria of the Astral Clocktower",  "Research Hall",              true),
        new Boss(13601800, "Orphan of Kos",                        "Fishing Hamlet",             true),
    };

    static int total() { return BOSSES.length; }

    private TrophyCatalog() {}
}
