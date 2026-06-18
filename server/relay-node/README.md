# Unciv multiplayer-v3 relay — lightweight Node edition

A tiny, **JVM-free** drop-in for the multiplayer-v3 relay. It is a behavioural twin of the canonical
Kotlin relay in [`../src/com/unciv/app/server/RelayServer.kt`](../src/com/unciv/app/server/RelayServer.kt):
it manages room membership / presence / the host role and forwards **opaque** `GameFrame` payloads
between the members of a room. It never inspects or mutates game state — the authority logic lives in
the host client's `GameSession`.

Use this when you want to run only the relay on a host that has Node but no Java (the full
`UncivServer` jar additionally serves the legacy v1 file-store and chat, which the relay does not need).

## Wire compatibility

Speaks the exact same protocol as the Kotlin client transport (`WebSocketRelayTransport`) and server:

- One WebSocket **TEXT** frame per message, JSON body, upgrade path **`/relay`**.
- kotlinx.serialization polymorphic JSON (`com.unciv.network.serialization.relayJson`):
  `classDiscriminator = "type"`, `ALL_JSON_OBJECTS`, `ignoreUnknownKeys = true`.
- `ClientToRelay`: `hello`, `createRoom`, `joinRoom`, `leaveRoom`, `relay`, `relayTo`.
- `RelayToClient`: `welcome`, `peerJoined`, `peerLeft`, `relayed`, `error`.
- `PeerRole`: `HOST`, `PLAYER`, `SPECTATOR`. Protocol version: **1**.

The opaque `payload` is forwarded verbatim; it contains only ints/strings/bools/nested objects
(ByteArrays are JSON arrays of bytes), so the `JSON.parse`→`JSON.stringify` round-trip is lossless.

> Because this is a reimplementation, it must stay in step with the relay envelope protocol. That
> protocol is intentionally small and additive (`ignoreUnknownKeys`), and host migration / authority
> logic is out of scope, so churn is expected to be rare. If `RelayMessages.kt` gains a new envelope
> type that clients rely on, mirror it here.

## Run locally

```bash
npm install --omit=dev
RELAY_PORT=8080 node relay.js
# health check:
curl localhost:8080/healthz
```

Clients then point their multiplayer **server URL** at `http://<host>:8080` (the client rewrites
`http(s)://` → `ws(s)://` and appends `/relay`).

## Run as a service (systemd)

```ini
# /etc/systemd/system/unciv-relay.service
[Unit]
Description=Unciv multiplayer-v3 relay (lightweight Node)
After=network.target

[Service]
Type=simple
WorkingDirectory=/opt/unciv-relay
Environment=RELAY_PORT=8080
ExecStart=/usr/bin/node /opt/unciv-relay/relay.js
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
```

```bash
systemctl daemon-reload
systemctl enable --now unciv-relay
systemctl status unciv-relay
```

### TLS (optional)

To serve `wss://` behind the existing nginx, reverse-proxy the `/relay` path to `127.0.0.1:8080`
with `Upgrade`/`Connection` headers and point clients at `https://<domain>`.

## Env

| Var          | Default  | Meaning                    |
|--------------|----------|----------------------------|
| `RELAY_PORT` | `8080`   | TCP port to listen on      |
| `RELAY_PATH` | `/relay` | WebSocket upgrade path     |
