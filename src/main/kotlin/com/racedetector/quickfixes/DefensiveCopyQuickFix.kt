package com.racedetector.quickfixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiExpression
import com.racedetector.inspections.MutableStatePublicationInspection.CollectionKind

class DefensiveCopyQuickFix(
    private val copyTemplate: String,
    private val displayName: String
) : LocalQuickFix {

    override fun getFamilyName(): String = "Create defensive copy"

    override fun getName(): String = displayName

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val expr = descriptor.psiElement as? PsiExpression ?: return
        val factory = JavaPsiFacade.getElementFactory(project)
        val newExpr = factory.createExpressionFromText(
            copyTemplate.replace("{field}", expr.text), expr
        )
        expr.replace(newExpr)
    }

    companion object {
        fun forKind(kind: CollectionKind): DefensiveCopyQuickFix? {
            return when (kind) {
                CollectionKind.LIST -> DefensiveCopyQuickFix(
                    "new java.util.ArrayList<>({field})",
                    "Create defensive copy with new ArrayList<>()"
                )
                CollectionKind.SET -> DefensiveCopyQuickFix(
                    "new java.util.HashSet<>({field})",
                    "Create defensive copy with new HashSet<>()"
                )
                CollectionKind.MAP -> DefensiveCopyQuickFix(
                    "new java.util.HashMap<>({field})",
                    "Create defensive copy with new HashMap<>()"
                )
                CollectionKind.COLLECTION -> DefensiveCopyQuickFix(
                    "new java.util.ArrayList<>({field})",
                    "Create defensive copy with new ArrayList<>()"
                )
                CollectionKind.ARRAY -> DefensiveCopyQuickFix(
                    "{field}.clone()",
                    "Create defensive copy with clone()"
                )
            }
        }
    }
}
