package com.unciv.logic.civilization.managers

import com.unciv.models.metadata.ALL_DLC_RULESET
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.GreatWorkType
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.UncivGame
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BNW Phase 2c — Increment 6: the bundled-data migration that replaced the ~60 hidden
 * `[<host>] [Great Work of …]` / `[<host>] [Theming Bonus]` sub-buildings with the data-driven
 * [UniqueType.ProvidesGreatWorkSlots] and [UniqueType.GreatWorkThemingBonus] uniques on the real
 * visible culture buildings/wonders.
 *
 * The hard acceptance bar is that the synthesized "Civ V - All DLC" base ruleset (= Gods & Kings +
 * the bundled Brave New World extension) still validates with no blocking errors after the edits —
 * no unknown uniques, no dangling building references, no orphaned conditionals. We also prove that a
 * host building's slot count is correctly derived by [GreatWorkSlotProvider.getSlotsForCiv] for a city
 * that actually built it.
 */
@RunWith(GdxTestRunner::class)
class GreatWorkDataMigrationTest {

    @Before
    fun setUp() {
        // Tests' working dir is android/assets, so consoleMode's relative FileHandle("jsons/...")
        // resolves and the synthesized All-DLC ruleset is registered (mirrors AllDlcRulesetValidationTest).
        if (RulesetCache.isEmpty() || RulesetCache[ALL_DLC_RULESET] == null)
            RulesetCache.loadRulesets(consoleMode = true, noMods = true)
        // The All-DLC ruleset runs tr() during validation; make sure a game + settings exist.
        if (!UncivGame.isCurrentInitialized()) {
            UncivGame.Current = UncivGame()
            UncivGame.Current.settings = GameSettings()
        }
    }

    private fun allDlc() = RulesetCache[ALL_DLC_RULESET]
        ?: error("The synthesized '$ALL_DLC_RULESET' ruleset must be present in the cache")

    @Test
    fun `the All-DLC ruleset still validates with no blocking errors`() {
        val ruleset = allDlc()
        val errors = ruleset.getErrorList()
        // isError() is the same blocking-severity gate BasicTests.baseRulesetHasNoBugs enforces on the
        // shipped base rulesets; the data migration must not introduce any Error-level problems.
        assertFalse(
            "The '$ALL_DLC_RULESET' ruleset must have no blocking errors after the Great-Works data " +
                "migration, but found:\n${errors.getErrorText(unfiltered = true)}",
            errors.isError()
        )
    }

    @Test
    fun `the bundled hidden Great-Work slot and theming sub-buildings are gone`() {
        val ruleset = allDlc()
        val leftover = ruleset.buildings.keys.filter {
            Regex("""^\[.+] \[Great Work of (Writing|Art|Music)](?: \d+)?$""").matches(it) ||
                it.endsWith("[Theming Bonus]")
        }
        assertTrue("No hidden slot/theming sub-buildings may remain, but found: $leftover", leftover.isEmpty())
    }

    @Test
    fun `host buildings carry the expected Great-Work slot uniques`() {
        val ruleset = allDlc()

        fun slotCount(buildingName: String): Int {
            val building = ruleset.buildings[buildingName]
                ?: error("$buildingName missing from $ALL_DLC_RULESET")
            return building.getMatchingUniques(UniqueType.ProvidesGreatWorkSlots)
                .sumOf { it.params[0].toIntOrNull() ?: 0 }
        }

        assertEquals("Museum should provide 2 Art slots", 2, slotCount("Museum"))
        assertEquals("The Louvre should provide 4 Art slots", 4, slotCount("The Louvre"))
        assertEquals("Hermitage should provide 3 Art slots", 3, slotCount("Hermitage"))
        assertEquals("Broadway should provide 3 Music slots", 3, slotCount("Broadway"))
        assertEquals("Globe Theatre should provide 2 Writing slots", 2, slotCount("Globe Theatre"))
    }

    @Test
    fun `getSlotsForCiv derives the host building's slots for a city that built it`() {
        // Copy the *bundled* Museum definition (with its migrated slot uniques) out of the All-DLC
        // ruleset into a TestGame, build it in a city, and confirm the provider derives its 2 Art slots
        // from the migrated data unique alone — proving the real data drives the slot count. Museum is
        // used (rather than The Louvre) because it carries no "Free [Great Person]" build trigger that
        // would need extra map/unit setup; the slot derivation path under test is identical.
        val museum = allDlc().buildings["Museum"]
        assertNotNull("The All-DLC ruleset must contain Museum", museum)

        val testGame = TestGame()
        testGame.makeHexagonalMap(4)
        testGame.gameInfo.greatWorkManager.setTransients(testGame.gameInfo)

        val civ = testGame.addCiv()
        val city = testGame.addCity(civ, testGame.getTile(0, 0))

        // Register the bundled building under its real name into the test ruleset, then build it.
        testGame.ruleset.buildings["Museum"] = museum!!
        museum.ruleset = testGame.ruleset
        city.cityConstructions.addBuilding(museum)

        val artSlots = GreatWorkSlotProvider.getSlotsForCiv(civ).filter { it.buildingName == "Museum" }
        assertEquals("Museum must yield 2 slots for the building city", 2, artSlots.size)
        assertTrue("All Museum slots must be Art slots",
            artSlots.all { it.slotType == GreatWorkType.Art })
        assertEquals("Slot indices must be 0..1", setOf(0, 1), artSlots.map { it.slotIndex }.toSet())
    }
}
