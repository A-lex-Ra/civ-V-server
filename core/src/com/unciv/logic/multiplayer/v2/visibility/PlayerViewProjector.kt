package com.unciv.logic.multiplayer.v2.visibility

import com.unciv.logic.GameInfo
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile

/**
 * Phase 3a — the visibility-filtered projection (the anti-maphack core, design goal #3 in
 * docs/multiplayer-v2.md §2).
 *
 * Given the canonical [GameInfo] and the civ id of a viewing player, [projectFor] returns a
 * **redacted deep copy** that is safe to put on the wire to that player: state the player may not
 * legally see (fogged enemy units, undiscovered enemy cities, unseen barbarians) is removed.
 *
 * ### How it works (cheapest correct approach)
 *
 *  1. Read the viewing civ's visibility off the **canonical** [GameInfo] — the engine keeps
 *     `Civilization.viewableTiles` (current fog) and per-tile `exploredBy` (ever-seen) live while
 *     the authority runs. We snapshot the visible / explored tile **positions** before touching
 *     anything. (`cache.updateOurTiles()` is called first only to guarantee the transient
 *     visibility set is fresh; it recomputes derived state, not serialized game state.)
 *  2. `gameInfo.clone()` — the engine's deep clone (tileMap + units, civilizations, barbarians,
 *     religions). This is the same clone the Undo / next-turn paths use. The clone's tile layout is
 *     identical to the canonical one, so a position keys a tile in both.
 *  3. Redact the clone by position, using the snapshot — **never** mutating the canonical
 *     [GameInfo]. The viewing civ's own data is left fully intact.
 *
 * We deliberately do **not** call `setTransients()` on the clone: that would re-resolve the ruleset
 * from `RulesetCache` and recompute every transient, which is both expensive and unnecessary here
 * (we already have the canonical visibility). Redaction works purely on the cloned serialized state.
 *
 * ### Conservative stance
 *
 * When unsure whether the viewer may see something about *another* civ, we hide it. This first cut
 * covers the map/unit/city/barbarian visibility the maintainer named. Finer per-civ secrets (exact
 * gold, tech progress, diplomacy internals, fog-tile contents reconstruction) are deliberately
 * **not** scrubbed yet — see the TODOs below. Those are additive and do not change the core property
 * this stage proves: a client never *receives* an enemy unit/city it cannot see.
 *
 * TODO(phase-3+): blank the *contents* (improvement / resource / road / city footprint) of tiles the
 *   viewer has never explored, reconstructing the "remembered" state from `lastSeenImprovement`
 *   instead of leaking the live tile. Left intact here to avoid corrupting the cloned map's
 *   structural invariants in this first cut; only units/cities/camps are removed.
 * TODO(phase-3+): redact other civs' interior secrets (gold, tech, policies, diplomacy, espionage,
 *   notifications, trade requests) so a hostile client cannot read another player's economy.
 */
object PlayerViewProjector {

    /** Snapshot of one civ's visibility, keyed by tile position so it survives the clone. */
    private class VisibilitySnapshot(
        val visiblePositions: Set<HexCoord>,
        val exploredPositions: Set<HexCoord>
    ) {
        fun canSee(tile: Tile) = tile.position in visiblePositions
        fun hasExplored(tile: Tile) = tile.position in exploredPositions
    }

    /**
     * Return a redacted deep copy of [gameInfo] safe to send to the player controlling
     * [viewingCivId]. The canonical [gameInfo]'s logical/serialized state is **not** mutated.
     *
     * @throws IllegalArgumentException if [viewingCivId] is not a civ in the game.
     */
    fun projectFor(gameInfo: GameInfo, viewingCivId: String): GameInfo {
        // 1. Snapshot the viewer's visibility from the canonical (live-transient) game.
        val canonicalViewer = gameInfo.getCivilizationOrNull(viewingCivId)
            ?: throw IllegalArgumentException("Viewing civ '$viewingCivId' is not part of this game")
        val visibility = snapshotVisibility(gameInfo, canonicalViewer)

        // 2. Deep-clone via the engine. Tile positions are preserved, so the snapshot keys both.
        val projected = gameInfo.clone()
        // clone() copies serialized fields only — the cloned TileMap has no rebuilt index yet, so
        // indexed lookups (TileMap.get/getOrNull, used by our city redaction) would fail. Rebuild
        // ONLY the tileMap's transients, reusing the canonical (already-resolved) ruleset. We pass
        // it explicitly so this does NOT re-resolve the ruleset from RulesetCache, and we skip the
        // full GameInfo.setTransients() entirely (no nation re-resolution, no civ recompute) — see
        // the class doc. setUnitCivTransients=false: we only read MapUnit.owner (a String) and call
        // Tile.removeUnit, neither of which needs the unit-to-civ object link.
        projected.tileMap.gameInfo = projected
        projected.tileMap.setTransients(gameInfo.ruleset, setUnitCivTransients = false)

        // 3. Redact the clone by position.
        redactUnits(projected, viewingCivId, visibility)
        redactCities(projected, viewingCivId, visibility)
        redactBarbarianEncampments(projected, visibility)

        return projected
    }

