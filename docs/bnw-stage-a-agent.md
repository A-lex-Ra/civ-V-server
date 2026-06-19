# BNW Stage A — "Civ V - All DLC" built-in base ruleset (agent spec)

Context: `docs/brave-new-world-adoption.md` (the design doc — §5.1, §7 "Bundle vs vendor").
This note is the **exact build spec + cross-stage contract** for Stage A. Read the design doc for *why*;
this file is the *how*. Phase 0 + the `ResolveEvent` netcode keystone are already done & committed.

## Goal
Add a **built-in base ruleset "Civ V - All DLC"** = the engine's *Gods & Kings* ruleset combined at
load time with the bundled *Brave New World* (BNW) data, registered so it appears in the New Game
base-ruleset dropdown, and make it the **default** for new games. Vanilla / G&K stay available.

## Chosen mechanism — runtime synthesis (NOT hand-merged JSON)
We let the engine merge **in-memory** via `RulesetCache.getComplexRuleset(base, extensions)`. That call
returns a *fresh* combined `Ruleset`, applies BNW's `ModOptions` removals
(`nationsToRemove`/`policiesToRemove`/`buildingsToRemove`), and recomputes building costs + resource
transients. We do **not** ship a pre-merged `jsons/Civ V - All DLC/` folder (a hand/round-tripped merge
risks corrupting the on-disk TechColumn / PolicyBranch structure — confirmed). We do **not** add an entry
to the `BaseRuleset` enum (the enum drives asset discovery; an entry would make startup try to load a
non-existent `jsons/Civ V - All DLC/` folder). Registration is post-load, by inserting the synthesized
`Ruleset` into the `RulesetCache` map with `isBaseRuleset = true` — `getSortedBaseRulesets()` then shows
it automatically (it filters `values.filter { it.modOptions.isBaseRuleset }`).

## Tasks (file ownership — you own ALL of these)

### 1. Bundle BNW data into assets
Copy the **contents** of `vendor/Civ-V-Brave-New-World/jsons/` (all `*.json` incl. `ModOptions.json`,
plus the `translations/` subfolder) into a NEW folder:

```
android/assets/jsons/Civ V - Brave New World/
```

The JSONs must sit **directly** in that folder (so `Ruleset.load(folderHandle)` finds `Buildings.json`
etc.), mirroring how `jsons/Civ V - Gods & Kings/` is laid out. Do **NOT** copy the mod's root
`game.atlas` / `game.png` / `Atlases.json` (those would collide with core atlases — images are a separate
later stage). Do **NOT** copy the mod's `.git` or `Images/`.

> NAME GOTCHA: BNW's `ModOptions.json` declares `"Mod is incompatible with [Brave New World]"`. So the
> bundled ruleset's **name must not be exactly `Brave New World`** or it self-conflicts during validation.
> Folder name `Civ V - Brave New World` is intentionally distinct. If validation still trips an
> incompatibility/self-reference error, adjust the bundled name until the validation test (task 4) is clean.

### 2. Synthesize + register in `RulesetCache.loadRulesets()`
File: `core/src/com/unciv/models/ruleset/RulesetCache.kt`. Insert **after line 74**
(`this.putAll(newRulesets)` — i.e. once the cache is repopulated), wrapped in try/catch so a failure
**logs and does not break startup**. Mirror the existing `getBuiltinRulesetFileHandle` console/internal
split for the file handle. Sketch:

