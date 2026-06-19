# Brave New World adoption — design doc / plan

**Status:** draft / analysis · **Date:** 2026-06-19 · **Author:** investigation via RobLoach mod study
**Scope:** how to bring Civ V *Brave New World* content & mechanics into this fork — what to **reuse as data**
from the [RobLoach `Civ-V-Brave-New-World`](https://github.com/RobLoach/Civ-V-Brave-New-World) mod, and what to
**reimplement in Kotlin** for fidelity, with the **multiplayer-v3** authority/command model in mind.

> The mod is cloned at `vendor/Civ-V-Brave-New-World/` for reference. It is an **extension mod** (not a base
> ruleset) layered on top of `Civ V - Gods & Kings`. It ships **only data** (`jsons/` + `Images/`) — no Kotlin.

---

## 1. TL;DR

Our engine is Unciv **4.20.12**. It has **no native** Tourism / Great Works / Ideology / World Congress system —
but it *does* have a powerful set of generic primitives:

- **stockpiled resources** (`Stockpiled` unique) that the turn loop accumulates and that double as **countables**;
- the **Events** system (`Event`/`EventChoice`, `Triggers a [event] event`) for triggered player choices;
- the victory milestone **`Have more [countable] than each player's [countable]`** (`MoreCountableThanEachPlayer`);
- `Provides [n] [resource] <for every [[X] Per Turn]>` to bank a resource proportional to a per-turn yield.

The RobLoach mod stacks these primitives to **emulate** most of BNW *in data*. We verified that **virtually every
unique the mod uses is recognized by our 4.20.12 engine** — so a large fraction of BNW is genuinely drop-in.

Three tiers (detail in §4):

| Tier | Meaning | Examples |
|---|---|---|
| **A — Reuse mod data as-is** | Data is sufficient *and* the result matches Civ V well enough | 9 civs + city-states, wonders/buildings, religion split, Aesthetics/Exploration/Piety trees, archaeology, Great People units, beliefs, techs, terrains, personalities, events |
| **B — Reimplement in Kotlin** | Mod fakes it in data but the result **diverges from real Civ V** | **Tourism** (flat counter, no influence levels), **Great Works** (no real objects/theming), **Ideologies** (no public opinion / switching), Great Musician concert tour |
| **C — Build from scratch** | **Absent in the mod entirely** (README marks `[ ]`) | **World Congress**, **International Trade Routes** (city-to-city yields), Venice's double-trade-routes economy |

**Fork-specific catch:** the data-only parts (Tier A) live inside `GameInfo`, so they serialize and project through
multiplayer-v3 for free — *except* anything routed through an **Event popup** (ideology pick, dig payoff, concert
tour, free-tenet pick). v3 has no command to resolve an Event, so those need a new `GameCommand` before they work
for a non-host player. See §5.

---

## 2. Background

- **What's missing today** (both in our fork and upstream Unciv): tourism as a stat, Great Works objects, ideology
  system, World Congress, BNW trade-route yields, archaeology. Confirmed against upstream `Stat.kt` and
  `UniqueType.kt` — these are Unciv-wide gaps, not a defect of this fork.
- **What the mod adds (README `Features`):** Nations `[x]`, Units `[x]`, Buildings `[x]`, Wonders `[x]`,
  Archaeology `[x]`, Social Policies & Ideologies `[x]`, Technologies `[x]`, **Cultural Victory with Tourism `[x]`**,
  **World Congress `[ ]`**, **International Trade Routes `[ ]`**.
- **Engine version match:** the mod is authored against a recent Unciv unique vocabulary that 4.20.12 already speaks;
  the static cross-check found **no behavior-bearing unique that our engine rejects** (the single unrecognized string,
  a bare `"Great Work"` marker, is a decorative no-op).

---

## 3. How the mod emulates BNW in data (the architecture to understand)

This is the crux of "what a data-mod is sufficient for". The mod's tricks:

1. **Tourism & "Accumulated Culture" are fake stockpiled resources** (`TileResources.json`). Each turn,
   `GlobalUniques.json` banks `Provides [1] [Accumulated Culture] <for every [[Culture] Per Turn]>`. Tourism is banked
   by explicit `Instantly provides [n] [Tourism]` triggers and `Provides [n] [Tourism]` on buildings.
   - **Cultural Victory** then = `Have more [Tourism] than each player's [Accumulated Culture]` (`VictoryTypes.json`).
   - This is **one global number vs one global number** — none of Civ V's per-rival Influence (Exotic→Familiar→
     Popular→Influential→Dominant), decay, or Open-Borders/Trade/Religion/Ideology multipliers.

