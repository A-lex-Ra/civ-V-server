package com.unciv.logic.multiplayer.v3.session

import com.unciv.logic.CompatibilityVersion
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.multiplayer.v3.client.GameInfoCodec
import com.unciv.logic.multiplayer.v3.command.CommandException
import com.unciv.logic.multiplayer.v3.command.CommandExecutor
import com.unciv.logic.multiplayer.v3.visibility.PlayerViewProjector
import com.unciv.network.Checksum
import com.unciv.network.PlayerId
import com.unciv.network.command.GameCommand
import com.unciv.network.game.GameFrame
import com.unciv.utils.Log

/**
 * The authoritative game session: owns the canonical [GameInfo], applies player commands through
 * the single [CommandExecutor] choke-point, runs inter-turn processing on `EndTurn`, and emits each
 * connected player their **own** visibility-filtered [GameFrame.PlayerView].
 *
 * The same class runs in **client-host** mode (a player's client *is* the authority) and in
 * **dedicated-server** mode (it runs in the server process), reachable via the relay exactly like
 * any other host. To stay transport-agnostic it does **not** know about relay envelopes: it decides
 * *what* frame goes to *which* [PlayerId] and hands it to the injected [outbound] sink; the sink's
 * owner maps [PlayerId] -> routing (wrapping per-player frames in
 * [com.unciv.network.relay.ClientToRelay.RelayTo], broadcasts in `Relay`, or sending directly in
 * dedicated mode).
 *
 * Sequential play: a player's client streams [GameFrame.PlayerCommand]s; the authority
 * validates+applies each and, on `EndTurn`, runs [GameInfo.nextTurn] and pushes a fresh per-player
 * filtered snapshot.
 *
 * Simultaneous play (the live UI path): every human acts at once and **streams** its
 * [GameFrame.PlayerCommand]s during a shared human phase — each applies immediately through the same
 * executor choke-point. `EndTurn` is a **barrier**: it marks a human done but does not advance the
 * turn until *every* rostered, alive, connected human has ended (see [onEndTurn] / [maybeResolveRound]),
 * at which point the round resolves once and pushes each human a fresh snapshot. Streaming is the only
 * simultaneous model — there is no whole-turn batch frame buffered on the authority; conflicts are
 * resolved by the order commands actually arrive, not by a post-hoc reorder of a buffered batch.
 */
