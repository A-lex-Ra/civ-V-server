# Licensing

This repository is a fork of [Unciv](https://github.com/yairm210/Unciv) and is
**not** under a single license. Two licenses apply, **by file**:

## Mozilla Public License 2.0 — the default

Everything in this repository is under the **MPL 2.0** (see [LICENSE](LICENSE))
**except** the files listed in the next section. The MPL portion includes:

- all upstream Unciv engine, UI, and asset code, and this fork's modifications
  to it (modifications to MPL files stay MPL — file-level copyleft);
- the bundled Brave New World ruleset data under
  `android/assets/jsons/Civ V - Brave New World/`, and the reference mod cloned
  in `vendor/Civ-V-Brave-New-World/` — both adopted from the
  [RobLoach `Civ-V-Brave-New-World`](https://github.com/RobLoach/Civ-V-Brave-New-World)
  mod (MPL-2.0; attribution chain ravignir → chris03-dev → RobLoach, see
  [docs/Credits.md](docs/Credits.md)).

## View-Only License — this fork's multiplayer-v3 netcode

The original **authoritative multiplayer-v3** netcode written for this fork is
under the **View-Only License** (see [LICENSE.v3](LICENSE.v3)). These files
carry the SPDX tag `LicenseRef-Unciv-v3-ViewOnly` in their header and live in:

| Path | What it is |
|---|---|
| `core/src/com/unciv/logic/multiplayer/v3/` | v3 client logic, session, transport, command, visibility projection |
| `network/src/com/unciv/network/` | engine-free v3 wire protocol — the whole `:network` module |
| `server/src/com/unciv/app/server/RelayServer.kt` | Kotlin relay server (`/relay`) |
| `server/relay-node/` | lightweight, JVM-free Node relay reimplementation |

**Not** included (stays MPL): `server/src/com/unciv/app/server/UncivServer.kt`
is upstream Unciv (the legacy v1 file-store), and the `tests/` suite.

The view-only files are published for reference and review. **Permission to
use, modify, run, or distribute them is granted on request** — email
rastorguev2047@gmail.com or open a GitHub issue. See [LICENSE.v3](LICENSE.v3)
for the full terms.
