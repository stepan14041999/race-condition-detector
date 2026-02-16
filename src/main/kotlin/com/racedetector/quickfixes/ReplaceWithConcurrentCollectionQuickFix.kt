package com.racedetector.quickfixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.*

class ReplaceWithConcurrentCollectionQuickFix(
    private val unsafeType: String,
    private val concurrentType: String,
    private val concurrentFqn: String
) : LocalQuickFix {

    override fun getFamilyName(): String = "Replace with thread-safe collection"

    override fun getName(): String = "Replace $unsafeType with $concurrentType"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val field = descriptor.psiElement.parent as? PsiField ?: return
        val factory = JavaPsiFacade.getElementFactory(project)

        val initializer = field.initializer
        if (initializer is PsiNewExpression) {
            val classRef = initializer.classReference
            if (classRef != null) {
                val newInitText = initializer.text.replaceFirst(
                    classRef.referenceName ?: return,
                    concurrentType
                )
                val newInit = factory.createExpressionFromText(newInitText, field)
                initializer.replace(newInit)
            }
        }

        val file = field.containingFile as? PsiJavaFile ?: return
        val psiClass = JavaPsiFacade.getInstance(project).findClass(concurrentFqn, field.resolveScope)
        if (psiClass != null) {
            file.importList?.add(factory.createImportStatement(psiClass))
        }
    }

    companion object {
        private val REPLACEMENTS = mapOf(
            "HashMap" to ("ConcurrentHashMap" to "java.util.concurrent.ConcurrentHashMap"),
            "ArrayList" to ("CopyOnWriteArrayList" to "java.util.concurrent.CopyOnWriteArrayList"),
            "HashSet" to ("ConcurrentHashMap.newKeySet()" to "java.util.concurrent.ConcurrentHashMap"),
            "LinkedHashMap" to ("ConcurrentHashMap" to "java.util.concurrent.ConcurrentHashMap"),
            "TreeMap" to ("ConcurrentSkipListMap" to "java.util.concurrent.ConcurrentSkipListMap"),
            "TreeSet" to ("ConcurrentSkipListSet" to "java.util.concurrent.ConcurrentSkipListSet"),
            "LinkedList" to ("CopyOnWriteArrayList" to "java.util.concurrent.CopyOnWriteArrayList"),
            "LinkedHashSet" to ("ConcurrentHashMap.newKeySet()" to "java.util.concurrent.ConcurrentHashMap")
        )

        fun forType(unsafeSimpleName: String): ReplaceWithConcurrentCollectionQuickFix? {
            val (concurrent, fqn) = REPLACEMENTS[unsafeSimpleName] ?: return null
            return ReplaceWithConcurrentCollectionQuickFix(unsafeSimpleName, concurrent, fqn)
        }
    }
}
