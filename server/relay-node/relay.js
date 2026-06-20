#!/usr/bin/env node

// SPDX-License-Identifier: LicenseRef-Unciv-v3-ViewOnly
// Copyright (c) 2026 Alexander Rastorguev (A-lex-Ra) <rastorguev2047@gmail.com>
//
// Part of the Unciv multiplayer-v3 netcode — view-only, NOT under the Mozilla
// Public License that covers the rest of this repository. No right to use,
// copy, modify, run, or distribute is granted without written permission;
// permission is gladly given on request (email or GitHub issue).
// Full terms: /LICENSE.v3  ·  License map: /LICENSING.md

'use strict';

/**
 * Lightweight, JVM-free reimplementation of the multiplayer-v3 relay.
 *
 * It is a behavioural twin of the canonical Kotlin relay
 * (server/src/com/unciv/app/server/RelayServer.kt): deliberately dumb about the game — it manages
 * room membership / presence / the host role and forwards opaque GameFrame payloads between the
 * members of a room, never inspecting or mutating game content.
 *
 * Wire-compatible with the Kotlin client transport (WebSocketRelayTransport) and the canonical
 * Kotlin RelayServer:
 *   - One WebSocket TEXT frame per message, JSON body, endpoint path "/relay".
 *   - kotlinx.serialization polymorphic format (com.unciv.network.serialization.relayJson):
 *     classDiscriminator = "type", ClassDiscriminatorMode.ALL_JSON_OBJECTS, ignoreUnknownKeys = true.
 *     A "type" field selects the sealed variant; unknown fields are ignored.
 *   - ClientToRelay  types: hello, createRoom, joinRoom, leaveRoom, relay, relayTo.
 *   - RelayToClient  types: welcome, peerJoined, peerLeft, relayed, error.
 *   - PeerRole values: HOST, PLAYER, SPECTATOR (kotlin enum names).
 *   - Protocol.VERSION = 1 (network/src/com/unciv/network/Protocol.kt).
 *
 * The opaque `payload` (a GameFrame — including ByteArray fields that kotlinx serialises as JSON
 * arrays of signed bytes, plus nested "type" discriminators on every object) is forwarded verbatim
 * by re-embedding the parsed value. JSON.parse -> JSON.stringify round-trips it losslessly here:
 * the payload contains only small integers, strings, booleans and nested objects — no floats — so
 * no precision or formatting drift, and every nested "type" key is preserved.
 *
 * Node is single-threaded (event loop), so message handling is naturally sequential and needs no
 * locking — unlike the Kotlin server which guards its room maps with a monitor.
 *
 * Env:
 *   RELAY_PORT  (default 8080)   TCP port to listen on.
 *   RELAY_PATH  (default /relay) WebSocket upgrade path.
 */

const http = require('http');
const { WebSocketServer } = require('ws');

const PROTOCOL_VERSION = 1;
const PORT = parseInt(process.env.RELAY_PORT || '8080', 10);
const RELAY_PATH = process.env.RELAY_PATH || '/relay';

/**
 * roomId -> { roomId, gameId, hostWs, peers: Map<ws, userId> }
 * `peers` preserves insertion (join) order so host election is deterministic, matching the
 * Kotlin LinkedHashMap.
 */
const rooms = new Map();
let roomCounter = 0;

function nextRoomId(gameId) {
  roomCounter += 1;
  return `${gameId}#${roomCounter}`; // mirrors RelayServer.nextRoomId
}

function send(ws, obj) {
  if (ws.readyState === ws.OPEN) {
    try { ws.send(JSON.stringify(obj)); } catch (_) { /* best-effort delivery */ }
  }
}

function broadcast(room, exceptWs, obj) {
  for (const peerWs of room.peers.keys()) {
    if (peerWs !== exceptWs) send(peerWs, obj);
  }
}

function needHello(ws) {
  send(ws, { type: 'error', code: 'no_hello', message: 'Send Hello before any other message' });
}

/** Remove a connection from its room; clear the host slot if it held it; announce departure. */
function leave(ws) {
  const room = ws.room;
  if (!room) return;
  const leavingUserId = room.peers.get(ws);
  room.peers.delete(ws);
  // Host migration is deferred (docs/multiplayer-v3.md §7): a departing host just clears the slot.
  if (room.hostWs === ws) room.hostWs = null;
  ws.room = null;
  if (room.peers.size === 0) {
    rooms.delete(room.roomId);
    return;
  }
  if (leavingUserId !== undefined) {
    broadcast(room, ws, { type: 'peerLeft', userId: leavingUserId });
  }
}

