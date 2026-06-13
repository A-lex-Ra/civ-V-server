package com.unciv.logic.multiplayer.v2.client

import com.unciv.logic.GameInfo
import com.unciv.logic.files.UncivFiles
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The on-the-wire codec for a (visibility-filtered) [GameInfo] snapshot carried inside a
 * [com.unciv.network.game.GameFrame.PlayerView]: JSON serialise -> gzip on the way out, gunzip ->
 * JSON deserialise (+ `setTransients()`) on the way in.
 *
 * This is the round-trip multiplayer-v2 hinges on (docs/multiplayer-v2.md §10 Phase 3): a
 * projected/redacted `GameInfo` must survive serialise -> deserialise -> `setTransients()` on the
 * client. We deliberately reuse the engine's own save/load entry points so the snapshot rides the
 * exact same compatibility machinery a save file does.
 */
object GameInfoCodec {

    /**
     * Serialise [gameInfo] to plain JSON (via [UncivFiles.gameInfoToString] with `forceZip = false`)
     * and gzip the JSON bytes. The result is the raw payload for
     * [com.unciv.network.game.GameFrame.PlayerView.gzippedFilteredGameInfo].
     */
    fun encode(gameInfo: GameInfo): ByteArray {
        val plainJson = UncivFiles.gameInfoToString(gameInfo, forceZip = false)
        val out = ByteArrayOutputStream(plainJson.length)
        GZIPOutputStream(out).use { it.write(plainJson.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    /**
     * Gunzip [bytes] back to the JSON string, then hand it to [UncivFiles.gameInfoFromString], which
     * deserialises **and** runs `setTransients()` for us. (`gameInfoFromString` first tries to treat
     * its input as a base64-gzipped save and falls back gracefully to plain JSON — which is exactly
     * what we pass it, so the fallback path is taken.)
     */
    fun decode(bytes: ByteArray): GameInfo {
        val json = GZIPInputStream(ByteArrayInputStream(bytes))
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        return UncivFiles.gameInfoFromString(json)
    }
}
