package com.racedetector.quickfixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.*

class WrapWithSynchronizedCollectionQuickFix(
    private val wrapperMethod: String
) : LocalQuickFix {

    override fun getFamilyName(): String = "Wrap with Collections.synchronized*()"

    override fun getName(): String = "Wrap with Collections.$wrapperMethod()"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val field = descriptor.psiElement.parent as? PsiField ?: return
        val initializer = field.initializer ?: return
        val factory = JavaPsiFacade.getElementFactory(project)

        val wrappedText = "java.util.Collections.$wrapperMethod(${initializer.text})"
        val newInit = factory.createExpressionFromText(wrappedText, field)
        initializer.replace(newInit)
    }

    companion object {
        private val WRAPPER_MAP = mapOf(
            "HashMap" to "synchronizedMap",
            "LinkedHashMap" to "synchronizedMap",
            "TreeMap" to "synchronizedSortedMap",
            "ArrayList" to "synchronizedList",
            "LinkedList" to "synchronizedList",
            "HashSet" to "synchronizedSet",
            "LinkedHashSet" to "synchronizedSet",
            "TreeSet" to "synchronizedSortedSet"
        )

        fun forType(unsafeSimpleName: String): WrapWithSynchronizedCollectionQuickFix? {
            val method = WRAPPER_MAP[unsafeSimpleName] ?: return null
            return WrapWithSynchronizedCollectionQuickFix(method)
        }
    }
}
