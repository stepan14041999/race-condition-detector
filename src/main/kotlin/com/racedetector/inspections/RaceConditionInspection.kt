package com.racedetector.inspections

import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.uast.UastHintedVisitorAdapter
import com.racedetector.analysis.FieldAccessAnalyzer
import com.racedetector.quickfixes.AddVolatileQuickFix
import com.racedetector.quickfixes.ReplaceWithAtomicQuickFix
import com.racedetector.quickfixes.WrapWithSynchronizedQuickFix
import org.jetbrains.uast.UClass
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

class RaceConditionInspection : AbstractBaseUastLocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return UastHintedVisitorAdapter.create(
            holder.file.language,
            object : AbstractUastNonRecursiveVisitor() {
                override fun visitClass(node: UClass): Boolean {
                    val psiClass = node.javaPsi
                    val results = FieldAccessAnalyzer.analyze(psiClass, node)
                    for (result in results) {
                        if (!result.isRace) continue

                        val field = result.field

                        // Skip fields that should not be inspected
                        if (FalsePositiveFilter.shouldSkipField(field)) continue

                        // Check for double-checked locking pattern
                        if (FalsePositiveFilter.isCorrectDoubleCheckedLocking(field)) continue

                        val nameIdentifier = field.nameIdentifier ?: continue

                        // Reduce severity for certain patterns
                        val severity = if (FalsePositiveFilter.shouldReduceSeverity(field)) {
                            ProblemHighlightType.WEAK_WARNING
                        } else {
                            ProblemHighlightType.GENERIC_ERROR_OR_WARNING
                        }

                        holder.registerProblem(
                            nameIdentifier,
                            "Field '${field.name}' is accessed from multiple threads without synchronization",
                            severity,
                            AddVolatileQuickFix(),
                            ReplaceWithAtomicQuickFix.forField(field),
                            WrapWithSynchronizedQuickFix()
                        )
                    }
                    return true
                }
            },
            arrayOf(UClass::class.java),
            true
        )
    }
}
