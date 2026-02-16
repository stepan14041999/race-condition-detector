package com.racedetector.quickfixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil

class ReplaceWithPutIfAbsentQuickFix : LocalQuickFix {

    override fun getFamilyName(): String = NAME

    override fun getName(): String = NAME

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val ifStatement = PsiTreeUtil.getParentOfType(descriptor.psiElement, PsiIfStatement::class.java) ?: return
        val condition = ifStatement.condition ?: return
        val thenBranch = ifStatement.thenBranch ?: return

        val info = extractCheckInfo(condition) ?: return
        val putCall = findPutCall(thenBranch, info.mapName) ?: return

        val factory = JavaPsiFacade.getElementFactory(project)
        val valueExpr = putCall.argumentList.expressions.getOrNull(1)?.text ?: "null"
        val newExpr = "${info.mapName}.putIfAbsent(${info.keyText}, $valueExpr)"
        val newStatement = factory.createStatementFromText("$newExpr;", ifStatement)
        ifStatement.replace(newStatement)
    }

    private data class CheckInfo(val mapName: String, val keyText: String)

    private fun extractCheckInfo(condition: PsiExpression): CheckInfo? {
        // Handle containsKey
        val call = condition as? PsiMethodCallExpression
        if (call != null) {
            val ref = call.methodExpression
            if (ref.referenceName == "containsKey") {
                val qualifier = ref.qualifierExpression?.text ?: return null
                val keyArg = call.argumentList.expressions.firstOrNull()?.text ?: return null
                return CheckInfo(qualifier, keyArg)
            }
        }
        // Handle negated containsKey: !map.containsKey(key)
        val prefix = condition as? PsiPrefixExpression
        if (prefix != null && prefix.operationTokenType == JavaTokenType.EXCL) {
            val inner = PsiUtil.skipParenthesizedExprDown(prefix.operand) as? PsiMethodCallExpression ?: return null
            val ref = inner.methodExpression
            if (ref.referenceName == "containsKey") {
                val qualifier = ref.qualifierExpression?.text ?: return null
                val keyArg = inner.argumentList.expressions.firstOrNull()?.text ?: return null
                return CheckInfo(qualifier, keyArg)
            }
        }
        return null
    }

    private fun findPutCall(element: PsiElement, mapName: String): PsiMethodCallExpression? {
        var result: PsiMethodCallExpression? = null
        element.accept(object : JavaRecursiveElementVisitor() {
            override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                super.visitMethodCallExpression(expression)
                val ref = expression.methodExpression
                if (ref.referenceName == "put" && ref.qualifierExpression?.text == mapName) {
                    result = expression
                }
            }
        })
        return result
    }

    companion object {
        const val NAME = "Replace with ConcurrentHashMap.putIfAbsent()"
    }
}
