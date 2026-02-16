package com.racedetector.threading

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.racedetector.analysis.ThreadContext

class ThreadContextResolverTest : LightJavaCodeInsightFixtureTestCase() {

    override fun setUp() {
        super.setUp()

        // Mock concurrent classes not present in the light test JDK
        myFixture.addClass(
            """
            package java.util.concurrent;
            public interface Executor {
                void execute(Runnable command);
            }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package java.util.concurrent;
            public interface ExecutorService extends Executor {
                void submit(Runnable task);
            }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package java.util.concurrent;
            public interface Callable<V> {
                V call() throws Exception;
            }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package java.util.concurrent;
            public class CompletableFuture<T> {
                public static CompletableFuture<Void> runAsync(Runnable runnable) { return null; }
            }
            """.trimIndent()
        )

        // Mock Spring annotations
        myFixture.addClass(
            """
            package org.springframework.scheduling.annotation;
            public @interface Async {}
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package org.springframework.scheduling.annotation;
            public @interface Scheduled {
                String cron() default "";
            }
            """.trimIndent()
        )

        // Mock functional interface for stream().forEach() test
        myFixture.addClass(
            """
            package java.util.function;
            @FunctionalInterface
            public interface Consumer<T> {
                void accept(T t);
            }
            """.trimIndent()
        )
    }

    // 1. Code inside Runnable.run() → WorkerThread
    fun testRunnableRun() {
        myFixture.configureByText(
            "MyTask.java",
            """
            public class MyTask implements Runnable {
                @Override
                public void run() {
                    int x = <caret>42;
                }
            }
            """.trimIndent()
        )
        assertContext(ThreadContext.WorkerThread("Runnable.run()"))
    }

    // 2. Code inside lambda in executor.submit() → WorkerThread
    fun testExecutorSubmitLambda() {
        myFixture.configureByText(
            "Test.java",
            """
            import java.util.concurrent.ExecutorService;
            public class Test {
                void test(ExecutorService executor) {
                    executor.submit(() -> {
                        int x = <caret>42;
                    });
                }
            }
            """.trimIndent()
        )
        assertContext(ThreadContext.WorkerThread("ExecutorService.submit()"))
    }

    // 3. Code inside CompletableFuture.runAsync() → WorkerThread
    fun testCompletableFutureRunAsync() {
        myFixture.configureByText(
            "Test.java",
            """
            import java.util.concurrent.CompletableFuture;
            public class Test {
                void test() {
                    CompletableFuture.runAsync(() -> {
                        int x = <caret>42;
                    });
                }
            }
            """.trimIndent()
        )
        assertContext(ThreadContext.WorkerThread("CompletableFuture.runAsync()"))
    }

    // 4. Code inside new Thread(() -> ...).start() → WorkerThread
    fun testNewThreadLambda() {
        myFixture.configureByText(
            "Test.java",
            """
            public class Test {
                void test() {
                    new Thread(() -> {
                        int x = <caret>42;
                    }).start();
                }
            }
            """.trimIndent()
        )
        assertContext(ThreadContext.WorkerThread("new Thread()"))
    }

    // 5. Code in ordinary method → Unknown
    fun testOrdinaryMethod() {
        myFixture.configureByText(
            "Test.java",
            """
            public class Test {
                void doSomething() {
                    int x = <caret>42;
                }
            }
            """.trimIndent()
        )
        assertContext(ThreadContext.Unknown)
    }

    // 6. Code in main() → Unknown
    fun testMainMethod() {
        myFixture.configureByText(
            "Test.java",
            """
            public class Test {
                public static void main(String[] args) {
                    int x = <caret>42;
                }
            }
            """.trimIndent()
        )
        assertContext(ThreadContext.Unknown)
    }

    // 7. Lambda in stream().forEach() → Unknown
    fun testStreamForEachLambda() {
        myFixture.configureByText(
            "Test.java",
            """
            import java.util.function.Consumer;
            public class Test {
                interface MyStream<T> { void forEach(Consumer<? super T> action); }
                interface MyList<T> { MyStream<T> stream(); }

                void test(MyList<String> list) {
                    list.stream().forEach(s -> {
                        String x = <caret>s;
                    });
                }
            }
            """.trimIndent()
        )
        assertContext(ThreadContext.Unknown)
    }

    // 8. Callable.call() → WorkerThread
    fun testCallableCall() {
        myFixture.configureByText(
            "MyCallable.java",
            """
            import java.util.concurrent.Callable;
            public class MyCallable implements Callable<String> {
                @Override
                public String call() throws Exception {
                    int x = <caret>42;
                    return "result";
                }
            }
            """.trimIndent()
        )
        assertContext(ThreadContext.WorkerThread("Callable.call()"))
    }

    // 9. @Async method → WorkerThread("Spring @Async")
    fun testAsyncAnnotation() {
        myFixture.configureByText(
            "Test.java",
            """
            import org.springframework.scheduling.annotation.Async;
            public class Test {
                @Async
                public void asyncMethod() {
                    int x = <caret>42;
                }
            }
            """.trimIndent()
        )
        assertContext(ThreadContext.WorkerThread("Spring @Async"))
    }

    // 10. @Scheduled method → ScheduledThread
    fun testScheduledAnnotation() {
        myFixture.configureByText(
            "Test.java",
            """
            import org.springframework.scheduling.annotation.Scheduled;
            public class Test {
                @Scheduled(cron = "0 0 * * * *")
                public void scheduledMethod() {
                    int x = <caret>42;
                }
            }
            """.trimIndent()
        )
        assertContext(ThreadContext.ScheduledThread)
    }

