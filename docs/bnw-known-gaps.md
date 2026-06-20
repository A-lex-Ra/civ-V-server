# BNW / Civ-V — Known Missing & Broken Features

Register of **verified** gaps between Civilization V: Brave New World and this fork. Compiled
2026-06-20 against `HEAD = 7ee27b927`, from a deep review of the Ideology, Tourism, Great Works,
ITR and World Congress work plus an audit of the bundled "Civ V – All DLC" data-mod.

Each item is tagged with how it was confirmed:
- ✅ **VERIFIED** — read against source line-by-line for this doc.
- 📋 **AUDIT-REPORTED** — found by a review agent, plausible and grounded in a `file:line`/TODO, but
  not independently re-confirmed line-by-line.
- 🔍 **SUSPECTED** — needs a runtime check (load the game / run the scenario) to confirm.

---

## 1. Bugs in shipped features (implemented, but broken)

### 1.1 ✅ Ideology — the *switch* mechanic is unreachable for human players · **Critical**
The whole "change ideology" loop has no working UI; only AI can switch.
- **Voluntary switch:** `GameCommand.SwitchIdeology` exists and the authority handles it, but it is
  emitted from **nowhere** under `core/src/com/unciv/ui/` (grep: zero hits). A human can never choose
  to switch.
- **Forced switch (Civil Resistance):** [PublicOpinionManager.kt:156](../core/src/com/unciv/logic/civilization/managers/PublicOpinionManager.kt#L156)
  raises `PopupAlert(AlertType.Event, "Civil Resistance")`, where `CIVIL_RESISTANCE_EVENT_NAME =
  "Civil Resistance"` ([:223](../core/src/com/unciv/logic/civilization/managers/PublicOpinionManager.kt#L223)).
  But **no ruleset Event named "Civil Resistance" exists** (absent from Events.json), so
  [AlertPopup.kt:690](../core/src/com/unciv/ui/screens/worldscreen/AlertPopup.kt#L690)
  `ruleset.events[eventName] ?: return false` fails and the alert is **silently removed unrendered**
  ([AlertPopup.kt:135](../core/src/com/unciv/ui/screens/worldscreen/AlertPopup.kt#L135)). It never
  re-raises (the `forcedSwitchPending` guard), so the human keeps paying dissident unhappiness with
  no prompt and no escape. AI escapes by reading `forcedSwitchPending` directly
  ([NextTurnAutomation.kt:352](../core/src/com/unciv/logic/automation/civilization/NextTurnAutomation.kt#L352)).
- **No anarchy indicator** anywhere in the UI (minor, same area).
- **Fix:** render the Civil-Resistance alert as a real forced-switch prompt and add a voluntary
  "Switch ideology" control (e.g. in `PolicyPickerScreen`) — both emitting `SwitchIdeology`. Either
  add a real "Civil Resistance" event to the ruleset routed through `ResolveEvent`, or give
  `AlertType.Event` a bespoke handler for this name.

### 1.2 ✅ ITR — visibility leak of a hidden rival city · **High**
[redactTradeRoutes](../core/src/com/unciv/logic/multiplayer/v3/visibility/PlayerViewProjector.kt#L392)
keeps any route where `ownerCivId == viewer || origin/dest in viewer's cities`, but leaves the **far**
endpoint's `originCityId`, `ownerCivId` and `length` intact. Meanwhile
[redactCities](../core/src/com/unciv/logic/multiplayer/v3/visibility/PlayerViewProjector.kt#L156)
has already removed any rival city the viewer never explored. So a route `B → (your city)` leaks the
existence, stable id, owner, and path distance of an unscouted rival city.
- **Fix:** when a kept route's far endpoint city is not in the viewer's explored set, blank
  `originCityId`/`destinationCityId` (and `length`/`ownerCivId`) so only the viewer-relevant half
  survives.

### 1.3 ✅ World Congress — Diplomatic Victory via election is effectively unwinnable · **High**
Two compounding causes:
1. [recordCongressVotes](../core/src/com/unciv/logic/civilization/managers/WorldCongressManager.kt#L492)
   writes **one entry per voter** into `diplomaticVictoryVotesCast` — delegate weight is discarded
   (the code comment admits it; the weighted `electionTally` exists but is unused).
   [VictoryManager.calculateDiplomaticVotingResults](../core/src/com/unciv/logic/civilization/managers/VictoryManager.kt#L31)
   then counts 1 vote/voter (2 only for the UN-building owner).
2. [votesNeededForDiplomaticVictory](../core/src/com/unciv/logic/civilization/managers/VictoryManager.kt#L59)
   derives the threshold from `getVotingCivs().count()`, which includes **city-states**
   ([:43](../core/src/com/unciv/logic/civilization/managers/VictoryManager.kt#L43) — no major-only
   filter), while votes only come from majors. With any city-states present the threshold is out of
   reach.
- 📋 **Compounding:** the target/choice resolutions (`WorldLeaderElection`, `BanLuxury`,
  `TradeSanctions`, `WorldReligion`, `WorldIdeology`) are **un-proposable in practice** — AI's
  `automateProposal` skips resolutions needing a target/choice and the human screen hides them. So
  the election can be neither proposed nor won.
- **Fix:** translate delegate counts into the victory tally (weighted votes incl. allied
  city-state delegates), and expose target/choice resolution proposing in the AI + UI paths.

### 1.4 ✅ World Congress — host delegate count off-by-one in projection · **Low**
`recomputeDelegates()` runs **before** `electHost()` in both
[tryFoundCongress:168-169](../core/src/com/unciv/logic/civilization/managers/WorldCongressManager.kt#L168)
and [beginSession:268-269](../core/src/com/unciv/logic/civilization/managers/WorldCongressManager.kt#L268),
so the **stored** `delegateCounts` lacks the new host's `+1`
([getDelegateCount:207](../core/src/com/unciv/logic/civilization/managers/WorldCongressManager.kt#L207)).
Vote validation uses the live `getDelegateCount`, so this is display/projection only.
- **Fix:** swap the two calls (elect host, then recompute).

### 1.5 ✅ ITR — non-idempotent establish (defensive) · **Low**
`establish` just appends a connection with no dedup, and the executor has no "this unit already has a
route" guard. In practice a same-unit duplicate is **already blocked** because `establish` hard-sets
`currentMovement = 0f` ([TradeRouteManager.kt:173](../core/src/com/unciv/logic/trade/TradeRouteManager.kt#L173))
and the executor gates on `unit.hasMovement()`
([CommandExecutor.kt:1431](../core/src/com/unciv/logic/multiplayer/v3/command/CommandExecutor.kt#L1431)),
with sequential authority application. Worth an explicit idempotency check as defense-in-depth.

### Other verified Mediums worth a pass
- 📋 ITR: per-turn yields can be paid to a **dead** destination owner (no `isAlive()` guard in
  `TradeRouteYields`); the BFS route is computed twice on establish.
- 📋 WC: `WorldLeaderElection` candidate (`choiceArg`) isn't validated as a living major; eliminated
  voters/hosts aren't pruned mid-session.

---

## 2. Missing mechanics — bundled as data but code-less (do nothing today)

These BNW abilities are in the data-mod as flavour text / inert uniques / TODO comments and require
engine code to actually function.

### 2.1 ✅ Assyria — "Treasures of Nineveh" (steal a tech on conquest) · **High (civ UA dead)**
[Nations.json:60-66](../android/assets/jsons/Civ%20V%20-%20Brave%20New%20World/Nations.json#L60): the
real effect is a TODO; the placeholder `"[+25]% [Science] [in annexed cities] <for [20] turns>"` is a
**triggered-only timed unique with no trigger**, so it is filtered out of `getMatchingUniques` and
does nothing. Assyria's unique ability is effectively absent.
- **Need:** city-capture hook granting a free tech the prior owner knew (if the captor is behind),
  once per enemy city — e.g. a `OneTimeFreeTechFromConqueredCity`-style triggerable.

### 2.2 ✅ Portugal — Nau "Sell Exotic Goods" · **Medium (UU signature absent)**
[Units.json:310-311](../android/assets/jsons/Civ%20V%20-%20Brave%20New%20World/Units.json#L310): only
a TODO comment. The Nau is a plain Caravel variant; its signature one-time Gold+XP sale (scaling with
distance from the capital) is missing.
- **Need:** a unit action + handler (distance-scaled gold/XP, once per unit).

### 2.3 📋 Trade-route reciprocal & incoming bonuses · **Medium**
Several "the *other* civ / the city *owner* of an **incoming** route gains gold" effects are TODO:
Colossus / East India Company / Bazaar incoming-route gold, Morocco "+2 Gold to civs trading with
me", Arabia "trade routes spread religion 2× / extra gold". The route *owner* side mostly works;
the reciprocal/incoming side is unmodeled. Arabia's religion bonus is replaced by a generic
`[+10]% Spread Religion Strength` proxy (lossy).

---

## 3. Suspected / needs runtime verification

### 3.1 🔍 Archaeology — system present as data, engine support unverified
The BNW data ships the Archaeologist unit, Antiquity / Hidden Antiquity Site resources, the Landmark
improvement, and dig "payoff" events (Units/TileResources/TileImprovements/Events.json). There is
**no dedicated archaeology code** in `core/src` (grep for `Archaeolog|Antiquity|Landmark` hits only
World-Congress resolution names + a tech description). Whether the full loop — sites spawn → dig →
choose **Landmark** vs **Artifact** (a Great Work of Art) — works end-to-end on the generic
data/event engine is **unconfirmed**. One review agent claimed it is "event-driven and wired";
this contradicts the absence of code and should be settled by actually playing a dig.
- **Verify:** start an All-DLC game, build an Archaeologist, attempt a dig on an Antiquity Site,
  confirm both reward branches resolve (and work over the multiplayer-v3 authority).

### 3.2 🔍 Brazil — "Carnival" (+100% Tourism during Golden Ages)
[Nations.json] uses `[+100]% [Tourism] resource production <during a Golden Age>`
(`PercentResourceProduction`), but that modifier is only applied to `Provides [N] Tourism` flat
supply ([CivInfoTransientCache.kt:347]); the dominant Great-Works tourism flows through
`TourismManager.tourismOutputContributors` and is likely **not** multiplied — so the Golden-Age boost
may be a near-no-op. Needs a live check of the tourism total during a Golden Age.

### 3.3 📋 Lossy-but-functional approximations (accept or refine)
- **Zulu Iklwa:** mapped to `[+25]% XP gained` rather than "−25% promotion XP cost" — wrong semantics.
- **Indonesia Candi:** flat `+3 Faith` instead of scaling per distinct religion present.
- **Aesthetics / Order tenets:** tourism conditionals (Cultural Exchange, Cult of Personality, Iron
  Curtain, etc.) approximated with simpler/flat effects.
- **Landmark culture:** flat value instead of scaling with era difference.
- **2 new BNW scenarios:** not ported (out of scope).

---

## Confirmed working (NOT gaps — recorded so they aren't re-raised)
Ideologies (public opinion, pressure, anarchy, free tenets), Tourism (per-rival influence, cultural
victory, concert tour, trade-route tourism modifier), Great Works (registry, slots, theming, AI
optimize, overview UI), ITR core (establish/yields/expiry/plunder/projection/AI, internal
food/production routes), World Congress core (founding, delegates, sessions, most resolutions, world
projects, vote-secret scrubbing), Venice (no-settle / puppet-only / double routes / Merchant of
Venice / buy-in-puppets), Shoshone ruins choice, Indonesia Kris random promotion, Poland free policy
on era, and the multiplayer-v3 authority/visibility/clone-transients discipline across all of the
above.
