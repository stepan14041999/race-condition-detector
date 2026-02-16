package com.racedetector.analysis

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.psi.*
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.racedetector.sync.SynchronizationChecker
import com.racedetector.threading.ThreadContextResolver
import org.jetbrains.uast.*
import org.jetbrains.uast.visitor.AbstractUastVisitor

object FieldAccessAnalyzer {

    private val ANALYSIS_CACHE_KEY = Key.create<CachedValue<List<FieldAnalysisResult>>>("racedetector.fieldAnalysis")

    fun analyze(psiClass: PsiClass, uClass: UClass? = null): List<FieldAnalysisResult> {
        return CachedValuesManager.getCachedValue(psiClass, ANALYSIS_CACHE_KEY) {
            // Don't capture uClass in lambda to avoid PSI leaks - convert to PsiClass instead
            val result = computeAnalysis(psiClass, null)
            CachedValueProvider.Result.create(result, PsiModificationTracker.MODIFICATION_COUNT)
        }
    }

    private fun computeAnalysis(psiClass: PsiClass, uClass: UClass? = null): List<FieldAnalysisResult> {
        // Get UAST representation if needed
        val uClassToUse = uClass ?: psiClass.toUElement() as? UClass

        val fields = if (uClassToUse != null && psiClass.fields.isEmpty()) {
            // Kotlin classes: psiClass.fields is empty, use UAST field enumeration
            uClassToUse.fields.mapNotNull { it.javaPsi as? PsiField }
                .filter { !isStaticFinalConstant(it) }
        } else {
            psiClass.fields.filter { !isStaticFinalConstant(it) }
        }
        return fields.map {
            ProgressManager.checkCanceled()
            analyzeField(it, psiClass, uClassToUse)
        }
    }

    private fun isStaticFinalConstant(field: PsiField): Boolean {
        return field.hasModifierProperty(PsiModifier.STATIC) &&
                field.hasModifierProperty(PsiModifier.FINAL)
    }

    private fun analyzeField(field: PsiField, psiClass: PsiClass, uClass: UClass? = null): FieldAnalysisResult {
        val protection = SynchronizationChecker.getProtection(field)
        val accesses = collectAccesses(field, psiClass, uClass)
        val isRace = detectRace(accesses, protection)
        val severity = if (isRace) ProblemHighlightType.GENERIC_ERROR_OR_WARNING
                       else ProblemHighlightType.INFORMATION

        return FieldAnalysisResult(
            field = field,
            accesses = accesses,
            protection = protection,
            isRace = isRace,
            severity = severity
        )
    }

    private fun collectAccesses(field: PsiField, psiClass: PsiClass, providedUClass: UClass? = null): List<FieldAccessInfo> {
        val accesses = mutableListOf<FieldAccessInfo>()

        // Primary path: Java PSI walking (works for Java files)
        psiClass.accept(object : JavaRecursiveElementVisitor() {
            override fun visitReferenceExpression(expression: PsiReferenceExpression) {
                ProgressManager.checkCanceled()
                super.visitReferenceExpression(expression)
                if (expression.resolve() != field) return

                val accessType = determineAccessType(expression)
                val threadContext = ThreadContextResolver.resolve(expression)
                val containingMethod = PsiTreeUtil.getParentOfType(expression, PsiMethod::class.java)

                accesses.add(
                    FieldAccessInfo(
                        field = field,
                        accessType = accessType,
                        location = expression,
                        containingMethod = containingMethod,
                        threadContext = threadContext
                    )
                )
            }
        })

        // Secondary path: UAST walking (for Kotlin and other UAST-supported languages)
        if (accesses.isEmpty()) {
            val uClass = providedUClass ?: psiClass.toUElement() as? UClass ?: run {
                return accesses
            }
            uClass.accept(object : AbstractUastVisitor() {
                override fun visitSimpleNameReferenceExpression(node: USimpleNameReferenceExpression): Boolean {
                    ProgressManager.checkCanceled()
                    val resolved = node.resolve()
                    if (resolved == null || !com.racedetector.analysis.isFieldMatch(resolved, field)) return false
                    val sourcePsi = node.sourcePsi ?: return false
                    val accessType = determineAccessTypeUast(node)
                    val threadContext = ThreadContextResolver.resolve(sourcePsi)
                    val containingMethod = node.getParentOfType<UMethod>()?.javaPsi

                    accesses.add(
                        FieldAccessInfo(
                            field = field,
                            accessType = accessType,
                            location = sourcePsi,
                            containingMethod = containingMethod,
                            threadContext = threadContext
                        )
                    )
                    return false
                }
            })
        }

        return accesses
    }

    private fun determineAccessType(expression: PsiReferenceExpression): AccessType {
        val parent = expression.parent

        if (parent is PsiAssignmentExpression && parent.lExpression == expression) {
            return AccessType.WRITE
        }

        if (parent is PsiUnaryExpression) {
            val token = parent.operationTokenType
            if (token == JavaTokenType.PLUSPLUS || token == JavaTokenType.MINUSMINUS) {
                return AccessType.WRITE
            }
        }

        return AccessType.READ
    }

    private fun determineAccessTypeUast(ref: UReferenceExpression): AccessType {
        val parent = ref.uastParent

        // Assignment: field = value
        if (parent is UBinaryExpression &&
            parent.operator is UastBinaryOperator.AssignOperator &&
            parent.leftOperand.sourcePsi == ref.sourcePsi) {
            return AccessType.WRITE
        }

        // Unary: field++, field--, ++field, --field
        if (parent is UUnaryExpression) {
            val op = parent.operator
            if (op == UastPostfixOperator.INC || op == UastPostfixOperator.DEC ||
                op == UastPrefixOperator.INC || op == UastPrefixOperator.DEC) {
                return AccessType.WRITE
            }
        }

        return AccessType.READ
    }

    private fun detectRace(accesses: List<FieldAccessInfo>, protection: SyncProtection): Boolean {
        if (protection != SyncProtection.None) return false
        if (accesses.size < 2) return false
        if (accesses.none { it.accessType == AccessType.WRITE }) return false

        val contexts = accesses.map { it.threadContext }.toSet()
        if (contexts.size < 2) return false

        if (accesses.all { SynchronizationChecker.isAccessSynchronized(it.location) }) return false

        return true
    }
}
