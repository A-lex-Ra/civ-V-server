package com.unciv.uniques

import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.Event
import com.unciv.models.ruleset.EventChoice
import com.unciv.models.ruleset.Policy
import com.unciv.models.ruleset.PolicyBranch
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BNW Phase 2a — Increment 3: free tenets from wonders / level-3 ideology buildings.
 *
 * ## Gap assessment (why this is a test-only increment)
 * The bundled "Civ V - Brave New World" data ALREADY routes every free-tenet source through the
 * generic event + `freePolicies` machinery, and that machinery already works in v3:
 *
 *  - **Early-adopter bonus** — `Policies.json` fires `Triggers a [[Ideology]: Free [<branch>] Tenet] event`
 *    on adopting Order / Freedom / Autocracy (2 free tenets for the first adopter, 1 for the second).
 *  - **The three ideology wonders** — Statue of Liberty (Freedom), Kremlin (Order) and Prora (Autocracy)
 *    in `Buildings.json` each carry `Free Social Policy`, which increments `freePolicies`; the picker
 *    then offers the civ's adopted-ideology tenets to spend it on. (This is the bundled mod's faithful
 *    rendering of those wonders' "free tenet" effect — kept verbatim, not re-balanced.)
 *  - The `[Ideology]: Free [X] Tenet` events in `Events.json` present `Adopt [tenet]` choices that, via
 *    [com.unciv.models.ruleset.unique.UniqueType.OneTimeAdoptPolicyOrBelief], grant the tenet for free.
 *
 * In v3 this whole chain is the already-committed ResolveEvent keystone:
 * `Triggers a [event] event` ([com.unciv.models.ruleset.unique.UniqueType.TriggerEvent]) auto-resolves
 * for AI / `None`-presentation events and queues a `PopupAlert(AlertType.Event)` for human / `Alert`
 * events, which `GameCommand.ResolveEvent` then resolves on the authority. **No genuine content gap
 * remains**, so this increment adds no data and no Kotlin primitive — only the proof below that a
 * free-tenet event grants a tenet end-to-end, at zero culture cost.
 *
 * The default test ruleset (Civ V - Gods & Kings) has no native ideologies, so we synthesize one
 * mutually-exclusive ideology branch + a member tenet, exactly as `PublicOpinionManagerTest` does, then
 * exercise the real trigger/event/adopt path against it.
 */
@RunWith(GdxTestRunner::class)
class IdeologyFreeTenetTest {

    private lateinit var testGame: TestGame
    private lateinit var ideology: PolicyBranch
    private lateinit var tenet: Policy

    private val freeTenetEventName = "[Ideology]: Free [TestIdeology] Tenet"

    @Before
    fun setUp() {
        testGame = TestGame()
        testGame.makeHexagonalMap(2)

        // A mutually-exclusive ideology branch (detected as an ideology via the same data markers the
        // bundled BNW ideologies carry) plus one member tenet to be granted by the free-tenet event.
        tenet = testGame.createPolicy()
        ideology = testGame.createPolicyBranch(
            "Unavailable <after adopting [SomeOtherIdeology]> <hidden from users>",
            "Remove [Ideology] [in capital] <hidden from users>",
            policy = tenet
        )

        // The free-tenet event: a single choice that adopts the tenet (mirrors the bundled
        // "[Ideology]: Free [X] Tenet" events, whose choices are all "Adopt [<tenet>]").
        val event = Event().apply {
            name = freeTenetEventName
            choices.add(EventChoice().apply {
                this.name = "$freeTenetEventName-Adopt"
                uniques.add("Adopt [${tenet.name}]")
            })
        }
        testGame.ruleset.events[freeTenetEventName] = event
    }

    /** A HUMAN major civ with one city (so it is alive) that has already adopted [ideology].
     *  Human matters for the wonder test: an Alert-presentation event only queues a PopupAlert for a
     *  human civ — an AI civ auto-resolves it inside the trigger (UniqueTriggerActivation's isAI branch). */
    private fun addCivInIdeology(): Civilization {
        val civ = testGame.addCiv(isPlayer = true)
        testGame.addCity(civ, testGame.getTile(HexCoord.Zero), replacePalace = true)
        civ.policies.getAdoptedPolicies().add(ideology.name)
        return civ
    }

    @Test
    fun `resolving a free-tenet event grants the tenet at no culture cost`() {
        val civ = addCivInIdeology()
        val cultureBefore = civ.policies.storedCulture
        // The cost of the next *paid* policy is driven by the count of paid policies adopted; a FREE
        // tenet must not advance it (numberOfAdoptedPolicies is private, so we observe it via this cost).
        val nextPolicyCostBefore = civ.policies.getCultureNeededForNextPolicy()

        assertFalse("Precondition: the tenet must not be adopted yet",
            civ.policies.isAdopted(tenet.name))

        // Resolve the event's only choice exactly as the v3 CommandExecutor.executeResolveEvent and the
        // AI auto-resolve path both do — by running the chosen choice's triggerable uniques.
        val event = testGame.ruleset.events[freeTenetEventName]!!
        event.choices[0].triggerChoice(civ)

        assertTrue("The free-tenet event's Adopt-tenet choice must grant the tenet",
            civ.policies.isAdopted(tenet.name))
        // The grant must be FREE: a free tenet costs no stored culture and does not raise the price of
        // the next paid policy (i.e. it didn't consume a culture-progression step).
        assertEquals("A free tenet must not consume stored culture",
            cultureBefore, civ.policies.storedCulture)
        assertEquals("A free tenet must not advance the paid-policy cost",
            nextPolicyCostBefore, civ.policies.getCultureNeededForNextPolicy())
        assertEquals("All free policies granted by the event must have been consumed by the adoption",
            0, civ.policies.freePolicies)
    }

    @Test
    fun `a wonder that triggers the free-tenet event queues an alert, and resolving it grants the tenet`() {
        val civ = addCivInIdeology()
        val city = civ.cities.first()

        // A wonder that fires the free-tenet event on construction — the realistic shape of the bundled
        // ideology wonders' free-tenet effect routed through an event trigger.
        val wonder = testGame.createWonder("Triggers a [$freeTenetEventName] event")

        assertFalse("Precondition: the tenet must not be adopted before the wonder triggers",
            civ.policies.isAdopted(tenet.name))

        // Construct-time trigger (the same call CityConstructions.addBuilding makes): for an
        // Alert-presentation event this queues a PopupAlert rather than adopting immediately (the human
        // path; AI / None-presentation events would auto-resolve inside the trigger instead).
        city.cityConstructions.triggerNewBuildingUniques(wonder)

        val pendingAlert = civ.popupAlerts.firstOrNull {
            it.type == AlertType.Event && it.value.substringBefore(com.unciv.Constants.stringSplitCharacter) == freeTenetEventName
        }
        assertTrue("Triggering the wonder must queue the free-tenet event as a pending alert",
            pendingAlert != null)

        // Resolve it (mirrors CommandExecutor.executeResolveEvent: run the chosen choice, consume alert).
        val event = testGame.ruleset.events[freeTenetEventName]!!
        event.choices[0].triggerChoice(civ)
        civ.popupAlerts.remove(pendingAlert)

        assertTrue("Resolving the wonder-triggered free-tenet event must grant the tenet",
            civ.policies.isAdopted(tenet.name))
        assertTrue("The resolved free-tenet alert must be consumed",
            civ.popupAlerts.none { it.type == AlertType.Event })
    }
}