```kotlin
// Synthesize the built-in "Civ V - All DLC" base ruleset = Gods & Kings + bundled Brave New World.
// Engine-merged in memory (applies BNW's removals + recomputes costs); not a pre-merged JSON folder.
try {
    val bnwPath = "jsons/$bundledBnwName"   // bundledBnwName = "Civ V - Brave New World"
    val bnwHandle = if (consoleMode) FileHandle(bnwPath) else Gdx.files.internal(bnwPath)
    if (bnwHandle.exists()) {
        val bnw = Ruleset().apply { name = bundledBnwName; load(bnwHandle) }
        this[bundledBnwName] = bnw
        val gnk = this[BaseRuleset.Civ_V_GnK.fullName]
        if (gnk != null) {
            val allDlc = getComplexRuleset(gnk, listOf(bnw))
            allDlc.name = ALL_DLC_RULESET            // "Civ V - All DLC"
            allDlc.modOptions.isBaseRuleset = true
            this[ALL_DLC_RULESET] = allDlc
        }
    }
} catch (ex: Exception) {
    Log.error("Failed to synthesize the '$ALL_DLC_RULESET' ruleset", ex)
}
```

Use the `getComplexRuleset(baseRuleset: Ruleset, extensionRulesets: Iterable<Ruleset>)` overload
(takes objects, not cache keys). Note `getComplexRuleset` already sets `isBaseRuleset=true` on the result
when a base is included; the explicit set is belt-and-suspenders.

### 3. Name constant + default + fallbacks
- Define the names ONCE. Add to `core/src/com/unciv/models/metadata/BaseRuleset.kt` (top-level or a
  companion), e.g. `const val ALL_DLC_RULESET = "Civ V - All DLC"` and
  `const val BUNDLED_BNW_RULESET = "Civ V - Brave New World"`. **Do NOT touch `Constants.kt`** — it has an
  unrelated uncommitted change; keep it out of this stage.
- Change the new-game default: `core/src/com/unciv/models/metadata/GameParameters.kt` (~line 66)
  `var baseRuleset: String = BaseRuleset.Civ_V_GnK.fullName` → reference `ALL_DLC_RULESET`.
- Leave the existing G&K *fallback* sites (`GameOptionsTable.kt` ~458, `ModCheckboxTable.kt` ~86) pointing
  at G&K — those are safety fallbacks for a deleted/missing selection and G&K is always present. Only
  change them if you find the default genuinely needs them to point at All DLC (justify in your report).

### 4. Permanent validation test
Add a test (suggested: `tests/src/com/unciv/models/ruleset/AllDlcRulesetValidationTest.kt`) that:
- bootstraps the cache headless: `RulesetCache.loadRulesets(consoleMode = true, noMods = true)`
  (the tests' working dir is `android/assets`, so `consoleMode`'s relative `FileHandle("jsons/...")`
  resolves; `noMods` skips the user mods folder but synthesis still runs);
- asserts `RulesetCache[ALL_DLC_RULESET]` is non-null and `isBaseRuleset`;
- asserts its `getErrorList()` has **0 blocking (Error-severity) entries** (warnings allowed). Model the
  validation/severity API on existing ruleset tests (e.g. `EventCircularTriggersTest`,
  `RulesetCache.checkCombinedModLinks`, `RulesetErrorList`/`RulesetErrorSeverity`).
- Optionally assert a couple of BNW signals merged in (e.g. a BNW nation like `Poland` and a stockpiled
  resource like `Tourism` exist in the ruleset) to prove the merge actually happened.

## Verify by outcome (required before reporting done)
- Run the new test: `./gradlew.bat :tests:test --tests "...AllDlcRulesetValidationTest"` → GREEN, 0 blocking errors.
- Compile the wiring: `./gradlew.bat :core:compileKotlin` → SUCCESS.
- You are the ONLY agent running, so you MAY run Gradle. Do **not** build `:desktop:dist` (the orchestrator
  runs the consolidated jar build). Do **NOT** `git commit` — the orchestrator commits the stage.

## Report back
- Exact files changed/created (+ the asset folder).
- The validation result: blocking-error count (must be 0) and the warning count (for the record).
- The bundled ruleset name you settled on (and whether the name gotcha forced a change).
- Anything that diverged from this spec and why.
- Note explicitly that **images/icons are deferred to Stage A2** (data-first; missing icons render as a
  white-dot placeholder and do not crash — verified).
