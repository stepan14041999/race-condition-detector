package com.racedetector.quickfixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiExpressionStatement
import com.intellij.psi.util.PsiTreeUtil

class WrapWithRegionSchedulerQuickFix : LocalQuickFix {

    override fun getName(): String = "Wrap with Bukkit.getRegionScheduler().run()"

    override fun getFamilyName(): String = name

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement
        val statement = PsiTreeUtil.getParentOfType(element, PsiExpressionStatement::class.java) ?: return

        val factory = JavaPsiFacade.getElementFactory(project)
        val wrapped = factory.createStatementFromText(
            "Bukkit.getRegionScheduler().run(plugin, location, task -> {\n    ${statement.text}\n});",
            statement.context
        )
        statement.replace(wrapped)
    }
}
