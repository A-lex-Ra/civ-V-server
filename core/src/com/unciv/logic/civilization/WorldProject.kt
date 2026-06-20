package com.unciv.logic.civilization

import com.unciv.logic.IsPartOfGameInfoSerialization
import yairm210.purity.annotations.Readonly

/**
 * BNW Phase 3 — World Congress (Increment 3). An in-flight **World Project** (World's Fair /
 * International Games) — a production-contribution competition started by a passing resolution. Members
 * bank production into [contributions] (civId → accumulated production) via a ruleset-gated city
 * construction; when [endTurn] is reached the authority ranks civs and grants tiered rewards.
 *
 * Flat primitives + a String-keyed map ([IsPartOfGameInfoSerialization]); a default-constructed instance
 * is a valid "no contributions yet" record. [projectType] is the [ResolutionType] enum name of the
 * world-project resolution that started it.
 */
class WorldProject : IsPartOfGameInfoSerialization {

    /** The [ResolutionType] enum name that started this project (WorldsFair / InternationalGames). */
    var projectType = ""

    /** Game turn the project started. */
    var startTurn = -1

    /** Game turn the project resolves (ranks + rewards granted). */
    var endTurn = -1

    /** civId → accumulated production contributed to the project. */
    var contributions = HashMap<String, Int>()

    fun clone(): WorldProject {
        val toReturn = WorldProject()
        toReturn.projectType = projectType
        toReturn.startTurn = startTurn
        toReturn.endTurn = endTurn
        toReturn.contributions.putAll(contributions)
        return toReturn
    }

    /** The resolved [ResolutionType], or null if unknown. */
    @Readonly
    fun getResolutionType(): ResolutionType? =
        ResolutionType.entries.firstOrNull { it.name == projectType }

    /** Add [amount] production toward [civId]'s contribution (clamped to non-negative). */
    fun contribute(civId: String, amount: Int) {
        if (amount <= 0) return
        contributions[civId] = (contributions[civId] ?: 0) + amount
    }

    /** Civ ids ranked by contribution, highest first; ties broken by civId for determinism. */
    @Readonly
    fun rankedContributors(): List<String> =
        contributions.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
}
