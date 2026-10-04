package com.notify.updater

import android.util.Log

/**
 * Reasons why a hard (mandatory) update block is imposed.
 */
enum class HardUpdateReason {
    URGENT,
    SKIPS_EXHAUSTED
}

/**
 * Decision returned by [UpdatePolicy.evaluateUpdatePolicy].
 */
sealed interface UpdatePolicyDecision {
    /**
     * App is up to date or current version is fully supported without prompts.
     */
    object None : UpdatePolicyDecision

    /**
     * A newer version is available and user can defer it.
     * @param skipsLeft Number of times user can still tap "Later".
     */
    data class Soft(val skipsLeft: Int) : UpdatePolicyDecision

    /**
     * App cannot proceed until updated.
     * @param reason Either [HardUpdateReason.URGENT] or [HardUpdateReason.SKIPS_EXHAUSTED].
     */
    data class Hard(val reason: HardUpdateReason) : UpdatePolicyDecision
}

/**
 * Pure policy evaluation logic for in-app updates based on integer versionCodes.
 */
object UpdatePolicy {
    private const val TAG = "UpdatePolicy"

    /**
     * Evaluates update policy based on current versionCode, server latest/min codes, and skip count.
     *
     * Rules:
     * 1. If [minSupportedCode] > [latestCode], it is a server misconfiguration: do NOT hard-block;
     *    treat minSupportedCode as [latestCode] (so at most Soft or None is returned).
     * 2. If [currentCode] < [minSupportedCode] -> HARD(URGENT).
     * 3. Else if [currentCode] < [latestCode] ->
     *      If [skipsUsed] < [maxSkips] -> SOFT(maxSkips - skipsUsed)
     *      Else -> HARD(SKIPS_EXHAUSTED)
     * 4. Else -> NONE.
     */
    fun evaluateUpdatePolicy(
        currentCode: Int,
        latestCode: Int,
        minSupportedCode: Int,
        skipsUsed: Int,
        maxSkips: Int
    ): UpdatePolicyDecision {
        // Safety guard: if minSupportedCode > latestCode, server misconfiguration exists
        val isMisconfigured = minSupportedCode > latestCode
        if (isMisconfigured) {
            try {
                Log.w(
                    TAG,
                    "Server misconfiguration detected: minSupportedCode ($minSupportedCode) > latestCode ($latestCode). Fallbacking to latestCode to avoid bricking app."
                )
            } catch (_: Throwable) {
                // In non-Robolectric plain JUnit tests, android.util.Log might not be initialized
            }
        }

        val effectiveMinSupportedCode = if (isMisconfigured) latestCode else minSupportedCode

        // 1. Below minimum supported version -> Hard URGENT
        // Note: if misconfigured, currentCode cannot be < effectiveMinSupportedCode without being < latestCode,
        // and because of misconfig rule we never treat as HARD(URGENT) when misconfigured.
        if (!isMisconfigured && currentCode < effectiveMinSupportedCode) {
            return UpdatePolicyDecision.Hard(HardUpdateReason.URGENT)
        }

        // 2. Below latest version
        if (currentCode < latestCode) {
            val safeMaxSkips = maxSkips.coerceAtLeast(0)
            val safeSkipsUsed = skipsUsed.coerceAtLeast(0)

            return if (safeSkipsUsed < safeMaxSkips) {
                UpdatePolicyDecision.Soft(skipsLeft = safeMaxSkips - safeSkipsUsed)
            } else {
                UpdatePolicyDecision.Hard(HardUpdateReason.SKIPS_EXHAUSTED)
            }
        }

        // 3. Current version is equal to or newer than latest
        return UpdatePolicyDecision.None
    }
}
