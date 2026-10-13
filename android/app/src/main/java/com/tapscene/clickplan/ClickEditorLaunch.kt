package com.tapscene.clickplan

/** Attaching can fail on the platform. Never leave the page that can display that failure first. */
internal fun openClickEditor(attachOverlay: () -> Unit, launchTarget: () -> Unit) {
    attachOverlay()
    launchTarget()
}
