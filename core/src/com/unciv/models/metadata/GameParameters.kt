package com.unciv.models.metadata

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.civilization.PlayerType
import com.unciv.models.ruleset.Speed

class GameParameters : IsPartOfGameInfoSerialization { // Default values are the default new game
    var difficulty = "Prince"
    var speed: String = Speed.DEFAULT // Not an instance of class Speed

    var randomNumberOfPlayers = false
    var minNumberOfPlayers = 3
    var maxNumberOfPlayers = 3
    var players = ArrayList<Player>().apply {
        add(Player(playerType = PlayerType.Human))
        repeat(3) { add(Player()) }
    }
    var randomNumberOfCityStates = false
    var minNumberOfCityStates = 6
    var maxNumberOfCityStates = 6
    var numberOfCityStates = 6

    var enableRandomNationsPool = false
    var randomNationsPool = arrayListOf<String>()

    var noCityRazing = false
    var noBarbarians = false
    var ragingBarbarians = false
    var oneCityChallenge = false
    var godMode = false
    var nuclearWeaponsEnabled = true
    var espionageEnabled = false
    var noStartBias = false
    var shufflePlayerOrder = false

    var victoryTypes: ArrayList<String> = arrayListOf()
    var startingEra = "Ancient era"

    var showVictoryStats = true
    var showDemographics = false

    // Multiplayer parameters
    var isOnlineMultiplayer = false
    /**
     * EXPERIMENTAL / PREVIEW (multiplayer-v3). When set, the game is hosted through the
     * authoritative command-in / filtered-view-out netcode (docs/multiplayer-v3.md) instead of the
     * classic PBEM file-store path. This flag is **additive and strictly behind a UI toggle**; the
     * classic [isOnlineMultiplayer] path is unchanged.
     *
     * Relationship to [isOnlineMultiplayer]: v2 *is* a kind of multiplayer, but it does **not** use
     * the v1 createGame/upload/poll machinery. We therefore keep [isOnlineMultiplayer] = false for a
     * v2 game so none of the v1 MP code paths (upload on nextTurn, spectate checks, deep-link
     * download, MultiplayerStatusButton, etc.) engage. v2 is selected solely by this flag; the two
     * flags are mutually exclusive at the UI (turning one on turns the other off).
     */
    var isMultiplayerV3 = false
    var multiplayerServerUrl: String? = null
    var anyoneCanSpectate = true
    /** After this amount of minutes, anyone can choose to 'skip turn' of the current player to keep the game going */
    var minutesUntilSkipTurn = 60 * 24
    /** Initial players' timer to play before they can be forced to resign permanently*/
    var minutesUntilForceResign = 3 * 24 * 60
    /** Time a player recover on their timer before they can be forced to resign. Time isn't added if the player get their turn skipped*/
    var minutesRecoveredPerTurn = 60 * 24

    // Serialization default stays Gods & Kings for SAVE COMPATIBILITY: gdx Json omits fields equal to
    // this default, so older saves written under the historical G&K default carry no baseRuleset and must
    // still resolve to G&K on load. The NEW-GAME default ("Civ V - All DLC") is applied in the new-game
    // setup path (GameSetupInfo.fromSettings / NewGameScreen "Reset to defaults"), NOT here.
    var baseRuleset: String = BaseRuleset.Civ_V_GnK.fullName
    var mods = LinkedHashSet<String>()

    var maxTurns = 500

    var acceptedModCheckErrors = ""

    fun clone(): GameParameters {
        val parameters = GameParameters()
        parameters.difficulty = difficulty
        parameters.speed = speed
        parameters.randomNumberOfPlayers = randomNumberOfPlayers
        parameters.minNumberOfPlayers = minNumberOfPlayers
        parameters.maxNumberOfPlayers = maxNumberOfPlayers
        parameters.players = ArrayList(players)
        parameters.randomNumberOfCityStates = randomNumberOfCityStates
        parameters.minNumberOfCityStates = minNumberOfCityStates
        parameters.maxNumberOfCityStates = maxNumberOfCityStates
        parameters.numberOfCityStates = numberOfCityStates
        parameters.enableRandomNationsPool = enableRandomNationsPool
        parameters.randomNationsPool = ArrayList(randomNationsPool)
        parameters.noCityRazing = noCityRazing
        parameters.noBarbarians = noBarbarians
        parameters.ragingBarbarians = ragingBarbarians
        parameters.oneCityChallenge = oneCityChallenge
        // godMode intentionally reset on clone
        parameters.nuclearWeaponsEnabled = nuclearWeaponsEnabled
        parameters.espionageEnabled = espionageEnabled
        parameters.noStartBias = noStartBias
        parameters.shufflePlayerOrder = shufflePlayerOrder
        parameters.victoryTypes = ArrayList(victoryTypes)
        parameters.startingEra = startingEra
        parameters.showVictoryStats = showVictoryStats
        parameters.showDemographics = showDemographics
        parameters.isOnlineMultiplayer = isOnlineMultiplayer
        parameters.isMultiplayerV3 = isMultiplayerV3
        parameters.multiplayerServerUrl = multiplayerServerUrl
        parameters.anyoneCanSpectate = anyoneCanSpectate
        parameters.baseRuleset = baseRuleset
        parameters.mods = LinkedHashSet(mods)
        parameters.maxTurns = maxTurns
        parameters.acceptedModCheckErrors = acceptedModCheckErrors
        return parameters
    }

    // For debugging and GameStarter console output
    override fun toString() = sequence {
            yield("$difficulty $speed $startingEra")
            yield("${players.count { it.playerType == PlayerType.Human }} ${PlayerType.Human}")
            yield("${players.count { it.playerType == PlayerType.AI }} ${PlayerType.AI}")
            if (randomNumberOfPlayers) yield("Random number of Players: $minNumberOfPlayers..$maxNumberOfPlayers")
            if (randomNumberOfCityStates) yield("Random number of City-States: $minNumberOfCityStates..$maxNumberOfCityStates")
            else yield("$numberOfCityStates CS")
            if (isOnlineMultiplayer) yield("Online Multiplayer")
            if (isMultiplayerV3) yield("Authoritative Multiplayer (experimental)")
            if (noBarbarians) yield("No barbs")
            if (ragingBarbarians) yield("Raging barbs")
            if (oneCityChallenge) yield("OCC")
            if (!nuclearWeaponsEnabled) yield("No nukes")
            if (godMode) yield("God mode")
            yield("Enabled Victories: " + victoryTypes.joinToString())
            yield(baseRuleset)
            yield(if (mods.isEmpty()) "no mods" else mods.joinToString(",", "mods=(", ")", 6) )
        }.joinToString(prefix = "(", postfix = ")")

    /** Get all mods including base
     *
     *  The returned Set is ordered base first, then in the order they are stored in a save.
     *  This creates a fresh instance, and the caller is allowed to mutate it.
     */
    fun getModsAndBaseRuleset() =
        LinkedHashSet<String>(mods.size + 1).apply {
            add(baseRuleset)
            addAll(mods)
        }
}
