package com.racedetector.folia

import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiType
import com.intellij.psi.util.InheritanceUtil
import com.intellij.uast.UastHintedVisitorAdapter
import com.racedetector.analysis.ThreadContext
import com.racedetector.quickfixes.WrapWithEntitySchedulerQuickFix
import com.racedetector.quickfixes.WrapWithRegionSchedulerQuickFix
import com.racedetector.threading.ThreadContextResolver
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

class FoliaCrossContextInspection : AbstractBaseUastLocalInspectionTool() {

    companion object {
        private val ENTITY_FQNS = setOf(
            "org.bukkit.entity.Entity",
            "org.bukkit.entity.LivingEntity",
            "org.bukkit.entity.HumanEntity",
            "org.bukkit.entity.Player"
        )

        private val BLOCK_FQNS = setOf(
            "org.bukkit.block.Block",
            "org.bukkit.Chunk",
            "org.bukkit.World"
        )

        private val EXCLUDED_METHODS = setOf("getScheduler")
    }

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (!FoliaProjectDetector.isFoliaProject(holder.project)) {
            return PsiElementVisitor.EMPTY_VISITOR
        }

        return UastHintedVisitorAdapter.create(
            holder.file.language,
            object : AbstractUastNonRecursiveVisitor() {
                override fun visitCallExpression(node: UCallExpression): Boolean {
                    checkCrossContextAccess(node, holder)
                    return true
                }
            },
            arrayOf(UCallExpression::class.java),
            true
        )
    }

    private fun checkCrossContextAccess(call: UCallExpression, holder: ProblemsHolder) {
        val methodName = call.methodName ?: return
        if (methodName in EXCLUDED_METHODS) return

        val receiverType = getReceiverType(call) ?: return

        val isEntity = isEntityType(receiverType)
        val isBlock = isBlockType(receiverType)
        if (!isEntity && !isBlock) return

        val sourcePsi = call.sourcePsi ?: return
        val context = ThreadContextResolver.resolve(sourcePsi)
        if (!isFoliaContext(context)) return

        val element = getRegistrationElement(call) ?: return
        val typeName = getSimpleTypeName(receiverType)

        if (isEntity) {
            reportEntityAccess(call, context, typeName, holder, element)
        } else {
            reportBlockAccess(context, typeName, holder, element)
        }
    }

    private fun reportEntityAccess(
        call: UCallExpression,
        context: ThreadContext,
        typeName: String,
        holder: ProblemsHolder,
        element: PsiElement
    ) {
        val receiverText = getReceiverText(call) ?: typeName.replaceFirstChar { it.lowercase() }

        when (context) {
            is ThreadContext.FoliaGlobalThread -> {
                holder.registerProblem(
                    element,
                    "Accessing $typeName API from global scheduler thread. Use $receiverText.getScheduler() instead.",
                    ProblemHighlightType.GENERIC_ERROR,
                    WrapWithEntitySchedulerQuickFix()
                )
            }
            is ThreadContext.FoliaAsyncThread -> {
                holder.registerProblem(
                    element,
                    "Accessing $typeName API from async thread. Schedule on entity's thread instead.",
                    ProblemHighlightType.GENERIC_ERROR,
                    WrapWithEntitySchedulerQuickFix()
                )
            }
            is ThreadContext.FoliaRegionThread -> {
                holder.registerProblem(
                    element,
                    "Accessing $typeName API from region scheduler. The entity may be in a different region.",
                    WrapWithEntitySchedulerQuickFix()
                )
            }
            is ThreadContext.FoliaEntityThread -> {
                if (isDifferentEntity(call, context)) {
                    holder.registerProblem(
                        element,
                        "Accessing $typeName API from another entity's scheduler thread.",
                        ProblemHighlightType.GENERIC_ERROR,
                        WrapWithEntitySchedulerQuickFix()
                    )
                }
            }
            else -> {}
        }
    }

    private fun reportBlockAccess(
        context: ThreadContext,
        typeName: String,
        holder: ProblemsHolder,
        element: PsiElement
    ) {
        val lowerType = typeName.replaceFirstChar { it.lowercase() }

        when (context) {
            is ThreadContext.FoliaEntityThread -> {
                holder.registerProblem(
                    element,
                    "Accessing $typeName API from entity scheduler. The $lowerType may be in a different region.",
                    WrapWithRegionSchedulerQuickFix()
                )
            }
            is ThreadContext.FoliaAsyncThread -> {
                holder.registerProblem(
                    element,
                    "Accessing $typeName API from async thread. Schedule on the region's thread instead.",
                    ProblemHighlightType.GENERIC_ERROR,
                    WrapWithRegionSchedulerQuickFix()
                )
            }
            is ThreadContext.FoliaGlobalThread -> {
                holder.registerProblem(
                    element,
                    "Accessing $typeName API from global scheduler thread. Schedule on the region's thread instead.",
                    ProblemHighlightType.GENERIC_ERROR,
                    WrapWithRegionSchedulerQuickFix()
                )
            }
            is ThreadContext.FoliaRegionThread -> {
                // Same region context — safe access
            }
            else -> {}
        }
    }

    private fun isFoliaContext(context: ThreadContext): Boolean {
        return context is ThreadContext.FoliaGlobalThread ||
                context is ThreadContext.FoliaAsyncThread ||
                context is ThreadContext.FoliaRegionThread ||
                context is ThreadContext.FoliaEntityThread
    }

    private fun getReceiverType(call: UCallExpression): PsiType? {
        call.receiver?.getExpressionType()?.let { return it }

        val parent = call.uastParent
        if (parent is UQualifiedReferenceExpression && parent.selector == call) {
            parent.receiver.getExpressionType()?.let { return it }
        }

        val psiCall = call.sourcePsi as? PsiMethodCallExpression
        psiCall?.methodExpression?.qualifierExpression?.type?.let { return it }

        return null
    }

    private fun getReceiverText(call: UCallExpression): String? {
        call.receiver?.sourcePsi?.text?.let { return it }
        val parent = call.uastParent
        if (parent is UQualifiedReferenceExpression && parent.selector == call) {
            return parent.receiver.sourcePsi?.text
        }
        val psiCall = call.sourcePsi as? PsiMethodCallExpression
        return psiCall?.methodExpression?.qualifierExpression?.text
    }

    private fun getRegistrationElement(call: UCallExpression): PsiElement? {
        val parent = call.uastParent
        if (parent is UQualifiedReferenceExpression && parent.selector == call) {
            parent.sourcePsi?.let { return it }
        }
        return call.sourcePsi
    }

    private fun isEntityType(type: PsiType): Boolean {
        if (type !is PsiClassType) return false
        val psiClass = type.resolve() ?: return false
        return ENTITY_FQNS.any { fqn ->
            psiClass.qualifiedName == fqn || InheritanceUtil.isInheritor(psiClass, fqn)
        }
    }

    private fun isBlockType(type: PsiType): Boolean {
        if (type !is PsiClassType) return false
        val psiClass = type.resolve() ?: return false
        return BLOCK_FQNS.any { fqn ->
            psiClass.qualifiedName == fqn || InheritanceUtil.isInheritor(psiClass, fqn)
        }
    }

    private fun isDifferentEntity(call: UCallExpression, context: ThreadContext.FoliaEntityThread): Boolean {
        val schedulerEntityText = context.entityExpression?.sourcePsi?.text ?: return false
        val receiverText = getReceiverText(call) ?: return false
        return receiverText != schedulerEntityText
    }

    private fun getSimpleTypeName(type: PsiType): String {
        if (type is PsiClassType) {
            type.resolve()?.name?.let { return it }
        }
        return type.presentableText
    }
}
