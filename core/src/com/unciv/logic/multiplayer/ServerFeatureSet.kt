package com.unciv.logic.multiplayer


/**
 * This class is used to store the features of the server.
 *
 * We use version numbers instead of simple boolean
 * to allow for future expansion and backwards compatibility.
 *
 * Everything is optional, so if a feature is not present, it is assumed to be 0.
 * Dropbox does not support anything of this, so it will always be 0.
 */
@Deprecated(
    "Legacy v1 multiplayer server feature-flags. Part of the file/Dropbox-based v1 multiplayer, " +
        "superseded by the relay-based multiplayer v3 (com.unciv.logic.multiplayer.v3)."
)
data class ServerFeatureSet(
    val authVersion: Int = 0,
    val chatVersion: Int = 0,
)
