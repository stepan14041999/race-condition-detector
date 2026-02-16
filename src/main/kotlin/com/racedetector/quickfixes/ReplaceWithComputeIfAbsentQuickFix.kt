package com.racedetector.quickfixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil

class ReplaceWithComputeIfAbsentQuickFix : LocalQuickFix {

    override fun getFamilyName(): String = NAME

    override fun getName(): String = NAME

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val ifStatement = PsiTreeUtil.getParentOfType(descriptor.psiElement, PsiIfStatement::class.java) ?: return
        val condition = ifStatement.condition ?: return
        val thenBranch = ifStatement.thenBranch ?: return

        val info = extractContainsKeyInfo(condition) ?: return
        val putCall = findPutCall(thenBranch, info.mapName) ?: return

        val factory = JavaPsiFacade.getElementFactory(project)
        val valueExpr = putCall.argumentList.expressions.getOrNull(1)?.text ?: "null"
        val newExpr = "${info.mapName}.computeIfAbsent(${info.keyText}, k -> $valueExpr)"
        val newStatement = factory.createStatementFromText("$newExpr;", ifStatement)
        ifStatement.replace(newStatement)
    }

    private data class ContainsKeyInfo(val mapName: String, val keyText: String)

    private fun extractContainsKeyInfo(condition: PsiExpression): ContainsKeyInfo? {
        val call = condition as? PsiMethodCallExpression ?: return null
        val ref = call.methodExpression
        if (ref.referenceName != "containsKey") return null
        val qualifier = ref.qualifierExpression?.text ?: return null
        val keyArg = call.argumentList.expressions.firstOrNull()?.text ?: return null
        return ContainsKeyInfo(qualifier, keyArg)
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
        const val NAME = "Replace with ConcurrentHashMap.computeIfAbsent()"
    }
}
