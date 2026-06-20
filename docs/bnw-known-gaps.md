# BNW / Civ-V — Known Missing & Broken Features

Register of **verified** gaps between Civilization V: Brave New World and this fork. Originally
compiled 2026-06-20 against `7ee27b927`; **re-verified + a follow-up fix pass landed 2026-06-20**
(ITR dead-owner gold guard + single-BFS establish, World-Congress mid-session dead-host re-election,
Zulu promotion-XP cost; on top of the earlier Assyria/Nau mechanics, ITR/WC fixes and tourism work).
Fixed items have been removed and recorded under "Confirmed working" so they aren't re-raised.
*(Gap 1.1 Ideology is being addressed separately.)*

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

*(ITR dead-destination-owner gold, ITR double-BFS-on-establish, and the World-Congress
mid-session-dead-host cases were fixed — see "Confirmed working".)*

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

### 2.2 ✅ ITR yields are banked *directly*, so they bypass the stat pipeline · **Low**
International-Trade-Route yields are **not** routed through `CityStats`/`statsForNextTurn`. The
authority banks them in their own pass — owner gold via `civ.addGold`, owner catch-up science via
`tech.addScience`, destination-owner gold to the other civ, and internal Food/Production + religion
pressure delivered straight to the destination **city**
([TradeRouteManager.applyYieldsForOwner](../core/src/com/unciv/logic/trade/TradeRouteManager.kt#L209),
called once per owner from [TurnManager.kt:332](../core/src/com/unciv/logic/civilization/managers/TurnManager.kt#L332),
*after* the `statsForNextTurn` gold/science banking at line 324).

This is deliberate (design decision **D4**): a route is a *bilateral* contract whose yield depends on
**both** endpoints and is split between **two** civs, which does not fit the per-city→civ aggregation
`getStatMapForNextTurn` is built on; folding it in would either double-bank (banked once by the stat
sum at line 324 and again by the ITR pass) or require ITR gold to ripple into AI income estimates,
gold-per-turn trade offers and the treasury-deficit science penalty — an economy change, not a
display change. The treasury value (`civInfo.gold`) is always correct; only its *attribution* lives
outside the stat map. Consequences that remain:

- **AI / trade blindness.** `statsForNextTurn.gold`/`.science` (read by `NextTurnAutomation`,
  `TradeEvaluation`, the GPT-offer cap in `TradeLogic`, the disband-when-broke loop, and the
  treasury-deficit→science penalty) does **not** include ITR yields. The AI under-counts ITR income
  when valuing trades and decisions. *Accepted* — fixing it means the economy ripple above.
- **City-level deliveries are unsurfaced.** Internal Food/Production and religion pressure land
  directly in the destination city's food store / current construction / religion pressure, bypassing
  `CityStats` too, so no city screen attributes them to "trade routes". (Out of scope for a top-bar
  fix — these are city stats, not civ stats.)
- **Filtered-view attribution.** On a multiplayer-v3 client whose filtered snapshot hides a route's
  far endpoint, the per-turn **display** of that route's yield reads 0 (`computeYields` → `EMPTY`),
  even though the authority banked the real amount and the treasury total is correct.

**Partially addressed:** the civ-level yields (owner gold + incoming destination gold; owner science)
are now shown in the top-bar Gold/Science **teardowns** and folded into the headline `+/turn` so the
two agree — display-only, via
[`TradeRouteManager.getStatsPerTurnForDisplay`](../core/src/com/unciv/logic/trade/TradeRouteManager.kt#L191)
read by [WorldScreenTopBarStats](../core/src/com/unciv/ui/screens/worldscreen/topbar/WorldScreenTopBarStats.kt#L218)
(an "International Trade Routes" line). The AI/trade blindness, the city-level Food/Production/religion
attribution, and the filtered-view 0 are the parts left open.

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
- **Indonesia Candi:** flat `+3 Faith`
  ([Buildings.json:260](../android/assets/jsons/Civ%20V%20-%20Brave%20New%20World/Buildings.json#L260),
  TODO present) instead of scaling per distinct religion present.
- **Ideology tenets — effects.** Names / levels / structure are **100% faithful** (Freedom/Order/
  Autocracy each have the correct 7/6/3 = 16 tenets, correct L3↔victory pairings, the three
  "allows construction" wonders gated, mutual exclusivity present). A set of individual *effects* are
  still approximated (most `// TODO`-tagged in `Policies.json`):
  - *Freedom* — **Economic Union** (+1 Gold/route; should be +3 with other Freedom civs — needs a
    per-route civ-ideology conditional); **Treaty Organization** (+50% CS-gift resources; should be
    +4 Influence/turn with CS you trade with).
  - *Order* — **Cultural Revolution** & **Dictatorship of the Proletariat** tourism applied
    unconditionally (should be vs other Order civs / vs less-happy civs); **Iron Curtain** trade
    bonus rides the legacy capital-connection path, not BNW internal routes; **Double Agents** /
    **Spaceflight Pioneers** spy & Great-Engineer flavour substituted. *(Socialist Realism is now
    **fixed** — +2 **local** Happiness from Monuments, +100% Monument production.)*
  - *Autocracy* — **Industrial Espionage** / **Cult of Personality** / **Gunboat Diplomacy** spy &
    city-state effects approximated (need spy "2× faster" / common-enemy / tribute-CS conditionals).
  - *Systematic:* Civ V's **local vs global Happiness** is now **implemented**
    ([CityStats.kt](../core/src/com/unciv/logic/city/CityStats.kt), `updateCityHappiness`). A
    building's Happiness is **local** — it can only offset unhappiness up to the city's **Population**,
    and the excess is wasted (booked as a `Wasted Local Happiness` line that the empire breakdown sums
    in). Luxuries / Social-Policy / belief / other civ-wide Happiness is aggregated empire-wide and so
    stays **global**. The four global-Happiness wonders (Notre Dame, Circus Maximus, Eiffel Tower,
    Prora) carry the new `Happiness from this building is global` unique
    ([UniqueType.GlobalHappiness](../core/src/com/unciv/models/ruleset/unique/UniqueType.kt)) and are
    exempt from the cap. This makes every `+X Local Happiness from [building]` tenet genuinely local —
    Socialist Realism, the three Universal Healthcares, Capitalism, Urbanization, Young Pioneers,
    Academy of Sciences, Fortified Borders, Militarism, Police State — since they all add Happiness to
    non-wonder buildings (`StatsFromBuildings`), which lands in the capped per-city "Buildings" pool.
    *Minor remaining approximations:* a few per-city Happiness **policy/belief** uniques that are not
    "from buildings" (e.g. Goddess of Love, Sacred Waters, Military Caste's garrison Happiness) are
    still treated as global rather than population-capped, and Neuschwanstein's "+1 global per Castle"
    rides the local Castle line.
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
**incoming/destination-owner gold** with a **dead-destination-owner `isAlive()` guard**, **single
connectivity-BFS on establish** (the authority's precomputed length is reused)), World Congress core (founding, delegates, sessions, most
resolutions, world projects, vote-secret scrubbing, **diplomatic-victory election via weighted
delegate tally over living majors**, **target/choice resolutions now proposable in both AI
([WorldCongressManager.kt:558-564](../core/src/com/unciv/logic/civilization/managers/WorldCongressManager.kt#L558))
and human ([CommandExecutor.kt:1633-1637](../core/src/com/unciv/logic/multiplayer/v3/command/CommandExecutor.kt#L1633))
paths**, **candidate validated as living major**, **host elected before delegate recompute**,
**mid-session host re-election if the elected host is eliminated**),
**Assyria "Treasures of Nineveh"** (steal a tech on conquest — `StealTechWhenConqueringCity` +
[Battle.kt:705-722](../core/src/com/unciv/logic/battle/Battle.kt#L705), once-per-city via
`City.hasProvidedConquestTech`), **Portugal Nau "Sell Exotic Goods"** (distance-scaled one-time
Gold+XP — [SellExoticGoods.kt](../core/src/com/unciv/logic/map/mapunit/SellExoticGoods.kt) + UI
action + v3 executor), Venice (no-settle / puppet-only / double routes / Merchant of Venice /
buy-in-puppets), Shoshone ruins choice, Indonesia Kris random promotion, **Zulu Iklwa −25%
promotion-XP cost** (`[-25]% XP required for promotions`), Poland free policy on era,
and the multiplayer-v3 authority/visibility/clone-transients discipline across all of the above.

The legacy `VictoryManager` UN-vote helpers (`calculateDiplomaticVotingResults`,
`votesNeededForDiplomaticVictory`) count 1 vote/voter and include city-states — that is **correct
for the non-congress fallback** (vanilla / G&K rulesets with a UN wonder, where city-states do vote
in the UN). `handleDiplomaticVictoryFlags` early-returns once a World Congress is founded, so the
congress path (weighted, majors-only) and the legacy path never both run. Not dead code, not a gap.