class GameSession(
    /** The canonical, authoritative game state owned by this session. */
    val gameInfo: GameInfo,
    /**
     * Maps each connected [PlayerId] to the civ id (`Civilization.civID`) it controls. A
     * [GameFrame.PlayerCommand]'s `playerId` is resolved through this to the acting civ. The set of
     * human players that receive [GameFrame.PlayerView] snapshots is derived from this roster.
     */
    private val roster: Map<PlayerId, String>,
    /**
     * Tells the barrier which rostered players currently have a **live connection** to this
     * authority. The streaming round resolves once every CONNECTED + alive human has ended — a
     * rostered human who never joined (an unfilled lobby slot keyed by a UserId nobody connects with)
     * or who has dropped must NOT keep the round waiting forever ("Waiting for other players...").
     * The host loop feeds relay presence here (see [com.unciv.logic.multiplayer.v3.net.V3GameHost]).
     *
     * Defaults to "everyone is connected" so the direct-`onFrame` unit tests (which have no transport
     * and no presence signal) behave exactly as before. Declared before [outbound] so the trailing-
     * lambda `GameSession(gameInfo, roster) { ... }` call form still binds the lambda to [outbound].
     */
    private val isConnected: (PlayerId) -> Boolean = { true },
    /**
     * Outbound sink: the session calls this to send a [GameFrame] to a specific [PlayerId]. The sink
     * owner is responsible for the actual transport (relay `RelayTo` / dedicated direct send). Kept
     * here so the same [GameSession] works unchanged in both authority modes.
     */
    private val outbound: (PlayerId, GameFrame) -> Unit
) {
    private val commandExecutor = CommandExecutor()

    /**
     * Streaming-simultaneous turn barrier (the path the live UI uses): the set of human players who
     * have sent their `EndTurn` intent for the **current** human phase. Commands stream in and apply
     * immediately ([onPlayerCommand]); `EndTurn` does not advance the turn, it just records that this
     * human is done. Once every rostered human is done the round resolves (see [onEndTurn]).
     */
    private val endedThisPhase = mutableSetOf<PlayerId>()

    /**
     * Handle an inbound [GameFrame] from a client. [GameFrame.PlayerCommand] drives both turn models
     * (sequential and streaming-simultaneous); [GameFrame.ResyncRequest] re-syncs a (re)connecting
     * client. Other inbound frame kinds are ignored (provisional lockstep leftovers — acks, etc.).
     *
     * `@Synchronized`: in option A the host process is also a local client of this authority, so
     * frames now arrive from two threads — the transport receive thread (remote players) and the UI
     * thread (the host's own [com.unciv.logic.multiplayer.v3.net.V3GameHost.submitLocal]). Serialising
     * here keeps all canonical-state mutation single-threaded without the callers needing to know.
     */
    @Synchronized
    fun onFrame(frame: GameFrame) {
        when (frame) {
            is GameFrame.PlayerCommand -> onPlayerCommand(frame)
            is GameFrame.ResyncRequest -> onResyncRequest(frame)
            else -> Unit // Ack and the provisional lockstep frames: not used by v3.
        }
    }

    private fun onPlayerCommand(frame: GameFrame.PlayerCommand) {
        // Resolve the acting civ from the roster. An unknown playerId is itself a rejected command.
        val civId = roster[frame.playerId]
        if (civId == null) {
            outbound(frame.playerId, GameFrame.CommandRejected(frame.seq, "Unknown player '${frame.playerId}'"))
            return
        }

        // EndTurn is session-level orchestration (inter-turn processing + per-player snapshots), not
        // a single-GameInfo mutation, so we branch here BEFORE the executor rather than teaching the
        // CommandExecutor about nextTurn. The executor stays the choke-point for *mutations*; turn
        // advancement and view projection live in the session. (Design decision — see report.)
        if (frame.command is GameCommand.EndTurn) {
            onEndTurn(frame.playerId)
            return
        }

        // All other commands go through the single mutation choke-point. A rejection becomes a
        // directed CommandRejected to the issuing player; the canonical state is left untouched.
        try {
            commandExecutor.execute(gameInfo, civId, frame.command)
            // The command applied immediately, but the actor's filtered view is otherwise only
            // re-pushed at round resolution — so newly-revealed tiles/units (a unit that just finished
            // moving) or new borders (a bought tile, a founded city) would not appear until the turn
            // advanced. For commands that change what the actor can SEE or OWNS, push it a fresh
            // filtered snapshot NOW so reveal is instant. Directed to the actor only: other players,
            // possibly mid-action, must not have their screens churned by someone else's move (the
            // single round-resolution broadcast remains the only cross-player view update per round).
            if (changesActorView(frame.command)) sendSnapshotTo(frame.playerId, civId)
        } catch (e: CommandException) {
            outbound(frame.playerId, GameFrame.CommandRejected(frame.seq, e.message ?: "Command rejected"))
        }
    }

    /**
     * Whether [command] can change what the acting player can SEE (vision) or OWNS (borders) — the
     * commands after which the actor needs a fresh filtered snapshot mid-phase so reveal is instant
     * (todos.txt: "reveal of units/cities ... on finishing a move; buying tiles likewise"). Deliberately
     * narrow: commands that only change off-map state (production, research, diplomacy, promotions)
     * carry no new map information, so the client's own optimistic feedback plus the round-resolution
     * broadcast already cover them — re-pushing a full filtered view for those would just churn the
     * actor's screen for nothing.
     */
    private fun changesActorView(command: GameCommand): Boolean = when (command) {
        is GameCommand.MoveUnit,
        is GameCommand.SwapUnits,
        is GameCommand.Paradrop,
        is GameCommand.AttackUnit,
        is GameCommand.FoundCity,
        is GameCommand.BuyTile -> true
        // Resolving an event applies its (possibly random, possibly unit-granting/tile-revealing) effect
        // on the authority and consumes the pending alert. Push a fresh snapshot NOW so the actor sees
        // the authoritative outcome immediately — replacing the client's optimistic local resolution —
        // and so the now-consumed Event alert disappears from their view rather than re-prompting.
        is GameCommand.ResolveEvent -> true
        else -> false
    }

    /**
     * Reconnection / desync recovery (docs/multiplayer-v3.md §10). A (re)connecting or
     * desynced client sends a [GameFrame.ResyncRequest]; the authority answers with a **fresh**
     * directed [GameFrame.PlayerView] projected from the *current* canonical state — mid-turn, on
     * demand, not only at turn boundaries. Because the authority ships full per-player filtered
     * snapshots (not incremental semantic deltas yet), reconnection is simply "send a fresh full
     * PlayerView"; no missed-delta replay / [GameFrame.Ack] cursor is needed (see report).
     *
     * An unknown or non-human requester is rejected like the other handlers ([GameFrame.CommandRejected]),
     * and **no** [GameFrame.PlayerView] is emitted.
     *
     * **SECURITY (cross-cutting TODO — not solved here).** The authority currently trusts
     * [GameFrame.ResyncRequest.playerId] verbatim, exactly as it trusts [GameFrame.PlayerCommand.playerId].
     * A hostile client could therefore request *another* player's filtered view and read state it may
     * not see — a leak that violates hidden-information goal #3 (docs/multiplayer-v3.md §8). When the
     * transport->session host loop is wired, the requester's identity MUST be bound to the connection
     * (the relay's `Relayed.fromId`), not taken from the frame, before this snapshot is sent. The
     * wiring that would enforce this does not exist yet (tests drive [onFrame] directly).
     */
    private fun onResyncRequest(frame: GameFrame.ResyncRequest) {
        val civId = roster[frame.playerId]
        if (civId == null || !isHuman(civId)) {
            outbound(frame.playerId, GameFrame.CommandRejected(0, "Unknown or non-human player '${frame.playerId}'"))
            return
        }
        // A directed, current-state snapshot — the executor/turn paths are untouched.
        sendSnapshotTo(frame.playerId, civId)
    }

    /**
     * The rostered players whose civ is human, still in the game, AND currently connected — the set
     * whose `EndTurn` gates round resolution.
     *
     * **Defeated humans are excluded.** An eliminated player never sends another `EndTurn`, so counting
     * them would deadlock the streaming barrier: every surviving client would sit on "Waiting for other
     * players..." forever, because [onEndTurn] could never see *every* expected human as done.
     *
     * **Disconnected / never-joined humans are excluded** ([isConnected]). A rostered human that no
     * client ever connected as (an unfilled lobby slot) — or one whose connection dropped — likewise
     * never sends `EndTurn`. Gating on it is the same deadlock; the host loop feeds real relay presence
     * via [isConnected] so the round only waits on players that can actually still act.
     *
     * This is the *barrier* set (who we wait for), deliberately distinct from [aliveHumanTurnCount]
     * (how many `nextTurn`s a round costs) — an absent-but-alive human still occupies a turn the engine
     * cycles through, but must not gate the barrier.
     */
    private fun expectedHumanPlayers(): Set<PlayerId> =
        roster.keys.filterTo(mutableSetOf()) { playerId ->
            val civ = gameInfo.getCivilizationOrNull(roster.getValue(playerId))
            civ?.playerType == PlayerType.Human && !civ.isDefeated() && isConnected(playerId)
        }

    /**
     * The number of `nextTurn()` calls one full simultaneous round costs: one per human civ the engine
     * **stops at** — i.e. alive, non-spectator humans. (Defeated and spectator civs are auto-processed
     * *inside* a single `nextTurn`, so they don't each need their own call.)
     *
     * This is independent of [expectedHumanPlayers]/[isConnected]: even when a rostered human is absent
     * or has dropped (so the barrier no longer waits on them), the engine still cycles through their
     * civ's turn, so the round must run a `nextTurn` for it to wrap back to the starting human and
     * advance `turns`. Counting only connected players here would under-cycle and leave `turns`
     * un-advanced — the clients' "ended" latch (cleared by a later-turn view) would then never clear.
     */
    private fun aliveHumanTurnCount(): Int =
        gameInfo.civilizations.count {
            it.playerType == PlayerType.Human && !it.isDefeated() && !it.isSpectator()
        }

    private fun isHuman(civId: String): Boolean =
        gameInfo.getCivilizationOrNull(civId)?.playerType == PlayerType.Human

    /**
     * Streaming-simultaneous `EndTurn` (the live UI path). A human's commands have already streamed
     * in and applied this phase; `EndTurn` only records that this human is **done**. The turn does
     * not advance until *every* rostered human is done — that is what lets two humans act at the same
     * time during the human phase while the AI runs separately (the user's design goal).
     *
     *  - **Everyone done →** [resolveRound]: advance a full round (each human's inter-turn processing
     *    plus all AI), reset the phase, and push everyone a fresh human-phase snapshot.
     *  - **Some still acting →** nothing is pushed. The player who just ended already disabled its own
     *    input locally before sending the intent, and the players still acting must not have their
     *    screens disrupted mid-turn by another player ending. The single round-resolution broadcast is
     *    the only view churn per round.
     *
     * With a single human this degrades exactly to the old sequential behaviour: one `EndTurn` →
     * one `nextTurn()` → broadcast.
     */
    private fun onEndTurn(playerId: PlayerId) {
        endedThisPhase.add(playerId)
        maybeResolveRound()
    }

    /**
     * Resolve the streaming round iff every still-active, connected human has ended. Factored out so a
     * connection drop ([onPlayerDisconnected]) can re-check the barrier with the same logic: if the
     * only humans still missing were the ones that just left, the round resolves now instead of hanging.
     *
     * The `isNotEmpty` guard means a stray `EndTurn` from a non-active player (e.g. one just eliminated
     * or disconnected) cannot trigger a spurious resolution when there is no live, connected human left
     * to gate on. The round is advanced by one `nextTurn` per alive human civ ([aliveHumanTurnCount]) —
     * NOT per connected player — so it always wraps back to the starting human even with an absent one.
     */
    private fun maybeResolveRound() {
        val expected = expectedHumanPlayers()
        if (expected.isNotEmpty() && endedThisPhase.containsAll(expected)) {
            resolveRound(aliveHumanTurnCount())
            endedThisPhase.clear()
        } else {
            // Diagnostic for the "Waiting for other players..." case: name exactly who the round is
            // still gated on, so a stuck barrier in the field is debuggable (e.g. a rostered human who
            // never connected would, before the isConnected gate, show up here forever).
            Log.debug("v2 round not resolving: ended=%s, still waiting on=%s",
                endedThisPhase, expected - endedThisPhase)
        }
    }

    /**
     * The host loop calls this when the relay reports a peer has left ([RelayToClient.PeerLeft] →
     * [com.unciv.logic.multiplayer.v3.net.V3GameHost]). The departed player is dropped from the set of
     * those who have ended (hygiene; they're already excluded from [expectedHumanPlayers] via
     * [isConnected]) and the barrier is re-checked: if the round was only still waiting on the player
     * that just dropped, it resolves now rather than leaving the survivors stuck on
     * "Waiting for other players...". No-op if the drop doesn't complete the round.
     *
     * `@Synchronized` for the same reason as [onFrame] — it runs on the transport receive thread and
     * mutates / reads the same barrier + canonical state.
     */
    @Synchronized
    fun onPlayerDisconnected(playerId: PlayerId) {
        endedThisPhase.remove(playerId)
        maybeResolveRound()
    }

    /**
     * Advance one full simultaneous round. The engine's [GameInfo.nextTurn] ends the *current* human,
     * auto-processes every AI civ (and any defeated/spectator civ) after it, and stops at the *next*
     * human — so cycling through all [humanCount] humans (and the AI between them) and back to a fresh
     * human phase is exactly one `nextTurn()` per human. Then push each human their fresh post-round
     * snapshot.
     *
     * [humanCount] is [aliveHumanTurnCount] — the count of human civs the engine actually stops at,
     * which can be LARGER than the barrier set ([expectedHumanPlayers]) when a rostered human is absent
     * or disconnected: that human's civ still occupies a turn the engine cycles through, so we must run
     * a `nextTurn` for it to wrap the round and advance `turns`, even though the barrier no longer waits
     * on them.
     */
    private fun resolveRound(humanCount: Int) {
        repeat(humanCount.coerceAtLeast(1)) { gameInfo.nextTurn() }
        broadcastPlayerViews()
    }

    /** Project, encode and send each human player in the roster their own filtered snapshot. */
    private fun broadcastPlayerViews() {
        for ((playerId, civId) in roster) {
            if (!isHuman(civId)) continue
            sendSnapshotTo(playerId, civId)
        }
    }

    /**
     * Project the current canonical state down to [civId]'s visibility-filtered view, encode it and
     * send it as a directed [GameFrame.PlayerView] to [playerId]. The single projection+encode path
     * shared by the turn-boundary broadcast ([broadcastPlayerViews]) and the on-demand resync
     * ([onResyncRequest]) — so a reconnecting client gets exactly the snapshot it would have received
     * at the next turn boundary, just computed now against the live state.
     *
     * No-op if the civ is gone (e.g. eliminated since the roster was built). Caller is responsible
     * for the human/roster checks it cares about; [onResyncRequest] also rejects non-human ids.
     */
    private fun sendSnapshotTo(playerId: PlayerId, civId: String) {
        val civ = gameInfo.getCivilizationOrNull(civId) ?: return
        val projected = PlayerViewProjector.projectFor(gameInfo, civId)
        val bytes = GameInfoCodec.encode(projected)
        // Most popup alerts are one-shot UI events (the StartIntro "Let's begin!", war declarations,
        // tech-researched, …). The client shows them from THIS snapshot and discards them locally, but
        // it has no channel to tell the authority it did. So once a snapshot carrying them has been
        // built (the projected deep copy above already captured them), drop them from the canonical civ
        // — otherwise every later PlayerView re-delivers and re-shows them, which is the "welcome modal
        // pops up at the start of every turn" bug. Single-player has the same fire-once semantics: the
        // UI removes each alert as it shows it.
        //
        // EXCEPTION: alerts a player resolves by sending a command back (demands -> DemandResponse,
        // ruleset events -> ResolveEvent) must NOT be cleared here. Clearing them fire-once destroys the
        // pending alert before the player's resolving command can round-trip — the executor would then
        // find nothing to resolve (this silently broke DemandResponse in real v3 play). Such alerts stay
        // on the canonical civ until their resolving command consumes them (and are re-delivered each
        // snapshot until then, so an unresolved event/demand keeps prompting — which is correct).
        civ.popupAlerts.removeAll { !it.type.isResolvedByPlayerCommand }
        outbound(
            playerId,
            GameFrame.PlayerView(
                turn = gameInfo.turns,
                compatVersion = CURRENT_COMPAT_VERSION,
                gzippedFilteredGameInfo = bytes
            )
        )
    }

    /**
     * A thread-safe, consistent deep copy of the authority's canonical [gameInfo], for persistence:
     * the host's manual save / autosave (and, later, a dedicated server). Cloned under the same lock
     * that guards command application and turn resolution, so the snapshot stays coherent even while
     * commands stream in on other threads. The host's WorldScreen renders only a *filtered* loopback
     * view, so the host must save THIS canonical copy, not what it shows.
     */
    @Synchronized
    fun cloneCanonicalForSave(): GameInfo = gameInfo.clone()

    /**
     * Compute a deterministic checksum of the current canonical state. Not needed this stage — v3 is
     * authoritative (clients never re-simulate), so there is no convergence checksum requirement
     * (docs/multiplayer-v3.md §3). Left unimplemented.
     *
     * @throws NotImplementedError — no determinism/checksum requirement in v3.
     */
    fun checksum(): Checksum =
        throw NotImplementedError("GameSession.checksum: v3 has no determinism/convergence checksum (see docs/multiplayer-v3.md §3)")

    companion object {
        /** The save-compatibility version stamped into each [GameFrame.PlayerView]. */
        private val CURRENT_COMPAT_VERSION = CompatibilityVersion.CURRENT_COMPATIBILITY_NUMBER
    }
}