    // 11. Kotlin coroutine launch {} → WorkerThread
    fun testKotlinCoroutineLaunch() {
        myFixture.addClass(
            """
            package kotlinx.coroutines;
            public class CoroutineScope {}
            """.trimIndent()
        )
        myFixture.configureByText(
            "Test.kt",
            """
            import kotlinx.coroutines.CoroutineScope

            fun CoroutineScope.test() {
                launch {
                    val x = <caret>42
                }
            }

            fun CoroutineScope.launch(block: () -> Unit) {}
            """.trimIndent()
        )
        assertContext(ThreadContext.WorkerThread("coroutine launch"))
    }

    // 12. Kotlin coroutine withContext(Dispatchers.IO) → WorkerThread
    fun testKotlinWithContextIO() {
        myFixture.configureByText(
            "Test.kt",
            """
            object Dispatchers {
                val IO = "IO"
                val Main = "Main"
            }

            fun <T> withContext(context: Any, block: () -> T): T = block()

            fun test() {
                withContext(Dispatchers.IO) {
                    val x = <caret>42
                }
            }
            """.trimIndent()
        )
        assertContext(ThreadContext.WorkerThread("withContext(Dispatchers.IO)"))
    }

    // 13. Kotlin coroutine withContext(Dispatchers.Main) → MainThread
    fun testKotlinWithContextMain() {
        myFixture.configureByText(
            "Test.kt",
            """
            object Dispatchers {
                val IO = "IO"
                val Main = "Main"
            }

            fun <T> withContext(context: Any, block: () -> T): T = block()

            fun test() {
                withContext(Dispatchers.Main) {
                    val x = <caret>42
                }
            }
            """.trimIndent()
        )
        assertContext(ThreadContext.MainThread)
    }

    // ====== Cross-class caller chain resolution tests ======

    // 14. Method called from Runnable.run() in another class → WorkerThread
    fun testCrossClassCalledFromRunnable() {
        myFixture.addClass(
            """
            public class Service {
                public void doWork() {
                    int x = 42;
                }
            }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            public class Worker implements Runnable {
                @Override
                public void run() {
                    new Service().doWork();
                }
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "ServiceTest.java",
            """
            public class ServiceTest {
                // Reference Service.doWork so caret is inside it
            }
            """.trimIndent()
        )
        // Navigate to the doWork method and resolve its context
        val serviceClass = myFixture.findClass("Service")
        val doWorkMethod = serviceClass.findMethodsByName("doWork", false).first()
        val body = doWorkMethod.body!!
        val element = body.statements.first()
        val actual = ThreadContextResolver.resolve(element)
        assertEquals(ThreadContext.WorkerThread("Runnable.run()"), actual)
    }

    // 15. Method called from both WorkerThread and ScheduledThread → MultiThread
    fun testCrossClassMultipleContexts() {
        myFixture.addClass(
            """
            public class SharedService {
                public void process() {
                    int x = 42;
                }
            }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            public class BackgroundWorker implements Runnable {
                @Override
                public void run() {
                    new SharedService().process();
                }
            }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            import org.springframework.scheduling.annotation.Scheduled;
            public class ScheduledTask {
                @Scheduled(cron = "0 0 * * * *")
                public void tick() {
                    new SharedService().process();
                }
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "Dummy.java",
            """
            public class Dummy {}
            """.trimIndent()
        )
        val serviceClass = myFixture.findClass("SharedService")
        val processMethod = serviceClass.findMethodsByName("process", false).first()
        val body = processMethod.body!!
        val element = body.statements.first()
        val actual = ThreadContextResolver.resolve(element)
        val expected = ThreadContext.MultiThread(
            setOf(
                ThreadContext.WorkerThread("Runnable.run()"),
                ThreadContext.ScheduledThread
            )
        )
        assertEquals(expected, actual)
    }

    // 16. Chain of 3: executor.submit → methodA → methodB (caret in methodB) → WorkerThread
    fun testCrossClassChainDepth3() {
        myFixture.addClass(
            """
            public class DeepService {
                public void methodB() {
                    int x = 42;
                }
            }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            public class MiddleLayer {
                public void methodA() {
                    new DeepService().methodB();
                }
            }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            import java.util.concurrent.ExecutorService;
            public class TopLevel {
                void start(ExecutorService executor) {
                    executor.submit(() -> new MiddleLayer().methodA());
                }
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "Dummy2.java",
            """
            public class Dummy2 {}
            """.trimIndent()
        )
        val serviceClass = myFixture.findClass("DeepService")
        val methodB = serviceClass.findMethodsByName("methodB", false).first()
        val body = methodB.body!!
        val element = body.statements.first()
        val actual = ThreadContextResolver.resolve(element)
        assertEquals(ThreadContext.WorkerThread("ExecutorService.submit()"), actual)
    }

    // 17. Method called only from @Async context → WorkerThread("Spring @Async")
    fun testCrossClassSingleContext() {
        myFixture.addClass(
            """
            public class Repository {
                public void save() {
                    int x = 42;
                }
            }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            import org.springframework.scheduling.annotation.Async;
            public class AsyncHandler {
                @Async
                public void handle() {
                    new Repository().save();
                }
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "Dummy3.java",
            """
            public class Dummy3 {}
            """.trimIndent()
        )
        val serviceClass = myFixture.findClass("Repository")
        val saveMethod = serviceClass.findMethodsByName("save", false).first()
        val body = saveMethod.body!!
        val element = body.statements.first()
        val actual = ThreadContextResolver.resolve(element)
        assertEquals(ThreadContext.WorkerThread("Spring @Async"), actual)
    }

    private fun assertContext(expected: ThreadContext) {
        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val actual = ThreadContextResolver.resolve(element)
        assertEquals(expected, actual)
    }
}
