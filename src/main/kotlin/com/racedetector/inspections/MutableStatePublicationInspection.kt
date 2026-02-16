package com.racedetector.inspections

import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.*
import com.intellij.uast.UastHintedVisitorAdapter
import com.racedetector.analysis.resolveToPsiField
import com.racedetector.quickfixes.DefensiveCopyQuickFix
import com.racedetector.quickfixes.ReplaceWithCopyOfQuickFix
import com.racedetector.quickfixes.WrapWithUnmodifiableQuickFix
import org.jetbrains.uast.*
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor
import org.jetbrains.uast.visitor.AbstractUastVisitor

class MutableStatePublicationInspection : AbstractBaseUastLocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return UastHintedVisitorAdapter.create(
            holder.file.language,
            object : AbstractUastNonRecursiveVisitor() {
                override fun visitMethod(node: UMethod): Boolean {
                    val psiMethod = node.javaPsi
                    if (psiMethod.isConstructor) return true
                    if (psiMethod.hasModifierProperty(PsiModifier.PRIVATE)) return true

                    val containingClass = psiMethod.containingClass ?: return true

                    node.accept(object : AbstractUastVisitor() {
                        override fun visitReturnExpression(node: UReturnExpression): Boolean {
                            val returnExpr = node.returnExpression ?: return false
                            checkReturnExpression(returnExpr, psiMethod, containingClass, holder)
                            return false
                        }
                    })
                    return true
                }
            },
            arrayOf(UMethod::class.java),
            true
        )
    }

    private fun checkReturnExpression(
        expr: UExpression,
        method: PsiMethod,
        containingClass: PsiClass,
        holder: ProblemsHolder
    ) {
        val field = resolveToField(expr) ?: return
        if (field.containingClass?.isEquivalentTo(containingClass) != true) return

        // Skip fields that should not be inspected
        if (FalsePositiveFilter.shouldSkipField(field)) return

        val collectionKind = getCollectionKind(field.type)
        if (collectionKind == null) return
        if (isImmutableField(field)) return

        val fixes = mutableListOf<LocalQuickFix>()
        WrapWithUnmodifiableQuickFix.forKind(collectionKind)?.let { fixes.add(it) }
        ReplaceWithCopyOfQuickFix.forKind(collectionKind)?.let { fixes.add(it) }
        DefensiveCopyQuickFix.forKind(collectionKind)?.let { fixes.add(it) }

        val sourcePsi = expr.sourcePsi ?: return

        // Reduce severity for certain patterns
        val severity = if (FalsePositiveFilter.shouldReduceSeverity(field)) {
            com.intellij.codeInspection.ProblemHighlightType.WEAK_WARNING
        } else {
            com.intellij.codeInspection.ProblemHighlightType.GENERIC_ERROR_OR_WARNING
        }

        holder.registerProblem(
            sourcePsi,
            "Mutable internal collection '${field.name}' is exposed via '${method.name}()' without defensive copy",
            severity,
            *fixes.toTypedArray()
        )
    }

    private fun resolveToField(expr: UExpression): PsiField? {
        if (expr is UReferenceExpression) {
            return resolveToPsiField(expr.resolve())
        }
        return null
    }

    private fun getCollectionKind(type: PsiType): CollectionKind? {
        if (type is PsiArrayType) return CollectionKind.ARRAY

        val simpleName = type.canonicalText.substringBefore('<').substringAfterLast('.')
        return when (simpleName) {
            "List", "ArrayList", "LinkedList", "Vector",
            "MutableList" -> CollectionKind.LIST
            "Set", "HashSet", "TreeSet", "LinkedHashSet",
            "MutableSet" -> CollectionKind.SET
            "Map", "HashMap", "TreeMap", "LinkedHashMap", "Hashtable",
            "MutableMap" -> CollectionKind.MAP
            "Collection", "MutableCollection" -> CollectionKind.COLLECTION
            else -> null
        }
    }

    private fun isImmutableField(field: PsiField): Boolean {
        val initializer = field.initializer ?: return false
        return isUnmodifiableCall(initializer)
                || isCopyOfCall(initializer)
                || isImmutableFactoryCall(initializer)
    }

    private fun isUnmodifiableCall(expr: PsiExpression): Boolean {
        if (expr !is PsiMethodCallExpression) return false
        val ref = expr.methodExpression
        val qualifier = ref.qualifierExpression?.text ?: return false
        val methodName = ref.referenceName ?: return false
        return qualifier == "Collections" && methodName.startsWith("unmodifiable")
    }

    private fun isCopyOfCall(expr: PsiExpression): Boolean {
        if (expr !is PsiMethodCallExpression) return false
        val ref = expr.methodExpression
        val qualifier = ref.qualifierExpression?.text ?: return false
        val methodName = ref.referenceName ?: return false
        return qualifier in setOf("List", "Set", "Map") && methodName == "copyOf"
    }

    private fun isImmutableFactoryCall(expr: PsiExpression): Boolean {
        if (expr !is PsiMethodCallExpression) return false
        val ref = expr.methodExpression
        val qualifier = ref.qualifierExpression?.text ?: return false
        val methodName = ref.referenceName ?: return false
        return qualifier in setOf("List", "Set", "Map") && methodName == "of"
    }

    enum class CollectionKind {
        LIST, SET, MAP, COLLECTION, ARRAY
    }
}
