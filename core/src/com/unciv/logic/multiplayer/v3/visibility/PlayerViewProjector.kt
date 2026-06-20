package com.unciv.logic.multiplayer.v3.visibility

import com.unciv.logic.GameInfo
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile

/**
 * Phase 3a — the visibility-filtered projection (the anti-maphack core, design goal #3 in
 * docs/multiplayer-v3.md §2).
 *
 * Given the canonical [GameInfo] and the civ id of a viewing player, [projectFor] returns a
 * **redacted deep copy** that is safe to put on the wire to that player: state the player may not
 * legally see is removed. This covers, in priority order:
 *
 *  - fogged enemy units, undiscovered enemy cities, unseen barbarian camps ([redactUnits],
 *    [redactCities], [redactBarbarianEncampments]);
 *  - **other civs' interior secrets** — gold, stockpiles, tech, policies, diplomacy internals with
 *    third parties, espionage, notifications/popups/trade requests, and production knowledge
 *    ([redactOtherCivSecrets]);
 *  - **seen-but-foreign city interiors** — building lists, construction queue, citizen assignment,
 *    food/production stockpiles, while keeping the city's existence/name/position/owner
 *    ([redactSeenEnemyCityInteriors]);
 *  - **unexplored tile contents** — resource/improvement/road/feature/wonder hidden on tiles the
 *    viewer has never seen, and the "remembered" ([Civilization.lastSeenImprovement]) improvement
 *    substituted on explored-but-currently-fogged tiles ([redactTileContents]).
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
 * (we already have the canonical visibility). We rebuild ONLY the tileMap's transients (with the
 * canonical ruleset) so position-keyed lookups and per-tile terrain transients are available for
 * redaction. All redaction operates on the cloned **serialized** state — exactly the fields a
 * client deserializes off the wire (the [PlayerView] frame carries a gzipped serialized GameInfo).
 *
 * ### Contract: the clone must still deserialize + run full setTransients() on the client
 *
 * Every removal/clear below leaves the cloned [GameInfo] structurally valid: we never remove a civ
 * (so every `DiplomacyManager.otherCivName` still resolves), we detach a removed city from the
 * tiles that referenced it, and we only ever *empty* serialized collections / zero serialized
 * scalars (never null out a `lateinit`/structural field such as `Tile.baseTerrain` or
 * `DiplomacyManager.otherCivName`). An empty tech/policy/buildings set is itself a valid state
 * (it is the game-start state), so the client's `setTransients()` rebuilds clean transients from it.
 *
 * ### Conservative stance
 *
 * When unsure whether the viewer may see something about *another* civ, we hide it. We intentionally
 * leave a few things alone where redacting them would risk the deserialization/setTransients
 * contract or where the data is legitimately observable — see the TODOs at the relevant call sites.
 */
object PlayerViewProjector {

    /** Snapshot of one civ's visibility, keyed by tile position so it survives the clone. */
    private class VisibilitySnapshot(
        val visiblePositions: Set<HexCoord>,
        val exploredPositions: Set<HexCoord>
    ) {
        fun canSee(tile: Tile) = tile.position in visiblePositions
        fun hasExplored(tile: Tile) = tile.position in exploredPositions
        fun hasExploredPosition(position: HexCoord) = position in exploredPositions
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
        // The remembered-improvement map is read off the CANONICAL viewer (its transients are live)
        // and applied to the clone by position, so we don't depend on the clone's transient state.
        val rememberedImprovements = HashMap(canonicalViewer.lastSeenImprovement)

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

        // 3. Redact the clone by position / by civ. Order: units & cities (which may remove cities)
        //    first, then strip the interiors of the cities that survived, then per-civ secrets, then
        //    tile contents.
        redactUnits(projected, viewingCivId, visibility)
        redactCities(projected, viewingCivId, visibility)
        redactSeenEnemyCityInteriors(projected, viewingCivId, visibility)
        redactBarbarianEncampments(projected, visibility)
        redactOtherCivSecrets(projected, viewingCivId)
        redactTradeRoutes(projected, viewingCivId)
        redactGreatWorkPlacements(projected, viewingCivId, visibility)
        redactTileContents(projected, visibility, rememberedImprovements)

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
                // Key off the serialized center position (city.location), NOT the transient
                // getCenterTileOrNull(): we deliberately skip City.setTransients() on the clone, so
                // the cloned city's centerTile is uninitialized and getCenterTileOrNull() is always
                // null here. location is always present and equals the center tile's position.
                !visibility.hasExploredPosition(city.location)
            }
            if (hiddenCities.isEmpty()) continue