function handleMessage(ws, raw) {
  let msg;
  try { msg = JSON.parse(raw); } catch (_) { return; }       // ignore non-JSON noise
  if (!msg || typeof msg.type !== 'string') return;

  switch (msg.type) {
    case 'hello': {
      if (msg.protocolVersion !== PROTOCOL_VERSION) {
        send(ws, {
          type: 'error', code: 'protocol_mismatch',
          message: `Server speaks protocol ${PROTOCOL_VERSION}, client sent ${msg.protocolVersion}`
        });
        try { ws.close(1003, 'protocol mismatch'); } catch (_) {}
        return;
      }
      ws.userId = msg.userId;
      return;
    }

    case 'createRoom': {
      if (ws.userId == null) return needHello(ws);
      if (ws.room) leave(ws);
      const roomId = nextRoomId(msg.gameId);
      const room = { roomId, gameId: msg.gameId, hostWs: ws, peers: new Map() };
      room.peers.set(ws, ws.userId);
      rooms.set(roomId, room);
      ws.room = room;
      send(ws, { type: 'welcome', roomId, role: 'HOST', peers: [] });
      return;
    }

    case 'joinRoom': {
      if (ws.userId == null) return needHello(ws);
      if (ws.room) leave(ws);
      const room = rooms.get(msg.roomId);
      if (!room) {
        send(ws, { type: 'error', code: 'no_room', message: `Unknown room: ${msg.roomId}` });
        return;
      }
      const existing = Array.from(room.peers.values()); // peer ids BEFORE adding self
      room.peers.set(ws, ws.userId);
      if (room.hostWs == null) room.hostWs = ws;
      const role = room.hostWs === ws ? 'HOST' : 'PLAYER';
      ws.room = room;
      send(ws, { type: 'welcome', roomId: room.roomId, role, peers: existing });
      broadcast(room, ws, { type: 'peerJoined', userId: ws.userId });
      return;
    }

    case 'leaveRoom': {
      if (ws.room) leave(ws);
      return;
    }

    case 'relay': {
      if (ws.userId == null) return needHello(ws);
      if (!ws.room) {
        send(ws, { type: 'error', code: 'no_room', message: 'Join a room before relaying' });
        return;
      }
      // Forward verbatim to every OTHER member; never inspect payload.
      broadcast(ws.room, ws, { type: 'relayed', fromId: ws.userId, payload: msg.payload });
      return;
    }

    case 'relayTo': {
      if (ws.userId == null) return needHello(ws);
      if (!ws.room) {
        send(ws, { type: 'error', code: 'no_room', message: 'Join a room before relaying' });
        return;
      }
      // Directed: deliver to the single addressed member (per-player filtered state — must not be
      // broadcast). Silently dropped if the target is not in the room, matching RelayServer.sendTo.
      for (const [peerWs, uid] of ws.room.peers) {
        if (uid === msg.targetUserId) {
          send(peerWs, { type: 'relayed', fromId: ws.userId, payload: msg.payload });
          break;
        }
      }
      return;
    }

    default:
      return; // unknown ClientToRelay type — ignore (additive protocol)
  }
}

const server = http.createServer((req, res) => {
  // Plain-HTTP health endpoint for monitoring / nginx upstream checks (never collides with the WS).
  if (req.url === '/healthz' || req.url === '/isalive') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true, rooms: rooms.size, protocol: PROTOCOL_VERSION }));
    return;
  }
  res.writeHead(404);
  res.end();
});

// PlayerView frames carry a gzipped GameInfo serialised as a JSON byte array, which can be a few
// hundred KB of text — keep the frame cap generous (the Kotlin server uses Long.MAX_VALUE).
const wss = new WebSocketServer({ server, path: RELAY_PATH, maxPayload: 512 * 1024 * 1024 });

wss.on('connection', (ws) => {
  ws.userId = null;
  ws.room = null;
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });
  ws.on('message', (data, isBinary) =>
    handleMessage(ws, isBinary ? data.toString('utf8') : data.toString()));
  ws.on('close', () => leave(ws));
  ws.on('error', () => { try { ws.terminate(); } catch (_) {} });
});

// Mirror the Kotlin server's 30s ping / 60s timeout: drop peers that miss two consecutive pings so
// stale memberships are cleaned up (the ktor client auto-replies to pings with pongs).
const heartbeat = setInterval(() => {
  for (const ws of wss.clients) {
    if (ws.isAlive === false) { try { ws.terminate(); } catch (_) {} continue; }
    ws.isAlive = false;
    try { ws.ping(); } catch (_) {}
  }
}, 30000);
wss.on('close', () => clearInterval(heartbeat));

server.listen(PORT, '0.0.0.0', () => {
  console.log(`[unciv-relay] listening on 0.0.0.0:${PORT}${RELAY_PATH} (multiplayer-v3 protocol v${PROTOCOL_VERSION})`);
});
