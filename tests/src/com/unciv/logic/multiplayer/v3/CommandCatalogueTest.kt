package com.unciv.logic.multiplayer.v3

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.multiplayer.v3.command.CommandException
import com.unciv.logic.multiplayer.v3.command.CommandExecutor
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.Event
import com.unciv.models.ruleset.EventChoice
import com.unciv.network.command.GameCommand
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Per-command catalogue check for multiplayer-v3 (see docs/multiplayer-v3.md §5): each
 * [GameCommand] subtype the [CommandExecutor] handles gets
 *  - a **legal** case asserting the engine effect actually happened, and
 *  - a representative **illegal** case asserting a [CommandException] with canonical state untouched.
 *
 * These operate on the canonical [com.unciv.logic.GameInfo] directly (no serialize/decode round
 * trip), so ad-hoc TestGame nations are fine here.
 */
@RunWith(GdxTestRunner::class)
class CommandCatalogueTest {

    private val testGame = TestGame()
    private lateinit var civInfo: Civilization
    private lateinit var enemyCiv: Civilization
    private val executor = CommandExecutor()

    @Before
    fun setUp() {
        // Founding a city makes civs meet, which completes a tutorial task -> settings.save() ->
        // needs UncivGame.files. TestGame doesn't init it under the headless runner; wire it up as
        // GameSerializationTests/GameSessionTest do. Pure test-harness plumbing.
        UncivGame.Current.files = UncivFiles(Gdx.files)

        testGame.makeHexagonalMap(4)
        // A real running game is past turn 0; some engine paths key off turns. Match that.
        testGame.gameInfo.turns = 1

        civInfo = testGame.addCiv(isPlayer = true)
        enemyCiv = testGame.addCiv(isPlayer = true)
    }

    // region FoundCity

    @Test
    fun foundCityCreatesCityAndConsumesSettler() {
        val tile = testGame.tileMap[0, 0]
        val settler: MapUnit = testGame.addUnit("Settler", civInfo, tile)
        assertTrue("Sanity: no city before founding", civInfo.cities.isEmpty())

        executor.execute(testGame.gameInfo, civInfo.civID, GameCommand.FoundCity(tile.position.x, tile.position.y))

        assertEquals("A city should exist at the founding tile", 1, civInfo.cities.size)
        assertTrue("The founded city should be centered on the tile", tile.isCityCenter())
        assertEquals(civInfo.civID, tile.getCity()!!.civ.civID)
        assertTrue("The settler should have been consumed", settler.isDestroyed)
    }

    @Test
    fun foundCityByNonOwnerIsRejectedAndStateUnchanged() {
        val tile = testGame.tileMap[0, 0]
        testGame.addUnit("Settler", civInfo, tile)

        // enemyCiv owns no settler on that tile -> illegal.
        assertThrows(CommandException::class.java) {
            executor.execute(testGame.gameInfo, enemyCiv.civID, GameCommand.FoundCity(tile.position.x, tile.position.y))
        }

        assertTrue("No city should have been founded", civInfo.cities.isEmpty() && enemyCiv.cities.isEmpty())
        assertFalse("Tile must not have become a city center", tile.isCityCenter())
    }

    // endregion
    // region SetCityProduction

    @Test
    fun setCityProductionSetsCurrentConstruction() {
        val cityTile = testGame.tileMap[0, 0]
        val city = testGame.addCity(civInfo, cityTile)
        // Monument has no required tech -> always buildable in a fresh city. The executor validates
        // buildability itself (it would throw CommandException otherwise); the post-condition proves it.
        executor.execute(
            testGame.gameInfo, civInfo.civID,
            GameCommand.SetCityProduction(cityTile.position.x, cityTile.position.y, "Monument")
        )

        assertEquals("Monument", city.cityConstructions.currentConstructionName())
    }

    @Test
    fun setCityProductionByNonOwnerIsRejectedAndStateUnchanged() {
        val cityTile = testGame.tileMap[0, 0]
        val city = testGame.addCity(civInfo, cityTile)
        val before = city.cityConstructions.currentConstructionName()

        assertThrows(CommandException::class.java) {
            executor.execute(
                testGame.gameInfo, enemyCiv.civID,
                GameCommand.SetCityProduction(cityTile.position.x, cityTile.position.y, "Monument")
            )
        }

        assertEquals("Production must be unchanged after a rejected command",
            before, city.cityConstructions.currentConstructionName())
    }

    // endregion
    // region ChooseTech

    /** First tech that is researchable right now and not yet researched. */
    private fun firstResearchableTechName(): String =
        testGame.ruleset.technologies.values
            .first { civInfo.tech.canBeResearched(it.name) && !civInfo.tech.isResearched(it.name) }
            .name

