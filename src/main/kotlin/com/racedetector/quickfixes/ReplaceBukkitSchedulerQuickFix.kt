package com.racedetector.quickfixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil

class ReplaceBukkitSchedulerQuickFix private constructor(
    private val newSchedulerCall: String,
    private val newMethodName: String,
    private val fixDescription: String
) : LocalQuickFix {

    companion object {
        fun forMethod(methodName: String): ReplaceBukkitSchedulerQuickFix? = when (methodName) {
            "runTask" -> ReplaceBukkitSchedulerQuickFix(
                "Bukkit.getGlobalRegionScheduler()", "run",
                "Replace with Bukkit.getGlobalRegionScheduler().run()"
            )
            "runTaskLater" -> ReplaceBukkitSchedulerQuickFix(
                "Bukkit.getGlobalRegionScheduler()", "runDelayed",
                "Replace with Bukkit.getGlobalRegionScheduler().runDelayed()"
            )
            "runTaskAsynchronously" -> ReplaceBukkitSchedulerQuickFix(
                "Bukkit.getAsyncScheduler()", "runNow",
                "Replace with Bukkit.getAsyncScheduler().runNow()"
            )
            "runTaskTimer" -> ReplaceBukkitSchedulerQuickFix(
                "Bukkit.getGlobalRegionScheduler()", "runAtFixedRate",
                "Replace with Bukkit.getGlobalRegionScheduler().runAtFixedRate()"
            )
            else -> null
        }
    }

    override fun getName(): String = fixDescription

    override fun getFamilyName(): String = "Replace deprecated BukkitScheduler call"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement
        val methodCall = element as? PsiMethodCallExpression
            ?: PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression::class.java)
            ?: return

        val args = methodCall.argumentList.text
        val factory = JavaPsiFacade.getElementFactory(project)
        val newExpression = factory.createExpressionFromText(
            "$newSchedulerCall.$newMethodName$args",
            methodCall.context
        )
        methodCall.replace(newExpression)
    }
}
