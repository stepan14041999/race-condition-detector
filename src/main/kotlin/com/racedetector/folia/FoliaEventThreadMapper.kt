package com.racedetector.folia

import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.InheritanceUtil
import com.racedetector.analysis.ThreadContext

/**
 * Maps Bukkit @EventHandler methods to Folia thread contexts based on event type hierarchy.
 *
 * In Folia's regionized threading model, event handlers run on different threads
 * depending on the event type:
 * - PlayerEvent / InventoryEvent → entity's owning thread (FoliaEntityThread)
 * - BlockEvent / WorldEvent → region thread for that location (FoliaRegionThread)
 * - ServerEvent → global region thread (FoliaGlobalThread)
 */
object FoliaEventThreadMapper {

    private val EVENT_THREAD_MAPPING = listOf(
        "org.bukkit.event.player.PlayerEvent" to ThreadContextKind.ENTITY,
        "org.bukkit.event.block.BlockEvent" to ThreadContextKind.REGION,
        "org.bukkit.event.inventory.InventoryEvent" to ThreadContextKind.ENTITY,
        "org.bukkit.event.server.ServerEvent" to ThreadContextKind.GLOBAL,
        "org.bukkit.event.world.WorldEvent" to ThreadContextKind.REGION
    )

    private enum class ThreadContextKind {
        ENTITY, REGION, GLOBAL
    }

    /**
     * Resolves the Folia thread context for a method annotated with @EventHandler.
     *
     * @return the thread context based on the event parameter type,
     *         [ThreadContext.Unknown] if the event type is not recognized,
     *         or `null` if the method is not an @EventHandler.
     */
    fun resolveEventHandlerContext(method: PsiMethod): ThreadContext? {
        if (!hasEventHandlerAnnotation(method)) return null

        val firstParam = method.parameterList.parameters.firstOrNull() ?: return ThreadContext.Unknown
        val paramType = firstParam.type as? PsiClassType ?: return ThreadContext.Unknown
        val paramClass = paramType.resolve() ?: return ThreadContext.Unknown

        for ((eventFqn, kind) in EVENT_THREAD_MAPPING) {
            if (paramClass.qualifiedName == eventFqn || InheritanceUtil.isInheritor(paramClass, eventFqn)) {
                return when (kind) {
                    ThreadContextKind.ENTITY -> ThreadContext.FoliaEntityThread(null)
                    ThreadContextKind.REGION -> ThreadContext.FoliaRegionThread(null)
                    ThreadContextKind.GLOBAL -> ThreadContext.FoliaGlobalThread
                }
            }
        }

        return ThreadContext.Unknown
    }

    private fun hasEventHandlerAnnotation(method: PsiMethod): Boolean {
        return method.annotations.any { annotation ->
            val qName = annotation.qualifiedName
            qName == "org.bukkit.event.EventHandler" ||
                (qName == null && annotation.nameReferenceElement?.referenceName == "EventHandler")
        }
    }
}