    /** Capture the viewer's current visibility from the canonical game without altering game state. */
    private fun snapshotVisibility(gameInfo: GameInfo, viewer: Civilization): VisibilitySnapshot {
        // Make sure the transient visibility set is current (recomputes derived caches only).
        viewer.cache.updateOurTiles()

        val visible = viewer.viewableTiles.mapTo(HashSet()) { it.position }
        val explored = gameInfo.tileMap.values.asSequence()
            .filter { it.isExplored(viewer) }
            .mapTo(HashSet()) { it.position }
        return VisibilitySnapshot(visible, explored)
    }

    /**
     * Remove every unit owned by a civ *other than the viewer* that sits on a tile the viewer
     * cannot currently see (fog of war). The viewer's own units are always kept, and any
     * other-civ unit standing on a currently-visible tile is kept (the viewer legitimately sees it).
     */
    private fun redactUnits(projected: GameInfo, viewerId: String, visibility: VisibilitySnapshot) {
        for (tile in projected.tileMap.values) {
            if (visibility.canSee(tile)) continue // the viewer sees this tile right now -> reveal units on it

            // Fogged (or never-seen) tile: strip any unit not owned by the viewer.
            tile.militaryUnit?.let { if (it.owner != viewerId) tile.removeUnit(it) }
            tile.civilianUnit?.let { if (it.owner != viewerId) tile.removeUnit(it) }
            if (tile.airUnits.isNotEmpty()) {
                for (airUnit in tile.airUnits.toList())
                    if (airUnit.owner != viewerId) tile.removeUnit(airUnit)
            }
        }
    }

    /**
     * Remove other civs' cities whose center tile the viewer has never explored. A city the viewer
     * has seen at least once stays (matching how the engine keeps a remembered city on a now-fogged
     * tile). The viewer's own cities are never touched.
     */
    private fun redactCities(projected: GameInfo, viewerId: String, visibility: VisibilitySnapshot) {
        for (civ in projected.civilizations) {
            if (civ.civID == viewerId) continue // never redact the viewer's own cities
            if (civ.cities.isEmpty()) continue

            val hiddenCities = civ.cities.filter { city ->
                val centerTile = city.getCenterTileOrNull() ?: return@filter false
                !visibility.hasExplored(centerTile)
            }
            if (hiddenCities.isEmpty()) continue

            for (city in hiddenCities) detachCityFromTiles(city, projected)
            civ.cities = civ.cities.filter { it !in hiddenCities }
        }
    }

    /**
     * Remove barbarian encampments the viewer cannot currently see. Barbarian *units* are handled by
     * [redactUnits] (the barbarian civ is just another civ). Camps are tracked separately in
     * [com.unciv.logic.automation.civilization.BarbarianManager.encampments] and keyed by position.
     */
    private fun redactBarbarianEncampments(projected: GameInfo, visibility: VisibilitySnapshot) {
        val encampments = projected.barbarians.encampments
        if (encampments.isEmpty()) return
        encampments.removeAll { encampment -> encampment.position !in visibility.visiblePositions }
    }

    /** Best-effort: clear the city pointer off tiles that referenced it, so the redacted clone has
     *  no dangling city reference on the map the viewer can see. */
    private fun detachCityFromTiles(city: City, projected: GameInfo) {
        for (position in city.tiles) {
            val tile = projected.tileMap.getOrNull(position.x, position.y) ?: continue
            if (tile.owningCity == city) tile.setOwningCity(null)
        }
    }
}
