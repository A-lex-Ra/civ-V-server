package com.unciv.network.serialization

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json

/**
 * The single, shared kotlinx.serialization [Json] configuration for the multiplayer-v3 relay
 * wire format. Both the client transport (`:core`) and the relay server (`:server`) must use
 * the *same* configuration so the sealed [com.unciv.network.relay.ClientToRelay] /
 * [com.unciv.network.relay.RelayToClient] / [com.unciv.network.game.GameFrame] /
 * [com.unciv.network.command.GameCommand] hierarchies round-trip identically.
 *
 * Notes:
 *  - [classDiscriminator] `"type"` and [ClassDiscriminatorMode.ALL_JSON_OBJECTS] mirror the
 *    existing `/chat` WebSocket convention. The discriminator MUST be emitted on outgoing
 *    objects, otherwise sealed-type messages can't be deserialized by the peer.
 *  - [Json.Default.ignoreUnknownKeys] lets newer peers add fields without breaking older ones,
 *    in keeping with the additive/versioned protocol design.
 */
@OptIn(ExperimentalSerializationApi::class)
val relayJson: Json = Json {
    classDiscriminator = "type"
    classDiscriminatorMode = ClassDiscriminatorMode.ALL_JSON_OBJECTS
    ignoreUnknownKeys = true
}