2. **Great Work "slots" are invisible auto-built sub-buildings.** A slot is a hidden building gated on
   `Unavailable <without [Artifact]> <without [Great Work of Art]>`, that `Instantly consumes` the great-work resource
   and `Provides [2] [Tourism]`, `Automatically built ... where buildable`, `Only available <in cities with a [Museum]>`.
   "Theming bonus" is another hidden building gated on *all slots filled* — it cannot model artist/era/civ matching.
   Great Works are stockpiled resources (`Great Work of Art/Writing/Music`, `Artifact`), **not** swappable named objects.

3. **Ideologies are ordinary policy branches** (`Order`/`Freedom`/`Autocracy`, 16 tenets each across 3 visual rows).
   Tier gating is faked with stockpile tokens: picking a Level-1 tenet `Instantly provides [1] [Level 1 Policy]`; a
   Level-2 tenet is `Only available <when above [1] [Level 1 Policy]>` and consumes it. Adoption gate:
   `Unavailable <before the [Modern era]> <when number of [[Factory] Buildings] is less than [3]>` + mutual exclusion
   via `Unavailable <after adopting [other ideology]>`. Selection & free-tenets run through `Events.json`.

4. **Archaeology is data-driven via improvements + events.** Archaeologist unit (requires `Archaeology` tech, built in
   University cities) builds an Archaeological Dig that `Triggers a [Archaeological Dig] event` → `Instantly provides
   [1] [Artifact]`, or builds a Landmark. Antiquity Site / Hidden Antiquity Site are `Bonus` resources with
   `revealedBy`.

5. **Great People create works via triggers.** Great Artist/Writer `Instantly provides [1] [Great Work of …] <by
   consuming this unit>`; Great Musician `Triggers a [Perform Concert Tour] event`. On-map GP actions route through the
   engine's headless unit-action path.

**Takeaway:** the mod is *much* more than flavor — it's a clever, fully-playable BNW approximation built from generic
primitives. The price is fidelity: tourism/great-works/ideologies are simplified counters, not Civ V's relationship
systems.

---

## 4. Classification

### Tier A — Reuse mod data as-is

Data is sufficient and matches Civ V acceptably. Import the JSON (and the matching `Images/` + `game.atlas`).
**These are not independent** — they reference each other and the new stockpiled resources, so import together.

