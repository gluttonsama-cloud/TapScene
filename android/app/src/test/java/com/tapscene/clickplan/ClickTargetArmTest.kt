package com.tapscene.clickplan

import org.junit.Assert.assertEquals
import org.junit.Test

class ClickTargetArmTest {
    @Test fun confirmationOnlySurvivesItsOwnExitAndTheImmediateTargetTransition() {
        val arm = ClickTargetArm(100)
        assertEquals(ClickArmDecision.IgnoreOld, arm.observe(99, 100, false, false))
        assertEquals(ClickArmDecision.AwaitTarget, arm.observe(101, 101, true, false))
        assertEquals(ClickArmDecision.TargetReady, arm.observe(102, 102, false, true))
        assertEquals(ClickArmDecision.Invalidated, arm.observe(103, 103, false, false))
        assertEquals(ClickArmDecision.Invalidated, arm.observe(104, 104, false, true))
        val expired = ClickTargetArm(100)
        assertEquals(ClickArmDecision.Invalidated, expired.observe(15_101, 15_101, false, true))
        val unknownBeforeTarget = ClickTargetArm(100)
        assertEquals(ClickArmDecision.Invalidated, unknownBeforeTarget.observe(101, 101, false, false))
        assertEquals(ClickArmDecision.Invalidated, unknownBeforeTarget.observe(102, 102, false, true))
    }
}
