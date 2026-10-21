package com.tapscene.data

/** A targeted, verified deletion outcome. Read errors must be shown as unknown by the caller. */
enum class ReleaseDeletionState { PRESENT, REPLACED, DELETED, CLEANUP_PENDING }
