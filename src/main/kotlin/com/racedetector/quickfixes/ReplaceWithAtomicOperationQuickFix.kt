package com.racedetector.quickfixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiModifier
import com.intellij.psi.util.PsiTreeUtil

class ReplaceWithAtomicOperationQuickFix(
    private val fieldName: String,
    private val atomicType: String,
    private val replacementExpr: String
) : LocalQuickFix {

    override fun getFamilyName(): String = "Replace with atomic operation"

    override fun getName(): String = "Replace with $replacementExpr"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val expression = descriptor.psiElement ?: return
        val factory = JavaPsiFacade.getElementFactory(project)

        val containingClass = PsiTreeUtil.getParentOfType(expression, PsiClass::class.java) ?: return
        val field = containingClass.findFieldByName(fieldName, false) ?: return

        // Change field type to AtomicXxx
        val newType = factory.createTypeFromText(atomicType, field)
        field.typeElement?.replace(factory.createTypeElement(newType))

        // Update initializer: e.g., 0 → new AtomicInteger(0)
        val initializer = field.initializer
        if (initializer != null) {
            val newInit = factory.createExpressionFromText(
                "new $atomicType(${initializer.text})", field
            )
            initializer.replace(newInit)
        }

        // Remove volatile if present (unnecessary with AtomicXxx)
        field.modifierList?.setModifierProperty(PsiModifier.VOLATILE, false)

        // Replace the expression with atomic call
        val newExpr = factory.createExpressionFromText(replacementExpr, expression)
        expression.replace(newExpr)

        // Add import
        val file = field.containingFile as? PsiJavaFile ?: return
        val fqn = "java.util.concurrent.atomic.$atomicType"
        val psiClass = JavaPsiFacade.getInstance(project).findClass(fqn, field.resolveScope)
        if (psiClass != null) {
            file.importList?.add(factory.createImportStatement(psiClass))
        }
    }
}
