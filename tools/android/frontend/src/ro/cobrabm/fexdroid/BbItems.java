package ro.cobrabm.fexdroid;

/** Short display names for Bloodborne goods ids (EquipParamGoods), for the Kit's inventory slots.
 *  The ids are the game's own; names are either confirmed live on-device or translated from the
 *  community param dump (soulsmods/Paramdex, BB/Names/EquipParamGoods.txt — the dev names there are
 *  Japanese, e.g. "Flame Pot" = Molotov, "Stone" = Pebble, "Oil bottle" = Oil Urn). Unknown ids show
 *  as "#id"; extend this map as more items are identified. */
final class BbItems {
    private BbItems() {}
    static String name(int id) {
        switch (id) {
            case 100:  return "Hunter Mark";      // confirmed on-device (this port counts it)
            case 120:  return "Notebook";         // confirmed on-device — leave messenger note
            case 900:  return "QS Bullet";       // Quicksilver Bullet
            case 910:  return "Blood Bullet";
            case 1000: return "Blood Vial";
            case 703:  return "Iosefka Vial";     // Iosefka's Blood Vial ("female doctor's medicine")
            case 1100: return "Antidote";         // "poison/bleeding recovery"
            case 1101: return "Sedative";         // "madness recovery"
            case 1110: return "Beast Pellet";     // Beast Blood Pellet ("beastification potion")
            case 1200: return "Molotov";          // Molotov Cocktail ("flame pot")
            case 1210: return "Poison Knife";
            case 1220: return "Oil Urn";          // "oil bottle"
            case 1230: return "Pebble";           // "stone"
            case 1240: return "Throw Knife";      // Throwing Knife
            case 1260: return "Pungent Cktl";     // Pungent Blood Cocktail ("beast seduction")
            case 2000: return "Augur";            // Augur of Ebrietas ("tentacle summoning")
            case 2010: return "A Call Beyond";    // "space summoning"
            default:
                if (id >= 1510 && id <= 1519) return "Coldblood";  // Coldblood Dew tiers
                return "#" + id;
        }
    }
}
