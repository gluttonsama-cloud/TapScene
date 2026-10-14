package com.tapscene.clickplan

import org.junit.Assert.*
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
    @Test fun preparationUsesTheOriginalInclusiveDeadlineAndStableConfirmationIdentity() {
        val arm = ClickTargetArm(100)
        val authorization = requireNotNull(arm.authorizationAt(100))
        assertEquals(100L, authorization.confirmedAtUptimeMs)
        assertEquals(15_100L, authorization.deadlineUptimeMs)
        assertNull(arm.authorizationAt(99))
        assertEquals(ClickArmDecision.TargetReady, arm.observe(5_000, 5_000, false, true))
        assertSame(authorization, arm.authorizationAt(15_100))
        assertNull(arm.authorizationAt(15_101))
        val renewed = requireNotNull(ClickTargetArm(15_101).authorizationAt(15_101))
        assertNotEquals(authorization.confirmationId, renewed.confirmationId)
        assertEquals(30_101L, renewed.deadlineUptimeMs)
    }

    @Test fun unknownWindowPermanentlyRevokesTheSameArmEvenBeforeItsDeadline() {
        val arm = ClickTargetArm(100)
        assertNotNull(arm.authorizationAt(101))
        assertEquals(ClickArmDecision.Invalidated, arm.observe(101, 101, false, false))
        assertNull(arm.authorizationAt(102))
    }
}
