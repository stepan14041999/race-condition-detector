package com.racedetector.analysis

import org.jetbrains.uast.UExpression

sealed class ThreadContext {
    object MainThread : ThreadContext()
    data class WorkerThread(val description: String) : ThreadContext()
    object ScheduledThread : ThreadContext()
    object Unknown : ThreadContext()

    // Folia thread contexts
    object FoliaGlobalThread : ThreadContext()
    data class FoliaEntityThread(val entityExpression: UExpression?) : ThreadContext()
    data class FoliaRegionThread(val locationExpression: UExpression?) : ThreadContext()
    object FoliaAsyncThread : ThreadContext()

    // Multi-thread context: element is accessed from multiple distinct thread contexts
    data class MultiThread(val contexts: Set<ThreadContext>) : ThreadContext()
}
