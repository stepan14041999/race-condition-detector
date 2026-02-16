package com.racedetector.quickfixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpressionStatement
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil

class WrapWithEntitySchedulerQuickFix : LocalQuickFix {

    override fun getName(): String = "Wrap with entity.getScheduler().run()"

    override fun getFamilyName(): String = name

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement
        val statement = PsiTreeUtil.getParentOfType(element, PsiExpressionStatement::class.java) ?: return
        val receiverName = extractReceiverName(element) ?: "entity"

        val factory = JavaPsiFacade.getElementFactory(project)
        val wrapped = factory.createStatementFromText(
            "$receiverName.getScheduler().run(plugin, task -> {\n    ${statement.text}\n}, null);",
            statement.context
        )
        statement.replace(wrapped)
    }

    private fun extractReceiverName(element: PsiElement): String? {
        val methodCall = element as? PsiMethodCallExpression
            ?: PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression::class.java)
        return methodCall?.methodExpression?.qualifierExpression?.text
    }
}
