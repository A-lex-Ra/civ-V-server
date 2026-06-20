package com.unciv.logic.map.mapunit

import com.unciv.models.ruleset.unique.UniqueType

/**
 * BNW Portuguese **Nau** "Sell Exotic Goods" — the one engine source of truth for the action, shared by the
 * UI action ([com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsFromUniques.getSellExoticGoodsActions],
 * which also handles single-player) and the authoritative v3 path
 * ([com.unciv.logic.multiplayer.v3.command.CommandExecutor.executeSellExoticGoods]). Keeping the reward and
 * the gates here means an optimistically-applying client and the authority compute the *same* result.
 *
 * Faithful to Civ V: a unit may sell exotic goods a limited number of times (the unique's count), only while
 * in foreign or neutral/unclaimed territory, gaining Gold that scales with the distance from the capital
 * (selling far-flung wares is more lucrative) plus a flat chunk of XP. The action spends the unit's turn but
 * does not consume the unit, so it can sail on and sell again elsewhere until its uses run out.
 */
object SellExoticGoods {

    /** Key under which a unit's uses-so-far are tracked in [MapUnit.abilityToTimesUsed]. */
    const val ABILITY_USE_KEY = "Sell Exotic Goods"

    /** Flat XP awarded by one sale (enough to make meaningful progress toward a promotion). */
    const val XP_REWARD = 30

    // Gold = BASE_GOLD + GOLD_PER_TILE * (tiles from the capital). Tunable balance constants.
    private const val BASE_GOLD = 120
    private const val GOLD_PER_TILE = 8

    /** The per-unit use limit from [UniqueType.CanSellExoticGoods] (0 if the unit can't sell exotic goods). */
    fun maxUses(unit: MapUnit): Int =
        unit.getMatchingUniques(UniqueType.CanSellExoticGoods).maxOfOrNull { it.params[0].toInt() } ?: 0

    /** How many more times this unit may still sell exotic goods. */
    fun usesLeft(unit: MapUnit): Int =
        (maxUses(unit) - (unit.abilityToTimesUsed[ABILITY_USE_KEY] ?: 0)).coerceAtLeast(0)

    /** Gold this unit would gain selling on its current tile, scaling with distance from the capital. */
    fun goldReward(unit: MapUnit): Int {
        val capital = unit.civ.getCapital()
        val distance = if (capital != null)
            unit.getTile().aerialDistanceTo(capital.getCenterTile()) else 0
        return BASE_GOLD + distance * GOLD_PER_TILE
    }

    /** Whether [unit] can sell exotic goods on its current tile right now. */
    fun canSellExoticGoods(unit: MapUnit): Boolean {
        if (usesLeft(unit) <= 0) return false
        if (!unit.hasMovement()) return false
        // Must be in foreign or neutral/unclaimed territory — never in our own land (Civ V "in foreign lands").
        if (unit.getTile().getOwner() == unit.civ) return false
        return true
    }

    /**
     * Apply the sale: grant Gold + XP, count the use, and spend the unit's whole turn. The caller is
     * responsible for having checked [canSellExoticGoods] (the v3 authority re-checks before calling).
     */
    fun sellExoticGoods(unit: MapUnit) {
        unit.civ.addGold(goldReward(unit))
        unit.promotions.XP += XP_REWARD
        unit.abilityToTimesUsed[ABILITY_USE_KEY] = (unit.abilityToTimesUsed[ABILITY_USE_KEY] ?: 0) + 1
        unit.currentMovement = 0f
    }
}
