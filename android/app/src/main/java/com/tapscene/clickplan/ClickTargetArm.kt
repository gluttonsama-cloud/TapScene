package com.tapscene.clickplan

internal enum class ClickArmDecision { IgnoreOld, AwaitTarget, TargetReady, Invalidated }

/** One explicit confirmation covers only the immediate confirmation-Activity -> target transition. */
internal class ClickTargetArm(private val confirmedAtMs: Long, val lifetimeMs: Long = 15_000) {
    private var targetSeen = false
    private var invalidated = false
    fun expired(nowMs: Long): Boolean = nowMs < confirmedAtMs || nowMs - confirmedAtMs > lifetimeMs
    fun observe(eventAtMs: Long, nowMs: Long, confirmationWindow: Boolean, targetAllowed: Boolean): ClickArmDecision {
        if (eventAtMs < confirmedAtMs) return ClickArmDecision.IgnoreOld
        if (invalidated || expired(nowMs)) { invalidated = true; return ClickArmDecision.Invalidated }
        if (confirmationWindow && !targetSeen) return ClickArmDecision.AwaitTarget
        if (targetAllowed) { targetSeen = true; return ClickArmDecision.TargetReady }
        invalidated = true
        return ClickArmDecision.Invalidated
    }
}
