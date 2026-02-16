package com.racedetector.threading

import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.util.*
import com.racedetector.analysis.ThreadContext
import com.racedetector.folia.FoliaEventThreadMapper
import com.racedetector.folia.FoliaProjectDetector
import com.racedetector.folia.FoliaSchedulerResolver
import com.racedetector.settings.RaceDetectorSettings
import org.jetbrains.uast.*

object ThreadContextResolver {

    private val CALLER_CHAIN_KEY = Key.create<CachedValue<ThreadContext>>("racedetector.callerChainContext")

    fun resolve(element: PsiElement): ThreadContext {
        val local = resolveLocal(element)
        if (local != ThreadContext.Unknown) return local

        // Fall back to cross-class caller chain analysis
        val method = findEnclosingPsiMethod(element) ?: return ThreadContext.Unknown
        return resolveByCallerChain(method)
    }

    /**
     * Resolves thread context by examining only the local PSI tree
     * (annotations, enclosing lambdas, method signatures).
     */
    internal fun resolveLocal(element: PsiElement): ThreadContext {
        var psi: PsiElement? = element
        while (psi != null) {
            val uElement = psi.toUElement()

            if (uElement is ULambdaExpression) {
                val result = checkLambdaContext(uElement)

                if (result != null) return result
            }
            if (uElement is UMethod) {
                val result = checkMethodContext(uElement)

                if (result != null) return result
            }
            psi = psi.parent
        }
        return ThreadContext.Unknown
    }

    /**
     * Finds the enclosing PsiMethod for an element, handling both Java and Kotlin.
     */
    private fun findEnclosingPsiMethod(element: PsiElement): PsiMethod? {
        var psi: PsiElement? = element
        while (psi != null) {
            if (psi is PsiMethod) return psi
            val uElement = psi.toUElement()
            if (uElement is UMethod) return uElement.javaPsi
            psi = psi.parent
        }
        return null
    }

    /**
     * Resolves thread context by analyzing the caller chain across class boundaries.
     * Results are cached per method with PsiModificationTracker dependency.
     */
    private fun resolveByCallerChain(method: PsiMethod): ThreadContext {
        return CachedValuesManager.getCachedValue(method, CALLER_CHAIN_KEY) {
            val result = computeContextByCallerChain(method, 0, mutableSetOf())
            CachedValueProvider.Result.create(result, PsiModificationTracker.MODIFICATION_COUNT)
        }
    }

    /**
     * Recursively computes thread context by searching for callers of the given method.
     *
     * Algorithm:
     * - Search for all call sites of [method] within the current module
     * - For each call site, resolve its local context
     * - If local context is Unknown, recurse into the caller's method (up to [MAX_CALLER_CHAIN_DEPTH])
     * - If all callers share one context → that context
     * - If callers have different contexts → MultiThread
     * - If no callers found or all Unknown → Unknown
     */
    private fun computeContextByCallerChain(
        method: PsiMethod,
        depth: Int,
        visited: MutableSet<PsiMethod>
    ): ThreadContext {
        val maxDepth = RaceDetectorSettings.getInstance().callchainDepth
        if (depth >= maxDepth) return ThreadContext.Unknown
        if (!visited.add(method)) return ThreadContext.Unknown

        try {
            val module = ModuleUtilCore.findModuleForPsiElement(method) ?: return ThreadContext.Unknown
            val scope = GlobalSearchScope.moduleScope(module)
            val refs = MethodReferencesSearch.search(method, scope, false).findAll()
            if (refs.isEmpty()) return ThreadContext.Unknown

            val contexts = mutableSetOf<ThreadContext>()
            for (ref in refs) {
                ProgressManager.checkCanceled()
                val callSite = ref.element
                val localCtx = resolveLocal(callSite)
                if (localCtx != ThreadContext.Unknown) {
                    contexts.add(localCtx)
                    continue
                }
                val callerMethod = PsiTreeUtil.getParentOfType(callSite, PsiMethod::class.java) ?: continue
                val callerCtx = computeContextByCallerChain(callerMethod, depth + 1, visited)
                if (callerCtx != ThreadContext.Unknown) {
                    if (callerCtx is ThreadContext.MultiThread) {
                        contexts.addAll(callerCtx.contexts)
                    } else {
                        contexts.add(callerCtx)
                    }
                }
            }

            return when {
                contexts.isEmpty() -> ThreadContext.Unknown
                contexts.size == 1 -> contexts.single()
                else -> ThreadContext.MultiThread(contexts)
            }
        } finally {
            visited.remove(method)
        }
    }

