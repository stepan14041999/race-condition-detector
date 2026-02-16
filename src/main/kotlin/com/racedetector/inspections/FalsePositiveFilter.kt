package com.racedetector.inspections

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.uast.*

/**
 * Utility for detecting false positives in race condition inspections.
 */
object FalsePositiveFilter {

    /**
     * Check if field inspection should be suppressed based on @SuppressWarnings
     */
    fun isSuppressed(field: PsiField): Boolean {
        return hasSuppressWarnings(field)
    }

    /**
     * Check if element has @SuppressWarnings("RaceCondition")
     */
    fun hasSuppressWarnings(element: PsiModifierListOwner): Boolean {
        // Try both fully qualified and simple name
        val annotation = element.modifierList?.findAnnotation("java.lang.SuppressWarnings")
            ?: element.modifierList?.findAnnotation("SuppressWarnings")
            ?: return false

        val value = annotation.findAttributeValue("value")

        return when (value) {
            is PsiLiteralExpression -> {
                val text = value.value as? String
                text == "RaceCondition" || text == "ALL"
            }
            is PsiArrayInitializerMemberValue -> {
                value.initializers.any {
                    val text = (it as? PsiLiteralExpression)?.value as? String
                    text == "RaceCondition" || text == "ALL"
                }
            }
            else -> false
        }
    }

    /**
     * Check if field is in a test class (heuristic: file is in test sources)
     */
    fun isInTestClass(field: PsiField): Boolean {
        val containingClass = field.containingClass ?: return false
        return isInTestClass(containingClass)
    }

    /**
     * Check if class is a test class (heuristic: file is in test sources)
     */
    fun isInTestClass(psiClass: PsiClass): Boolean {
        // Primary check: file is in test sources directory
        val virtualFile = psiClass.containingFile?.virtualFile ?: return false
        val path = virtualFile.path

        // Check for common test source patterns
        val isInTestDir = path.contains("/test/") || path.contains("\\test\\") ||
                          path.contains("/tests/") || path.contains("\\tests\\")

        if (isInTestDir) return true

        // Secondary check: class name matches test naming conventions (more specific patterns)
        val className = psiClass.name ?: return false

        // Only match specific test suffixes, not just "Test" which is too common
        return className.endsWith("Tests") ||
               className.endsWith("TestCase") ||
               className.matches(Regex(".*Test\\d+")) || // e.g., MyTest1, Test123
               className.startsWith("Test") && className.length > 4 // e.g., TestSuite but not just "Test"
    }

    /**
     * Check if field is in an enum class
     */
    fun isInEnum(field: PsiField): Boolean {
        val containingClass = field.containingClass ?: return false
        return containingClass.isEnum
    }

    /**
     * Check if field is in a record class (Java 16+)
     */
    fun isInRecord(field: PsiField): Boolean {
        val containingClass = field.containingClass ?: return false
        return containingClass.isRecord
    }

    /**
     * Check if field is a private field in a private inner class
     */
    fun isPrivateFieldInPrivateInnerClass(field: PsiField): Boolean {
        if (!field.hasModifierProperty(PsiModifier.PRIVATE)) return false

        val containingClass = field.containingClass ?: return false
        if (containingClass.containingClass == null) return false // not an inner class

        return containingClass.hasModifierProperty(PsiModifier.PRIVATE)
    }

    /**
     * Check if field is used only in constructor and one other method
     */
    fun isUsedOnlyInConstructorAndOneMethod(field: PsiField): Boolean {
        val containingClass = field.containingClass ?: return false
        val usedInMethods = mutableSetOf<PsiMethod>()

        containingClass.accept(object : JavaRecursiveElementVisitor() {
            override fun visitReferenceExpression(expression: PsiReferenceExpression) {
                ProgressManager.checkCanceled()
                super.visitReferenceExpression(expression)

                if (expression.resolve() == field) {
                    val method = PsiTreeUtil.getParentOfType(expression, PsiMethod::class.java)
                    if (method != null) {
                        usedInMethods.add(method)
                    }
                }
            }
        })

        if (usedInMethods.size > 2) return false
        if (usedInMethods.isEmpty()) return false

        // Check if one of them is a constructor
        val hasConstructor = usedInMethods.any { it.isConstructor }
        return hasConstructor && usedInMethods.size <= 2
    }

