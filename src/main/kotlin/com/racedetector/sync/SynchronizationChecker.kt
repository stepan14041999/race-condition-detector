package com.racedetector.sync

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiSynchronizedStatement
import com.intellij.psi.util.PsiTreeUtil
import com.racedetector.analysis.SyncProtection
import com.racedetector.settings.RaceDetectorSettings

import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UField
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.toUElement

object SynchronizationChecker {

    private val ATOMIC_TYPES = setOf(
        "java.util.concurrent.atomic.AtomicInteger",
        "java.util.concurrent.atomic.AtomicLong",
        "java.util.concurrent.atomic.AtomicBoolean",
        "java.util.concurrent.atomic.AtomicReference",
        "java.util.concurrent.atomic.AtomicIntegerArray",
        "java.util.concurrent.atomic.AtomicLongArray",
        "java.util.concurrent.atomic.AtomicReferenceArray",
        "java.util.concurrent.atomic.AtomicStampedReference",
        "java.util.concurrent.atomic.AtomicMarkableReference",
        "java.util.concurrent.atomic.AtomicIntegerFieldUpdater",
        "java.util.concurrent.atomic.AtomicLongFieldUpdater",
        "java.util.concurrent.atomic.AtomicReferenceFieldUpdater"
    )

    private val GUARDED_BY_ANNOTATIONS = setOf(
        "javax.annotation.concurrent.GuardedBy",
        "net.jcip.annotations.GuardedBy",
        "org.checkerframework.checker.lock.qual.GuardedBy",
        "com.android.annotations.concurrency.GuardedBy",
        "androidx.annotation.GuardedBy"
    )

    private val THREAD_SAFE_ANNOTATIONS = setOf(
        "javax.annotation.concurrent.ThreadSafe",
        "net.jcip.annotations.ThreadSafe"
    )

    private val IMMUTABLE_ANNOTATIONS = setOf(
        "javax.annotation.concurrent.Immutable",
        "net.jcip.annotations.Immutable"
    )

    fun getProtection(field: PsiField): SyncProtection {
        if (field.hasModifierProperty(PsiModifier.VOLATILE)) {
            return SyncProtection.Volatile
        }

        if (field.hasModifierProperty(PsiModifier.FINAL)) {
            return SyncProtection.Final
        }

        // Kotlin val: check via UAST as fallback
        val uField = field.toUElement() as? UField
        if (uField != null && uField.isFinal) {
            return SyncProtection.Final
        }

        val fieldType = field.type.canonicalText
        if (fieldType in ATOMIC_TYPES) {
            return SyncProtection.AtomicWrapper
        }

        val settings = RaceDetectorSettings.getInstance()

        for (annotation in field.annotations) {
            val qualifiedName = annotation.qualifiedName ?: continue
            if (qualifiedName in GUARDED_BY_ANNOTATIONS) {
                val guardValue = annotation.findAttributeValue("value")?.text?.removeSurrounding("\"") ?: "this"
                return SyncProtection.GuardedByAnnotation(guardValue)
            }
            // Check custom safe annotations on field
            if (qualifiedName in settings.customSafeAnnotations) {
                return SyncProtection.ThreadSafeAnnotation
            }
        }

        val containingClass = field.containingClass
        if (containingClass != null) {
            for (annotation in containingClass.annotations) {
                val qualifiedName = annotation.qualifiedName ?: continue
                if (qualifiedName in THREAD_SAFE_ANNOTATIONS || qualifiedName in IMMUTABLE_ANNOTATIONS) {
                    return SyncProtection.ThreadSafeAnnotation
                }
                // Check custom safe annotations on class
                if (qualifiedName in settings.customSafeAnnotations) {
                    return SyncProtection.ThreadSafeAnnotation
                }
            }
        }

        return SyncProtection.None
    }

    fun isAccessSynchronized(accessPoint: PsiElement): Boolean {
        // Java synchronized statement
        if (PsiTreeUtil.getParentOfType(accessPoint, PsiSynchronizedStatement::class.java) != null) {
            return true
        }

        // Kotlin synchronized {} call
        if (isInsideKotlinSynchronized(accessPoint)) {
            return true
        }

        val uMethod = accessPoint.toUElement()?.getParentOfType<UMethod>() ?: return false
        val psiMethod = uMethod.javaPsi
        return psiMethod.hasModifierProperty(PsiModifier.SYNCHRONIZED)
    }

    private fun isInsideKotlinSynchronized(element: PsiElement): Boolean {
        val maxDepth = RaceDetectorSettings.getInstance().maxParentTraversalDepth
        // Walk UAST parent chain with depth limit to prevent infinite loops
        var uElement = element.toUElement()
        var depth = 0
        while (uElement != null && depth++ < maxDepth) {
            if (uElement is UCallExpression && uElement.methodName == "synchronized") {
                return true
            }
            uElement = uElement.uastParent
        }
        // Fallback: walk PSI parent chain checking each element
        var psi: PsiElement? = element.parent
        depth = 0
        while (psi != null && depth++ < maxDepth) {
            ProgressManager.checkCanceled()
            // Direct PSI check for Kotlin call expression named "synchronized"
            if (psi.javaClass.name.contains("KtCallExpression")) {
                val callee = psi.firstChild
                if (callee != null && callee.text == "synchronized") {
                    return true
                }
            }
            psi = psi.parent
        }
        return false
    }
}
