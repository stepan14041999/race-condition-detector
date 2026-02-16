package com.racedetector.quickfixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.racedetector.sync.SynchronizationChecker

class WrapWithSynchronizedQuickFix : LocalQuickFix {

    override fun getFamilyName(): String = NAME

    override fun getName(): String = NAME

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val field = descriptor.psiElement.parent as? PsiField ?: return
        val containingClass = field.containingClass ?: return
        val factory = JavaPsiFacade.getElementFactory(project)

        val references = mutableListOf<PsiReferenceExpression>()
        containingClass.accept(object : JavaRecursiveElementVisitor() {
            override fun visitReferenceExpression(expression: PsiReferenceExpression) {
                super.visitReferenceExpression(expression)
                if (expression.resolve() == field) {
                    references.add(expression)
                }
            }
        })

        val statementsToWrap = mutableSetOf<PsiStatement>()
        for (ref in references) {
            if (SynchronizationChecker.isAccessSynchronized(ref)) continue
            val statement = findStatementInCodeBlock(ref) ?: continue
            statementsToWrap.add(statement)
        }

        for (stmt in statementsToWrap.sortedByDescending { it.textOffset }) {
            val syncText = "synchronized (this) {\n${stmt.text}\n}"
            val syncStmt = factory.createStatementFromText(syncText, stmt)
            stmt.replace(syncStmt)
        }
    }

    private fun findStatementInCodeBlock(element: PsiElement): PsiStatement? {
        var current: PsiElement? = element
        while (current != null) {
            if (current is PsiStatement && current.parent is PsiCodeBlock) {
                return current
            }
            if (current is PsiField || current is PsiClass) return null
            current = current.parent
        }
        return null
    }

    companion object {
        const val NAME = "Wrap accesses in synchronized block"
    }
}
