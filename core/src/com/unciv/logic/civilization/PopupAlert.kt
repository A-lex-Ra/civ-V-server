package com.unciv.logic.civilization

import com.unciv.logic.IsPartOfGameInfoSerialization

enum class AlertType : IsPartOfGameInfoSerialization {
    Defeated,
    WonderBuilt,
    TechResearched,
    WarDeclaration,
    FirstContact,
    CityConquered,
    CityTraded,
    BorderConflict,
    TilesStolen,

    DemandToStopSettlingCitiesNear,
    CitySettledNearOtherCivDespiteOurPromise,

    DemandToStopSpreadingReligion,
    ReligionSpreadDespiteOurPromise,
    
    DemandToStopSpyingOnUs,
    SpyingOnUsDespiteOurPromise,
    
    DemandToNotAttackUs,
    AttackedUsDespitePromise,
    
    AcceptingDemand,
    RejectingDemand,

    GoldenAge,
    DeclarationOfFriendship,
    StartIntro,
    DiplomaticMarriage,
    BulliedProtectedMinor,
    AttackedProtectedMinor,
    AttackedAllyMinor,
    RecapturedCivilian,
    GameHasBeenWon,
    Event,

    Denounced;

    /**
     * Whether this alert is resolved by an explicit **multiplayer-v3 player command** that consumes it
     * from the acting civ's `popupAlerts` on the authority — as opposed to a fire-once informational
     * popup the client merely shows and discards.
     *
     * The authority must NOT auto-clear these after delivering one snapshot (see
     * `GameSession.sendSnapshotTo`): an actionable alert has to survive until the player's resolving
     * command round-trips back, otherwise the command finds nothing to resolve. Demands are answered by
     * [GameCommand.DemandResponse][com.unciv.network.command.GameCommand.DemandResponse]; ruleset events
     * by [GameCommand.ResolveEvent][com.unciv.network.command.GameCommand.ResolveEvent].
     *
     * (City conquest — annex/raze/puppet — is intentionally NOT here: its commands key on the city, not
     * the `CityConquered` alert, so that alert stays fire-once like the informational ones.)
     */
    val isResolvedByPlayerCommand: Boolean
        get() = this in resolvedByPlayerCommand

    companion object {
        private val resolvedByPlayerCommand = setOf(
            DemandToStopSettlingCitiesNear,
            DemandToStopSpreadingReligion,
            DemandToStopSpyingOnUs,
            DemandToNotAttackUs,
            Event,
        )
    }
}

class PopupAlert : IsPartOfGameInfoSerialization {
    lateinit var type: AlertType
    lateinit var value: String

    constructor(type: AlertType, value: String) {
        this.type = type
        this.value = value
    }

    constructor() // for json serialization
}
