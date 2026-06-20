# BNW / Civ-V — Known Missing & Broken Features

Register of **verified** gaps between Civilization V: Brave New World and this fork. Originally
compiled 2026-06-20 against `7ee27b927`; **re-verified 2026-06-20 against `HEAD = 9a66ea451`**
after the Assyria/Nau mechanics, ITR/WC fixes and tourism work landed. Fixed items have been
removed and recorded under "Confirmed working" so they aren't re-raised.

Each item is tagged with how it was confirmed:
- ✅ **VERIFIED** — read against source line-by-line for this doc.
- 📋 **AUDIT-REPORTED** — found by a review agent, plausible and grounded in a `file:line`/TODO, but
  not independently re-confirmed line-by-line.
- 🔍 **SUSPECTED** — needs a runtime check (load the game / run the scenario) to confirm.

---

## 1. Bugs in shipped features (implemented, but broken)

### 1.1 ✅ Ideology — *voluntary* switch is still unreachable; no anarchy indicator · **Medium**
The *forced* path now works: the Civil-Resistance alert is rendered through a bespoke handler
([AlertPopup.kt:136-137, 715-738](../core/src/com/unciv/ui/screens/worldscreen/AlertPopup.kt#L136))
that draws a real prompt ("Switch to […] (and enter Anarchy)" / "Hold firm for now") and emits
`GameCommand.SwitchIdeology`; it re-raises each turn while the revolt persists
([PublicOpinionManager.kt:156-157](../core/src/com/unciv/logic/civilization/managers/PublicOpinionManager.kt#L156)).
The "Civil Resistance" string is intentionally **not** a ruleset Event — it's a carrier reused by
the bespoke handler, so the old "silently removed unrendered" bug is gone.

What's still missing:
- **Voluntary switch:** no UI control anywhere under `core/src/com/unciv/ui/` emits `SwitchIdeology`
  except the forced Civil-Resistance prompt. A human still cannot *choose* to switch ideology (e.g.
  there is no control in `PolicyPickerScreen`). The authority handler exists; only the UI entry
  point is absent.
- **No anarchy indicator:** anarchy is applied and counted down
  ([PublicOpinionManager.kt:216-217](../core/src/com/unciv/logic/civilization/managers/PublicOpinionManager.kt#L216))
  but nothing in the UI shows the player they are in anarchy (no civ-level status; `StatusTable`
  shows only city-level states).
- **Fix:** add a voluntary "Switch ideology" control (e.g. in `PolicyPickerScreen`) emitting
  `SwitchIdeology`, and surface an anarchy indicator.

### 1.2 📋 ITR — per-turn yields can be paid to a **dead** destination owner · **Medium**
[applyYieldsForOwner](../core/src/com/unciv/logic/trade/TradeRouteManager.kt#L206) checks
`destCity != null` and `destOwner.civID != civ.civID` before `destOwner.addGold(...)` but does
**not** check `destOwner.isAlive()`. If the destination owner is eliminated while still nominally
owning the city, gold is credited to a dead civ.
- **Fix:** add an `isAlive()` guard on the destination owner before crediting incoming-route gold.

### 1.3 📋 ITR — BFS route computed twice on establish (defensive/perf) · **Low**
The route BFS runs once in the executor to validate connectivity
([CommandExecutor.kt:1442](../core/src/com/unciv/logic/multiplayer/v3/command/CommandExecutor.kt#L1442))
and again inside `establish` to record `length`
([TradeRouteManager.kt:161](../core/src/com/unciv/logic/trade/TradeRouteManager.kt#L161)). Correct,
but the expensive pathfind happens twice.
- **Fix:** return both existence and length from a single compute, or pass the validated length into
  `establish`.

### 1.4 ✅ World Congress — legacy diplomatic-vote helpers are stale dead code · **Low**
The live diplomatic-victory path now goes through the congressional authority
([WorldCongressManager.enactWorldLeaderElection:479-496](../core/src/com/unciv/logic/civilization/managers/WorldCongressManager.kt#L479)),
which tallies **weighted delegate counts** over living majors only — so the victory is winnable.
But the legacy helpers it superseded still ship with the old bugs and are no longer called for the
congress path:
[VictoryManager.calculateDiplomaticVotingResults:31](../core/src/com/unciv/logic/civilization/managers/VictoryManager.kt#L31)
counts 1 vote/voter (2 for the UN owner), and
[votesNeededForDiplomaticVictory:59](../core/src/com/unciv/logic/civilization/managers/VictoryManager.kt#L59)
derives the threshold from `getVotingCivs().count()`, which still includes city-states
([:43](../core/src/com/unciv/logic/civilization/managers/VictoryManager.kt#L43)).
- **Fix:** remove or reconcile the legacy helpers so the two paths can't diverge.

### Other verified Mediums worth a pass
- 📋 WC: eliminated voters/hosts aren't *pruned* mid-session. Votes from dead civs are filtered at
  tally time ([:488-490](../core/src/com/unciv/logic/civilization/managers/WorldCongressManager.kt#L488))
  so the outcome is correct, but a defeated host keeps the seat (and its stored delegate count)
  until the next session boundary. Defensive/lazy rather than a victory bug.

---

## 2. Missing mechanics — bundled as data but code-less (do nothing today)

### 2.1 📋 Trade-route **reciprocal** owner bonuses (Morocco / Arabia) · **Medium**
The incoming/destination-owner gold side now works generically
([TradeRouteYields.kt:97-101](../core/src/com/unciv/logic/trade/TradeRouteYields.kt#L97),
applied at [TradeRouteManager.kt:210](../core/src/com/unciv/logic/trade/TradeRouteManager.kt#L210)),
but two civ-specific reciprocal bonuses are still TODO flavour text:
- **Morocco** "+2 Gold to the *owner* of each route sent to Morocco"
  ([Nations.json:236](../android/assets/jsons/Civ%20V%20-%20Brave%20New%20World/Nations.json#L236)).
- **Arabia** "your routes spread the home religion **twice** as effectively / extra gold"
  ([Nations.json:341](../android/assets/jsons/Civ%20V%20-%20Brave%20New%20World/Nations.json#L341)) —
  still approximated by a generic `[+10]% Spread Religion Strength` proxy (religion spread itself
  works via `TradeRouteYields.RELIGION_PRESSURE`, but the 2× multiplier is unmodeled).

---

## 3. Suspected / needs runtime verification

### 3.1 🔍 Archaeology — system present as data, engine support unverified
The BNW data ships the Archaeologist unit, Antiquity / Hidden Antiquity Site resources, the Landmark
improvement, and dig "payoff" events (Units/TileResources/TileImprovements/Events.json). There is
still **no dedicated archaeology code** in `core/src` (grep for `Archaeolog|Antiquity|Landmark` hits
only World-Congress resolution names + a tech description). Whether the full loop — sites spawn →
dig → choose **Landmark** vs **Artifact** (a Great Work of Art) — works end-to-end on the generic
data/event engine is **unconfirmed**.
- **Verify:** start an All-DLC game, build an Archaeologist, attempt a dig on an Antiquity Site,
  confirm both reward branches resolve (and work over the multiplayer-v3 authority).

### 3.2 📋 Lossy-but-functional approximations (accept or refine)
- **Zulu Iklwa:** mapped to `[+25]% XP gained from combat`
  ([Nations.json:130](../android/assets/jsons/Civ%20V%20-%20Brave%20New%20World/Nations.json#L130))
  rather than "−25% promotion XP cost" — wrong semantics.
- **Indonesia Candi:** flat `+3 Faith`
  ([Buildings.json:260](../android/assets/jsons/Civ%20V%20-%20Brave%20New%20World/Buildings.json#L260),
  TODO present) instead of scaling per distinct religion present.
- **Aesthetics / Order tenets:** tourism conditionals (Cultural Exchange, Cult of Personality, Iron
  Curtain, etc.) approximated with simpler/flat effects.
- **Landmark culture:** flat value instead of scaling with era difference.
- **2 new BNW scenarios:** not ported (out of scope).

---

## Confirmed working (NOT gaps — recorded so they aren't re-raised)
Ideologies (public opinion, pressure, anarchy, free tenets, **forced Civil-Resistance switch prompt
+ re-raise**), Tourism (per-rival influence, cultural victory, concert tour, trade-route tourism
modifier, **Brazil Carnival Golden-Age multiplier now scaling Great-Works tourism** —
[TourismManager.kt:76-81](../core/src/com/unciv/logic/civilization/managers/TourismManager.kt#L76)),
Great Works (registry, slots, theming, AI optimize, overview UI), ITR core (establish/yields/expiry/
plunder/projection/AI, internal food/production routes, **far-endpoint visibility scrubbing of
unexplored rival cities** — [PlayerViewProjector.kt:405-423](../core/src/com/unciv/logic/multiplayer/v3/visibility/PlayerViewProjector.kt#L405),
**establish idempotency guard** — [CommandExecutor.kt:1431-1436](../core/src/com/unciv/logic/multiplayer/v3/command/CommandExecutor.kt#L1431),
**incoming/destination-owner gold**), World Congress core (founding, delegates, sessions, most
resolutions, world projects, vote-secret scrubbing, **diplomatic-victory election via weighted
delegate tally over living majors**, **target/choice resolutions now proposable in both AI
([WorldCongressManager.kt:558-564](../core/src/com/unciv/logic/civilization/managers/WorldCongressManager.kt#L558))
and human ([CommandExecutor.kt:1633-1637](../core/src/com/unciv/logic/multiplayer/v3/command/CommandExecutor.kt#L1633))
paths**, **candidate validated as living major**, **host elected before delegate recompute**),
**Assyria "Treasures of Nineveh"** (steal a tech on conquest — `StealTechWhenConqueringCity` +
[Battle.kt:705-722](../core/src/com/unciv/logic/battle/Battle.kt#L705), once-per-city via
`City.hasProvidedConquestTech`), **Portugal Nau "Sell Exotic Goods"** (distance-scaled one-time
Gold+XP — [SellExoticGoods.kt](../core/src/com/unciv/logic/map/mapunit/SellExoticGoods.kt) + UI
action + v3 executor), Venice (no-settle / puppet-only / double routes / Merchant of Venice /
buy-in-puppets), Shoshone ruins choice, Indonesia Kris random promotion, Poland free policy on era,
and the multiplayer-v3 authority/visibility/clone-transients discipline across all of the above.
