package com.racedetector.folia

import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.*
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.uast.UastHintedVisitorAdapter
import com.racedetector.analysis.ThreadContext
import com.racedetector.analysis.isFieldMatch as sharedIsFieldMatch
import com.racedetector.quickfixes.ReplaceWithAtomicQuickFix
import com.racedetector.quickfixes.ReplaceWithConcurrentCollectionQuickFix
import com.racedetector.sync.SynchronizationChecker
import org.jetbrains.uast.*
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor
import org.jetbrains.uast.visitor.AbstractUastVisitor

class FoliaSharedStateInspection : AbstractBaseUastLocalInspectionTool() {

    companion object {
        private val LISTENER_FQN = "org.bukkit.event.Listener"
        private val JAVA_PLUGIN_FQN = "org.bukkit.plugin.java.JavaPlugin"

        private val UNSAFE_COLLECTION_TYPES = setOf(
            "HashMap", "ArrayList", "HashSet", "LinkedList",
            "TreeMap", "TreeSet", "LinkedHashMap", "LinkedHashSet"
        )

        private val UNSAFE_PRIMITIVE_TYPES = setOf(
            "int", "long", "boolean", "double", "float", "short", "byte", "char"
        )

        private val UNSAFE_WRAPPER_TYPES = setOf(
            "Integer", "Long", "Boolean", "Double", "Float", "Short", "Byte", "Character",
            "String"
        )

        private val CONCURRENT_COLLECTION_TYPES = setOf(
            "ConcurrentHashMap", "CopyOnWriteArrayList", "CopyOnWriteArraySet",
            "ConcurrentSkipListMap", "ConcurrentSkipListSet",
            "ConcurrentLinkedQueue", "ConcurrentLinkedDeque"
        )

        private val ATOMIC_TYPE_NAMES = setOf(
            "AtomicInteger", "AtomicLong", "AtomicBoolean", "AtomicReference"
        )
    }

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (!FoliaProjectDetector.isFoliaProject(holder.project)) {
            return PsiElementVisitor.EMPTY_VISITOR
        }

