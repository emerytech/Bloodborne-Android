// SPDX-License-Identifier: GPL-3.0-or-later
package ro.cobrabm.fexdroid;

/** The full Bloodborne PS4 trophy set (CUSA03173) for the companion's Trophies tab.
 *  Ids, names, descriptions and grades are the game's own, as the native port's shadPS4 trophy runtime
 *  knows them (libraries/np/np_trophy.cpp). The companion reads which ones are unlocked from the per-user
 *  trophy save XML through {@link TrophyProgress}, so this tracks every trophy the game actually awards —
 *  bosses, endings, chalices, weapons, runes and discoveries — not just boss-clear map flags. */
final class TrophyCatalog {
    static final int PLATINUM = 0, GOLD = 1, SILVER = 2, BRONZE = 3;

    static final class Trophy {
        final int id;            // PS4 trophy id, and the id in the trophy save XML
        final String name, desc; // the game's own name and description
        final int grade;         // PLATINUM / GOLD / SILVER / BRONZE
        final boolean dlc;       // The Old Hunters (does not count toward the platinum)
        Trophy(int id, String name, String desc, int grade, boolean dlc) {
            this.id = id; this.name = name; this.desc = desc; this.grade = grade; this.dlc = dlc;
        }
    }

    /** All 40 trophies in id order (platinum, then the base game, then The Old Hunters). */
    static final Trophy[] TROPHIES = {
        new Trophy(0,  "Bloodborne",                            "All trophies acquired. Hats off!", PLATINUM, false),
        new Trophy(1,  "Yharnam Sunrise",                       "You lived through the hunt, and saw another day.", GOLD, false),
        new Trophy(2,  "Honoring Wishes",                       "Captivated by the moon presence, you pledge to watch over the hunter's dream.", GOLD, false),
        new Trophy(3,  "Childhood's Beginning",                 "You became an infant Great One, lifting humanity into its next childhood.", GOLD, false),
        new Trophy(4,  "Yharnam, Pthumerian Queen",             "Defeat Yharnam, Blood Queen of the Old Labyrinth.", GOLD, false),
        new Trophy(5,  "Hunter's Essence",                      "Acquire all hunter weapons.", GOLD, false),
        new Trophy(6,  "Hunter's Craft",                        "Acquire all special hunter tools.", GOLD, false),
        new Trophy(7,  "Weapon Master",                         "Acquire a weapon of the highest level.", SILVER, false),
        new Trophy(8,  "Blood Gem Master",                      "Acquire an extremely precious blood gem.", SILVER, false),
        new Trophy(9,  "Rune Master",                           "Acquire an extremely precious Caryll Rune.", SILVER, false),
        new Trophy(10, "Cainhurst",                             "Gain entry to Cainhurst, the lost and ruined castle.", SILVER, false),
        new Trophy(11, "The Choir",                             "Gain entry to the realm of the Choir, the high stratum of the Healing Church.", SILVER, false),
        new Trophy(12, "The Source of the Dream",               "Discover the abandoned old workshop, the source of the hunter's dream.", SILVER, false),
        new Trophy(13, "Nightmare Lecture Building",            "Gain entry into the Byrgenwerth lecture building, that drifts within the realm of nightmare.", SILVER, false),
        new Trophy(14, "Father Gascoigne",                      "Defeat the beast that once was Father Gascoigne.", BRONZE, false),
        new Trophy(15, "Vicar Amelia",                          "Defeat the beast that once was Vicar Amelia.", BRONZE, false),
        new Trophy(16, "Shadow of Yharnam",                     "Defeat the Shadow of Yharnam.", BRONZE, false),
        new Trophy(17, "Rom, the Vacuous Spider",               "Defeat Great One: Rom, the Vacuous Spider.", BRONZE, false),
        new Trophy(18, "The One Reborn",                        "Defeat the One Reborn.", BRONZE, false),
        new Trophy(19, "Micolash, Host of the Nightmare",       "Defeat Micolash, Host of the Nightmare.", BRONZE, false),
        new Trophy(20, "Mergo's Wet Nurse",                     "Defeat Great One: Mergo's Wet Nurse.", BRONZE, false),
        new Trophy(21, "Cleric Beast",                          "Defeat Cleric Beast.", BRONZE, false),
        new Trophy(22, "Blood-starved Beast",                   "Defeat Blood-starved Beast.", BRONZE, false),
        new Trophy(23, "The Witch of Hemwick",                  "Defeat the Witch of Hemwick.", BRONZE, false),
        new Trophy(24, "Darkbeast Paarl",                       "Defeat Darkbeast Paarl.", BRONZE, false),
        new Trophy(25, "Amygdala",                              "Defeat Great One: Amygdala.", BRONZE, false),
        new Trophy(26, "Martyr Logarius",                       "Defeat Martyr Logarius.", BRONZE, false),
        new Trophy(27, "Celestial Emissary",                    "Defeat Great One: Celestial Emissary.", BRONZE, false),
        new Trophy(28, "Ebrietas, Daughter of the Cosmos",      "Defeat Great One: Ebrietas, Daughter of the Cosmos.", BRONZE, false),
        new Trophy(29, "Blood Gem Contact",                     "Acquire a blood gem that imbues hunter weapons with special strength.", BRONZE, false),
        new Trophy(30, "Rune Contact",                          "Acquire a Caryll Rune that endows hunters with special strength.", BRONZE, false),
        new Trophy(31, "Chalice of Pthumeru",                   "Acquire the Chalice of Pthumeru that seals the catacombs that form a web deep below Yharnam.", BRONZE, false),
        new Trophy(32, "Chalice of Ailing Loran",              "Acquire the Chalice of Ailing Loran that seals the tragic land lost to the sands.", BRONZE, false),
        new Trophy(33, "Chalice of Isz",                        "Acquire the Great Chalice of Isz that seals the home of the cosmic kin.", BRONZE, false),
        new Trophy(34, "Old Hunter's Essence",                  "Acquire all old hunter weapons.", GOLD, true),
        new Trophy(35, "Orphan of Kos",                         "Defeat Great One: Orphan of Kos.", SILVER, true),
        new Trophy(36, "Ludwig, the Holy Blade",               "Defeat the beast that was once Ludwig, the Holy Blade.", BRONZE, true),
        new Trophy(37, "Lady Maria of the Astral Clocktower",   "Defeat Lady Maria of the Astral Clocktower.", BRONZE, true),
        new Trophy(38, "Living Failures",                       "Defeat the failed attempts to become Great Ones.", BRONZE, true),
        new Trophy(39, "Laurence, the First Vicar",            "Defeat the beast that was once Laurence, the First Vicar.", BRONZE, true),
    };

    static int total() { return TROPHIES.length; }

    static String gradeName(int grade) {
        switch (grade) { case PLATINUM: return "Platinum"; case GOLD: return "Gold"; case SILVER: return "Silver"; default: return "Bronze"; }
    }

    /** Tile/accent colour for a grade, for the Trophies board. */
    static int gradeColor(int grade) {
        switch (grade) {
            case PLATINUM: return 0xffd9ecf2; // pale cyan-white
            case GOLD:     return 0xffe8c97a;
            case SILVER:   return 0xffc3c9d1;
            default:       return 0xffcf9b6b; // bronze
        }
    }

    private TrophyCatalog() {}
}