    @Test
    fun chooseTechSetsResearchQueue() {
        val techName = firstResearchableTechName()
        assertFalse(civInfo.tech.techsToResearch.contains(techName))

        executor.execute(testGame.gameInfo, civInfo.civID, GameCommand.ChooseTech(techName))

        // ChooseTech mirrors the tech picker: it sets the research path to the chosen tech (the path
        // ends in techName; already-researched prerequisites are dropped) and banks overflow toward it.
        // The tech must end up as the civ's research goal — or already researched, if it was affordable.
        assertTrue("The chosen tech must become the research goal (queued or researched)",
            civInfo.tech.techsToResearch.contains(techName) || civInfo.tech.isResearched(techName))
    }

    @Test
    fun chooseUnknownTechIsRejectedAndStateUnchanged() {
        val before = ArrayList(civInfo.tech.techsToResearch)

        assertThrows(CommandException::class.java) {
            executor.execute(testGame.gameInfo, civInfo.civID, GameCommand.ChooseTech("No Such Tech"))
        }

        assertEquals("Research queue must be unchanged after a rejected command",
            before, civInfo.tech.techsToResearch)
    }

    // endregion
    // region PromoteUnit

    /** Creates a promotion available to [unit]'s type and gives the unit enough XP to take it. */
    private fun makeAvailablePromotion(unit: MapUnit): String {
        val promotion = testGame.createUnitPromotion()
        promotion.unitTypes = listOf(unit.type.name)
        unit.promotions.XP += 100 // plenty for the first promotion
        return promotion.name
    }

    @Test
    fun promoteUnitAddsPromotion() {
        val tile = testGame.tileMap[0, 0]
        val unit = testGame.addUnit("Warrior", civInfo, tile)
        val promotionName = makeAvailablePromotion(unit)
        assertFalse(unit.promotions.promotions.contains(promotionName))

        executor.execute(
            testGame.gameInfo, civInfo.civID,
            GameCommand.PromoteUnit(tile.position.x, tile.position.y, promotionName)
        )

        assertTrue("The unit should now have the promotion",
            unit.promotions.promotions.contains(promotionName))
    }

    @Test
    fun promoteWithUnavailablePromotionIsRejectedAndStateUnchanged() {
        val tile = testGame.tileMap[0, 0]
        val unit = testGame.addUnit("Warrior", civInfo, tile)
        // A promotion for a DIFFERENT unit type is never available to this unit.
        val foreignPromotion = testGame.createUnitPromotion()
        foreignPromotion.unitTypes = listOf("Scout")
        unit.promotions.XP += 100

        assertThrows(CommandException::class.java) {
            executor.execute(
                testGame.gameInfo, civInfo.civID,
                GameCommand.PromoteUnit(tile.position.x, tile.position.y, foreignPromotion.name)
            )
        }

        assertFalse("Unit must not have gained an unavailable promotion",
            unit.promotions.promotions.contains(foreignPromotion.name))
    }

    // endregion
    // region GenericUnitAction

    @Test
    fun genericUnitActionFortifies() {
        val tile = testGame.tileMap[0, 0]
        val unit = testGame.addUnit("Warrior", civInfo, tile)
        assertFalse(unit.isFortified())

        executor.execute(
            testGame.gameInfo, civInfo.civID,
            GameCommand.GenericUnitAction(tile.position.x, tile.position.y, UnitActionType.Fortify.name)
        )

        assertTrue("The unit should be fortified after a Fortify action", unit.isFortified())
    }

    @Test
    fun genericUnitActionByNonOwnerIsRejectedAndStateUnchanged() {
        val tile = testGame.tileMap[0, 0]
        val unit = testGame.addUnit("Warrior", civInfo, tile)

        assertThrows(CommandException::class.java) {
            executor.execute(
                testGame.gameInfo, enemyCiv.civID,
                GameCommand.GenericUnitAction(tile.position.x, tile.position.y, UnitActionType.Fortify.name)
            )
        }

        assertFalse("Unit must not have fortified on a rejected command", unit.isFortified())
    }

    @Test
    fun genericUnitActionWithUnknownTypeIsRejected() {
        val tile = testGame.tileMap[0, 0]
        testGame.addUnit("Warrior", civInfo, tile)

        assertThrows(CommandException::class.java) {
            executor.execute(
                testGame.gameInfo, civInfo.civID,
                GameCommand.GenericUnitAction(tile.position.x, tile.position.y, "NotARealAction")
            )
        }
    }

    // endregion
    // region AttackUnit

    private fun declareWar() {
        civInfo.diplomacyFunctions.makeCivilizationsMeet(enemyCiv)
        civInfo.diplomacy[enemyCiv.civName]?.declareWar()
    }

    @Test
    fun attackUnitDamagesAdjacentEnemy() {
        declareWar()
        val attackerTile = testGame.tileMap[0, 0]
        val targetTile = testGame.tileMap[1, 0]
        val attacker = testGame.addUnit("Warrior", civInfo, attackerTile)
        attacker.currentMovement = attacker.getMaxMovement().toFloat()
        val defender = testGame.addUnit("Warrior", enemyCiv, targetTile)
        val defenderHealthBefore = defender.health

        executor.execute(
            testGame.gameInfo, civInfo.civID,
            GameCommand.AttackUnit(attackerTile.position.x, attackerTile.position.y,
                targetTile.position.x, targetTile.position.y)
        )

        assertTrue("The defender should have taken damage (or been destroyed)",
            defender.isDestroyed || defender.health < defenderHealthBefore)
    }

