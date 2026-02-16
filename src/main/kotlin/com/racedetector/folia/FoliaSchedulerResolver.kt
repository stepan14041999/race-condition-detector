package com.racedetector.folia

import com.racedetector.analysis.ThreadContext
import org.jetbrains.uast.*

/**
 * Resolves Folia scheduler API calls to appropriate [ThreadContext].
 *
 * Detects lambdas/callbacks passed to:
 * - GlobalRegionScheduler.run/runDelayed/runAtFixedRate → FoliaGlobalThread
 * - RegionScheduler.run/runDelayed → FoliaRegionThread(location)
 * - AsyncScheduler.runNow/runDelayed/runAtFixedRate → FoliaAsyncThread
 * - EntityScheduler.run/runDelayed → FoliaEntityThread(entity)
 */
object FoliaSchedulerResolver {

    private val SCHEDULER_CLASSES = mapOf(
        "io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler" to SchedulerType.GLOBAL,
        "io.papermc.paper.threadedregions.scheduler.RegionScheduler" to SchedulerType.REGION,
        "io.papermc.paper.threadedregions.scheduler.AsyncScheduler" to SchedulerType.ASYNC,
        "io.papermc.paper.threadedregions.scheduler.EntityScheduler" to SchedulerType.ENTITY
    )

    private val SCHEDULER_GETTER_NAMES = mapOf(
        "getGlobalRegionScheduler" to SchedulerType.GLOBAL,
        "getRegionScheduler" to SchedulerType.REGION,
        "getAsyncScheduler" to SchedulerType.ASYNC,
        "getScheduler" to SchedulerType.ENTITY
    )

    private val GLOBAL_METHODS = setOf("run", "runDelayed", "runAtFixedRate")
    private val REGION_METHODS = setOf("run", "runDelayed")
    private val ASYNC_METHODS = setOf("runNow", "runDelayed", "runAtFixedRate")
    private val ENTITY_METHODS = setOf("run", "runDelayed")

    private enum class SchedulerType {
        GLOBAL, REGION, ASYNC, ENTITY
    }

    fun resolve(call: UCallExpression): ThreadContext? {
        val methodName = call.methodName ?: return null
        val schedulerType = resolveSchedulerType(call) ?: return null

        return when (schedulerType) {
            SchedulerType.GLOBAL -> {
                if (methodName in GLOBAL_METHODS) ThreadContext.FoliaGlobalThread else null
            }
            SchedulerType.REGION -> {
                if (methodName in REGION_METHODS) {
                    val locationArg = findLocationArgument(call)
                    ThreadContext.FoliaRegionThread(locationArg)
                } else null
            }
            SchedulerType.ASYNC -> {
                if (methodName in ASYNC_METHODS) ThreadContext.FoliaAsyncThread else null
            }
            SchedulerType.ENTITY -> {
                if (methodName in ENTITY_METHODS) {
                    val entityExpr = findEntityExpression(call)
                    ThreadContext.FoliaEntityThread(entityExpr)
                } else null
            }
        }
    }

    private fun resolveSchedulerType(call: UCallExpression): SchedulerType? {
        // Strategy 1: resolve method and check its containing class
        val containingClassName = call.resolve()?.containingClass?.qualifiedName
        if (containingClassName != null) {
            SCHEDULER_CLASSES[containingClassName]?.let { return it }
        }

        // Strategy 2: check receiver expression for scheduler getter method name
        val receiver = getReceiver(call)
        val receiverMethodName = extractMethodName(receiver)
        if (receiverMethodName != null) {
            SCHEDULER_GETTER_NAMES[receiverMethodName]?.let { return it }
        }

        return null
    }

    private fun getReceiver(call: UCallExpression): UExpression? {
        call.receiver?.let { return it }
        val parent = call.uastParent
        if (parent is UQualifiedReferenceExpression && parent.selector == call) {
            return parent.receiver
        }
        return null
    }

    private fun extractMethodName(expr: UExpression?): String? {
        if (expr is UCallExpression) return expr.methodName
        if (expr is UQualifiedReferenceExpression) {
            val selector = expr.selector
            if (selector is UCallExpression) return selector.methodName
        }
        return null
    }

    /**
     * RegionScheduler.run(plugin, location, task) — location is the 2nd argument (index 1).
     */
    private fun findLocationArgument(call: UCallExpression): UExpression? {
        val args = call.valueArguments
        return if (args.size >= 2) args[1] else null
    }

    /**
     * entity.getScheduler().run(...) — extracts the `entity` expression
     * from the receiver chain.
     */
    private fun findEntityExpression(call: UCallExpression): UExpression? {
        val receiver = getReceiver(call) ?: return null

        // receiver is a direct call: getScheduler()
        if (receiver is UCallExpression && receiver.methodName == "getScheduler") {
            return getReceiver(receiver)
        }
        // receiver is a qualified expression: entity.getScheduler()
        if (receiver is UQualifiedReferenceExpression) {
            val selector = receiver.selector
            if (selector is UCallExpression && selector.methodName == "getScheduler") {
                return receiver.receiver
            }
        }
        return null
    }
}
