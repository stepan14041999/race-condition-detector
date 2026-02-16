package com.racedetector.inspections

import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.*
import com.intellij.uast.UastHintedVisitorAdapter
import com.racedetector.quickfixes.ExtractToFactoryMethodQuickFix
import org.jetbrains.uast.*
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor
import org.jetbrains.uast.visitor.AbstractUastVisitor

class ThisEscapeInspection : AbstractBaseUastLocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return UastHintedVisitorAdapter.create(
            holder.file.language,
            object : AbstractUastNonRecursiveVisitor() {
                override fun visitMethod(node: UMethod): Boolean {
                    val psiMethod = node.javaPsi
                    if (!psiMethod.isConstructor) return true

                    val containingClass = psiMethod.containingClass ?: return true
                    if (containingClass.hasModifierProperty(PsiModifier.FINAL)) return true

                    // Skip test classes, enums, records
                    if (shouldSkipClass(containingClass)) return true

                    walkConstructorBody(node, containingClass, holder)
                    return true
                }

                override fun visitClass(node: UClass): Boolean {
                    val psiClass = node.javaPsi
                    if (psiClass.hasModifierProperty(PsiModifier.FINAL)) return true

                    // Skip test classes, enums, records
                    if (shouldSkipClass(psiClass)) return true

                    // Check constructors from UClass.methods
                    for (method in node.methods) {
                        if (method.javaPsi.isConstructor) {
                            walkConstructorBody(method, psiClass, holder)
                        }
                    }

                    for (initializer in node.initializers) {
                        if (initializer.isStatic) continue
                        walkConstructorBody(initializer, psiClass, holder)
                    }
                    return true
                }
            },
            arrayOf(UMethod::class.java, UClass::class.java),
            true
        )
    }

    private fun walkConstructorBody(
        node: UElement,
        containingClass: PsiClass,
        holder: ProblemsHolder
    ) {
        node.accept(object : AbstractUastVisitor() {
            override fun visitThisExpression(node: UThisExpression): Boolean {
                checkThisExpression(node, containingClass, holder)
                return false
            }

            override fun visitCallExpression(node: UCallExpression): Boolean {
                checkOverridableMethodCall(node, containingClass, holder)
                return false
            }
        })
    }

    private fun checkThisExpression(
        thisExpr: UThisExpression,
        constructedClass: PsiClass,
        holder: ProblemsHolder
    ) {
        // Only handle unqualified this
        if (thisExpr.label != null) return

        val parent = thisExpr.uastParent

        // Case 1: this passed as argument to a call
        if (parent is UCallExpression) {
            val args = parent.valueArguments
            if (args.any { it.sourcePsi == thisExpr.sourcePsi }) {
                val resolved = parent.resolve()
                if (resolved != null && !isSafeMethodCall(resolved, constructedClass)) {
                    val sourcePsi = thisExpr.sourcePsi ?: return
                    if (parent.kind == UastCallKind.CONSTRUCTOR_CALL) {
                        holder.registerProblem(
                            sourcePsi,
                            "'this' passed to constructor before constructor completes",
                            ExtractToFactoryMethodQuickFix()
                        )
                    } else {
                        holder.registerProblem(
                            sourcePsi,
                            "'this' passed as argument before constructor completes",
                            ExtractToFactoryMethodQuickFix()
                        )
                    }
                }
            }
        }

        // Case 2: this assigned to a field
        if (parent is UBinaryExpression && parent.operator is UastBinaryOperator.AssignOperator) {
            val rightSrc = parent.rightOperand.sourcePsi
            val thisSrc = thisExpr.sourcePsi
            if (rightSrc != null && thisSrc != null && rightSrc == thisSrc) {
                val leftOperand = parent.leftOperand
                if (leftOperand is UReferenceExpression) {
                    val target = leftOperand.resolve()
                    if (target is PsiField) {
                        val isStaticField = target.hasModifierProperty(PsiModifier.STATIC)
                        val isQualified = leftOperand is UQualifiedReferenceExpression
                        if (isStaticField || isQualified) {
                            val psi = thisExpr.sourcePsi ?: return
                            holder.registerProblem(
                                psi,
                                "'this' assigned to external field before constructor completes",
                                ExtractToFactoryMethodQuickFix()
                            )
                        }
                    }
                }
            }
        }
    }

    private fun checkOverridableMethodCall(
        call: UCallExpression,
        constructedClass: PsiClass,
        holder: ProblemsHolder
    ) {
        // Only check method calls (not constructor calls)
        if (call.kind != UastCallKind.METHOD_CALL) return

        // Only calls on implicit or explicit this
        val receiver = call.receiver
        if (receiver != null && receiver !is UThisExpression) return

        val method = call.resolve() ?: return
        val methodClass = method.containingClass ?: return

        if (methodClass != constructedClass && !constructedClass.isInheritor(methodClass, true)) return

        if (method.hasModifierProperty(PsiModifier.PRIVATE)) return
        if (method.hasModifierProperty(PsiModifier.FINAL)) return
        if (method.hasModifierProperty(PsiModifier.STATIC)) return

        val sourcePsi = call.sourcePsi ?: return
        holder.registerProblem(
            sourcePsi,
            "Overridable method '${method.name}()' called in constructor — subclass may observe incomplete state",
            ExtractToFactoryMethodQuickFix()
        )
    }

    private fun isSafeMethodCall(method: PsiMethod, constructedClass: PsiClass): Boolean {
        val methodClass = method.containingClass ?: return false
        if (methodClass == constructedClass || constructedClass.isInheritor(methodClass, true)) {
            if (method.hasModifierProperty(PsiModifier.PRIVATE) ||
                method.hasModifierProperty(PsiModifier.FINAL)) {
                return true
            }
        }
        return false
    }

    private fun shouldSkipClass(psiClass: PsiClass): Boolean {
        // Check @SuppressWarnings
        if (FalsePositiveFilter.hasSuppressWarnings(psiClass)) return true

        // Check if in test class
        if (FalsePositiveFilter.isInTestClass(psiClass)) return true

        // Skip enums and records
        if (psiClass.isEnum || psiClass.isRecord) return true

        return false
    }
}
