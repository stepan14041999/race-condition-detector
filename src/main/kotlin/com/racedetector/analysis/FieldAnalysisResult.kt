package com.racedetector.analysis

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.psi.PsiField

data class FieldAnalysisResult(
    val field: PsiField,
    val accesses: List<FieldAccessInfo>,
    val protection: SyncProtection,
    val isRace: Boolean,
    val severity: ProblemHighlightType
)