    @Test
    fun attackOutOfRangeEnemyIsRejectedAndStateUnchanged() {
        declareWar()
        val attackerTile = testGame.tileMap[0, 0]
        val targetTile = testGame.tileMap[3, 0] // far out of a melee Warrior's reach+strike this turn
        val attacker = testGame.addUnit("Warrior", civInfo, attackerTile)
        attacker.currentMovement = attacker.getMaxMovement().toFloat()
        val defender = testGame.addUnit("Warrior", enemyCiv, targetTile)
        val defenderHealthBefore = defender.health

        assertThrows(CommandException::class.java) {
            executor.execute(
                testGame.gameInfo, civInfo.civID,
                GameCommand.AttackUnit(attackerTile.position.x, attackerTile.position.y,
                    targetTile.position.x, targetTile.position.y)
            )
        }

        assertEquals("Defender must be undamaged after a rejected attack",
            defenderHealthBefore, defender.health)
        assertEquals("Attacker must not have moved on a rejected attack",
            attackerTile, attacker.currentTile)
    }

    @Test
    fun attackWithoutWarIsRejected() {
        // No declareWar() here: you cannot attack a civ you are at peace with.
        val attackerTile = testGame.tileMap[0, 0]
        val targetTile = testGame.tileMap[1, 0]
        val attacker = testGame.addUnit("Warrior", civInfo, attackerTile)
        attacker.currentMovement = attacker.getMaxMovement().toFloat()
        val defender = testGame.addUnit("Warrior", enemyCiv, targetTile)
        val defenderHealthBefore = defender.health

        assertThrows(CommandException::class.java) {
            executor.execute(
                testGame.gameInfo, civInfo.civID,
                GameCommand.AttackUnit(attackerTile.position.x, attackerTile.position.y,
                    targetTile.position.x, targetTile.position.y)
            )
        }

        assertEquals(defenderHealthBefore, defender.health)
    }

    // endregion
    // region ResolveEvent

    /**
     * Registers a single-choice `Alert` event in the game's ruleset whose only choice grants gold, and
     * queues its `PopupAlert(AlertType.Event, name)` on [civInfo] — the exact state an `Alert` event
     * leaves behind on the authority after inter-turn processing, ready for a `ResolveEvent` command.
     */
    private fun addPendingGoldEvent(name: String, gold: Int = 100) {
        val event = Event()
        event.name = name
        val choice = EventChoice()
        choice.name = "$name-choice-0"
        choice.uniques.add("Gain [$gold] [Gold]")
        event.choices.add(choice)
        testGame.gameInfo.ruleset.events[name] = event
        civInfo.popupAlerts.add(PopupAlert(AlertType.Event, name))
    }

    @Test
    fun resolveEventAppliesChosenBranchAndConsumesAlert() {
        addPendingGoldEvent("Test Gold Event")
        val goldBefore = civInfo.gold

        executor.execute(testGame.gameInfo, civInfo.civID, GameCommand.ResolveEvent("Test Gold Event", 0))

        assertTrue("The chosen branch's Gain-Gold trigger should have run on the canonical civ",
            civInfo.gold > goldBefore)
        assertTrue("The resolved event alert should be consumed",
            civInfo.popupAlerts.none { it.type == AlertType.Event })
    }

    @Test
    fun resolveEventWithNoPendingAlertIsRejectedAndStateUnchanged() {
        // The event exists in the ruleset but no PopupAlert is queued -> there is nothing to resolve.
        val event = Event().apply {
            name = "Unqueued Event"
            choices.add(EventChoice().apply { name = "c"; uniques.add("Gain [50] [Gold]") })
        }
        testGame.gameInfo.ruleset.events["Unqueued Event"] = event
        val goldBefore = civInfo.gold

        assertThrows(CommandException::class.java) {
            executor.execute(testGame.gameInfo, civInfo.civID, GameCommand.ResolveEvent("Unqueued Event", 0))
        }
        assertEquals("A rejected event resolution must not change state", goldBefore, civInfo.gold)
    }

    @Test
    fun resolveEventWithInvalidChoiceIndexIsRejectedAndAlertKept() {
        addPendingGoldEvent("Index Event")

        assertThrows(CommandException::class.java) {
            executor.execute(testGame.gameInfo, civInfo.civID, GameCommand.ResolveEvent("Index Event", 5))
        }
        assertTrue("The alert must remain pending after a rejected resolution",
            civInfo.popupAlerts.any { it.type == AlertType.Event })
    }

    @Test
    fun resolveUnknownEventIsRejected() {
        assertThrows(CommandException::class.java) {
            executor.execute(testGame.gameInfo, civInfo.civID, GameCommand.ResolveEvent("No Such Event", 0))
        }
    }

    // endregion
}
