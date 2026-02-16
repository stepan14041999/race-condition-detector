package com.racedetector.analysis

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import org.jetbrains.uast.UField
import org.jetbrains.uast.toUElement

/**
 * Resolves a PsiElement to a PsiField, handling Kotlin properties via UAST.
 * In Kotlin, resolve() on a property reference returns KtProperty (not PsiField).
 * This function converts via UAST to get the Java-facing PsiField.
 */
fun resolveToPsiField(element: PsiElement?): PsiField? {
    if (element == null) return null
    if (element is PsiField) return element
    val uElement = element.toUElement()
    if (uElement is UField) return uElement.javaPsi as? PsiField
    return null
}

/**
 * Checks if a resolved PsiElement matches the given PsiField, handling Kotlin property resolution.
 */
fun isFieldMatch(resolved: PsiElement, field: PsiField): Boolean {
    if (resolved.isEquivalentTo(field)) return true
    if (resolved is PsiField) {
        return resolved.name == field.name &&
                resolved.containingClass?.isEquivalentTo(field.containingClass) == true
    }
    // Kotlin: resolved might be KtProperty, convert via UAST
    val resolvedField = resolveToPsiField(resolved)
    if (resolvedField != null) {
        if (resolvedField.isEquivalentTo(field)) return true
        return resolvedField.name == field.name &&
                resolvedField.containingClass?.isEquivalentTo(field.containingClass) == true
    }
    return false
}