| Content | File(s) | Notes / caveats |
|---|---|---|
| **9 BNW civs + leaders** | `Nations.json` (+ unit/building refs) | Poland, Brazil, Zulu, Shoshone, France/Japan/Germany/Iroquois reworks: clean. Lossy approximations (author TODOs): Assyria (free-tech→timed +Science), Portugal/Morocco (trade bonuses), **Indonesia "Spice Islanders" is uniqueText-only — a near no-op**. |
| **~24 new city-states** | `Nations.json` + `ModOptions.nationsToRemove` | Pure data; old CS merged into their civ counterparts are removed. |
| **Wonders & buildings (static)** | `Buildings.json` | Borobudur, Red Fort, Prora, guilds, culture buildings, Hotel/Airport/Visitor Center, the `Ideology` trigger building. All on supported uniques. |
| **Religion split** | `Religions.json` + translations | Adds `Eastern Orthodoxy` + `Protestantism`; `Christianity`→`Catholicism` via `English.properties` rename. Matches Civ V. |
| **Aesthetics / Exploration / Piety-rework** | `Policies.json` | The 8 non-ideology branches are clean, reusable policy data. |
| **Ideology *structure*** | `Policies.json` + `Events.json` | 16 tenets × 3 tiers as a *playable approximation* (see Tier B for what's missing). |
| **Archaeology** | `Units.json`, `TileImprovements.json`, `TileResources.json`, `Events.json` | Good structural match (Archaeologist, Antiquity/Hidden sites, Dig, Landmark). Tuning diverges: 75% vs 30% hidden chance, flat Landmark culture. |
| **Great People units** | `Units.json`, `Specialists.json` | Great Writer/Artist/Musician units + Artist/Writer/Musician specialists. Work *creation* works; work *model* is Tier B. |
| **Trade units (scaffolding)** | `Units.json` | Caravan/Cargo Ship exist, gated by a `Trade Route` stockpile token, usable for **City-State trade missions**. City-to-city yields are Tier C. |
| **Techs / Terrains / Beliefs / Personalities / Events** | resp. files | Tech overlay (incl. "The Internet", "Hidden Antiquity Sites"), 3 natural wonders, Reformation beliefs, AI personalities, the events catalogue. All supported. (Minor: duplicate `Gajah Mada` in `Personalities.json`.) |
| **Stockpiled resources (plumbing)** | `TileResources.json` | `Tourism`, `Accumulated Culture`, `Great Work of *`, `Artifact`, `Trade Route`, `Level 1/2 Policy`. Required by everything above. |

### Tier B — Reimplement in Kotlin for Civ V fidelity

The mod fakes these in data, but the result **does not match real Civ V**. Decide per-feature whether the mod's
approximation is "good enough" or worth real engine work.

| Feature | Mod's data approximation | Why it diverges / what real Civ V needs |
|---|---|---|
| **Tourism** | One global stockpiled counter banked from buildings/works | No **per-rival Influence levels** (Exotic→Dominant), no **decay** (culture-as-defense), no **multipliers** (Open Borders, Trade Route, shared Religion, Ideology difference, Research Agreement). Real tourism is a *relationship* system per (you → each other civ). |
| **Cultural Victory** | `Tourism > each rival's Accumulated Culture` (two global numbers) | Real win = being **Influential over *all* living majors** simultaneously, derived from the per-rival model above. Reimplement together with Tourism. |
| **Great Works** | Stockpiled resources + hidden slot/theming sub-buildings | No named/swappable Great Work **objects**, no moving works between buildings/cities, no real **theming** (artist/era/civ/Great-Writer matching). "Theming" is a crude all-slots-filled flag. |
| **Ideologies** | Policy branches + stockpile tier tokens + selection events | Missing: **public opinion / ideological happiness pressure** from rival ideologies (Dissidents → Civil Resistance), **switching ideology** with **anarchy + tenet loss**, free tenets from wonders/level-3 buildings. Tier prerequisite is a global token budget, not true per-tenet prereqs. |
| **Great Musician concert tour** | Flat self-Tourism boost (`Instantly provides [10] [Tourism] <for every [Tourism] Per Turn>`) | Real tour **reduces the target civ's influence / boosts yours over them** — needs the per-rival model. |
| **Misc stubs** | `Comment`/TODO no-ops | ISS (+33% GS science, International Project), Great Firewall (negate Internet tourism), Caravansary trade-range, several tenet effects. |

### Tier C — Build from scratch (absent in the mod)

| Feature | Status in mod | What it takes |
|---|---|---|
| **World Congress** | README `[ ]`. Only the legacy UN diplomatic-vote (`VictoryManager`) exists in our engine. | New subsystem: sessions, proposals/resolutions, voting, host, World's Fair / world projects, embargoes, delegates from CS. Net-new Kotlin + UI + state. |
| **International Trade Routes (yields)** | README `[ ]`. Caravan/Cargo Ship exist but city-to-city establishment is `// TODO`. | New: establish a route city↔city, per-turn Gold/Science/Religion/pressure, route length/danger, plundering. Builds on the existing `Trade Route` stockpile token + the `StatsFromTradeRoute`/`ConnectTradeRoutes` uniques. |
| **Venice double-trade-routes** | Tag exists; grants extra `Trade Route` *tokens* (→ more trade units), but no extra route *economy* | Folds into the ITR work above. |

---

## 5. multiplayer-v3 integration (fork-specific)

This is what makes the doc more than "install a mod". v3 = one **authority** owns canonical `GameInfo`; clients send
`GameCommand` intents and receive visibility-filtered `PlayerView` snapshots. Implications:

**5.1 Tier A data "just works" for state.** Stockpiled resources, policies, buildings, units, improvements all live in
`GameInfo`, so they serialize to joiners and project through `PlayerViewProjector` with no new code. A v3 game just
needs **all participants to have the same mod/ruleset installed** (host + joiners), or the canonical save won't load.

**5.2 The real gap — `Alert` Events have no command.** `GameCommand` (`network/.../command/GameCommand.kt`) is rich
(`AdoptPolicy`, `ChooseGreatPerson`, religion pickers, unit/city actions; on-map GP actions route through
`GenericUnitAction` headless). **But there is no `ResolveEvent`/`EventChoice` command.**

What an Event *is*: a `Events.json` object fired by the `Triggers a [event] event` unique, with `choices[]` whose
chosen branch runs triggerable uniques. Its `presentation` decides who resolves it (see
`UniqueTriggerActivation.kt:119`):
- **`None`** → the engine **auto-picks a choice (weighted random) on the authority** — no popup. *Not a v3 problem.*
  In this mod that's **Perform Concert Tour** (single choice → deterministic).
- **`Alert`** → enqueues `PopupAlert(AlertType.Event, …)` on the civ; a human resolves it by clicking in the popup.
- **`Floating`** → currently `NotImplementedError` (tutorial tasks only).
- **AI civs auto-resolve any event** (same `isAI()` branch) — so the gap is strictly **human joiners + `Alert` events**.

The mod's `Alert` events that a joiner must resolve:
- **Ideology selection** (`Ideology` building → event) — pick Freedom/Order/Autocracy.
- **Archaeological dig payoff** (Artifact vs Great Work vs Cultural Renaissance; hidden-site choices).
- **Free-tenet** picks, ancient-ruins choices.

Under v3 today, an `Alert` event firing in the authoritative `GameInfo` for a **joiner's** civ lands the `PopupAlert`
in the *canonical* civ on the host, with no path to prompt *that player* and apply *their* click. → **Add a
`GameCommand.ResolveEvent(eventName, choiceIndex)`** — keyed on the pending `AlertType.Event` entry in the acting
civ's `popupAlerts` (event name + optional `unitId`), validated against the choice's current conditions, then
`EventChoice.triggerChoice(civ, unit)` on the authority (mirrors how `DemandResponse` keys on a recorded
`PopupAlert`). *Verify first* whether v3 currently surfaces/auto-resolves events at all. This is a **Phase 0 spike.**

> Things that are **not** Events (already have their own command/screen, so no `ResolveEvent` needed): tech pick
> (`ChooseTech`), policy/tenet adoption (`AdoptPolicy`), religion (`Found/EnhanceReligion`, `FoundPantheon`), free
> Great Person (`ChooseGreatPerson`), the hardcoded `AlertType`s (annex/raze/puppet, demands → `AnnexCity`/`RazeCity`/
> `DemandResponse`), notifications, and on-map GP/unit actions (incl. Great Artist/Writer **creating** a Great Work via
> `Instantly provides [Great Work] <by consuming this unit>`, which is a unit action, not an event).

> Note: ideology *tenet* adoption itself can go through the existing `AdoptPolicy` command (tenets are policies). It's
> the *selection/free-tenet/dig/tour* events that need the new command.

**5.3 Tier B/C Kotlin features each need the full v3 treatment:**
- New **authoritative state** in `GameInfo` (tourism-influence map, great-work objects, ideology public-opinion,
  congress state, trade routes).
- New **`GameCommand`s** (e.g. `MoveGreatWork`, `ProposeResolution`, `CastCongressVote`, `EstablishTradeRoute`).
- **`PlayerViewProjector`** rules: what each player sees of rivals' tourism/works/votes/routes (visibility filtering).
- AI on the authority to use them.

---

## 6. Recommended phased plan

1. **Phase 0 — spike (small):** Install the mod into a 4.20.12 build, start a G&K + BNW game, confirm it **loads &
   validates** (collect any unknown-unique warnings). Then test it under **v3**: does an Event (e.g. ideology pick)
   surface and resolve for a *joiner*? This decides 5.2.
2. **Phase 1 — adopt Tier A (data):** Vendor the mod's JSON/images into our ruleset (or ship it as a bundled mod).
   Add `ResolveEvent` to `GameCommand` + executor + a client prompt, so events work in v3. Fix the lossy/no-op civ
   abilities we care about. **Outcome: a playable BNW-flavored game over v3** with approximated tourism/ideologies.
3. **Phase 2 — Tier B fidelity (pick what matters):** Decide which approximations to upgrade. Likely order by value:
   (a) **Ideology public-opinion + switching** (most gameplay-visible), (b) **real Tourism influence model + cultural
   victory**, (c) **Great Works objects + theming**. Each: new `GameInfo` state + commands + view projection + AI.
4. **Phase 3 — Tier C net-new:** **International Trade Routes** first (cheaper; scaffolding exists, big gameplay
   payoff, unlocks Venice), then **World Congress** (largest; full subsystem + UI + diplomacy + v3 voting commands).

Effort gradient: Phase 1 ≪ Phase 2 ≪ Phase 3. Phase 1 is mostly data + one command; Phase 3 items are multi-week
engine+netcode subsystems.

---

## 7. Risks & open decisions

- **[decision] Bundle vs vendor the ruleset.** Ship the mod as a downloadable mod (clients enable it) or fold its JSON
  into our own base ruleset? v3 needs ruleset parity across host+joiners — a bundled/built-in ruleset is safer than
  relying on each player downloading a mod.
- **[risk] Events under v3** (5.2) — unverified; blocks ideology/archaeology/concert-tour in MP until `ResolveEvent`
  lands. Resolve in Phase 0.
- **[decision] How faithful?** The mod's approximations are fully playable. Tier B/C work is only justified if we want
  Civ-V-accurate tourism/ideology/congress. Could ship Phase 1 and stop.
- **[risk] `ProposeTrade` already deferred in v3** (see `GameCommand` notes — bilateral `Trade` isn't kotlinx-
  serializable). World Congress proposals will hit the same serialization design question; plan a kotlinx DTO layer.
- **[housekeeping] Nested git.** `vendor/Civ-V-Brave-New-World/` contains its own `.git`. Decide: add `vendor/` to
  `.gitignore`, vendor as a git submodule, or strip `.git` and commit a pinned snapshot. (Not committed yet.)
- **[content] Companion-file dependencies.** Civs reference unique units/buildings in `Units.json`/`Buildings.json`;
  a partial import will dangle. Import the ruleset as a consistent whole and run ruleset validation.

---

## Appendix — engine-support evidence (4.20.12)

Verified the load-bearing primitives exist in our engine:

- `MoreCountableThanEachPlayer("Have more [countable] than each player's [countable]")` — `core/.../ruleset/Victory.kt:33`
  (handled at 166/231/345/407).
- Stockpile accumulation each turn — `core/.../managers/TurnManager.kt:40-41`
  (`getCivResourceSupply().filter { it.resource.isStockpiled }` → `gainStockpiledResource`).
- `ProvidesResources` (`UniqueType.kt:238`), `Stockpiled` (`:664`), `OneTimeProvideResources` (`:901`),
  `OneTimeConsumeResources` (`:900`), `StatPercentFromObjectToResource` (`:65`), `PercentResourceProduction` (`:246`),
  `TriggerEvent` (`:939`), `OneTimeAdoptPolicyOrBelief` (`:890`), `OneTimeEnterGoldenAgeTurns` (`:885`),
  `MayParadrop` (`:417`), `CanTradeWithCityStateForGoldAndInfluence` (`:424`).
- Resources are usable as **countables** (`Countables.TileResources`); `[X] Per Turn` via `StatOrResourcePerTurn`;
  `<for every …>` via the `ForEveryCountable` modifier (`UniqueType.kt:1055`).
- Events: `core/.../ruleset/Event.kt` (`Presentation { None, Alert, Floating }`, `EventChoice`).
- **Not found / unsupported (behavior-bearing):** none. (`"Great Work"` bare marker is a no-op; literal
  `Double the normal number of Trade Routes available` has no UniqueType but is used only as a nation-tag filter.)
- **No native concept** of `ideology`/`tenet`/tier, public opinion, tourism influence, World Congress, or city-to-city
  trade-route yields anywhere in `core/src` — these are the Tier B/C gaps.
