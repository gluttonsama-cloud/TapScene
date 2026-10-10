package com.tapscene.recording

/** Small in-memory ownership pool; callers must perform all driver work outside [lock]. */
internal class FrameLeasePool<T : Any>(size: Int = 3) {
    data class Write(val index: Int, val generation: Long)
    data class Read<T>(val index: Int, val generation: Long, val leaseId: Long, val frame: T)
    private class Slot<T>(var generation: Long = 0, var writing: Boolean = false, var pins: Int = 0, var frame: T? = null)
    val lock = Any()
    private val slots = List(size) { Slot<T>() }
    private val reads = mutableMapOf<Long, Pair<Int, Long>>()
    private var nextReadId = 0L
    private var latest: Int? = null
    init { require(size in 1..3) }

    fun claim(): Write? = synchronized(lock) {
        val index = slots.indices.firstOrNull { it != latest && !slots[it].writing && slots[it].pins == 0 }
            ?: slots.indices.firstOrNull { !slots[it].writing && slots[it].pins == 0 }
            ?: return@synchronized null
        val slot = slots[index]
        slot.generation++; slot.writing = true; slot.frame = null
        if (latest == index) latest = null
        Write(index, slot.generation)
    }
    fun publish(write: Write, frame: T) = synchronized(lock) {
        val slot = requireWrite(write)
        slot.frame = frame; slot.writing = false; latest = write.index
    }
    fun abandon(write: Write) = synchronized(lock) {
        val slot = requireWrite(write)
        slot.frame = null; slot.writing = false
    }
    fun pinLatest(): Read<T>? = synchronized(lock) {
        val index = latest ?: return@synchronized null
        val slot = slots[index]
        val frame = slot.frame ?: return@synchronized null
        check(!slot.writing)
        val id = ++nextReadId
        slot.pins++
        reads[id] = index to slot.generation
        Read(index, slot.generation, id, frame)
    }
    fun isPinned(read: Read<T>): Boolean = synchronized(lock) {
        reads[read.leaseId] == (read.index to read.generation) && read.index in slots.indices &&
            slots[read.index].generation == read.generation && !slots[read.index].writing
    }
    fun release(read: Read<T>) = synchronized(lock) {
        check(isPinned(read)) { "Stale or already released frame lease" }
        check(slots[read.index].pins > 0)
        reads.remove(read.leaseId); slots[read.index].pins--
    }
    private fun requireWrite(write: Write): Slot<T> {
        check(write.index in slots.indices)
        return slots[write.index].also { check(it.writing && it.generation == write.generation && it.pins == 0) }
    }
}
