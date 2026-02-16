package com.racedetector.quickfixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiField
import com.intellij.psi.PsiModifier

class AddVolatileQuickFix : LocalQuickFix {

    override fun getFamilyName(): String = NAME

    override fun getName(): String = NAME

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val field = descriptor.psiElement.parent as? PsiField ?: return
        field.modifierList?.setModifierProperty(PsiModifier.VOLATILE, true)
    }

    companion object {
        const val NAME = "Add 'volatile' modifier"
    }
}
