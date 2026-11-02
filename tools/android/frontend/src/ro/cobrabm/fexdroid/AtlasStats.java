package ro.cobrabm.fexdroid;

/** An immutable snapshot of the player's live figures for the companion HUD, read from the game's shm
 *  (version 2+). A value is present only when its flag is true; the Thor companion shows "—" otherwise.
 *  Only HP and blood echoes are exposed by the port's reverse engineering so far. */
final class AtlasStats {
    final boolean hpValid, echoesValid, spValid, insightValid, vialsValid, bulletsValid;
    final int curHp, maxHp, echoes, curSp, maxSp, insight, vials, bullets;
    final int goodsN;
    final int[] goodsIds, goodsCounts;
    AtlasStats(boolean hpValid, int curHp, int maxHp, boolean echoesValid, int echoes,
               boolean spValid, int curSp, int maxSp, boolean insightValid, int insight,
               boolean vialsValid, int vials, boolean bulletsValid, int bullets,
               int goodsN, int[] goodsIds, int[] goodsCounts) {
        this.hpValid = hpValid; this.curHp = curHp; this.maxHp = maxHp;
        this.echoesValid = echoesValid; this.echoes = echoes;
        this.spValid = spValid; this.curSp = curSp; this.maxSp = maxSp;
        this.insightValid = insightValid; this.insight = insight;
        this.vialsValid = vialsValid; this.vials = vials;
        this.bulletsValid = bulletsValid; this.bullets = bullets;
        this.goodsN = goodsN; this.goodsIds = goodsIds; this.goodsCounts = goodsCounts;
    }
}
