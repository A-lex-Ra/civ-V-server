package com.unciv.logic.multiplayer.v3

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.civilization.managers.PublicOpinionManager
import com.unciv.logic.multiplayer.v3.command.CommandException
import com.unciv.logic.multiplayer.v3.command.CommandExecutor
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.Event
import com.unciv.models.ruleset.EventChoice
import com.unciv.models.ruleset.PolicyBranch
import com.unciv.network.command.GameCommand
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    // region SwitchIdeology

    /**
     * Creates an ideology policy branch under [name]. Uses the `Remove [Ideology]` data marker so
     * [PolicyBranch.isIdeology] detects it (Signal 2) WITHOUT a mutual-exclusion marker — so a civ
     * already following one ideology can still *voluntarily* adopt this one (no `Unavailable <after
     * adopting [other]>` gate), which keeps the legal-switch case simple.
     */
    private fun createIdeologyBranch(name: String): PolicyBranch {
        val branch = testGame.createPolicyBranch("Remove [Ideology] [in capital] <hidden from users>")
        val autoName = branch.name
        testGame.ruleset.policyBranches.remove(autoName)
        testGame.ruleset.policies.remove(autoName)
        val complete = testGame.ruleset.policies.remove(autoName + com.unciv.models.ruleset.Policy.branchCompleteSuffix)
        branch.name = name
        // The real ruleset loader sets a branch start's `requires` to an empty list (members get
        // [branchName]); replicate it so PolicyManager.isAdoptable (which does requires!!) doesn't NPE.
        branch.requires = ArrayList()
        // isAdoptable also looks up ruleset.eras[branch.era]!! — a fresh branch has era="" which would
        // NPE, so anchor it to a real era.
        branch.era = testGame.ruleset.eras.keys.first()
        testGame.ruleset.policyBranches[name] = branch
        testGame.ruleset.policies[name] = branch
        if (complete != null) {
            complete.name = name + com.unciv.models.ruleset.Policy.branchCompleteSuffix
            complete.requires = arrayListOf(name)
            testGame.ruleset.policies[complete.name] = complete
        }
        return branch
    }

    /**
     * Mark [branch] as the civ's adopted ideology WITHOUT running the engine adoption (so the branch's
     * data-marker uniques never fire). Adds both the branch start AND its auto-complete to the adopted
     * set so the state is consistent — i.e. a later [com.unciv.logic.civilization.managers.PolicyManager.removePolicy]
     * cascade won't try to remove a non-adopted "Complete" policy.
     */
    private fun adoptIdeologyDirectly(branch: PolicyBranch) {
        val adopted = civInfo.policies.getAdoptedPolicies()
        adopted.add(branch.name)
        adopted.add(branch.name + com.unciv.models.ruleset.Policy.branchCompleteSuffix)
    }

    @Test
    fun switchIdeologyChangesIdeologyAndEntersAnarchy() {
        val from = createIdeologyBranch("IdeologyFrom")
        val to = createIdeologyBranch("IdeologyTo")
        testGame.addCity(civInfo, testGame.tileMap[0, 0])
        adoptIdeologyDirectly(from)
        assertEquals(from.name, civInfo.policies.getCurrentIdeology()?.name)

        executor.execute(testGame.gameInfo, civInfo.civID, GameCommand.SwitchIdeology(to.name))

        assertEquals("The civ should now follow the new ideology", to.name, civInfo.policies.getCurrentIdeology()?.name)
        assertFalse("The old ideology branch must no longer be adopted",
            civInfo.policies.isAdopted(from.name))
        assertTrue("The civ should be in anarchy after switching",
            civInfo.publicOpinion.isInAnarchy())
    }

    @Test
    fun switchIdeologyWhenForcedConsumesTheCivilResistanceAlert() {
        val from = createIdeologyBranch("ForcedFrom")
        val to = createIdeologyBranch("ForcedTo")
        testGame.addCity(civInfo, testGame.tileMap[0, 0])
        adoptIdeologyDirectly(from)
        // Simulate Civil Resistance: forced flag set + the actionable alert queued (as recompute would).
        civInfo.publicOpinion.forcedSwitchPending = true
        civInfo.popupAlerts.add(com.unciv.logic.civilization.PopupAlert(
            AlertType.Event, PublicOpinionManager.CIVIL_RESISTANCE_EVENT_NAME))

        executor.execute(testGame.gameInfo, civInfo.civID, GameCommand.SwitchIdeology(to.name))

        assertEquals("Forced switch should change the ideology", to.name, civInfo.policies.getCurrentIdeology()?.name)
        assertTrue("The Civil-Resistance alert must be consumed on a forced switch",
            civInfo.popupAlerts.none {
                it.type == AlertType.Event && it.value == PublicOpinionManager.CIVIL_RESISTANCE_EVENT_NAME
            })
    }

    @Test
    fun switchIdeologyWithNoCurrentIdeologyIsRejectedAndStateUnchanged() {
        val to = createIdeologyBranch("TargetIdeology")
        testGame.addCity(civInfo, testGame.tileMap[0, 0])
        // civInfo follows no ideology -> you can't *switch*, only *select* a first one.
        assertThrows(CommandException::class.java) {
            executor.execute(testGame.gameInfo, civInfo.civID, GameCommand.SwitchIdeology(to.name))
        }
        assertEquals("No ideology should have been adopted on a rejected switch",
            null, civInfo.policies.getCurrentIdeology())
        assertFalse("No anarchy on a rejected switch", civInfo.publicOpinion.isInAnarchy())
    }

    @Test
    fun switchIdeologyWhileInAnarchyIsRejectedAndStateUnchanged() {
        val from = createIdeologyBranch("AnarchyFrom")
        val to = createIdeologyBranch("AnarchyTo")
        testGame.addCity(civInfo, testGame.tileMap[0, 0])
        civInfo.policies.getAdoptedPolicies().add(from.name)
        // Already mid-switch: in anarchy from a previous switch.
        civInfo.publicOpinion.anarchyTurnsRemaining = 3

        assertThrows(CommandException::class.java) {
            executor.execute(testGame.gameInfo, civInfo.civID, GameCommand.SwitchIdeology(to.name))
        }
        assertEquals("Ideology must be unchanged while in anarchy", from.name, civInfo.policies.getCurrentIdeology()?.name)
        assertFalse("The target must not have been adopted", civInfo.policies.isAdopted(to.name))
    }

    @Test
    fun switchToNonIdeologyBranchIsRejected() {
        val from = createIdeologyBranch("RealIdeology")
        testGame.addCity(civInfo, testGame.tileMap[0, 0])
        civInfo.policies.getAdoptedPolicies().add(from.name)
        // A plain (non-ideology) branch: no markers -> isIdeology == false.
        val plainBranch = testGame.createPolicyBranch("[+10]% Production")

        assertThrows(CommandException::class.java) {
            executor.execute(testGame.gameInfo, civInfo.civID, GameCommand.SwitchIdeology(plainBranch.name))
        }
        assertEquals("Ideology must be unchanged after a rejected non-ideology switch",
            from.name, civInfo.policies.getCurrentIdeology()?.name)
    }

    @Test
    fun switchToUnknownBranchIsRejected() {
        val from = createIdeologyBranch("KnownIdeology")
        testGame.addCity(civInfo, testGame.tileMap[0, 0])
        civInfo.policies.getAdoptedPolicies().add(from.name)

        assertThrows(CommandException::class.java) {
            executor.execute(testGame.gameInfo, civInfo.civID, GameCommand.SwitchIdeology("No Such Branch"))
        }
    }

    // endregion

    // region MoveGreatWork (BNW Phase 2c — Increment 3)

    /** Build a building named [name] carrying [uniques], register it in the ruleset, add it to [city]. */
    private fun addBuildingNamed(
        city: com.unciv.logic.city.City, name: String, vararg uniques: String
    ): com.unciv.models.ruleset.Building {
        val building = testGame.createBuilding(*uniques)
        testGame.ruleset.buildings.remove(building.name)
        building.name = name
        testGame.ruleset.buildings[name] = building
        city.cityConstructions.addBuilding(building)
        return building
    }

    /** Register a fresh Art Great Work created by [civInfo], returning it. */
    private fun makeArtWork(): com.unciv.logic.civilization.managers.GreatWork {
        val manager = testGame.gameInfo.greatWorkManager
        val work = com.unciv.logic.civilization.managers.GreatWork().apply {
            id = manager.newId()
            type = com.unciv.models.ruleset.GreatWorkType.Art
            creatingCivName = civInfo.civName
            name = "Work-$id"
        }
        manager.registerWork(work)
        return work
    }

    @Test
    fun moveGreatWorkPlacesWorkInTheDestinationSlot() {
        val cityTile = testGame.tileMap[0, 0]
        val city = testGame.addCity(civInfo, cityTile)
        addBuildingNamed(city, "TestMuseum", "Provides [2] [Art] Great Work slots")
        val manager = testGame.gameInfo.greatWorkManager

        val work = makeArtWork()
        // Place it in slot 0; move it to slot 1.
        val slot0 = com.unciv.logic.civilization.managers.GreatWorkSlotProvider.getSlotsForCiv(civInfo)
            .first { it.buildingName == "TestMuseum" && it.slotIndex == 0 }
        val slot1 = com.unciv.logic.civilization.managers.GreatWorkSlotProvider.getSlotsForCiv(civInfo)
            .first { it.buildingName == "TestMuseum" && it.slotIndex == 1 }
        manager.placeWork(work, slot0)

        executor.execute(
            testGame.gameInfo, civInfo.civID,
            GameCommand.MoveGreatWork(work.id, cityTile.position.x, cityTile.position.y, "TestMuseum", 1)
        )

        assertEquals("Work must now sit in slot 1", work, manager.getWorkInSlot(slot1))
        assertNull("Old slot 0 must now be empty", manager.getWorkInSlot(slot0))
    }

    @Test
    fun moveGreatWorkSwapsWithASameCivWorkInTheDestination() {
        val cityTile = testGame.tileMap[0, 0]
        val city = testGame.addCity(civInfo, cityTile)
        addBuildingNamed(city, "TestMuseum", "Provides [2] [Art] Great Work slots")
        val manager = testGame.gameInfo.greatWorkManager

        val workA = makeArtWork()
        val workB = makeArtWork()
        val slot0 = com.unciv.logic.civilization.managers.GreatWorkSlotProvider.getSlotsForCiv(civInfo)
            .first { it.slotIndex == 0 }
        val slot1 = com.unciv.logic.civilization.managers.GreatWorkSlotProvider.getSlotsForCiv(civInfo)
            .first { it.slotIndex == 1 }
        manager.placeWork(workA, slot0)
        manager.placeWork(workB, slot1)

        // Move A onto B's slot -> swap.
        executor.execute(
            testGame.gameInfo, civInfo.civID,
            GameCommand.MoveGreatWork(workA.id, cityTile.position.x, cityTile.position.y, "TestMuseum", 1)
        )

        assertEquals("A must now be in slot 1", workA, manager.getWorkInSlot(slot1))
        assertEquals("B must have been displaced into slot 0", workB, manager.getWorkInSlot(slot0))
    }

    @Test
    fun moveGreatWorkNotOwnedIsRejectedAndStateUnchanged() {
        val cityTile = testGame.tileMap[0, 0]
        val city = testGame.addCity(civInfo, cityTile)
        addBuildingNamed(city, "TestMuseum", "Provides [1] [Art] Great Work slots")
        val manager = testGame.gameInfo.greatWorkManager

        // A work created by a DIFFERENT civ, unplaced -> not owned by civInfo.
        val foreignWork = com.unciv.logic.civilization.managers.GreatWork().apply {
            id = manager.newId()
            type = com.unciv.models.ruleset.GreatWorkType.Art
            creatingCivName = enemyCiv.civName
        }
        manager.registerWork(foreignWork)

        assertThrows(CommandException::class.java) {
            executor.execute(
                testGame.gameInfo, civInfo.civID,
                GameCommand.MoveGreatWork(foreignWork.id, cityTile.position.x, cityTile.position.y, "TestMuseum", 0)
            )
        }
        assertTrue("No placement may have been created on a rejected move", manager.slotPlacements.isEmpty())
    }

    @Test
    fun moveGreatWorkWithTypeMismatchIsRejected() {
        val cityTile = testGame.tileMap[0, 0]
        val city = testGame.addCity(civInfo, cityTile)
        // Only a Writing slot exists; an Art work does not fit it.
        addBuildingNamed(city, "TestLibrary", "Provides [1] [Writing] Great Work slots")
        val manager = testGame.gameInfo.greatWorkManager
        val artWork = makeArtWork()

        assertThrows(CommandException::class.java) {
            executor.execute(
                testGame.gameInfo, civInfo.civID,
                GameCommand.MoveGreatWork(artWork.id, cityTile.position.x, cityTile.position.y, "TestLibrary", 0)
            )
        }
        assertTrue("No placement may have been created on a type-mismatched move", manager.slotPlacements.isEmpty())
    }

    @Test
    fun moveUnknownGreatWorkIsRejected() {
        val cityTile = testGame.tileMap[0, 0]
        val city = testGame.addCity(civInfo, cityTile)
        addBuildingNamed(city, "TestMuseum", "Provides [1] [Art] Great Work slots")

        assertThrows(CommandException::class.java) {
            executor.execute(
                testGame.gameInfo, civInfo.civID,
                GameCommand.MoveGreatWork("gw-nonexistent", cityTile.position.x, cityTile.position.y, "TestMuseum", 0)
            )
        }
    }

    // endregion

    // region EstablishTradeRoute (BNW Phase 3 — Increment 2)

    /** Synthesize the `Trade Route` stockpile token the BNW ruleset would supply (G&K lacks it). */
    private fun ensureTradeRouteResource(): com.unciv.models.ruleset.tile.TileResource {
        testGame.ruleset.tileResources[com.unciv.logic.trade.TradeRouteManager.TRADE_ROUTE_RESOURCE]?.let { return it }
        val resource = testGame.createResource("Stockpiled")
        testGame.ruleset.tileResources.remove(resource.name)
        resource.name = com.unciv.logic.trade.TradeRouteManager.TRADE_ROUTE_RESOURCE
        testGame.ruleset.tileResources[com.unciv.logic.trade.TradeRouteManager.TRADE_ROUTE_RESOURCE] = resource
        return resource
    }

    private fun addLandTradeUnit(owner: Civilization, tile: Tile): MapUnit {
        val baseUnit = testGame.createBaseUnit(
            "Civilian", "Costs [1] [Trade Route]", "Can establish trade routes between cities"
        )
        baseUnit.movement = 2
        return testGame.addUnit(baseUnit.name, owner, tile)
    }

    @Test
    fun establishTradeRouteRecordsTheRoute() {
        val resource = ensureTradeRouteResource()
        val originTile = testGame.tileMap[0, 0]
        val origin = testGame.addCity(civInfo, originTile)
        val destTile = testGame.tileMap[3, 0]
        val dest = testGame.addCity(civInfo, destTile)
        civInfo.gainStockpiledResource(resource, 1)
        // New ITR model: the trade unit stands ON its own ORIGIN city center; that city is the route origin.
        val unit = addLandTradeUnit(civInfo, originTile)

        executor.execute(
            testGame.gameInfo, civInfo.civID,
            GameCommand.EstablishTradeRoute(originTile.position.x, originTile.position.y, destTile.position.x, destTile.position.y)
        )

        val manager = testGame.gameInfo.tradeRouteManager
        assertEquals("A trade route must have been recorded", 1, manager.connections.size)
        val route = manager.connections.first()
        assertEquals("Origin must be the unit's own city", origin.id, route.originCityId)
        assertEquals("Destination must be the targeted city", dest.id, route.destinationCityId)
        assertEquals(civInfo.civID, route.ownerCivId)
        assertEquals("The parked unit must have spent its movement", 0f, unit.currentMovement, 0f)
    }

    @Test
    fun establishInternationalTradeRouteFromOwnCityCenter() {
        val resource = ensureTradeRouteResource()
        val originTile = testGame.tileMap[0, 0]
        val origin = testGame.addCity(civInfo, originTile)
        val foreignCity = testGame.addCity(enemyCiv, testGame.tileMap[3, 0])
        // Open borders so the land route may cross the foreign civ's territory to reach its city.
        civInfo.diplomacyFunctions.makeCivilizationsMeet(enemyCiv)
        civInfo.getDiplomacyManager(enemyCiv)!!.hasOpenBorders = true
        civInfo.gainStockpiledResource(resource, 1)
        // New ITR model: the unit stands ON its own origin city center; the destination may be foreign.
        val unit = addLandTradeUnit(civInfo, originTile)

        executor.execute(
            testGame.gameInfo, civInfo.civID,
            GameCommand.EstablishTradeRoute(
                originTile.position.x, originTile.position.y,
                foreignCity.getCenterTile().position.x, foreignCity.getCenterTile().position.y
            )
        )

        val manager = testGame.gameInfo.tradeRouteManager
        assertEquals("One international route must have been recorded", 1, manager.connections.size)
        val route = manager.connections.first()
        assertEquals("Origin must be the unit's own city", origin.id, route.originCityId)
        assertEquals("Destination must be the foreign city", foreignCity.id, route.destinationCityId)
        assertEquals(civInfo.civID, route.ownerCivId)
        assertEquals("The parked unit must have spent its movement", 0f, unit.currentMovement, 0f)
    }

    @Test
    fun establishTradeRouteWithNoCapacityIsRejectedAndStateUnchanged() {
        ensureTradeRouteResource()
        val originTile = testGame.tileMap[0, 0]
        testGame.addCity(civInfo, originTile)
        val destTile = testGame.tileMap[3, 0]
        testGame.addCity(civInfo, destTile)
        // No tokens granted -> capacity 0. Unit on its own origin city center.
        addLandTradeUnit(civInfo, originTile)

        assertThrows(CommandException::class.java) {
            executor.execute(
                testGame.gameInfo, civInfo.civID,
                GameCommand.EstablishTradeRoute(originTile.position.x, originTile.position.y, destTile.position.x, destTile.position.y)
            )
        }
        assertTrue("No route may exist after a rejected establish", testGame.gameInfo.tradeRouteManager.connections.isEmpty())
    }

    @Test
    fun establishTradeRouteByNonOwnerIsRejected() {
        val resource = ensureTradeRouteResource()
        val originTile = testGame.tileMap[0, 0]
        testGame.addCity(civInfo, originTile)
        val destTile = testGame.tileMap[3, 0]
        testGame.addCity(civInfo, destTile)
        civInfo.gainStockpiledResource(resource, 1)
        addLandTradeUnit(civInfo, originTile) // unit owned by civInfo, not enemyCiv

        assertThrows(CommandException::class.java) {
            executor.execute(
                testGame.gameInfo, enemyCiv.civID,
                GameCommand.EstablishTradeRoute(originTile.position.x, originTile.position.y, destTile.position.x, destTile.position.y)
            )
        }
        assertTrue(testGame.gameInfo.tradeRouteManager.connections.isEmpty())
    }

    @Test
    fun establishTradeRouteWithNonTradeUnitIsRejected() {
        ensureTradeRouteResource()
        val originTile = testGame.tileMap[0, 0]
        testGame.addCity(civInfo, originTile)
        val destTile = testGame.tileMap[3, 0]
        testGame.addCity(civInfo, destTile)
        // A plain Warrior is not a trade unit.
        testGame.addUnit("Warrior", civInfo, originTile)

        assertThrows(CommandException::class.java) {
            executor.execute(
                testGame.gameInfo, civInfo.civID,
                GameCommand.EstablishTradeRoute(originTile.position.x, originTile.position.y, destTile.position.x, destTile.position.y)
            )
        }
        assertTrue(testGame.gameInfo.tradeRouteManager.connections.isEmpty())
    }

    @Test
    fun establishTradeRouteToOwnCityItselfIsRejected() {
        val resource = ensureTradeRouteResource()
        val originTile = testGame.tileMap[0, 0]
        testGame.addCity(civInfo, originTile)
        civInfo.gainStockpiledResource(resource, 1)
        // The unit stands on its own city and targets that same city -> dest == origin.
        addLandTradeUnit(civInfo, originTile)

        assertThrows(CommandException::class.java) {
            executor.execute(
                testGame.gameInfo, civInfo.civID,
                GameCommand.EstablishTradeRoute(originTile.position.x, originTile.position.y, originTile.position.x, originTile.position.y)
            )
        }
        assertTrue(testGame.gameInfo.tradeRouteManager.connections.isEmpty())
    }

    @Test
    fun establishTradeRouteNotOnOwnCityCenterIsRejected() {
        val resource = ensureTradeRouteResource()
        testGame.addCity(civInfo, testGame.tileMap[0, 0])
        val destTile = testGame.tileMap[3, 0]
        testGame.addCity(civInfo, destTile)
        civInfo.gainStockpiledResource(resource, 1)
        // The unit stands on a plain (non-city) tile -> the new origin gate rejects it.
        val unitTile = testGame.tileMap[1, 0]
        addLandTradeUnit(civInfo, unitTile)

        assertThrows(CommandException::class.java) {
            executor.execute(
                testGame.gameInfo, civInfo.civID,
                GameCommand.EstablishTradeRoute(unitTile.position.x, unitTile.position.y, destTile.position.x, destTile.position.y)
            )
        }
        assertTrue(testGame.gameInfo.tradeRouteManager.connections.isEmpty())
    }

    @Test
    fun establishTradeRouteToUnknownDestinationIsRejected() {
        val resource = ensureTradeRouteResource()
        val originTile = testGame.tileMap[0, 0]
        testGame.addCity(civInfo, originTile)
        civInfo.gainStockpiledResource(resource, 1)
        addLandTradeUnit(civInfo, originTile)
        // (1,0) is not a city center -> requireCityCenterTile rejects the destination.
        val nonCityTile = testGame.tileMap[1, 0]

        assertThrows(CommandException::class.java) {
            executor.execute(
                testGame.gameInfo, civInfo.civID,
                GameCommand.EstablishTradeRoute(originTile.position.x, originTile.position.y, nonCityTile.position.x, nonCityTile.position.y)
            )
        }
        assertTrue(testGame.gameInfo.tradeRouteManager.connections.isEmpty())
    }

    @Test
    fun establishTradeRouteCommandRoundTripsThroughKotlinx() {
        val command: GameCommand = GameCommand.EstablishTradeRoute(1, 2, 3, 4)
        val encoded = com.unciv.network.serialization.relayJson.encodeToString(GameCommand.serializer(), command)
        val decoded = com.unciv.network.serialization.relayJson.decodeFromString(GameCommand.serializer(), encoded)
        assertTrue(decoded is GameCommand.EstablishTradeRoute)
        decoded as GameCommand.EstablishTradeRoute
        assertEquals(1, decoded.unitX)
        assertEquals(2, decoded.unitY)
        assertEquals(3, decoded.destCityX)
        assertEquals(4, decoded.destCityY)
    }

    // endregion

    // region MoveTradeUnitToCity (BNW Phase 3 — ITR)

    @Test
    fun moveTradeUnitToCityRelocatesTheUnitAndSpendsItsTurn() {
        ensureTradeRouteResource()
        val fromTile = testGame.tileMap[0, 0]
        testGame.addCity(civInfo, fromTile)
        val toTile = testGame.tileMap[3, 0]
        val toCity = testGame.addCity(civInfo, toTile)
        val unit = addLandTradeUnit(civInfo, fromTile)

        executor.execute(
            testGame.gameInfo, civInfo.civID,
            GameCommand.MoveTradeUnitToCity(fromTile.position.x, fromTile.position.y, toTile.position.x, toTile.position.y)
        )

        assertEquals("The trade unit must now sit on the destination city center", toCity.getCenterTile(), unit.currentTile)
        assertEquals("The destination tile's trade slot must hold the moved unit", unit, toCity.getCenterTile().tradeUnit)
        assertNull("The source tile's trade slot must be cleared", fromTile.tradeUnit)
        assertEquals("The move must consume the unit's whole turn", 0f, unit.currentMovement, 0f)
    }

    @Test
    fun moveTradeUnitToACityHoldingATradeUnitIsRejected() {
        ensureTradeRouteResource()
        val fromTile = testGame.tileMap[0, 0]
        testGame.addCity(civInfo, fromTile)
        val toTile = testGame.tileMap[3, 0]
        testGame.addCity(civInfo, toTile)
        val unit = addLandTradeUnit(civInfo, fromTile)
        // Another trade unit already occupies the destination city center's trade slot.
        addLandTradeUnit(civInfo, toTile)

        assertThrows(CommandException::class.java) {
            executor.execute(
                testGame.gameInfo, civInfo.civID,
                GameCommand.MoveTradeUnitToCity(fromTile.position.x, fromTile.position.y, toTile.position.x, toTile.position.y)
            )
        }
        assertEquals("The unit must not have moved on a rejected relocation", fromTile, unit.currentTile)
    }

    @Test
    fun moveTradeUnitToAForeignCityIsRejected() {
        ensureTradeRouteResource()
        val fromTile = testGame.tileMap[0, 0]
        testGame.addCity(civInfo, fromTile)
        val foreignTile = testGame.tileMap[3, 0]
        testGame.addCity(enemyCiv, foreignTile)
        val unit = addLandTradeUnit(civInfo, fromTile)

        assertThrows(CommandException::class.java) {
            executor.execute(
                testGame.gameInfo, civInfo.civID,
                GameCommand.MoveTradeUnitToCity(fromTile.position.x, fromTile.position.y, foreignTile.position.x, foreignTile.position.y)
            )
        }
        assertEquals(fromTile, unit.currentTile)
    }

    @Test
    fun moveTradeUnitCommandRoundTripsThroughKotlinx() {
        val command: GameCommand = GameCommand.MoveTradeUnitToCity(5, 6, 7, 8)
        val encoded = com.unciv.network.serialization.relayJson.encodeToString(GameCommand.serializer(), command)
        val decoded = com.unciv.network.serialization.relayJson.decodeFromString(GameCommand.serializer(), encoded)
        assertTrue(decoded is GameCommand.MoveTradeUnitToCity)
        decoded as GameCommand.MoveTradeUnitToCity
        assertEquals(5, decoded.unitX)
        assertEquals(6, decoded.unitY)
        assertEquals(7, decoded.destCityX)
        assertEquals(8, decoded.destCityY)
    }

    // endregion

    // region SellExoticGoods (BNW Portuguese Nau)

    /** A unit that may sell exotic goods [uses] times. */
    private fun addExoticGoodsUnit(owner: Civilization, tile: Tile, uses: Int = 2): MapUnit {
        val baseUnit = testGame.createBaseUnit(
            "Civilian", "Can perform the Sell Exotic Goods action [$uses] times"
        )
        baseUnit.movement = 2
        return testGame.addUnit(baseUnit.name, owner, tile)
    }

    @Test
    fun sellExoticGoodsGrantsGoldAndXpAndSpendsTheTurn() {
        // A tile owned by nobody (no city founded) is "foreign/neutral" — the action is allowed there.
        val tile = testGame.tileMap[0, 0]
        val unit = addExoticGoodsUnit(civInfo, tile)
        unit.currentMovement = 2f
        val goldBefore = civInfo.gold
        val xpBefore = unit.promotions.XP

        executor.execute(
            testGame.gameInfo, civInfo.civID,
            GameCommand.SellExoticGoods(tile.position.x, tile.position.y)
        )

        assertTrue("Selling exotic goods must grant Gold", civInfo.gold > goldBefore)
        assertTrue("Selling exotic goods must grant XP", unit.promotions.XP > xpBefore)
        assertEquals("The sale must spend the unit's whole turn", 0f, unit.currentMovement, 0f)
    }

    @Test
    fun sellExoticGoodsInOwnTerritoryIsRejectedAndStateUnchanged() {
        val tile = testGame.tileMap[0, 0]
        testGame.addCity(civInfo, tile) // now civInfo OWNS this tile — selling here is not allowed
        val unit = addExoticGoodsUnit(civInfo, tile)
        unit.currentMovement = 2f
        val goldBefore = civInfo.gold

        assertThrows(CommandException::class.java) {
            executor.execute(
                testGame.gameInfo, civInfo.civID,
                GameCommand.SellExoticGoods(tile.position.x, tile.position.y)
            )
        }
        assertEquals("Gold must be unchanged after a rejected sale", goldBefore, civInfo.gold)
    }

    @Test
    fun sellExoticGoodsBeyondTheUseLimitIsRejected() {
        val tile = testGame.tileMap[0, 0]
        val unit = addExoticGoodsUnit(civInfo, tile, uses = 1)
        unit.currentMovement = 2f
        // Use the single allowed sale.
        executor.execute(
            testGame.gameInfo, civInfo.civID,
            GameCommand.SellExoticGoods(tile.position.x, tile.position.y)
        )
        // Refund movement so ONLY the spent use-limit can block the next attempt.
        unit.currentMovement = 2f
        assertThrows(CommandException::class.java) {
            executor.execute(
                testGame.gameInfo, civInfo.civID,
                GameCommand.SellExoticGoods(tile.position.x, tile.position.y)
            )
        }
    }

    @Test
    fun sellExoticGoodsCommandRoundTripsThroughKotlinx() {
        val command: GameCommand = GameCommand.SellExoticGoods(9, 10)
        val decoded = com.unciv.network.serialization.relayJson.decodeFromString(
            GameCommand.serializer(),
            com.unciv.network.serialization.relayJson.encodeToString(GameCommand.serializer(), command)
        )
        assertTrue(decoded is GameCommand.SellExoticGoods)
        decoded as GameCommand.SellExoticGoods
        assertEquals(9, decoded.unitX)
        assertEquals(10, decoded.unitY)
    }

    // endregion

    // region World Congress (BNW Phase 3 — Increment 2)

    /** Found the congress (era-0) and force it into the Proposing phase. */
    private fun foundCongressInProposing() {
        testGame.ruleset.modOptions.constants.worldCongressFoundingEra = 0
        // A congress member is an ALIVE major civ (getMemberCivs() == getAliveMajorCivs()). A civ with no
        // city and no unit is isDefeated() (no original capital -> getCivUnitsSize() == 0), so it would NOT
        // be a member and tryFoundCongress() would find no founders. Give civInfo a unit so it is alive and
        // founding (and the member-gated propose/vote commands below) work. Scoped to the WC helper: only the
        // WC command tests call it, and they don't place their own cities/units, so there is no collision.
        if (civInfo.units.getCivUnitsSize() == 0 && civInfo.cities.isEmpty())
            testGame.addUnit("Warrior", civInfo, testGame.tileMap[0, 0])
        val congress = testGame.gameInfo.congress
        congress.tryFoundCongress()
        congress.recomputeDelegates()
        congress.currentPhase = com.unciv.logic.civilization.managers.CongressPhase.Proposing
    }

    @Test
    fun proposeResolutionAddsProposal() {
        foundCongressInProposing()
        val congress = testGame.gameInfo.congress

        executor.execute(
            testGame.gameInfo, civInfo.civID,
            GameCommand.ProposeResolution(com.unciv.logic.civilization.ResolutionType.SciencesFunding.name)
        )

        assertEquals("A proposal must have been added", 1, congress.activeProposals.size)
        assertEquals(com.unciv.logic.civilization.ResolutionType.SciencesFunding.name,
            congress.activeProposals.first().resolutionType)
        assertEquals(civInfo.civID, congress.activeProposals.first().proposerCivId)
    }

    @Test
    fun proposeResolutionWhenNotProposingIsRejected() {
        testGame.ruleset.modOptions.constants.worldCongressFoundingEra = 0
        testGame.gameInfo.congress.tryFoundCongress()
        // Phase is Idle, not Proposing.
        assertThrows(CommandException::class.java) {
            executor.execute(
                testGame.gameInfo, civInfo.civID,
                GameCommand.ProposeResolution(com.unciv.logic.civilization.ResolutionType.SciencesFunding.name)
            )
        }
        assertTrue(testGame.gameInfo.congress.activeProposals.isEmpty())
    }

    @Test
    fun proposeUnknownResolutionTypeIsRejected() {
        foundCongressInProposing()
        assertThrows(CommandException::class.java) {
            executor.execute(testGame.gameInfo, civInfo.civID, GameCommand.ProposeResolution("NoSuchResolution"))
        }
    }

    @Test
    fun proposeByNonMemberIsRejected() {
        foundCongressInProposing()
        // A city-state is not a member.
        val cityState = testGame.addCiv(cityStateType = "Cultured")
        assertThrows(CommandException::class.java) {
            executor.execute(
                testGame.gameInfo, cityState.civID,
                GameCommand.ProposeResolution(com.unciv.logic.civilization.ResolutionType.SciencesFunding.name)
            )
        }
    }

    @Test
    fun castCongressVoteRecordsTheFullBloc() {
        foundCongressInProposing()
        val congress = testGame.gameInfo.congress
        val proposal = congress.addProposal(civInfo, com.unciv.logic.civilization.ResolutionType.SciencesFunding)
        congress.currentPhase = com.unciv.logic.civilization.managers.CongressPhase.Voting

        val delegates = congress.getDelegateCount(civInfo)
        executor.execute(
            testGame.gameInfo, civInfo.civID,
            GameCommand.CastCongressVote(proposal.id, delegates, voteFor = true)
        )

        assertEquals("The full bloc must be recorded FOR", delegates, proposal.votesFor[civInfo.civID])
    }

    @Test
    fun castCongressVoteWhenNotVotingIsRejected() {
        foundCongressInProposing()
        val congress = testGame.gameInfo.congress
        val proposal = congress.addProposal(civInfo, com.unciv.logic.civilization.ResolutionType.SciencesFunding)
        // Still in Proposing, not Voting.
        assertThrows(CommandException::class.java) {
            executor.execute(
                testGame.gameInfo, civInfo.civID,
                GameCommand.CastCongressVote(proposal.id, congress.getDelegateCount(civInfo), true)
            )
        }
    }

    @Test
    fun castCongressVoteWithWrongDelegateCountIsRejected() {
        foundCongressInProposing()
        val congress = testGame.gameInfo.congress
        val proposal = congress.addProposal(civInfo, com.unciv.logic.civilization.ResolutionType.SciencesFunding)
        congress.currentPhase = com.unciv.logic.civilization.managers.CongressPhase.Voting
        val wrong = congress.getDelegateCount(civInfo) + 5
        assertThrows(CommandException::class.java) {
            executor.execute(testGame.gameInfo, civInfo.civID, GameCommand.CastCongressVote(proposal.id, wrong, true))
        }
        assertFalse("No vote may be recorded on a rejected cast", proposal.hasVoted(civInfo.civID))
    }

    @Test
    fun castCongressVoteTwiceIsRejected() {
        foundCongressInProposing()
        val congress = testGame.gameInfo.congress
        val proposal = congress.addProposal(civInfo, com.unciv.logic.civilization.ResolutionType.SciencesFunding)
        congress.currentPhase = com.unciv.logic.civilization.managers.CongressPhase.Voting
        val delegates = congress.getDelegateCount(civInfo)
        executor.execute(testGame.gameInfo, civInfo.civID, GameCommand.CastCongressVote(proposal.id, delegates, true))
        assertThrows(CommandException::class.java) {
            executor.execute(testGame.gameInfo, civInfo.civID, GameCommand.CastCongressVote(proposal.id, delegates, true))
        }
    }

    @Test
    fun castCongressVoteOnUnknownProposalIsRejected() {
        foundCongressInProposing()
        testGame.gameInfo.congress.currentPhase = com.unciv.logic.civilization.managers.CongressPhase.Voting
        assertThrows(CommandException::class.java) {
            executor.execute(testGame.gameInfo, civInfo.civID, GameCommand.CastCongressVote(999, 1, true))
        }
    }

    @Test
    fun congressCommandsRoundTripThroughKotlinx() {
        val propose: GameCommand = GameCommand.ProposeResolution("BanLuxury", "", "Silk")
        val proposeDecoded = com.unciv.network.serialization.relayJson.decodeFromString(
            GameCommand.serializer(),
            com.unciv.network.serialization.relayJson.encodeToString(GameCommand.serializer(), propose)
        )
        assertTrue(proposeDecoded is GameCommand.ProposeResolution)
        proposeDecoded as GameCommand.ProposeResolution
        assertEquals("BanLuxury", proposeDecoded.resolutionType)
        assertEquals("Silk", proposeDecoded.choiceArg)

        val vote: GameCommand = GameCommand.CastCongressVote(3, 4, false)
        val voteDecoded = com.unciv.network.serialization.relayJson.decodeFromString(
            GameCommand.serializer(),
            com.unciv.network.serialization.relayJson.encodeToString(GameCommand.serializer(), vote)
        )
        assertTrue(voteDecoded is GameCommand.CastCongressVote)
        voteDecoded as GameCommand.CastCongressVote
        assertEquals(3, voteDecoded.proposalId)
        assertEquals(4, voteDecoded.delegates)
        assertFalse(voteDecoded.voteFor)
    }

    // endregion

    // region City-state gift & tribute

    /** A met city-state with a capital on [capitalTile] (a capital is needed for tribute willingness). */
    private fun addMetCityState(capitalTile: Tile): Civilization {
        val cs = testGame.addCiv(cityStateType = "Cultured")
        testGame.addCity(cs, capitalTile)
        civInfo.diplomacyFunctions.makeCivilizationsMeet(cs)
        return cs
    }

    @Test
    fun giftGoldDeductsGoldAndRaisesInfluence() {
        val cs = addMetCityState(testGame.tileMap[2, 0])
        civInfo.addGold(1000)
        val goldBefore = civInfo.gold
        val influenceBefore = cs.getDiplomacyManager(civInfo)!!.getInfluence()

        executor.execute(testGame.gameInfo, civInfo.civID, GameCommand.GiftGold(cs.civName, 500))

        assertEquals("Gift must deduct the donor's gold", goldBefore - 500, civInfo.gold)
        assertTrue("Gift must raise the city-state's influence toward the donor",
            cs.getDiplomacyManager(civInfo)!!.getInfluence() > influenceBefore)
    }

    @Test
    fun tributeGoldGrantsGoldWhenCityStateIsWilling() {
        val csCapitalTile = testGame.tileMap[2, 0]
        val cs = addMetCityState(csCapitalTile)
        // Surround the city-state capital with overwhelming military so its gold tribute willingness is
        // non-negative — the same gate the "Take gold" button uses to enable itself.
        for (neighbor in csCapitalTile.neighbors.filter { it.isLand })
            testGame.addUnit("Warrior", civInfo, neighbor)
        assertTrue("Precondition: the city-state must be willing to pay gold tribute",
            cs.cityStateFunctions.getTributeWillingness(civInfo, demandingWorker = false) >= 0)
        val goldBefore = civInfo.gold

        executor.execute(testGame.gameInfo, civInfo.civID, GameCommand.TributeGold(cs.civName))

        assertTrue("Demanding gold tribute must grant the demander gold", civInfo.gold > goldBefore)
    }

    @Test
    fun tributeGoldRejectedWhenCityStateUnwillingAndStateUnchanged() {
        // Deeply negative influence (< -30) drives tribute willingness negative -> the demand is refused.
        val cs = addMetCityState(testGame.tileMap[2, 0])
        cs.getDiplomacyManager(civInfo)!!.setInfluenceWithoutSideEffects(-40f)
        assertTrue("Precondition: a hostile (very-low-influence) city-state must be unwilling",
            cs.cityStateFunctions.getTributeWillingness(civInfo, demandingWorker = false) < 0)
        val goldBefore = civInfo.gold

        assertThrows(CommandException::class.java) {
            executor.execute(testGame.gameInfo, civInfo.civID, GameCommand.TributeGold(cs.civName))
        }
        assertEquals("A rejected tribute must not change the demander's gold", goldBefore, civInfo.gold)
    }

    @Test
    fun tributeOnNonCityStateIsRejected() {
        // enemyCiv is a major civ, not a city-state.
        civInfo.diplomacyFunctions.makeCivilizationsMeet(enemyCiv)
        assertThrows(CommandException::class.java) {
            executor.execute(testGame.gameInfo, civInfo.civID, GameCommand.TributeGold(enemyCiv.civName))
        }
    }

    @Test
    fun tributeCommandsRoundTripThroughKotlinx() {
        for (command in listOf<GameCommand>(GameCommand.TributeGold("Brussels"), GameCommand.TributeWorker("Brussels"))) {
            val decoded = com.unciv.network.serialization.relayJson.decodeFromString(
                GameCommand.serializer(),
                com.unciv.network.serialization.relayJson.encodeToString(GameCommand.serializer(), command)
            )
            assertEquals(command, decoded)
        }
    }

    // endregion
}
