package org.jetbrains.impls

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import org.jetbrains.impls.ThinOrSpin.Status.*
import platform.posix.pthread_self
import platform.posix.pthread_t
import platform.posix.pthread_yield_np
import kotlin.concurrent.AtomicReference

@OptIn(ExperimentalForeignApi::class)
class ThinOrSpin {
    private val lock = AtomicReference(LockState(UNLOCKED, 0, 0))

    fun lock() {
        val currentThreadId = pthread_self()!!
        while (true) {
            val state = lock.value
            when (state.status) {
                UNLOCKED -> {
                    val thinLock = LockState(THIN, 1, 0, currentThreadId)
                    if (lock.compareAndSet(state, thinLock))
                        return
                }
                THIN -> {
                    if (currentThreadId == state.ownerThreadId) {
                        // reentrant lock
                        val thinNested = LockState(THIN, state.nestedLocks + 1, state.waiters, currentThreadId)
                        if (lock.compareAndSet(state, thinNested))
                            return
                    } else {
                        pthread_yield_np()
                    }
                }
            }
        }
    }

    fun unlock() {
        val currentThreadId = pthread_self()!!
        while (true) {
            val state = lock.value
            require(currentThreadId == state.ownerThreadId) { "Thin lock may be only released by the owner thread, expected: ${state.ownerThreadId}, real: $currentThreadId" }
            when (state.status) {
                THIN -> {
                    // nested unlock
                    if (state.nestedLocks == 1) {
                        val unlocked = LockState(UNLOCKED, 0, 0)
                        if (lock.compareAndSet(state, unlocked))
                            return
                    } else {
                        val releasedNestedLock =
                            LockState(THIN, state.nestedLocks - 1, state.waiters, state.ownerThreadId)
                        if (lock.compareAndSet(state, releasedNestedLock))
                            return
                    }
                }
                else -> error("It is not possible to unlock the mutex that is not obtained")
            }
        }
    }

    private class LockState(
        val status: Status,
        val nestedLocks: Int,
        val waiters: Int,
        val ownerThreadId: pthread_t? = null,
    )

    private enum class Status { UNLOCKED, THIN }
}

public inline fun <T> synchronized(lock: ThinOrSpin, block: () -> T): T {
    lock.lock()
    try {
        return block()
    } finally {
        lock.unlock()
    }
}