        return UastHintedVisitorAdapter.create(
            holder.file.language,
            object : AbstractUastNonRecursiveVisitor() {
                override fun visitClass(node: UClass): Boolean {
                    checkClass(node, holder)
                    return true
                }
            },
            arrayOf(UClass::class.java),
            true
        )
    }

    private fun checkClass(uClass: UClass, holder: ProblemsHolder) {
        val psiClass = uClass.javaPsi
        if (!isListenerOrPlugin(psiClass)) return

        val fields = if (psiClass.fields.isNotEmpty()) {
            psiClass.fields.toList()
        } else {
            uClass.fields.mapNotNull { it.javaPsi as? PsiField }
        }

        val eventHandlerMethods = collectEventHandlerMethods(uClass)
        if (eventHandlerMethods.size < 2) return

        for (field in fields) {
            checkField(field, psiClass, uClass, eventHandlerMethods, holder)
        }
    }

    private fun checkField(
        field: PsiField,
        psiClass: PsiClass,
        uClass: UClass,
        eventHandlerMethods: List<UMethod>,
        holder: ProblemsHolder
    ) {
        if (field.hasModifierProperty(PsiModifier.STATIC) && field.hasModifierProperty(PsiModifier.FINAL)) return
        if (isThreadSafeType(field)) return

        val methodsAccessingField = findEventHandlerMethodsAccessingField(
            field, psiClass, uClass, eventHandlerMethods
        )
        if (methodsAccessingField.size < 2) return

        if (allAccessesSynchronized(field, psiClass, uClass, eventHandlerMethods)) return

        val nameIdentifier = field.nameIdentifier ?: return
        val fieldTypeName = getUnsafeTypeName(field)
        val fixes = buildQuickFixes(field, fieldTypeName)

        holder.registerProblem(
            nameIdentifier,
            "Field '${field.name}' is accessed from multiple @EventHandler methods. " +
                "In Folia, each handler may run on a different thread (per-entity/per-region scheduling).",
            ProblemHighlightType.GENERIC_ERROR,
            *fixes.toTypedArray()
        )
    }

    private fun isListenerOrPlugin(psiClass: PsiClass): Boolean {
        return InheritanceUtil.isInheritor(psiClass, LISTENER_FQN) ||
            InheritanceUtil.isInheritor(psiClass, JAVA_PLUGIN_FQN)
    }

    private fun collectEventHandlerMethods(uClass: UClass): List<UMethod> {
        return uClass.methods.filter { method ->
            method.javaPsi.annotations.any { annotation ->
                val qName = annotation.qualifiedName
                qName == "org.bukkit.event.EventHandler" ||
                    (qName == null && annotation.nameReferenceElement?.referenceName == "EventHandler")
            }
        }
    }

    private fun findEventHandlerMethodsAccessingField(
        field: PsiField,
        psiClass: PsiClass,
        uClass: UClass,
        eventHandlerMethods: List<UMethod>
    ): List<UMethod> {
        return eventHandlerMethods.filter { method ->
            methodAccessesField(method, field)
        }
    }

    private fun methodAccessesField(method: UMethod, field: PsiField): Boolean {
        var found = false

        // Try Java PSI path first
        val psiMethod = method.javaPsi
        psiMethod.accept(object : JavaRecursiveElementVisitor() {
            override fun visitReferenceExpression(expression: PsiReferenceExpression) {
                ProgressManager.checkCanceled()
                super.visitReferenceExpression(expression)
                if (found) return
                val resolved = expression.resolve()
                if (resolved != null && sharedIsFieldMatch(resolved, field)) {
                    found = true
                }
            }
        })

        if (found) return true

        // UAST path for Kotlin
        method.accept(object : AbstractUastVisitor() {
            override fun visitSimpleNameReferenceExpression(node: USimpleNameReferenceExpression): Boolean {
                ProgressManager.checkCanceled()
                if (found) return true
                val resolved = node.resolve()
                if (resolved != null && sharedIsFieldMatch(resolved, field)) {
                    found = true
                }
                return found
            }
        })

        return found
    }

    private fun allAccessesSynchronized(
        field: PsiField,
        psiClass: PsiClass,
        uClass: UClass,
        eventHandlerMethods: List<UMethod>
    ): Boolean {
        for (method in eventHandlerMethods) {
            ProgressManager.checkCanceled()
            var hasUnsyncedAccess = false

            method.javaPsi.accept(object : JavaRecursiveElementVisitor() {
                override fun visitReferenceExpression(expression: PsiReferenceExpression) {
                    ProgressManager.checkCanceled()
                    super.visitReferenceExpression(expression)
                    val resolved = expression.resolve()
                    if (resolved != null && sharedIsFieldMatch(resolved, field)) {
                        if (!SynchronizationChecker.isAccessSynchronized(expression)) {
                            hasUnsyncedAccess = true
                        }
                    }
                }
            })

            if (hasUnsyncedAccess) return false

            // UAST path
            method.accept(object : AbstractUastVisitor() {
                override fun visitSimpleNameReferenceExpression(node: USimpleNameReferenceExpression): Boolean {
                    ProgressManager.checkCanceled()
                    val resolved = node.resolve()
                    if (resolved != null && sharedIsFieldMatch(resolved, field)) {
                        val sourcePsi = node.sourcePsi
                        if (sourcePsi != null && !SynchronizationChecker.isAccessSynchronized(sourcePsi)) {
                            hasUnsyncedAccess = true
                        }
                    }
                    return false
                }
            })

            if (hasUnsyncedAccess) return false
        }
        return true
    }

    private fun isThreadSafeType(field: PsiField): Boolean {
        if (field.hasModifierProperty(PsiModifier.VOLATILE)) return true

        val protection = SynchronizationChecker.getProtection(field)
        if (protection is com.racedetector.analysis.SyncProtection.AtomicWrapper) return true
        if (protection is com.racedetector.analysis.SyncProtection.GuardedByAnnotation) return true
        if (protection is com.racedetector.analysis.SyncProtection.ThreadSafeAnnotation) return true

        val typeText = field.type.canonicalText
        val simpleName = typeText.substringBefore('<').substringAfterLast('.')

        if (simpleName in CONCURRENT_COLLECTION_TYPES) return true
        if (simpleName in ATOMIC_TYPE_NAMES) return true

        return false
    }

    private fun getUnsafeTypeName(field: PsiField): String? {
        val typeText = field.type.canonicalText
        val simpleName = typeText.substringBefore('<').substringAfterLast('.')

        if (simpleName in UNSAFE_COLLECTION_TYPES) return simpleName
        if (simpleName in UNSAFE_PRIMITIVE_TYPES) return simpleName
        if (simpleName in UNSAFE_WRAPPER_TYPES) return simpleName

        val initializer = field.initializer
        if (initializer is PsiNewExpression) {
            val className = initializer.classReference?.referenceName
            if (className != null && className in UNSAFE_COLLECTION_TYPES) return className
        }

        return simpleName
    }

    private fun buildQuickFixes(field: PsiField, typeName: String?): List<LocalQuickFix> {
        val fixes = mutableListOf<LocalQuickFix>()

        if (typeName != null && typeName in UNSAFE_COLLECTION_TYPES) {
            ReplaceWithConcurrentCollectionQuickFix.forType(typeName)?.let { fixes.add(it) }
        }

        val canonicalType = field.type.canonicalText
        if (canonicalType in UNSAFE_PRIMITIVE_TYPES || canonicalType == "java.lang.String") {
            fixes.add(ReplaceWithAtomicQuickFix.forField(field))
        }

        return fixes
    }
}
