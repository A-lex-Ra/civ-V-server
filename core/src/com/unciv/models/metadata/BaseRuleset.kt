package com.unciv.models.metadata

/**
 * Name of the bundled *Brave New World* data folder under `jsons/`. This is an **extension** ruleset
 * (not a base) layered on top of [BaseRuleset.Civ_V_GnK] at load time.
 *
 * NAME GOTCHA: the bundled `ModOptions.json` declares `Mod is incompatible with [Brave New World]`,
 * so the name must NOT be exactly `Brave New World` or it self-conflicts during validation. The
 * distinct `Civ V - Brave New World` is intentional.
 */
const val BUNDLED_BNW_RULESET = "Civ V - Brave New World"

/**
 * Name of the synthesized built-in base ruleset = [BaseRuleset.Civ_V_GnK] combined in memory with the
 * bundled [BUNDLED_BNW_RULESET] data (see `RulesetCache.loadRulesets`). It is NOT a [BaseRuleset] enum
 * entry (the enum drives on-disk asset discovery and there is no `jsons/Civ V - All DLC/` folder);
 * it is registered post-load with `isBaseRuleset = true`.
 */
const val ALL_DLC_RULESET = "Civ V - All DLC"

@Suppress("EnumEntryName")  // These merit unusual names
enum class BaseRuleset(val fullName: String) {
    Civ_V_Vanilla("Civ V - Vanilla"),
    Civ_V_GnK("Civ V - Gods & Kings"),
}
