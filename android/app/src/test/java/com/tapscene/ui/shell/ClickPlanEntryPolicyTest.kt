package com.tapscene.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class ClickPlanEntryPolicyTest {
    @Test fun nextActionExplainsPrerequisitesWithoutStartingPermissionsOrPlayback() {
        val initial = ClickPlanUiState()
        assertEquals(ClickPlanPrimaryAction.ChooseTarget, clickPlanPrimaryAction(initial))
        val chosen = initial.copy(targetPackage = "com.example.demo", targetLabel = "Example")
        assertEquals(ClickPlanPrimaryAction.EnableService, clickPlanPrimaryAction(chosen))
        assertEquals(ClickPlanPrimaryAction.OpenOverlay, clickPlanPrimaryAction(chosen.copy(connected = true, canLocate = true)))
        assertEquals(ClickPlanPrimaryAction.Unsupported, clickPlanPrimaryAction(chosen.copy(overlaySupported = false)))
        assertEquals(ClickPlanPrimaryAction.Wait, clickPlanPrimaryAction(chosen.copy(loading = true)))
        assertEquals(ClickPlanPrimaryAction.Wait, clickPlanPrimaryAction(chosen.copy(saving = true)))
        assertEquals(ClickPlanPrimaryAction.Blocked, clickPlanPrimaryAction(chosen.copy(sessionActive = true)))
        assertEquals(ClickPlanPrimaryAction.Blocked, clickPlanPrimaryAction(chosen.copy(editingEnabled = false)))
        assertEquals(ClickPlanPrimaryAction.Blocked, clickPlanPrimaryAction(chosen.copy(connected = true, geometryMatches = false)))
        assertEquals(ClickPlanPrimaryAction.ChooseTarget, clickPlanPrimaryAction(chosen.copy(connected = true, targetLabel = null)))
    }
}
