package com.tapscene.clickplan

import org.junit.Assert.assertEquals
import org.junit.Test

class ClickWindowPolicyTest {
    @Test fun onlyKnownFullscreenTargetActivityIsAllowedAndUnknownNeverMeansSafe() {
        fun classify(pkg: String? = "com.example.demo", cls: String? = "com.example.demo.MainActivity", full: Boolean = true, known: Boolean = true) =
            ClickWindowPolicy.classify("com.example.demo", pkg, cls, full, known)
        assertEquals(ClickGuard.Allowed, classify())
        assertEquals(ClickGuard.Unknown, classify(pkg = null))
        assertEquals(ClickGuard.Unknown, classify(cls = null))
        assertEquals(ClickGuard.Unknown, classify(full = false))
        assertEquals(ClickGuard.Unknown, classify(known = false))
        assertEquals(ClickGuard.Blocked, classify(pkg = "com.android.permissioncontroller"))
        assertEquals(ClickGuard.Blocked, classify(pkg = "com.example.other"))
        assertEquals(ClickGuard.Blocked, classify(cls = "com.example.demo.PaymentActivity"))
        assertEquals(ClickGuard.Blocked, classify(cls = "com.example.demo.BiometricActivity"))
        for (tapScene in listOf("com.tapscene", "com.tapscene.preview", "com.tapscene.preview.r33", "com.tapscene.preview.hevc", "com.tapscene.hosted.dev")) {
            check(ClickWindowPolicy.protectedPackage(tapScene))
            assertEquals(ClickGuard.Blocked, ClickWindowPolicy.classify(tapScene, tapScene,
                "com.tapscene.MainActivity", true, true))
        }
        // A similarly named unrelated package is not accidentally blocked.
        check(!ClickWindowPolicy.protectedPackage("com.tapsceneexample.demo"))
        check(!ClickWindowPolicy.protectedPackage("com.tapscene.runtime.target"))
        // A generic same-app activity can still contain sensitive content: no content safety claim.
        assertEquals(ClickGuard.Allowed, classify(cls = "com.example.demo.GenericActivity"))
    }
}