    /**
     * Check if field uses correct double-checked locking pattern
     */
    fun isCorrectDoubleCheckedLocking(field: PsiField): Boolean {
        // Field must be volatile for correct DCL
        if (!field.hasModifierProperty(PsiModifier.VOLATILE)) return false

        // Look for the pattern:
        // if (field == null) {
        //   synchronized (...) {
        //     if (field == null) {
        //       field = ...
        //     }
        //   }
        // }

        val containingClass = field.containingClass ?: return false
        var foundDCL = false

        containingClass.accept(object : JavaRecursiveElementVisitor() {
            override fun visitIfStatement(statement: PsiIfStatement) {
                ProgressManager.checkCanceled()
                super.visitIfStatement(statement)

                // First if: field == null
                if (!isNullCheck(statement.condition, field)) return

                val thenBranch = statement.thenBranch ?: return

                // Look for synchronized block inside
                thenBranch.accept(object : JavaRecursiveElementVisitor() {
                    override fun visitSynchronizedStatement(sync: PsiSynchronizedStatement) {
                        super.visitSynchronizedStatement(sync)

                        val syncBody = sync.body ?: return

                        // Look for second if inside synchronized
                        syncBody.accept(object : JavaRecursiveElementVisitor() {
                            override fun visitIfStatement(innerIf: PsiIfStatement) {
                                super.visitIfStatement(innerIf)

                                if (isNullCheck(innerIf.condition, field)) {
                                    val innerThen = innerIf.thenBranch
                                    if (innerThen != null && containsAssignment(innerThen, field)) {
                                        foundDCL = true
                                    }
                                }
                            }
                        })
                    }
                })
            }
        })

        return foundDCL
    }

    private fun isNullCheck(condition: PsiExpression?, field: PsiField): Boolean {
        val binary = condition as? PsiBinaryExpression ?: return false
        val op = binary.operationTokenType

        if (op != JavaTokenType.EQEQ && op != JavaTokenType.NE) return false

        val left = binary.lOperand
        val right = binary.rOperand

        return (isFieldReference(left, field) && isNull(right)) ||
               (isNull(left) && isFieldReference(right, field))
    }

    private fun isFieldReference(expr: PsiExpression?, field: PsiField): Boolean {
        val ref = expr as? PsiReferenceExpression ?: return false
        return ref.resolve() == field
    }

    private fun isNull(expr: PsiExpression?): Boolean {
        return expr is PsiLiteralExpression && expr.value == null
    }

    private fun containsAssignment(element: PsiElement, field: PsiField): Boolean {
        var found = false
        element.accept(object : JavaRecursiveElementVisitor() {
            override fun visitAssignmentExpression(expression: PsiAssignmentExpression) {
                super.visitAssignmentExpression(expression)

                val lExpr = expression.lExpression as? PsiReferenceExpression
                if (lExpr?.resolve() == field) {
                    found = true
                }
            }
        })
        return found
    }

    /**
     * Determine if field should have reduced severity based on usage patterns
     */
    fun shouldReduceSeverity(field: PsiField): Boolean {
        return isPrivateFieldInPrivateInnerClass(field) ||
               isUsedOnlyInConstructorAndOneMethod(field)
    }

    /**
     * Check if field inspection should be skipped entirely
     */
    fun shouldSkipField(field: PsiField): Boolean {
        return isSuppressed(field) ||
               isInTestClass(field) ||
               isInEnum(field) ||
               isInRecord(field)
    }
}
