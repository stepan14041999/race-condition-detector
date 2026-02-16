package com.racedetector.inspections

import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.uast.UastHintedVisitorAdapter
import com.racedetector.quickfixes.ReplaceWithComputeIfAbsentQuickFix
import com.racedetector.quickfixes.ReplaceWithPutIfAbsentQuickFix
import com.racedetector.sync.SynchronizationChecker
import org.jetbrains.uast.*
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor
import org.jetbrains.uast.visitor.AbstractUastVisitor

class CheckThenActInspection : AbstractBaseUastLocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return UastHintedVisitorAdapter.create(
            holder.file.language,
            object : AbstractUastNonRecursiveVisitor() {
                override fun visitIfExpression(node: UIfExpression): Boolean {
                    val sourcePsi = node.sourcePsi ?: return true
                    if (isInsideSynchronized(sourcePsi)) return true

                    // Skip if in test class
                    if (shouldSkipExpression(sourcePsi)) return true

                    val condition = node.condition
                    val thenBranch = node.thenExpression ?: return true

                    checkContainsKeyThenGetOrPut(condition, thenBranch, holder)
                    checkGetNotNullThenGet(condition, thenBranch, holder)
                    checkNotContainsThenAdd(condition, thenBranch, holder)
                    checkIsEmptyThenGet(condition, thenBranch, holder)
                    return true
                }
            },
            arrayOf(UIfExpression::class.java),
            true
        )
    }

    private data class CallInfo(
        val methodName: String,
        val qualifierText: String?,
        val qualifierType: PsiType?,
        val call: UCallExpression
    )

    private fun checkContainsKeyThenGetOrPut(
        condition: UExpression,
        thenBranch: UExpression,
        holder: ProblemsHolder
    ) {
        val call = extractContainsKeyCall(condition) ?: return
        if (call.methodName != "containsKey") return
        val qualifier = call.qualifierText ?: return
        if (isConcurrentMap(call.qualifierType)) return

        val bodyActions = collectMethodCalls(thenBranch)
        val hasAct = bodyActions.any {
            it.qualifierText == qualifier && it.methodName in setOf("get", "put", "remove")
        }
        if (hasAct) {
            val conditionPsi = condition.sourcePsi ?: return
            val fixes = buildMapQuickFixes(call.qualifierType)
            holder.registerProblem(
                conditionPsi,
                "Check-then-act: 'containsKey()' followed by 'get()/put()' on '${qualifier}' without synchronization",
                *fixes
            )
        }
    }

    private fun isNullExpression(expr: UExpression): Boolean {
        if (expr is ULiteralExpression && expr.value == null) return true
        val text = expr.sourcePsi?.text
        return text == "null"
    }

    private fun checkGetNotNullThenGet(
        condition: UExpression,
        thenBranch: UExpression,
        holder: ProblemsHolder
    ) {
        val binary = condition as? UBinaryExpression ?: return
        if (binary.operator.text != "!=" && binary.operator.text != "!==") return

        val getExpr = when {
            isNullExpression(binary.rightOperand) -> binary.leftOperand
            isNullExpression(binary.leftOperand) -> binary.rightOperand
            else -> return
        }

        val call = extractMethodCallFromExpr(getExpr) ?: return
        if (call.methodName != "get") return
        val qualifier = call.qualifierText ?: return
        if (isConcurrentMap(call.qualifierType)) return

        val bodyActions = collectMethodCalls(thenBranch)
        val hasAct = bodyActions.any {
            it.qualifierText == qualifier && it.methodName in setOf("get", "put", "remove")
        }
        if (hasAct) {
            val conditionPsi = condition.sourcePsi ?: return
            val fixes = buildMapQuickFixes(call.qualifierType)
            holder.registerProblem(
                conditionPsi,
                "Check-then-act: 'get() != null' followed by 'get()/put()' on '${qualifier}' without synchronization",
                *fixes
            )
        }
    }

    private fun checkNotContainsThenAdd(
        condition: UExpression,
        thenBranch: UExpression,
        holder: ProblemsHolder
    ) {
        val prefix = condition as? UPrefixExpression ?: return
        if (prefix.operator.text != "!") return
        val inner = prefix.operand
        val call = extractMethodCallFromExpr(inner) ?: return
        if (call.methodName != "contains") return
        val qualifier = call.qualifierText ?: return
        if (isConcurrentCollection(call.qualifierType)) return

        val bodyActions = collectMethodCalls(thenBranch)
        val hasAdd = bodyActions.any {
            it.qualifierText == qualifier && it.methodName == "add"
        }
        if (hasAdd) {
            val conditionPsi = condition.sourcePsi ?: return
            holder.registerProblem(
                conditionPsi,
                "Check-then-act: '!contains()' followed by 'add()' on '${qualifier}' without synchronization"
            )
        }
    }

    private fun checkIsEmptyThenGet(
        condition: UExpression,
        thenBranch: UExpression,
        holder: ProblemsHolder
    ) {
        val call: CallInfo
        val prefix = condition as? UPrefixExpression
        if (prefix != null && prefix.operator.text == "!") {
            call = extractMethodCallFromExpr(prefix.operand) ?: return
        } else {
            call = extractMethodCallFromExpr(condition) ?: return
        }

        if (call.methodName != "isEmpty") return
        val qualifier = call.qualifierText ?: return
        if (isConcurrentCollection(call.qualifierType)) return

        val bodyActions = collectMethodCalls(thenBranch)
        val hasGet = bodyActions.any {
            it.qualifierText == qualifier && it.methodName in setOf("get", "remove", "iterator")
        }
        if (hasGet) {
            val conditionPsi = condition.sourcePsi ?: return
            holder.registerProblem(
                conditionPsi,
                "Check-then-act: 'isEmpty()' followed by 'get()' on '${qualifier}' without synchronization"
            )
        }
    }

    private fun extractContainsKeyCall(expr: UExpression): CallInfo? {
        extractMethodCallFromExpr(expr)?.let { return it }
        val prefix = expr as? UPrefixExpression ?: return null
        if (prefix.operator.text != "!") return null
        return extractMethodCallFromExpr(prefix.operand)
    }

    private fun extractMethodCallFromExpr(expr: UExpression?): CallInfo? {
        val call: UCallExpression
        var receiverExpr: UExpression? = null

        when (expr) {
            is UQualifiedReferenceExpression -> {
                call = expr.selector as? UCallExpression ?: return null
                receiverExpr = expr.receiver
            }
            is UCallExpression -> {
                call = expr
                receiverExpr = expr.receiver
            }
            is UParenthesizedExpression -> return extractMethodCallFromExpr(expr.expression)
            else -> return null
        }

        val methodName = call.methodName ?: return null
        var qualifierText = receiverExpr?.sourcePsi?.text
        var qualifierType: PsiType? = (receiverExpr?.sourcePsi as? PsiExpression)?.type
            ?: receiverExpr?.getExpressionType()

        // Fallback for Java: qualifier is in PsiMethodCallExpression.methodExpression
        if (qualifierText == null) {
            val psiCall = call.sourcePsi as? PsiMethodCallExpression
            val qualifier = psiCall?.methodExpression?.qualifierExpression
            qualifierText = qualifier?.text
            qualifierType = qualifier?.type
        }

        return CallInfo(methodName, qualifierText, qualifierType, call)
    }

    private fun collectMethodCalls(element: UExpression): List<CallInfo> {
        val result = mutableListOf<CallInfo>()
        element.accept(object : AbstractUastVisitor() {
            override fun visitCallExpression(node: UCallExpression): Boolean {
                ProgressManager.checkCanceled()
                val methodName = node.methodName ?: return false
                val receiver = node.receiver
                var qualifierText = receiver?.sourcePsi?.text
                var qualifierType: PsiType? = (receiver?.sourcePsi as? PsiExpression)?.type
                    ?: receiver?.getExpressionType()

                // Fallback for Java PSI
                if (qualifierText == null) {
                    val psiCall = node.sourcePsi as? PsiMethodCallExpression
                    val qualifier = psiCall?.methodExpression?.qualifierExpression
                    qualifierText = qualifier?.text
                    qualifierType = qualifier?.type
                }

                result.add(CallInfo(methodName, qualifierText, qualifierType, node))
                return false
            }
        })
        return result
    }

    private fun isInsideSynchronized(element: PsiElement): Boolean {
        if (PsiTreeUtil.getParentOfType(element, PsiSynchronizedStatement::class.java) != null) return true
        return SynchronizationChecker.isAccessSynchronized(element)
    }

    private fun shouldSkipExpression(element: PsiElement): Boolean {
        // Check containing class
        val containingClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java) ?: return false

        // Check @SuppressWarnings on class or method
        if (FalsePositiveFilter.hasSuppressWarnings(containingClass)) return true

        val containingMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
        if (containingMethod != null && FalsePositiveFilter.hasSuppressWarnings(containingMethod)) {
            return true
        }

        // Check if in test class
        if (FalsePositiveFilter.isInTestClass(containingClass)) return true

        // Skip enums and records
        if (containingClass.isEnum || containingClass.isRecord) return true

        return false
    }

    private fun isConcurrentMap(type: PsiType?): Boolean {
        val canonical = type?.canonicalText ?: return false
        return CONCURRENT_MAP_TYPES.any { canonical.startsWith(it) }
    }

    private fun isConcurrentCollection(type: PsiType?): Boolean {
        val canonical = type?.canonicalText ?: return false
        return CONCURRENT_COLLECTION_TYPES.any { canonical.startsWith(it) }
    }

    private fun buildMapQuickFixes(qualifierType: PsiType?): Array<com.intellij.codeInspection.LocalQuickFix> {
        val fixes = mutableListOf<com.intellij.codeInspection.LocalQuickFix>()
        if (!isConcurrentMap(qualifierType)) {
            fixes.add(ReplaceWithComputeIfAbsentQuickFix())
            fixes.add(ReplaceWithPutIfAbsentQuickFix())
        }
        return fixes.toTypedArray()
    }

    companion object {
        private val CONCURRENT_MAP_TYPES = setOf(
            "java.util.concurrent.ConcurrentHashMap",
            "java.util.concurrent.ConcurrentSkipListMap",
            "java.util.concurrent.ConcurrentMap"
        )
        private val CONCURRENT_COLLECTION_TYPES = setOf(
            "java.util.concurrent.ConcurrentHashMap",
            "java.util.concurrent.ConcurrentSkipListMap",
            "java.util.concurrent.CopyOnWriteArrayList",
            "java.util.concurrent.CopyOnWriteArraySet",
            "java.util.concurrent.ConcurrentLinkedQueue",
            "java.util.concurrent.ConcurrentLinkedDeque"
        )
    }
}
