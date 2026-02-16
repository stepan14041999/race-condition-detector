package com.racedetector.analysis

sealed class SyncProtection {
    object None : SyncProtection()
    object Volatile : SyncProtection()
    object Final : SyncProtection()
    data class SynchronizedBlock(val monitor: String) : SyncProtection()
    object AtomicWrapper : SyncProtection()
    data class GuardedByAnnotation(val guard: String) : SyncProtection()
    object ThreadSafeAnnotation : SyncProtection()
}
