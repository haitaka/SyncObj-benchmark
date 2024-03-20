package org.jetbrains.impls

import platform.posix.pthread_yield_np
import kotlin.concurrent.AtomicLong
import kotlin.experimental.ExperimentalNativeApi

private val threadCounter = AtomicLong(0)

@kotlin.native.concurrent.ThreadLocal
private var threadId: UInt = threadCounter.addAndGet(1).toUInt()

class NoalLock {

    // TODO properly design FAT locks
    private enum class LockStatus { UNLOCKED, THIN }

    @OptIn(ExperimentalNativeApi::class)
    private value class LockWord private constructor(private val encoded: ULong) {
        companion object {
            private const val FAT_BIT_MASK = 1uL

            fun unlocked() = Thin(0u, 0u).lockWord

            fun fromLong(l: Long) = LockWord(l.toULong())
        }

        inline val fat: Boolean get() = encoded.and(FAT_BIT_MASK) != 0uL
        inline val thin: Boolean get() = !fat

        inline val status: LockStatus get() = if (asThin().isUnlocked()) LockStatus.UNLOCKED else LockStatus.THIN

        inline fun asThin() = Thin(this)

        inline fun toLong() = encoded.toLong()

        value class Thin internal constructor(val lockWord: LockWord) {
            init { assert(lockWord.thin) }

            companion object {
                // TODO outline some consts

                inline operator fun invoke(nested: UInt, ownerTid: UInt): Thin {
                    if (nested > UInt.MAX_VALUE.shr(1)) throw IllegalArgumentException() // TODO
                    val nestedPart = nested.shl(1).toULong()
                    val tidPart = ownerTid.toULong().shl(UInt.SIZE_BITS)
                    val result = Thin(LockWord(nestedPart.or(tidPart)))
                    assert(result.nested == nested)
                    assert(result.ownerTid == ownerTid)
                    return result
                }
            }

            inline val nested: UInt get() = lockWord.encoded.and(UInt.MAX_VALUE.toULong()).shr(1).toUInt()
            inline val ownerTid: UInt get() = lockWord.encoded.and(UInt.MAX_VALUE.toULong().inv()).shr(UInt.SIZE_BITS).toUInt()

            internal inline fun isUnlocked() = ownerTid == 0u
        }

    }

    // TODO introduce AtomicLockWord
    private val lockWord = AtomicLong(LockWord.unlocked().toLong())

    private inline fun loadLockState() = LockWord.fromLong(lockWord.value)

    private fun compareSetAndFreeLock(expected: LockWord, desired: LockWord): Boolean {
        return lockWord.compareAndSet(expected.toLong(), desired.toLong())
    }

    fun lock() {
        val currentThreadId = threadId
        while (true) {
            val state = loadLockState()
            when (state.status) {
                LockStatus.UNLOCKED -> {
                    val thinLock = LockWord.Thin(1u, currentThreadId)
                    if (compareSetAndFreeLock(state, thinLock.lockWord))
                        return
                }
                LockStatus.THIN -> {
                    val thinState = state.asThin()
                    if (currentThreadId == thinState.ownerTid) {
                        // reentrant lock
                        val thinNested = LockWord.Thin(thinState.nested + 1u, currentThreadId)
                        if (compareSetAndFreeLock(state, thinNested.lockWord))
                            return
                    } else {
                        // another thread holds the lock -> allocate native mutex
                        // TODO allocate native mutex
                        // or just spin
                        pthread_yield_np()
                    }
                }
            }
        }
    }

    fun unlock() {
        val currentThreadId = threadId
        while (true) {
            val state = loadLockState()
            when (state.status) {
                LockStatus.THIN -> {
                    val thinState = state.asThin()
                    require(currentThreadId == thinState.ownerTid) { "Thin lock may be only released by the owner thread, expected: ${thinState.ownerTid}, real: $currentThreadId" }
                    // nested unlock
                    if (thinState.nested == 1u) {
                        val unlocked = LockWord.unlocked()
                        if (compareSetAndFreeLock(state, unlocked))
                            return
                    } else {
                        val releasedNestedLock = LockWord.Thin(thinState.nested - 1u, thinState.ownerTid)
                        if (compareSetAndFreeLock(state, releasedNestedLock.lockWord))
                            return
                    }
                }
                else -> error("It is not possible to unlock the mutex that is not obtained")
            }
        }
    }
}

inline fun <T> synchronized(lock: NoalLock, block: () -> T): T {
    lock.lock()
    try {
        return block()
    } finally {
        lock.unlock()
    }
}