    private fun checkLambdaContext(lambda: ULambdaExpression): ThreadContext? {
        // First try UAST parent chain
        var uastAncestor: UElement? = lambda.uastParent
        while (uastAncestor != null) {
            if (uastAncestor is UCallExpression) {
                checkCallForThreading(uastAncestor)?.let { return it }
                break
            }
            if (uastAncestor is UMethod || uastAncestor is UClass) break
            uastAncestor = uastAncestor.uastParent
        }
        // Fallback: walk up PSI from the lambda to find the enclosing call expression
        val psiLambda = lambda.sourcePsi ?: return null
        var psi: PsiElement? = psiLambda.parent
        while (psi != null) {
            val uElement = psi.toUElement()
            if (uElement is UCallExpression) {
                checkCallForThreading(uElement)?.let { return it }
            }
            // Stop at method/class boundaries
            if (psi is PsiMethod || psi is PsiClass) break
            // Kotlin: KtClass also serves as class boundary
            if (psi.javaClass.name.contains("KtClass")) break
            psi = psi.parent
        }
        return null
    }

    private fun checkCallForThreading(call: UCallExpression): ThreadContext? {
        // Check Folia scheduler contexts first (if this is a Folia project)
        val project = call.sourcePsi?.project
        if (project != null && FoliaProjectDetector.isFoliaProject(project)) {
            FoliaSchedulerResolver.resolve(call)?.let { return it }
        }

        val methodName = call.methodName
        val resolved = call.resolve()
        val containingClassName = resolved?.containingClass?.qualifiedName

        // ExecutorService.submit() / execute()
        if (methodName in listOf("submit", "execute")) {
            if (isExecutorClass(containingClassName)) {
                return ThreadContext.WorkerThread("ExecutorService.$methodName()")
            }
        }

        // CompletableFuture.runAsync() / supplyAsync()
        if (methodName in listOf("runAsync", "supplyAsync")) {
            if (containingClassName == "java.util.concurrent.CompletableFuture") {
                return ThreadContext.WorkerThread("CompletableFuture.$methodName()")
            }
        }

        // new Thread(lambda) — check via resolved constructor
        if (containingClassName == "java.lang.Thread" && resolved?.isConstructor == true) {
            return ThreadContext.WorkerThread("new Thread()")
        }

        // Fallback for constructor calls where resolve() fails (e.g. light test JDK)
        if (call.kind == UastCallKind.CONSTRUCTOR_CALL) {
            val psiNew = call.sourcePsi as? PsiNewExpression
            if (psiNew?.classReference?.referenceName == "Thread") {
                return ThreadContext.WorkerThread("new Thread()")
            }
            // Kotlin: Thread { ... } — check class reference text
            val classRef = call.classReference
            if (classRef != null) {
                val refName = classRef.resolvedName
                    ?: classRef.sourcePsi?.text?.substringBefore(' ')?.substringBefore('{')?.substringBefore('(')?.trim()
                if (refName == "Thread") {
                    return ThreadContext.WorkerThread("new Thread()")
                }
            }
        }

        // Kotlin: Thread { ... } where UAST kind might not be CONSTRUCTOR_CALL
        if (methodName == "Thread" || call.sourcePsi?.text?.startsWith("Thread") == true) {
            val classRef = call.classReference
            val refName = classRef?.resolvedName
                ?: classRef?.sourcePsi?.text?.substringBefore(' ')?.substringBefore('{')?.substringBefore('(')?.trim()
            if (refName == "Thread") {
                return ThreadContext.WorkerThread("new Thread()")
            }
        }

        // Kotlin coroutines: launch { }, async { }
        if (methodName in listOf("launch", "async")) {
            return ThreadContext.WorkerThread("coroutine $methodName")
        }

        // Kotlin coroutines: withContext(Dispatchers.XXX) { }
        if (methodName == "withContext") {
            val firstArgText = call.valueArguments.firstOrNull()?.sourcePsi?.text ?: ""
            return when {
                firstArgText.contains("Main") -> ThreadContext.MainThread
                else -> ThreadContext.WorkerThread("withContext($firstArgText)")
            }
        }

        return null
    }

    private fun isExecutorClass(qualifiedName: String?): Boolean {
        if (qualifiedName == null) return false
        return qualifiedName in setOf(
            "java.util.concurrent.Executor",
            "java.util.concurrent.ExecutorService",
            "java.util.concurrent.AbstractExecutorService",
            "java.util.concurrent.ThreadPoolExecutor",
            "java.util.concurrent.ScheduledExecutorService",
            "java.util.concurrent.ScheduledThreadPoolExecutor",
            "java.util.concurrent.ForkJoinPool"
        )
    }

