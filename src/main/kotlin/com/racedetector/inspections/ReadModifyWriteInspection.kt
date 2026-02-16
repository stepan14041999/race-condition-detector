package com.racedetector.inspections

import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.*
import com.intellij.uast.UastHintedVisitorAdapter
import com.racedetector.analysis.ThreadContext
import com.racedetector.analysis.resolveToPsiField
import com.racedetector.quickfixes.ReplaceWithAtomicOperationQuickFix
import com.racedetector.sync.SynchronizationChecker
import com.racedetector.threading.ThreadContextResolver
import org.jetbrains.uast.*
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

class ReadModifyWriteInspection : AbstractBaseUastLocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return UastHintedVisitorAdapter.create(
            holder.file.language,
            object : AbstractUastNonRecursiveVisitor() {
                override fun visitPostfixExpression(node: UPostfixExpression): Boolean {
                    handleUnary(node, node.operand, node.operator, holder)
                    return true
                }

                override fun visitPrefixExpression(node: UPrefixExpression): Boolean {
                    handleUnary(node, node.operand, node.operator, holder)
                    return true
                }

                override fun visitBinaryExpression(node: UBinaryExpression): Boolean {
                    handleAssignment(node, holder)
                    return true
                }
            },
            arrayOf(UPostfixExpression::class.java, UPrefixExpression::class.java, UBinaryExpression::class.java),
            true
        )
    }

    private fun handleUnary(
        expression: UExpression,
        operand: UExpression,
        operator: UastOperator,
        holder: ProblemsHolder
    ) {
        if (operator != UastPostfixOperator.INC && operator != UastPostfixOperator.DEC &&
            operator != UastPrefixOperator.INC && operator != UastPrefixOperator.DEC) return

        val ref = operand as? UReferenceExpression ?: return
        val resolved = ref.resolve()
        val field = resolveToPsiField(resolved) ?: return

        val opDesc = if (operator == UastPostfixOperator.INC || operator == UastPrefixOperator.INC) "++" else "--"
        reportIfApplicable(expression, field, opDesc, holder)
    }

    private fun handleAssignment(expression: UBinaryExpression, holder: ProblemsHolder) {
        val operator = expression.operator
        if (operator !is UastBinaryOperator.AssignOperator) return

        val lExpr = expression.leftOperand as? UReferenceExpression ?: return
        val field = resolveToPsiField(lExpr.resolve()) ?: return

        // Compound assignment: +=, -=, *=, /=, etc.
        if (operator != UastBinaryOperator.ASSIGN) {
            val opDesc = expression.operatorIdentifier?.sourcePsi?.text ?: operator.text
            reportIfApplicable(expression, field, opDesc, holder)
            return
        }

        // Simple assignment: field = field + something
        val rExpr = expression.rightOperand as? UBinaryExpression ?: return
        if (isReadModifyWrite(rExpr, field)) {
            val opSign = rExpr.operatorIdentifier?.sourcePsi?.text ?: rExpr.operator.text
            reportIfApplicable(expression, field, "= ${field.name} $opSign ...", holder)
        }
    }

    private fun isReadModifyWrite(binary: UBinaryExpression, field: PsiField): Boolean {
        val lRef = binary.leftOperand as? UReferenceExpression
        val rRef = binary.rightOperand as? UReferenceExpression
        val lField = if (lRef != null) resolveToPsiField(lRef.resolve()) else null
        val rField = if (rRef != null) resolveToPsiField(rRef.resolve()) else null
        return (lField != null && lField.isEquivalentTo(field)) ||
               (rField != null && rField.isEquivalentTo(field))
    }

    private fun reportIfApplicable(
        expression: UExpression,
        field: PsiField,
        operationDesc: String,
        holder: ProblemsHolder
    ) {
        // Skip fields that should not be inspected
        if (FalsePositiveFilter.shouldSkipField(field)) return

        val fieldTypeName = field.type.canonicalText
        if (ATOMIC_TYPES.any { fieldTypeName.startsWith(it) }) return

        val sourcePsi = expression.sourcePsi ?: return
        if (SynchronizationChecker.isAccessSynchronized(sourcePsi)) return

        val isVolatile = field.hasModifierProperty(PsiModifier.VOLATILE)
        val fieldName = field.name ?: return
        val context = ThreadContextResolver.resolve(sourcePsi)

        // Reduce severity for certain patterns
        val baseSeverity = if (FalsePositiveFilter.shouldReduceSeverity(field)) {
            ProblemHighlightType.WEAK_WARNING
        } else {
            ProblemHighlightType.GENERIC_ERROR_OR_WARNING
        }

        if (isVolatile) {
            val fixes = createQuickFix(expression, field)
            holder.registerProblem(
                sourcePsi,
                "Non-atomic read-modify-write '$operationDesc' on volatile field '$fieldName' — volatile does not guarantee atomicity",
                baseSeverity,
                *fixes
            )
        } else {
            val context = ThreadContextResolver.resolve(sourcePsi)
            if (context is ThreadContext.WorkerThread || context is ThreadContext.ScheduledThread) {
                val fixes = createQuickFix(expression, field)
                val severity = if (baseSeverity == ProblemHighlightType.WEAK_WARNING) {
                    ProblemHighlightType.WEAK_WARNING
                } else {
                    ProblemHighlightType.ERROR
                }
                holder.registerProblem(
                    sourcePsi,
                    "Non-atomic read-modify-write '$operationDesc' on field '$fieldName' in multithreaded context",
                    severity,
                    *fixes
                )
            }
        }
    }

    private fun createQuickFix(expression: UExpression, field: PsiField): Array<LocalQuickFix> {
        val fieldName = field.name ?: return emptyArray()
        val fieldType = field.type.canonicalText
        if (fieldType != "int" && fieldType != "long") return emptyArray()

        val atomicType = if (fieldType == "int") "AtomicInteger" else "AtomicLong"

        val replacement: String? = when (expression) {
            is UUnaryExpression -> {
                val op = expression.operator
                when {
                    op == UastPostfixOperator.INC || op == UastPrefixOperator.INC ->
                        "$fieldName.incrementAndGet()"
                    op == UastPostfixOperator.DEC || op == UastPrefixOperator.DEC ->
                        "$fieldName.decrementAndGet()"
                    else -> null
                }
            }
            is UBinaryExpression -> {
                val rhs = expression.rightOperand.sourcePsi?.text
                val op = expression.operator
                when {
                    op == UastBinaryOperator.PLUS_ASSIGN && rhs != null -> "$fieldName.addAndGet($rhs)"
                    op == UastBinaryOperator.MINUS_ASSIGN && rhs != null -> "$fieldName.addAndGet(-($rhs))"
                    else -> null
                }
            }
            else -> null
        }

        return if (replacement != null) {
            arrayOf(ReplaceWithAtomicOperationQuickFix(fieldName, atomicType, replacement))
        } else {
            emptyArray()
        }
    }

    companion object {
        private val ATOMIC_TYPES = setOf(
            "java.util.concurrent.atomic.AtomicInteger",
            "java.util.concurrent.atomic.AtomicLong",
            "java.util.concurrent.atomic.AtomicBoolean",
            "java.util.concurrent.atomic.AtomicReference"
        )
    }
}
