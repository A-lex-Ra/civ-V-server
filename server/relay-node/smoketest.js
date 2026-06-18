'use strict';
/* Throwaway protocol smoke test for relay.js — run from a dir where `ws` resolves.
 * Exercises: hello/createRoom (HOST), hello/joinRoom (PLAYER + peers + peerJoined),
 * relay (broadcast), relayTo (directed), and verifies opaque payload round-trips intact. */
const WebSocket = require('ws');
const URL = process.env.URL || 'ws://127.0.0.1:8080/relay';
const log = (...a) => console.log(...a);
let failures = 0;
const check = (cond, msg) => { if (!cond) { failures++; log('FAIL:', msg); } else log('ok  :', msg); };
const open = () => new Promise((res) => { const w = new WebSocket(URL); w.q = []; w.on('message', (d) => w.q.push(JSON.parse(d.toString()))); w.on('open', () => res(w)); });
const send = (w, o) => w.send(JSON.stringify(o));
const wait = (w, type, ms = 3000) => new Promise((res, rej) => {
  const t0 = Date.now();
  const tick = () => {
    const i = w.q.findIndex((m) => m.type === type);
    if (i >= 0) return res(w.q.splice(i, 1)[0]);
    if (Date.now() - t0 > ms) return rej(new Error('timeout waiting for ' + type));
    setTimeout(tick, 25);
  };
  tick();
});

(async () => {
  const host = await open();
  send(host, { type: 'hello', protocolVersion: 1, userId: 'host-uid' });
  send(host, { type: 'createRoom', gameId: 'smoke-game' });
  const w1 = await wait(host, 'welcome');
  check(w1.role === 'HOST', 'creator gets role HOST');
  check(Array.isArray(w1.peers) && w1.peers.length === 0, 'creator welcome peers == []');
  const roomId = w1.roomId;
  check(roomId.startsWith('smoke-game#'), 'roomId is gameId#N -> ' + roomId);

  const p2 = await open();
  send(p2, { type: 'hello', protocolVersion: 1, userId: 'p2-uid' });
  send(p2, { type: 'joinRoom', roomId });
  const w2 = await wait(p2, 'welcome');
  check(w2.role === 'PLAYER', 'joiner gets role PLAYER');
  check(JSON.stringify(w2.peers) === JSON.stringify(['host-uid']), 'joiner welcome lists existing peer host-uid');
  const pj = await wait(host, 'peerJoined');
  check(pj.userId === 'p2-uid', 'host receives peerJoined{p2-uid}');

  // relay (broadcast to others): a GameFrame-shaped opaque payload with nested type + byte array.
  const payloadA = { type: 'playerView', turn: 7, compatVersion: 3, gzippedFilteredGameInfo: [31, -117, 8, 0, 127, -1] };
  send(p2, { type: 'relay', payload: payloadA });
  const r1 = await wait(host, 'relayed');
  check(r1.fromId === 'p2-uid', 'relayed.fromId == sender');
  check(JSON.stringify(r1.payload) === JSON.stringify(payloadA), 'broadcast payload round-trips intact (incl. signed bytes + nested type)');

  // relayTo (directed to a single peer).
  const payloadB = { type: 'commandRejected', seq: 42, reason: 'nope' };
  send(host, { type: 'relayTo', targetUserId: 'p2-uid', payload: payloadB });
  const r2 = await wait(p2, 'relayed');
  check(r2.fromId === 'host-uid', 'directed relayed.fromId == host');
  check(JSON.stringify(r2.payload) === JSON.stringify(payloadB), 'directed payload round-trips intact');

  // peerLeft on disconnect.
  p2.close();
  const pl = await wait(host, 'peerLeft');
  check(pl.userId === 'p2-uid', 'host receives peerLeft{p2-uid} on disconnect');

  host.close();
  log(failures === 0 ? '\nALL PASS' : `\n${failures} FAILURE(S)`);
  process.exit(failures === 0 ? 0 : 1);
})().catch((e) => { log('ERROR', e.message); process.exit(2); });
