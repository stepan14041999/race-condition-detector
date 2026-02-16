package com.racedetector.analysis

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod

data class FieldAccessInfo(
    val field: PsiField,
    val accessType: AccessType,
    val location: PsiElement,
    val containingMethod: PsiMethod?,
    val threadContext: ThreadContext
)
