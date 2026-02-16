package com.racedetector.inspections

import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.uast.UastHintedVisitorAdapter
import com.racedetector.analysis.ThreadContext
import com.racedetector.analysis.isFieldMatch as sharedIsFieldMatch
import com.racedetector.quickfixes.ReplaceWithConcurrentCollectionQuickFix
import com.racedetector.quickfixes.WrapWithSynchronizedCollectionQuickFix
import com.racedetector.sync.SynchronizationChecker
import com.racedetector.threading.ThreadContextResolver
import org.jetbrains.uast.*
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor
import org.jetbrains.uast.visitor.AbstractUastVisitor

class UnsafeCollectionInspection : AbstractBaseUastLocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return UastHintedVisitorAdapter.create(
            holder.file.language,
            object : AbstractUastNonRecursiveVisitor() {
                override fun visitClass(node: UClass): Boolean {
                    val psiClass = node.javaPsi
                    val fields = if (psiClass.fields.isNotEmpty()) {
                        psiClass.fields.toList()
                    } else {
                        // Kotlin classes: psiClass.fields is empty, use UAST field enumeration
                        node.fields.mapNotNull { it.javaPsi as? PsiField }
                    }
                    for (field in fields) {
                        checkField(field, psiClass, node, holder)
                    }
                    return true
                }
            },
            arrayOf(UClass::class.java),
            true
        )
    }

    private fun checkField(field: PsiField, psiClass: PsiClass, uClass: UClass, holder: ProblemsHolder) {
        if (field.hasModifierProperty(PsiModifier.STATIC) && field.hasModifierProperty(PsiModifier.FINAL)) return

        // Skip fields that should not be inspected
        if (FalsePositiveFilter.shouldSkipField(field)) return

        val unsafeSimpleName = getUnsafeCollectionSimpleName(field) ?: return

        if (isSafeInitialization(field)) return

        val accesses = collectFieldAccesses(field, psiClass, uClass)
        if (accesses.size < 2) return

        val contexts = accesses.map { it.second }.toSet()
        if (contexts.size < 2) return

        if (accesses.all { isInsideSynchronized(it.first) }) return

        val nameIdentifier = field.nameIdentifier ?: return
        val fixes = mutableListOf<LocalQuickFix>()
        ReplaceWithConcurrentCollectionQuickFix.forType(unsafeSimpleName)?.let { fixes.add(it) }
        WrapWithSynchronizedCollectionQuickFix.forType(unsafeSimpleName)?.let { fixes.add(it) }

        // Reduce severity for certain patterns
        val severity = if (FalsePositiveFilter.shouldReduceSeverity(field)) {
            com.intellij.codeInspection.ProblemHighlightType.WEAK_WARNING
        } else {
            com.intellij.codeInspection.ProblemHighlightType.GENERIC_ERROR_OR_WARNING
        }

        holder.registerProblem(
            nameIdentifier,
            "Unsafe collection '${field.name}' ($unsafeSimpleName) is accessed from multiple thread contexts without synchronization",
            severity,
            *fixes.toTypedArray()
        )
    }

    private fun getUnsafeCollectionSimpleName(field: PsiField): String? {
        val typeText = field.type.canonicalText
        val declaredSimple = typeText.substringBefore('<').substringAfterLast('.')
        if (declaredSimple in UNSAFE_COLLECTION_TYPES) return declaredSimple

        val initializer = field.initializer
        if (initializer is PsiNewExpression) {
            val className = initializer.classReference?.referenceName
            if (className != null && className in UNSAFE_COLLECTION_TYPES) return className
        }

        return null
    }

    private fun isSafeInitialization(field: PsiField): Boolean {
        if (!field.hasModifierProperty(PsiModifier.FINAL)) return false
        val initializer = field.initializer ?: return false
        return isConcurrentType(initializer)
                || isSynchronizedWrapper(initializer)
                || isUnmodifiableWrapper(initializer)
                || isImmutableFactoryCall(initializer)
    }

    private fun isConcurrentType(expr: PsiExpression): Boolean {
        if (expr is PsiNewExpression) {
            val className = expr.classReference?.referenceName ?: return false
            return className in CONCURRENT_COLLECTION_TYPES
        }
        if (expr is PsiMethodCallExpression) {
            val ref = expr.methodExpression
            val qualifier = ref.qualifierExpression?.text ?: ""
            val methodName = ref.referenceName ?: ""
            if (qualifier == "ConcurrentHashMap" && methodName == "newKeySet") return true
        }
        return false
    }

    private fun isSynchronizedWrapper(expr: PsiExpression): Boolean {
        if (expr !is PsiMethodCallExpression) return false
        val ref = expr.methodExpression
        val qualifier = ref.qualifierExpression?.text ?: return false
        val methodName = ref.referenceName ?: return false
        return qualifier == "Collections" && methodName.startsWith("synchronized")
    }

    private fun isUnmodifiableWrapper(expr: PsiExpression): Boolean {
        if (expr !is PsiMethodCallExpression) return false
        val ref = expr.methodExpression
        val qualifier = ref.qualifierExpression?.text ?: return false
        val methodName = ref.referenceName ?: return false
        return qualifier == "Collections" && methodName.startsWith("unmodifiable")
    }

    private fun isImmutableFactoryCall(expr: PsiExpression): Boolean {
        if (expr !is PsiMethodCallExpression) return false
        val ref = expr.methodExpression
        val qualifier = ref.qualifierExpression?.text ?: return false
        val methodName = ref.referenceName ?: return false
        return qualifier in setOf("List", "Map", "Set") && methodName == "of"
    }

    private fun collectFieldAccesses(
        field: PsiField,
        psiClass: PsiClass,
        uClass: UClass
    ): List<Pair<PsiElement, ThreadContext>> {
        val accesses = mutableListOf<Pair<PsiElement, ThreadContext>>()

        // Primary path: Java PSI walking
        psiClass.accept(object : JavaRecursiveElementVisitor() {
            override fun visitReferenceExpression(expression: PsiReferenceExpression) {
                ProgressManager.checkCanceled()
                super.visitReferenceExpression(expression)
                if (expression.resolve() != field) return
                val context = ThreadContextResolver.resolve(expression)
                accesses.add(expression to context)
            }
        })

        // Secondary path: UAST walking (for Kotlin)
        if (accesses.isEmpty()) {
            uClass.accept(object : AbstractUastVisitor() {
                override fun visitSimpleNameReferenceExpression(node: USimpleNameReferenceExpression): Boolean {
                    ProgressManager.checkCanceled()
                    val resolved = node.resolve()
                    if (resolved == null || !sharedIsFieldMatch(resolved, field)) return false
                    val sourcePsi = node.sourcePsi ?: return false
                    val context = ThreadContextResolver.resolve(sourcePsi)
                    accesses.add(sourcePsi to context)
                    return false
                }
            })
        }

        return accesses
    }

    private fun isInsideSynchronized(element: PsiElement): Boolean {
        if (PsiTreeUtil.getParentOfType(element, PsiSynchronizedStatement::class.java) != null) return true
        return SynchronizationChecker.isAccessSynchronized(element)
    }

    companion object {
        val UNSAFE_COLLECTION_TYPES = setOf(
            "HashMap", "ArrayList", "HashSet", "LinkedList",
            "TreeMap", "TreeSet", "LinkedHashMap", "LinkedHashSet"
        )
        private val CONCURRENT_COLLECTION_TYPES = setOf(
            "ConcurrentHashMap", "CopyOnWriteArrayList", "CopyOnWriteArraySet",
            "ConcurrentSkipListMap", "ConcurrentSkipListSet",
            "ConcurrentLinkedQueue", "ConcurrentLinkedDeque"
        )
    }
}
