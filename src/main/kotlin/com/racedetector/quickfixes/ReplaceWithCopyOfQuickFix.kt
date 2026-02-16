package com.racedetector.quickfixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiExpression
import com.racedetector.inspections.MutableStatePublicationInspection.CollectionKind

class ReplaceWithCopyOfQuickFix(
    private val typeName: String
) : LocalQuickFix {

    override fun getFamilyName(): String = "Replace with *.copyOf()"

    override fun getName(): String = "Replace with $typeName.copyOf()"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val expr = descriptor.psiElement as? PsiExpression ?: return
        val factory = JavaPsiFacade.getElementFactory(project)
        val newExpr = factory.createExpressionFromText(
            "$typeName.copyOf(${expr.text})", expr
        )
        expr.replace(newExpr)
    }

    companion object {
        fun forKind(kind: CollectionKind): ReplaceWithCopyOfQuickFix? {
            val type = when (kind) {
                CollectionKind.LIST -> "List"
                CollectionKind.SET -> "Set"
                CollectionKind.MAP -> "Map"
                CollectionKind.COLLECTION -> return null
                CollectionKind.ARRAY -> return null
            }
            return ReplaceWithCopyOfQuickFix(type)
        }
    }
}