    private fun checkMethodContext(method: UMethod): ThreadContext? {
        // Check Folia event handler context
        val project = method.sourcePsi?.project
        if (project != null && FoliaProjectDetector.isFoliaProject(project)) {
            val psiMethod = method.javaPsi
            FoliaEventThreadMapper.resolveEventHandlerContext(psiMethod)?.let { return it }
        }

        val settings = RaceDetectorSettings.getInstance()

        // Check annotations
        for (annotation in method.uAnnotations) {
            val qualifiedName = annotation.qualifiedName ?: continue
            val simpleName = qualifiedName.substringAfterLast('.')

            // Check custom thread annotations
            if (qualifiedName in settings.customThreadAnnotations) {
                return when {
                    simpleName.contains("Main", ignoreCase = true) ||
                    simpleName.contains("Ui", ignoreCase = true) -> ThreadContext.MainThread
                    simpleName.contains("Worker", ignoreCase = true) ||
                    simpleName.contains("Background", ignoreCase = true) -> ThreadContext.WorkerThread("@$simpleName")
                    else -> ThreadContext.WorkerThread("@$simpleName")
                }
            }

            // Spring checks
            if (settings.enableSpringChecks) {
                when (simpleName) {
                    "Async" -> return ThreadContext.WorkerThread("Spring @Async")
                    "Scheduled" -> return ThreadContext.ScheduledThread
                    "EventListener" -> {
                        // Check for async mode (Spring @EventListener)
                        val psiAnnotation = annotation.javaPsi
                        if (psiAnnotation != null) {
                            val asyncAttr = psiAnnotation.findAttributeValue("async")
                            val isAsync = asyncAttr?.text == "true"
                            if (isAsync) {
                                return ThreadContext.WorkerThread("Spring @EventListener(async)")
                            }
                        }
                    }
                }
            }
        }

        // Check if method is a request handler in @Controller / @RestController
        if (settings.enableSpringChecks) {
            val containingClass = method.getContainingUClass()
            if (containingClass != null && isSpringController(containingClass)) {
                if (isRequestHandlerMethod(method)) {
                    return ThreadContext.WorkerThread("Tomcat request thread")
                }
            }
        }

        // Check run() in Runnable
        if (method.name == "run" && method.uastParameters.isEmpty()) {
            val containingClass = method.getContainingUClass() ?: return null
            if (implementsInterface(containingClass, "java.lang.Runnable")) {
                return ThreadContext.WorkerThread("Runnable.run()")
            }
        }

        // Check call() in Callable
        if (method.name == "call" && method.uastParameters.isEmpty()) {
            val containingClass = method.getContainingUClass() ?: return null
            if (implementsInterface(containingClass, "java.util.concurrent.Callable")) {
                return ThreadContext.WorkerThread("Callable.call()")
            }
        }

        return null
    }

    private val SPRING_CONTROLLER_ANNOTATIONS = setOf(
        "Controller", "RestController"
    )

    private val SPRING_REQUEST_MAPPING_ANNOTATIONS = setOf(
        "RequestMapping", "GetMapping", "PostMapping", "PutMapping",
        "DeleteMapping", "PatchMapping"
    )

    private fun isSpringController(uClass: UClass): Boolean {
        return uClass.uAnnotations.any { annotation ->
            val name = (annotation.qualifiedName ?: "").substringAfterLast('.')
            name in SPRING_CONTROLLER_ANNOTATIONS
        }
    }

    private fun isRequestHandlerMethod(method: UMethod): Boolean {
        return method.uAnnotations.any { annotation ->
            val name = (annotation.qualifiedName ?: "").substringAfterLast('.')
            name in SPRING_REQUEST_MAPPING_ANNOTATIONS
        }
    }

    private fun implementsInterface(uClass: UClass, interfaceFqn: String): Boolean {
        val psiClass = uClass.javaPsi
        val simpleName = interfaceFqn.substringAfterLast('.')
        // Try full resolution first
        if (InheritanceUtil.isInheritor(psiClass, interfaceFqn)) return true
        // Check UAST super types (works for Kotlin classes)
        for (superType in uClass.uastSuperTypes) {
            val psiType = superType.type as? com.intellij.psi.PsiClassType
            val resolvedClass = psiType?.resolve()
            if (resolvedClass?.qualifiedName == interfaceFqn) return true
            // Fallback: check by source text (when resolution fails)
            val text = superType.sourcePsi?.text?.substringBefore('<') ?: continue
            if (text == simpleName) return true
        }
        // Fallback: check implements/extends lists by reference name.
        // This handles cases where the JDK mock doesn't fully resolve types.
        val implementsRefs = psiClass.implementsList?.referenceElements ?: emptyArray()
        val extendsRefs = psiClass.extendsList?.referenceElements ?: emptyArray()
        for (ref in implementsRefs) {
            val resolved = ref.resolve() as? PsiClass
            if (resolved?.qualifiedName == interfaceFqn) return true
            if (ref.referenceName == simpleName) return true
        }
        for (ref in extendsRefs) {
            val resolved = ref.resolve() as? PsiClass
            if (resolved?.qualifiedName == interfaceFqn) return true
            if (ref.referenceName == simpleName) return true
        }
        return false
    }
}
