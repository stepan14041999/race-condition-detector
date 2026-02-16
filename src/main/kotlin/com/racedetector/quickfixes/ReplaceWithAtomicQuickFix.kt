package com.racedetector.quickfixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaFile

class ReplaceWithAtomicQuickFix(
    private val atomicTypeText: String,
    private val atomicSimpleName: String
) : LocalQuickFix {

    override fun getFamilyName(): String = "Replace with atomic type"

    override fun getName(): String = "Replace with $atomicTypeText"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val field = descriptor.psiElement.parent as? PsiField ?: return
        val factory = JavaPsiFacade.getElementFactory(project)

        val newType = factory.createTypeFromText(atomicTypeText, field)
        field.typeElement?.replace(factory.createTypeElement(newType))

        val file = field.containingFile as? PsiJavaFile ?: return
        val fqn = "java.util.concurrent.atomic.$atomicSimpleName"
        val psiClass = JavaPsiFacade.getInstance(project).findClass(fqn, field.resolveScope)
        if (psiClass != null) {
            file.importList?.add(factory.createImportStatement(psiClass))
        }
    }

    companion object {
        fun forField(field: PsiField): ReplaceWithAtomicQuickFix {
            val (typeText, simpleName) = when (field.type.canonicalText) {
                "int" -> "AtomicInteger" to "AtomicInteger"
                "long" -> "AtomicLong" to "AtomicLong"
                "boolean" -> "AtomicBoolean" to "AtomicBoolean"
                else -> {
                    val refType = field.type.presentableText
                    "AtomicReference<$refType>" to "AtomicReference"
                }
            }
            return ReplaceWithAtomicQuickFix(typeText, simpleName)
        }
    }
}
