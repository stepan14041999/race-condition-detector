package com.racedetector.inspections

import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiField
import com.intellij.psi.PsiModifier
import com.intellij.uast.UastHintedVisitorAdapter
import com.racedetector.sync.SynchronizationChecker
import com.racedetector.analysis.SyncProtection
import com.racedetector.settings.RaceDetectorSettings
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UField
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

class SpringControllerSharedStateInspection : AbstractBaseUastLocalInspectionTool() {

    companion object {
        private val SINGLETON_BEAN_ANNOTATIONS = setOf(
            "Controller", "RestController", "Service"
        )

        private val SCOPE_ANNOTATION_NAMES = setOf(
            "org.springframework.context.annotation.Scope",
            "Scope"
        )

        private val SAFE_FIELD_TYPES = setOf(
            "org.slf4j.Logger",
            "org.apache.logging.log4j.Logger",
            "java.util.logging.Logger"
        )
    }

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (!RaceDetectorSettings.getInstance().enableSpringChecks) {
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
        if (!isSingletonSpringBean(uClass)) return

        val psiClass = uClass.javaPsi
        val fields = if (psiClass.fields.isNotEmpty()) {
            psiClass.fields.toList()
        } else {
            uClass.fields.mapNotNull { it.javaPsi as? PsiField }
        }

        for (field in fields) {
            if (isMutableSharedField(field)) {
                // Skip fields that should not be inspected
                if (FalsePositiveFilter.shouldSkipField(field)) continue

                val nameIdentifier = field.nameIdentifier ?: continue

                // Reduce severity for certain patterns
                if (FalsePositiveFilter.shouldReduceSeverity(field)) {
                    holder.registerProblem(
                        nameIdentifier,
                        "Mutable field '${field.name}' in singleton Spring bean may be accessed from multiple request threads",
                        com.intellij.codeInspection.ProblemHighlightType.WEAK_WARNING
                    )
                } else {
                    holder.registerProblem(
                        nameIdentifier,
                        "Mutable field '${field.name}' in singleton Spring bean may be accessed from multiple request threads"
                    )
                }
            }
        }
    }

    private fun isSingletonSpringBean(uClass: UClass): Boolean {
        val hasBean = uClass.uAnnotations.any { annotation ->
            val name = (annotation.qualifiedName ?: "").substringAfterLast('.')
            name in SINGLETON_BEAN_ANNOTATIONS
        }
        if (!hasBean) return false

        // Check if explicitly scoped to non-singleton (e.g. @Scope("prototype"))
        for (annotation in uClass.uAnnotations) {
            val name = (annotation.qualifiedName ?: "").substringAfterLast('.')
            if (name == "Scope") {
                val psiAnnotation = annotation.javaPsi ?: continue
                val value = psiAnnotation.findAttributeValue("value")?.text?.removeSurrounding("\"")
                if (value != null && value != "singleton") {
                    return false
                }
            }
        }

        return true
    }

    private fun isMutableSharedField(field: PsiField): Boolean {
        // Static final constants are fine
        if (field.hasModifierProperty(PsiModifier.STATIC) && field.hasModifierProperty(PsiModifier.FINAL)) return false

        // Final fields are fine
        if (field.hasModifierProperty(PsiModifier.FINAL)) return false

        // Kotlin val
        val uField = field.toUElement() as? UField
        if (uField != null && uField.isFinal) return false

        // Volatile is still mutable shared state but user is at least aware — still flag it? No, spec says non-volatile.
        if (field.hasModifierProperty(PsiModifier.VOLATILE)) return false

        // Atomic/thread-safe types
        val protection = SynchronizationChecker.getProtection(field)
        if (protection is SyncProtection.AtomicWrapper) return false
        if (protection is SyncProtection.GuardedByAnnotation) return false
        if (protection is SyncProtection.ThreadSafeAnnotation) return false

        // Logger fields are fine
        val typeText = field.type.canonicalText
        if (typeText in SAFE_FIELD_TYPES) return false

        return true
    }
}
