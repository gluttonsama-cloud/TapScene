package com.tapscene.recording

import org.junit.Assert.*
import org.junit.Test

class FrameLeasePoolTest {
    @Test fun pinnedFramesRemainImmutableWhileOtherSlotsCycle() {
        val pool = FrameLeasePool<String>()
        fun publish(value: String) { pool.publish(checkNotNull(pool.claim()), value) }
        publish("before")
        val first = checkNotNull(pool.pinLatest())
        publish("after")
        val second = checkNotNull(pool.pinLatest())
        repeat(30) { publish("later-$it") }
        assertEquals("before", first.frame); assertEquals("after", second.frame)
        assertTrue(pool.isPinned(first)); assertTrue(pool.isPinned(second))
        pool.release(first); pool.release(second)
    }
    @Test fun drawingAndFailedPublicationNeverBecomeLatest() {
        val pool = FrameLeasePool<String>(1)
        val initial = checkNotNull(pool.claim())
        assertNull(pool.pinLatest())
        pool.publish(initial, "first")
        val next = checkNotNull(pool.claim())
        assertNull(pool.pinLatest())
        pool.abandon(next)
        assertNull(pool.pinLatest())
        assertThrows(IllegalStateException::class.java) { pool.publish(next, "late") }
    }
    @Test fun exhaustionAndStaleReleaseCannotFreeReusedSlot() {
        val pool = FrameLeasePool<String>(1)
        pool.publish(checkNotNull(pool.claim()), "first")
        val first = checkNotNull(pool.pinLatest())
        assertNull(pool.claim())
        pool.release(first)
        pool.publish(checkNotNull(pool.claim()), "second")
        val second = checkNotNull(pool.pinLatest())
        assertThrows(IllegalStateException::class.java) { pool.release(first) }
        assertTrue(pool.isPinned(second)); assertNull(pool.claim())
        pool.release(second)
        assertThrows(IllegalStateException::class.java) { pool.release(second) }
    }
}
