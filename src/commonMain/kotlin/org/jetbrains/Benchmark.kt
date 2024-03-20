package org.jetbrains

import kotlinx.atomicfu.locks.*
import kotlinx.benchmark.*
import org.jetbrains.impls.*

@State(Scope.Benchmark)
class AtomicFUBenchmark {
    val lock = SynchronizedObject()

    @Benchmark
    fun benchmarkUncontended(blackhole: Blackhole) {
        synchronized(lock) {
            blackhole.consume(42)
        }
    }
}

@State(Scope.Benchmark)
class NoalLockBenchmark {
    val lock = NoalLock()

    @Benchmark
    fun benchmarkUncontended(blackhole: Blackhole) {
        synchronized(lock) {
            blackhole.consume(42)
        }
    }
}

//@State(Scope.Benchmark)
//class ThinOrSpinBenchmark {
//    val lock = ThinOrSpin()
//
//    @Benchmark
//    fun benchmarkUncontended(blackhole: Blackhole) {
//        synchronized(lock) {
//            blackhole.consume(42)
//        }
//    }
//}
