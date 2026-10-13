package com.tapscene.clickplan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ClickEditorLaunchTest {
    @Test fun attachmentFailureNeverOpensTheTargetAndSuccessAttachesFirst() {
        val calls = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            openClickEditor({ calls += "attach"; error("platform rejected overlay") }, { calls += "target" })
        }
        assertEquals(listOf("attach"), calls)
        calls.clear()
        openClickEditor({ calls += "attach" }, { calls += "target" })
        assertEquals(listOf("attach", "target"), calls)
        calls.clear()
        assertThrows(IllegalStateException::class.java) {
            openClickEditor({ calls += "attach" }, { calls += "target"; error("target unavailable") })
        }
        assertEquals(listOf("attach", "target"), calls)
    }
}
