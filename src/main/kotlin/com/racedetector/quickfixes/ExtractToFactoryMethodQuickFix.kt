package com.racedetector.quickfixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil

class ExtractToFactoryMethodQuickFix : LocalQuickFix {

    override fun getFamilyName(): String = NAME

    override fun getName(): String = NAME

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement ?: return
        val containingClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java) ?: return
        val constructor = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java) ?: return
        if (!constructor.isConstructor) return

        val factory = JavaPsiFacade.getElementFactory(project)
        val className = containingClass.name ?: return

        // Make constructor private
        constructor.modifierList.setModifierProperty(PsiModifier.PRIVATE, true)

        // Build parameter list from the constructor
        val params = constructor.parameterList.parameters
        val paramDecls = params.joinToString(", ") { "${it.type.canonicalText} ${it.name}" }
        val paramNames = params.joinToString(", ") { it.name ?: "arg" }

        // Create factory method
        val factoryMethod = factory.createMethodFromText(
            """
            public static $className create($paramDecls) {
                $className instance = new $className($paramNames);
                // TODO: move registration logic here
                return instance;
            }
            """.trimIndent(),
            containingClass
        )

        containingClass.addAfter(factoryMethod, constructor)
    }

    companion object {
        const val NAME = "Extract to factory method"
    }
}
