package com.unciv.logic.multiplayer.v3.client

import com.unciv.logic.GameInfo
import com.unciv.network.game.GameFrame

/**
 * The thin, UI-less client-side holder of a player's **visibility-filtered** view of the game
 * (docs/multiplayer-v3.md §4 "View model"). A client never owns the canonical [GameInfo]; it only
 * holds the redacted snapshot the authority last sent it.
 *
 * Phase 3 (sequential): on each [GameFrame.PlayerView] the holder decodes the snapshot via
 * [GameInfoCodec] — which gunzips, deserialises and runs `setTransients()` — and exposes the
 * resulting [GameInfo] as [currentView]. Delta application + prediction land in later phases.
 */
class ClientGameView {

    /** The latest decoded filtered view, or `null` before the first [PlayerView] has arrived. */
    var currentView: GameInfo? = null
        private set

    /** The turn number of [currentView], or `-1` if no view has been received yet. */
    var turn: Int = -1
        private set

    /**
     * Apply an inbound [PlayerView]: decode (gunzip -> deserialise -> `setTransients()`) and replace
     * [currentView] with the result. Returns the freshly decoded [GameInfo].
     */
    fun onPlayerView(view: GameFrame.PlayerView): GameInfo {
        val decoded = GameInfoCodec.decode(view.gzippedFilteredGameInfo)
        currentView = decoded
        turn = view.turn
        return decoded
    }
}
