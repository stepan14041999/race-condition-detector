package com.racedetector.quickfixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiExpression
import com.racedetector.inspections.MutableStatePublicationInspection.CollectionKind

class WrapWithUnmodifiableQuickFix(
    private val wrapperMethod: String
) : LocalQuickFix {

    override fun getFamilyName(): String = "Wrap with Collections.unmodifiable*()"

    override fun getName(): String = "Wrap with Collections.$wrapperMethod()"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val expr = descriptor.psiElement as? PsiExpression ?: return
        val factory = JavaPsiFacade.getElementFactory(project)
        val newExpr = factory.createExpressionFromText(
            "java.util.Collections.$wrapperMethod(${expr.text})", expr
        )
        expr.replace(newExpr)
    }

    companion object {
        fun forKind(kind: CollectionKind): WrapWithUnmodifiableQuickFix? {
            val method = when (kind) {
                CollectionKind.LIST -> "unmodifiableList"
                CollectionKind.SET -> "unmodifiableSet"
                CollectionKind.MAP -> "unmodifiableMap"
                CollectionKind.COLLECTION -> "unmodifiableCollection"
                CollectionKind.ARRAY -> return null
            }
            return WrapWithUnmodifiableQuickFix(method)
        }
    }
}
