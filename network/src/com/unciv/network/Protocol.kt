package com.unciv.network

/**
 * Multiplayer v3 wire protocol — shared, engine-independent definitions.
 *
 * This module ([`:network`]) is intentionally free of any game-engine ("core") dependency.
 * It only describes *how* peers talk to each other and to the public relay:
 *
 *  - [com.unciv.network.relay] — relay envelopes (membership/routing, never game content)
 *  - [com.unciv.network.game]  — game frames exchanged authority <-> clients (opaque to the relay)
 *  - [com.unciv.network.command] — [com.unciv.network.command.GameCommand], the player-intent payloads
 *
 * See `docs/multiplayer-v3.md` for the full design.
 *
 * Phase 0: skeleton only — the types compile and serialize, but no transport/authority logic
 * is wired up yet, and no existing behaviour is changed.
 */
object Protocol {
    /**
     * Wire protocol version, negotiated during the relay handshake
     * ([com.unciv.network.relay.ClientToRelay.Hello]). Peers with mismatching major versions
     * must refuse or downgrade rather than risk corrupting shared state.
     */
    const val VERSION = 1
}

/** Stable identifier of a connected user/account (reuses the existing multiplayer userId). */
typealias UserId = String

/** Identifier of a relay room; one room hosts one game. */
typealias RoomId = String

/** Identifier of a game (matches the existing `GameInfo.gameId`). */
typealias GameId = String

/** Identifier of a player within a game. */
typealias PlayerId = String

/**
 * A deterministic checksum of the canonical game state at a point in time, used to detect drift
 * between the authority and replicas. Represented as an opaque string (e.g. hex digest).
 */
typealias Checksum = String
