package com.unciv.logic.civilization.managers

import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.GreatWorkType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BNW Phase 2c — Increment 1: [GreatWorkManager] (the GameInfo-level Great-Work registry).
 *
 * Exercises the registry/placement API and the serialization discipline (D7): a clone is a deep copy
 * with distinct map instances, and a default-empty manager (an old save with no `greatWorkManager`)
 * is fully usable after [GreatWorkManager.setTransients] without NPE.
 */
@RunWith(GdxTestRunner::class)
class GreatWorkManagerTest {

    private lateinit var testGame: TestGame
    private lateinit var manager: GreatWorkManager

    @Before
    fun setUp() {
        testGame = TestGame()
        manager = testGame.gameInfo.greatWorkManager
        manager.setTransients(testGame.gameInfo)
    }

    private fun makeWork(type: GreatWorkType = GreatWorkType.Art, creator: String = "Babylon"): GreatWork {
        val work = GreatWork()
        work.id = manager.newId()
        work.type = type
        work.name = "Work-${work.id}"
        work.creatingCivName = creator
        work.artistName = "Artist-${work.id}"
        work.fromEra = "Ancient era"
        work.turnCreated = 5
        manager.registerWork(work)
        return work
    }

    private fun slotFor(work: GreatWork, index: Int = 0, building: String = "Museum") =
        GreatWorkSlot("Babylon", HexCoord(1, 2), building, index, work.type)

    @Test
    fun `register get and remove works`() {
        val work = makeWork()
        assertSame(work, manager.getWork(work.id))

        manager.removeWork(work.id)
        assertNull(manager.getWork(work.id))
    }

    @Test
    fun `newId yields unique sequential ids`() {
        val a = manager.newId()
        val b = manager.newId()
        assertEquals("gw0", a)
        assertEquals("gw1", b)
    }

    @Test
    fun `place getWorkInSlot and clearSlot`() {
        val work = makeWork()
        val slot = slotFor(work)

        assertNull(manager.getWorkInSlot(slot))
        manager.placeWork(work, slot)
        assertSame(work, manager.getWorkInSlot(slot))

        manager.clearSlot(slot)
        assertNull(manager.getWorkInSlot(slot))
    }

    @Test
    fun `removeWork also clears its placement`() {
        val work = makeWork()
        val slot = slotFor(work)
        manager.placeWork(work, slot)

        manager.removeWork(work.id)
        assertNull(manager.getWorkInSlot(slot))
        assertFalse(manager.slotPlacements.containsKey(slot.key()))
    }

    @Test
    fun `clone is a deep copy with distinct maps`() {
        val work = makeWork()
        val slot = slotFor(work)
        manager.placeWork(work, slot)

        val clone = manager.clone()
        clone.setTransients(testGame.gameInfo)

        // Distinct map instances.
        assertNotSame(manager.works, clone.works)
        assertNotSame(manager.slotPlacements, clone.slotPlacements)
        // Distinct work object instances (deep copy).
        assertNotSame(manager.getWork(work.id), clone.getWork(work.id))
        // nextId carried.
        assertEquals(manager.nextId, clone.nextId)

        // Mutating the clone's work does NOT affect the original.
        clone.getWork(work.id)!!.name = "MUTATED"
        assertEquals("Work-${work.id}", manager.getWork(work.id)!!.name)

        // getWork resolves on the clone, and placement round-trips.
        assertNotNull(clone.getWork(work.id))
        assertSame(clone.getWork(work.id), clone.getWorkInSlot(slot))
    }

    @Test
    fun `default empty manager is usable after setTransients`() {
        // Simulates an old save that lacked greatWorkManager: a freshly-constructed manager.
        val fresh = GreatWorkManager()
        fresh.setTransients(testGame.gameInfo)

        assertTrue(fresh.works.isEmpty())
        assertTrue(fresh.slotPlacements.isEmpty())
        // No NPE on any read path.
        assertNull(fresh.getWork("nope"))
        assertNull(fresh.getWorkInSlot(GreatWorkSlot("X", HexCoord(0, 0), "B", 0, GreatWorkType.Writing)))
        assertEquals("gw0", fresh.newId())
    }

    @Test
    fun `getWorksOf returns placed-in-civ-slots plus unplaced created works`() {
        val civ = testGame.addCiv()
        // Re-key the slot's civId to this civ's name.
        val placed = makeWork(creator = civ.civName)
        val placedSlot = GreatWorkSlot(civ.civName, HexCoord(0, 0), "Museum", 0, placed.type)
        manager.placeWork(placed, placedSlot)

        val unplacedOurs = makeWork(creator = civ.civName)        // registered, no placement
        val unplacedOther = makeWork(creator = "SomeoneElse")     // not ours, unplaced
        val placedElsewhere = makeWork(creator = civ.civName)     // placed in a DIFFERENT civ's slot
        manager.placeWork(placedElsewhere, GreatWorkSlot("OtherCiv", HexCoord(9, 9), "Museum", 0, placedElsewhere.type))

        val owned = manager.getWorksOf(civ).map { it.id }.toHashSet()
        assertTrue(placed.id in owned)
        assertTrue(unplacedOurs.id in owned)
        assertFalse(unplacedOther.id in owned)
        assertFalse(placedElsewhere.id in owned)
    }
}
