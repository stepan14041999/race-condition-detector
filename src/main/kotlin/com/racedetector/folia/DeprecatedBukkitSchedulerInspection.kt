package com.racedetector.folia

import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.InheritanceUtil
import com.intellij.uast.UastHintedVisitorAdapter
import com.racedetector.quickfixes.ReplaceBukkitSchedulerQuickFix
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

class DeprecatedBukkitSchedulerInspection : AbstractBaseUastLocalInspectionTool() {

    companion object {
        private const val BUKKIT_SCHEDULER_FQN = "org.bukkit.scheduler.BukkitScheduler"

        private val REPLACEMENT_MAP = mapOf(
            "runTask" to "Bukkit.getGlobalRegionScheduler().run()",
            "runTaskLater" to "Bukkit.getGlobalRegionScheduler().runDelayed()",
            "runTaskAsynchronously" to "Bukkit.getAsyncScheduler().runNow()",
            "runTaskTimer" to "Bukkit.getGlobalRegionScheduler().runAtFixedRate()"
        )
    }

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (!FoliaProjectDetector.isFoliaProject(holder.project)) {
            return PsiElementVisitor.EMPTY_VISITOR
        }

        return UastHintedVisitorAdapter.create(
            holder.file.language,
            object : AbstractUastNonRecursiveVisitor() {
                override fun visitCallExpression(node: UCallExpression): Boolean {
                    checkSchedulerCall(node, holder)
                    return true
                }
            },
            arrayOf(UCallExpression::class.java),
            true
        )
    }

    private fun checkSchedulerCall(call: UCallExpression, holder: ProblemsHolder) {
        if (!isBukkitSchedulerCall(call)) return

        val methodName = call.methodName ?: return
        val element = getRegistrationElement(call) ?: return

        val replacement = REPLACEMENT_MAP[methodName]
        val message = if (replacement != null) {
            "BukkitScheduler.$methodName() is not supported in Folia. Use $replacement instead."
        } else {
            "BukkitScheduler.$methodName() is not supported in Folia and throws UnsupportedOperationException."
        }

        val quickFix = ReplaceBukkitSchedulerQuickFix.forMethod(methodName)
        if (quickFix != null) {
            holder.registerProblem(element, message, ProblemHighlightType.GENERIC_ERROR, quickFix)
        } else {
            holder.registerProblem(element, message, ProblemHighlightType.GENERIC_ERROR)
        }
    }

    private fun isBukkitSchedulerCall(call: UCallExpression): Boolean {
        val method = call.resolve() as? PsiMethod ?: return false
        val containingClass = method.containingClass ?: return false
        return containingClass.qualifiedName == BUKKIT_SCHEDULER_FQN ||
            InheritanceUtil.isInheritor(containingClass, BUKKIT_SCHEDULER_FQN)
    }

    private fun getRegistrationElement(call: UCallExpression): PsiElement? {
        val parent = call.uastParent
        if (parent is UQualifiedReferenceExpression && parent.selector == call) {
            parent.sourcePsi?.let { return it }
        }
        return call.sourcePsi
    }
}
