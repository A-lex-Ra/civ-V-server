package com.unciv.models.ruleset

import com.unciv.models.metadata.ALL_DLC_RULESET
import com.unciv.models.ruleset.validation.RulesetErrorSeverity
import com.unciv.testing.GdxTestRunner
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Permanent guard for BNW Stage A: the built-in "Civ V - All DLC" base ruleset
 * (Gods & Kings synthesized in memory with the bundled "Civ V - Brave New World" data) must be
 * registered as a base ruleset and must validate with **zero blocking (Error-severity) entries**.
 *
 * Warnings are allowed (the bundled mod data carries some) and reported for the record only.
 */
@RunWith(GdxTestRunner::class)
class AllDlcRulesetValidationTest {

    @Before
    fun loadRulesets() {
        // Tests' working dir is android/assets, so consoleMode's relative FileHandle("jsons/...")
        // resolves; noMods skips the user mods folder but the All-DLC synthesis still runs.
        if (RulesetCache.isEmpty() || RulesetCache[ALL_DLC_RULESET] == null)
            RulesetCache.loadRulesets(consoleMode = true, noMods = true)
    }

    @Test
    fun allDlcRulesetIsRegisteredAsBaseRuleset() {
        val ruleset = RulesetCache[ALL_DLC_RULESET]
        Assert.assertNotNull("'$ALL_DLC_RULESET' must be registered in RulesetCache", ruleset)
        Assert.assertTrue(
            "'$ALL_DLC_RULESET' must be flagged isBaseRuleset",
            ruleset!!.modOptions.isBaseRuleset
        )
        Assert.assertTrue(
            "'$ALL_DLC_RULESET' must appear in getSortedBaseRulesets()",
            RulesetCache.getSortedBaseRulesets().contains(ALL_DLC_RULESET)
        )
    }

    @Test
    fun allDlcRulesetHasNoBlockingErrors() {
        val ruleset = RulesetCache[ALL_DLC_RULESET]!!
        val errorList = ruleset.getErrorList()

        val blockingErrors = errorList.filter { it.errorSeverityToReport == RulesetErrorSeverity.Error }
        val warnings = errorList.filter { it.errorSeverityToReport == RulesetErrorSeverity.Warning }

        println("[$ALL_DLC_RULESET] validation: ${blockingErrors.size} blocking error(s), ${warnings.size} warning(s)")
        if (blockingErrors.isNotEmpty())
            println(errorList.getErrorText(unfiltered = true))

        Assert.assertTrue(
            "'$ALL_DLC_RULESET' must validate with 0 blocking Error-severity entries, found " +
                "${blockingErrors.size}:\n" + blockingErrors.joinToString("\n") { it.text },
            blockingErrors.isEmpty()
        )
    }

    @Test
    fun bnwContentMergedIntoAllDlcRuleset() {
        val ruleset = RulesetCache[ALL_DLC_RULESET]!!
        // BNW nation that is not present in Gods & Kings.
        Assert.assertTrue(
            "BNW nation 'Poland' should be present in '$ALL_DLC_RULESET'",
            ruleset.nations.containsKey("Poland")
        )
        // BNW stockpiled resource that drives the Tourism / cultural-victory plumbing.
        Assert.assertTrue(
            "BNW stockpiled resource 'Tourism' should be present in '$ALL_DLC_RULESET'",
            ruleset.tileResources.containsKey("Tourism")
        )
    }
}