            for (city in hiddenCities) detachCityFromTiles(city, projected)
            civ.cities = civ.cities.filter { it !in hiddenCities }
        }
    }

    /**
     * For cities of OTHER civs that survived [redactCities] (i.e. the viewer has explored their
     * center, so the city's existence/name/position/owner legitimately stays visible), strip the
     * interior detail the viewer cannot legally know:
     *  - the built-buildings list and the current construction / production queue;
     *  - citizen assignment (worked & locked tiles) and specialist allocation;
     *  - food and production stockpiles.
     *
     * Everything cleared here is a serialized collection emptied or a serialized scalar zeroed, so
     * the clone still deserializes and `City.setTransients()` (which rebuilds `builtBuildingObjects`
     * from the now-empty `builtBuildings`) runs without throwing.
     */
    private fun redactSeenEnemyCityInteriors(
        projected: GameInfo,
        viewerId: String,
        visibility: VisibilitySnapshot
    ) {
        for (civ in projected.civilizations) {
            if (civ.civID == viewerId) continue
            for (city in civ.cities) {
                // Defensive: only strip cities that are actually visible-by-exploration. After
                // redactCities every remaining other-civ city qualifies, but guard anyway so this is
                // safe regardless of call order. Key off the serialized center position
                // (city.location) since the clone's transient centerTile is uninitialized here.
                if (!visibility.hasExploredPosition(city.location)) continue
                stripCityInterior(city)
            }
        }
    }

    private fun stripCityInterior(city: City) {
        // Construction / production knowledge.
        val constructions = city.cityConstructions
        constructions.builtBuildings.clear()
        constructions.inProgressConstructions.clear()
        constructions.constructionQueue.clear()
        constructions.productionOverflow = 0
        constructions.currentConstructionIsUserSet = false
        constructions.freeBuildingsProvidedFromThisCity.clear()

        // Citizen assignment & specialists.
        city.workedTiles.clear()
        city.lockedTiles.clear()
        city.population.getNewSpecialists().clear()
        city.population.foodStored = 0

        // City-level stockpiled resources.
        city.resourceStockpiles.clear()
    }

    /**
     * Scrub a single non-viewer civ's top-level interior secrets — the things a hostile client must
     * not be able to read about another player's economy / plans. The viewer's own civ is excluded
     * by the caller's id check; we never touch it.
     */
    private fun redactOtherCivSecrets(projected: GameInfo, viewerId: String) {
        for (civ in projected.civilizations) {
            if (civ.civID == viewerId) continue
            scrubCivSecrets(civ)
        }
    }

    private fun scrubCivSecrets(civ: Civilization) {
        // --- Treasury & stockpiled resources ---
        if (civ.gold != 0) civ.addGold(-civ.gold) // gold has a private setter; addGold is the public path
        civ.resourceStockpiles.clear()

        // --- Tech (known/researched techs + research progress) ---
        // Clearing the researched-tech set is a valid state (game start); the client's
        // tech.setTransients() rebuilds researchedTechnologies / era from the empty set.
        civ.tech.techsResearched.clear()
        civ.tech.techsInProgress.clear()
        civ.tech.techsToResearch.clear()
        civ.tech.freeTechs = 0
        civ.tech.repeatingTechsResearched = 0
        civ.tech.scienceFromResearchAgreements = 0

        // --- Adopted policies & stored culture ---
        civ.policies.getAdoptedPolicies().clear()
        civ.policies.freePolicies = 0
        civ.policies.storedCulture = 0
        civ.policies.shouldOpenPolicyPicker = false

        // --- Ideological public opinion (BNW Phase 2a, authority-only state — D2) ---
        // A rival's ideology is already hidden (adopted policies are scrubbed above), so its derived
        // public opinion must be hidden too, or a client could infer the rival's ideology from the
        // pressure meter. Clearing the map and zeroing the penalty is a valid empty state (matches a
        // civ with no ideology), so the client's setTransients() deserializes it cleanly. The
        // Increment-2 switch state (anarchy countdown / forced-switch flag) is likewise an authority
        // secret about a rival's internal politics — zero/clear it (a valid "not switching" state).
        civ.publicOpinion.ideologyPressureByBranch.clear()
        civ.publicOpinion.dissidentUnhappiness = 0
        civ.publicOpinion.anarchyTurnsRemaining = 0
        civ.publicOpinion.forcedSwitchPending = false

        // --- Tourism influence (BNW Phase 2b, authority-only state — D2) ---
        // A rival's accumulated influence over *other* civs is computed from culture/buildings the
        // client cannot see, so it is an authority secret: clear it. The empty map is a valid "no
        // influence yet" state that the client's setTransients() deserializes cleanly. The viewer's
        // own tourism lives on the viewer civ (skipped by the civID guard in redactOtherCivSecrets),
        // and the publicly-observable culture-defense (totalCultureForContests) is left intact.
        civ.tourism.accumulatedInfluence.clear()

        // --- Production / purchasing knowledge held at the civ level ---
        civ.civConstructions.boughtItemsWithIncreasingPrice.clear()
        civ.civConstructions.builtItemsWithIncreasingCost.clear()

        // --- Espionage ---
        civ.espionageManager.spyList.clear()
        civ.espionageManager.erasSpyEarnedFor.clear()

        // --- Notifications / popups / trade requests (purely this civ's private UI/turn state) ---
        civ.notifications.clear()
        civ.notificationsLog.clear()
        civ.notificationCountAtStartTurn = null
        civ.popupAlerts.clear()
        civ.tradeRequests.clear()

        // --- Diplomacy internals with third parties ---
        // Keep the diplomacy *map* intact (so otherCivName still resolves and met/at-war status is
        // preserved — the viewer legitimately knows who has met whom enough for setTransients), but
        // clear the AI-internal relationship payload the viewer must not read.
        for (diplomacyManager in civ.diplomacy.values) {
            diplomacyManager.trades.clear()
            diplomacyManager.diplomaticModifiers.clear()
            diplomacyManager.flagsCountdown.clear()
            diplomacyManager.influence = 0f
            diplomacyManager.totalOfScienceDuringRA = 0
        }

        // TODO(phase-3+): the smoothed-opinion fields (DiplomacyManager.smoothedOpinionOfOtherCiv /
        //   cachedSmoothedOpinionOfOtherCiv) are AI relationship internals but have private setters,
        //   so they cannot be scrubbed from this sibling object without a dedicated redaction API on
        //   DiplomacyManager. They leak only a coarse AI opinion scalar between third parties; left
        //   for a follow-up rather than widening DiplomacyManager's API from here.
        // TODO(phase-3+): religion/victory/quest/great-person/golden-age managers are NOT scrubbed.
        //   Some of their state is legitimately public (founded religion, score-relevant data) and
        //   blindly clearing them risks the setTransients contract (e.g. religionManager wiring).
        //   Decide per-field what is secret vs. observable in a dedicated pass.
    }

    /**
     * Hide the *contents* of tiles the viewer may not see:
     *  - **Never explored**: strip resource / improvement / road / terrain features / natural wonder
     *    so a hostile client can't read them. We deliberately keep [Tile.baseTerrain] (it is a
     *    `lateinit` structural field the client's setTransients requires) — see the TODO below.
     *  - **Explored but currently fogged**: replace the live improvement with the viewer's
     *    *remembered* one ([Civilization.lastSeenImprovement]); other live contents (resource, road,
     *    features) are kept as last-seen, matching how the engine remembers an explored tile.
     *
     * Tiles the viewer can currently see are left fully intact.
     */
    private fun redactTileContents(
        projected: GameInfo,
        visibility: VisibilitySnapshot,
        rememberedImprovements: Map<HexCoord, String>
    ) {
        for (tile in projected.tileMap.values) {
            if (visibility.canSee(tile)) continue // currently visible -> the viewer sees it for real

            if (!visibility.hasExplored(tile)) {
                hideUnexploredTileContents(tile)
            } else {
                // Explored but fogged: show the remembered improvement instead of the live one.
                tile.improvement = rememberedImprovements[tile.position]
                tile.improvementIsPillaged = false
                tile.improvementQueue.clear()
            }
        }
    }

    private fun hideUnexploredTileContents(tile: Tile) {
        // Resource (clears both the serialized `resource` string and the transient cache).
        tile.tileResource = null
        tile.resourceAmount = 0

        // Improvement + any in-progress improvement.
        tile.improvement = null
        tile.improvementIsPillaged = false
        tile.improvementQueue.clear()

        // Roads.
        tile.roadStatus = RoadStatus.None
        tile.roadIsPillaged = false

        // Terrain features & natural wonder. baseTerrain stays (structural / lateinit).
        tile.naturalWonder = null
        tile.setTerrainFeatures(emptyList())

        // TODO(phase-3+): Tile.baseTerrain itself still leaks the land/water shape of never-explored
        //   tiles. It cannot simply be nulled (it is a non-null lateinit the client's setTransients
        //   requires) — fully hiding it would mean substituting a neutral placeholder terrain and is
        //   left for a follow-up to avoid corrupting the cloned map's structural invariants here.
    }

    /**
     * BNW Phase 3 — Increment 5 (D5). International-Trade-Route connections are scrubbed so a player sees
     * only routes they are entitled to: **their own routes fully; routes touching one of their own cities
     * fully; purely-rival routes removed.** A rival↔rival route would otherwise leak the existence and
     * relationship of two unseen cities.
     *
     * Keep a connection iff it is owned by the viewer OR its origin/destination is one of the viewer's
     * cities. This reads ONLY serialized fields ([com.unciv.logic.trade.TradeRouteConnection] ids + the
     * cloned civ/city ids) — the projector deliberately skips full `setTransients`, so
     * `tradeRouteManager.gameInfo` is NOT set on the clone and we must not call any gameInfo-dependent
     * helper here. Runs once per projection (the manager is GameInfo-level), so it lives here, not in the
     * per-civ [scrubCivSecrets].
     */
    private fun redactTradeRoutes(projected: GameInfo, viewerId: String) {
        val connections = projected.tradeRouteManager.connections
        if (connections.isEmpty()) return

        val myCityIds = projected.civilizations
            .firstOrNull { it.civID == viewerId }
            ?.cities?.mapTo(HashSet()) { it.id }
            ?: HashSet()

        connections.removeAll { c ->
            !(c.ownerCivId == viewerId || c.originCityId in myCityIds || c.destinationCityId in myCityIds)
        }
    }

    /**
     * BNW Phase 2c — Increment 3 (D5). Great Works are *public* (names/types/theming/owner are visible
     * in the Culture Overview), so the GameInfo-level [com.unciv.logic.civilization.managers.GreatWorkManager]
     * registry ([com.unciv.logic.civilization.managers.GreatWorkManager.works]) is NOT scrubbed.
     *
     * The one leak to close: a *placement* whose host city the viewer has never explored would reveal
     * that an unseen rival city exists (and where). So we drop every placement whose slot belongs to a
     * civ other than the viewer AND whose city location the viewer has not explored — leaving the
     * [com.unciv.logic.civilization.managers.GreatWork] object itself intact in the registry.
     *
     * This runs once per projection (the manager is GameInfo-level, not per-civ), so it lives here and
     * NOT inside the per-civ [scrubCivSecrets]. The slot key is the flat
     * `"$civId|$x,$y|$building|$idx"` produced by `GreatWorkSlot.key()`; we parse the owning civId and
     * the city `(x,y)` back out of it to test against the viewer's explored set.
     */
    private fun redactGreatWorkPlacements(
        projected: GameInfo,
        viewerId: String,
        visibility: VisibilitySnapshot
    ) {
        val placements = projected.greatWorkManager.slotPlacements
        if (placements.isEmpty()) return

        val keysToDrop = placements.keys.filter { key ->
            // key = "civId|x,y|building|idx". civNames don't contain '|', so split on '|' is safe.
            val segments = key.split('|')
            if (segments.size < 2) return@filter false // malformed — leave it alone
            val slotCivId = segments[0]
            if (slotCivId == viewerId) return@filter false // the viewer's own placements are always kept

            val coords = segments[1].split(',')
            val x = coords.getOrNull(0)?.toIntOrNull()
            val y = coords.getOrNull(1)?.toIntOrNull()
            if (x == null || y == null) return@filter false // malformed — leave it alone

            // Drop only when the viewer has NOT explored the host city's location.
            !visibility.hasExploredPosition(HexCoord(x, y))
        }.toList()

        for (key in keysToDrop) placements.remove(key)
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
